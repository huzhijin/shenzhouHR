import ExcelJS from 'exceljs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { downloadQuerySheetWorkbook, buildWorkHoursExportRow } from './queryReportWorkbook';

vi.mock('../../shared/api/apiClient', () => ({
  triggerBrowserDownload: vi.fn(),
}));

async function captureWorkbook(): Promise<ExcelJS.Workbook> {
  const { triggerBrowserDownload } = await import('../../shared/api/apiClient');
  const mock = vi.mocked(triggerBrowserDownload);
  const blob = mock.mock.calls[mock.mock.calls.length - 1]?.[0] as Blob;
  const buffer = await blob.arrayBuffer();
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(buffer);
  return wb;
}

describe('downloadQuerySheetWorkbook financeOvertime bucket formulas', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('classifies 2026-09-25 as holiday (statutory) and 2026-09-26 as weekend (not in set)', async () => {
    // Standard finance-overtime page has 9 identity headers: 部门/工号/加班人/平时/周末/节假日/加班费/转调休/义务加班
    // Date cols start at J (col 10, 1-based)
    const dates = ['2026-09-25', '2026-09-26'];
    const headers = [
      '部门', '工号', '加班人', '平时加班', '周末加班', '节假日加班',
      '加班费', '转调休', '义务加班',
      ...dates.map(d => d),
    ];
    const rows = [['研发部', 'E001', '张三', '99', '99', '99', '0', '0', '0', '8', '4']];
    const dayRows = [[
      { date: '2026-09-25', hours: 8, dayType: 'PUBLIC_HOLIDAY' },
      { date: '2026-09-26', hours: 4, dayType: 'PUBLIC_HOLIDAY' },
    ]];

    await downloadQuerySheetWorkbook({ title: 'test', month: '2026-09', headers, rows, financeOvertime: { dates, dayRows } });
    const wb = await captureWorkbook();
    const sheet = wb.worksheets[0]!;
    const dataRow = sheet.getRow(2);

    // Date cells: J2=8 (9/25, holiday), K2=4 (9/26, weekend)
    expect(dataRow.getCell(10).value).toBe(8);
    expect(dataRow.getCell(11).value).toBe(4);

    // D2 weekday: no weekday dates → explicit 0
    expect(dataRow.getCell(4).value).toBe(0);

    // E2 weekend: 9/26 (PUBLIC_HOLIDAY but not in statutory set → weekend) → formula=K2, result=4
    const e2 = dataRow.getCell(5).value as ExcelJS.CellFormulaValue;
    expect(e2.formula).toBe('K2');
    expect(e2.result).toBe(4);

    // F2 holiday: 9/25 (PUBLIC_HOLIDAY, in statutory set → holiday) → formula=J2, result=8
    const f2 = dataRow.getCell(6).value as ExcelJS.CellFormulaValue;
    expect(f2.formula).toBe('J2');
    expect(f2.result).toBe(8);
  });

  it('writes correct column offsets for dedicated sheet with 7 identity headers (H/I instead of J/K)', async () => {
    // Dedicated sheet: 7 identity headers → date cols start at H (col 8)
    const dates = ['2026-09-25', '2026-09-26'];
    const headers = ['部门', '工号', '加班人', '平时加班', '周末加班', '节假日加班', '加班费', ...dates.map(d => d)];
    const rows = [['研发部', 'E001', '张三', '0', '0', '0', '0', '8', '4']];
    const dayRows = [[
      { date: '2026-09-25', hours: 8, dayType: 'PUBLIC_HOLIDAY' },
      { date: '2026-09-26', hours: 4, dayType: 'PUBLIC_HOLIDAY' },
    ]];

    await downloadQuerySheetWorkbook({ title: 'fee', month: '2026-09', headers, rows, financeOvertime: { dates, dayRows } });
    const wb = await captureWorkbook();
    const sheet = wb.worksheets[0]!;
    const dataRow = sheet.getRow(2);

    // Date cols: H2=8, I2=4
    expect(dataRow.getCell(8).value).toBe(8);
    expect(dataRow.getCell(9).value).toBe(4);

    // E2 weekend → formula=I2, result=4
    const e2 = dataRow.getCell(5).value as ExcelJS.CellFormulaValue;
    expect(e2.formula).toBe('I2');
    expect(e2.result).toBe(4);

    // F2 holiday → formula=H2, result=8
    const f2 = dataRow.getCell(6).value as ExcelJS.CellFormulaValue;
    expect(f2.formula).toBe('H2');
    expect(f2.result).toBe(8);
  });

  it('passes through rows without financeOvertime unchanged', async () => {
    await downloadQuerySheetWorkbook({
      title: 'plain',
      month: '2026-09',
      headers: ['部门', '工号', '姓名', '合计'],
      rows: [['研发部', 'E001', '张三', '8']],
    });
    const wb = await captureWorkbook();
    const sheet = wb.worksheets[0]!;
    expect(sheet.getRow(2).getCell(4).value).toBe(8);
  });

  it('work-hours export caches K-column actualHours formula result with non-half-hour precision', async () => {
    // Use the same row builder called by QueryReportsPage's work-hours export.
    // Verify the actual downloaded XLSX without a receiving-side formula evaluator.
    const columnKeys = [
      'department', 'employeeNumber', 'employeeName',
      'scheduledHours', 'paidOvertimeHours', 'voluntaryOvertimeHours',
      'leaveHours', 'annualLeaveHours', 'compensatoryOvertimeHours',
      'timeOffHours', 'actualHours', 'note',
    ];
    const headers = [
      '部门', '工号', '姓名',
      '应出勤工时', '加班时数', '义务加班',
      '事假+病假+其他假期', '年假', '加班换调休',
      '实际调休', '个人实际出勤工时', '备注',
    ];
    const rawRows = [
      {
        department: '研发部', employeeNumber: 'E001', employeeName: '张三',
        scheduledHours: 168,
        paidOvertimeHours: 4.25,
        voluntaryOvertimeHours: 3.125,
        leaveHours: 0.5,
        annualLeaveHours: 0.25,
        compensatoryOvertimeHours: 1.75,
        timeOffHours: 0.125,
        // Legacy display cache was rounded to half hours; export must use the D:J terms.
        actualHours: 176.5,
        note: '',
      },
      {
        department: '测试部', employeeNumber: 'E002', employeeName: '李四',
        scheduledHours: 0,
        paidOvertimeHours: 0,
        voluntaryOvertimeHours: 0,
        leaveHours: 0,
        annualLeaveHours: 0,
        compensatoryOvertimeHours: 0,
        timeOffHours: 0,
        actualHours: 0,
        note: '',
      },
    ];
    const rows = rawRows.map((row, index) => buildWorkHoursExportRow(
      row,
      columnKeys,
      index + 2,
      (_key, value) => String(value ?? ''),
    ));

    await downloadQuerySheetWorkbook({
      title: 'work-hours', month: '2026-09', headers, rows,
    });
    const wb = await captureWorkbook();
    const sheet = wb.worksheets[0]!;

    // Include F and derive cache from D:J (176.25), not the legacy actualHours (176.5).
    const k2Cell = sheet.getRow(2).getCell(11);
    expect(k2Cell.formula).toBe('D2+E2+F2-G2-H2+I2-J2');
    expect(k2Cell.result).toBe(176.25);

    // Cell.result reads the persisted numeric zero; Cell.value's object view omits zero.
    const k3Cell = sheet.getRow(3).getCell(11);
    expect(k3Cell.formula).toBe('D3+E3+F3-G3-H3+I3-J3');
    expect(k3Cell.result).toBe(0);

    // D:J are Excel numeric cells, even though the row builder supplies numeric strings.
    expect(sheet.getRow(2).getCell(4).value).toBe(168);
    expect(sheet.getRow(2).getCell(5).value).toBe(4.25);
    expect(sheet.getRow(2).getCell(6).value).toBe(3.125);
    expect(sheet.getRow(2).getCell(7).value).toBe(0.5);
    expect(sheet.getRow(2).getCell(8).value).toBe(0.25);
    expect(sheet.getRow(2).getCell(9).value).toBe(1.75);
    expect(sheet.getRow(2).getCell(10).value).toBe(0.125);
  });

});

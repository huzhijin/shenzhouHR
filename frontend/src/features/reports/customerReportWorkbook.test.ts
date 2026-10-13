import ExcelJS from 'exceljs';
import { describe, expect, it } from 'vitest';

import { getCustomerReportDemo } from './customerReportDemo';
import { persistAppearance } from '../../shared/appearance/appearance';
import {
  INCOMPLETE_REPORT_EXPORT_MESSAGE,
  buildCustomerReportWorkbook,
  workHoursExportHeaders,
} from './customerReportWorkbook';
import { toWorkHoursRows } from './customerReportMapper';

describe('customer report workbook', () => {
  it('exports work-hours headers for the named month on a white canvas', async () => {
    persistAppearance('night');
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    expect(workHoursExportHeaders('2026-06')[2]).toBe('6月应出勤工时');
    const { blob, fileName } = await buildCustomerReportWorkbook({
      reportKey: 'work-hours',
      reportTitle: '个人月度工时',
      month: '2026-06',
      report,
    });
    expect(fileName).toBe('2026-06_个人月度工时.xlsx');
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    const sheet = workbook.worksheets[0]!;
    expect(sheet.getRow(1).getCell(3).value).toBe('6月应出勤工时');
    expect(sheet.getRow(1).getCell(5).value).toBe('义务加班');
    const actual = sheet.getRow(2).getCell(10).value as ExcelJS.CellFormulaValue;
    expect(actual.formula).toBe('C2+D2+E2-F2-G2+H2-I2');
    const fill = sheet.getRow(2).getCell(1).fill as ExcelJS.FillPattern;
    expect(fill.fgColor?.argb).toBe('FFFFFFFF');
  });

  it('paints month-matrix rest-day cells with the daytime legend color', async () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const restIndex = report.attendanceRows[0]?.days.findIndex((day) => day.status === 'rest-day') ?? -1;
    expect(restIndex).toBeGreaterThanOrEqual(0);
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'attendance-detail',
      reportTitle: '月度考勤明细矩阵',
      month: '2026-06',
      report,
    });
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    const sheet = workbook.worksheets[0]!;
    const fill = sheet.getRow(2).getCell(restIndex + 5).fill as ExcelJS.FillPattern;
    expect(fill.fgColor?.argb).toBe('FFF2EFE7');
  });

  it('exports attendance detail as sign-in and sign-out rows with independent fills', async () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const employee = report.attendanceRows[0]!;
    employee.days[0] = {
      ...employee.days[0]!,
      primary: '08:32 迟到',
      secondary: '21:38',
      primaryStatus: 'late',
      secondaryStatus: 'overtime',
      merged: false,
      mergedLabel: undefined,
    };
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'attendance-detail',
      reportTitle: '月度考勤明细矩阵',
      month: '2026-06',
      report,
    });
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    const sheet = workbook.worksheets[0]!;
    expect(sheet.getRow(1).getCell(4).value).toBe('签到/签退');
    expect(sheet.getRow(2).getCell(4).value).toBe('签到');
    expect(sheet.getRow(3).getCell(4).value).toBe('签退');
    expect(sheet.getRow(2).getCell(5).value).toBe('08:32 迟到');
    expect(sheet.getRow(3).getCell(5).value).toBe('21:38');
    const inFill = sheet.getRow(2).getCell(5).fill as ExcelJS.FillPattern;
    const outFill = sheet.getRow(3).getCell(5).fill as ExcelJS.FillPattern;
    expect(inFill.fgColor?.argb).toBe('FFFF8578');
    expect(outFill.fgColor?.argb).toBe('FF3F8850');
  });

  it('writes full-day annual leave on both export rows without hours', async () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const employee = report.attendanceRows[0]!;
    employee.days[1] = {
      ...employee.days[1]!,
      primary: '年假',
      secondary: '年假',
      primaryStatus: 'annual-leave',
      secondaryStatus: 'annual-leave',
      merged: true,
      mergedLabel: '年假',
      mergedStatus: 'annual-leave',
    };
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'attendance-detail',
      reportTitle: '月度考勤明细矩阵',
      month: '2026-06',
      report,
    });
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    const sheet = workbook.worksheets[0]!;
    expect(sheet.getRow(2).getCell(6).value).toBe('年假');
    expect(sheet.getRow(3).getCell(6).value).toBe('年假');
    expect(String(sheet.getRow(2).getCell(6).value)).not.toContain('4.5');
  });

  it('hides the compensatory column when overtime type is 加班费', async () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'overtime-daily',
      reportTitle: '每日加班',
      month: '2026-06',
      report,
      overtimeType: '加班费',
    });
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    const sheet = workbook.worksheets[0]!;
    const headers: string[] = [];
    sheet.getRow(1).eachCell((cell) => headers.push(String(cell.value ?? '')));
    expect(headers).toContain('加班费');
    expect(headers).not.toContain('转调休');
    expect(blob.type).toContain('spreadsheetml.sheet');
    const bytes = new Uint8Array(await blob.arrayBuffer());
    expect(Array.from(bytes.slice(0, 2))).toEqual([0x50, 0x4B]);
  });

  it('exports a live snapshot whose company scope has empty client allow-lists', async () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    report.metadata.isDemo = false;
    report.metadata.dataScope = {
      reference: '41000000-0000-0000-0000-000000000003',
      type: 'COMPANY',
      label: '江苏神州半导体科技股份有限公司',
      actorLabel: '授权范围',
      allowedDepartments: [],
      allowedEmployees: [],
    };
    const { blob, fileName } = await buildCustomerReportWorkbook({
      reportKey: 'work-hours',
      reportTitle: '个人月度工时',
      month: '2026-06',
      report,
    });
    expect(fileName).toBe('2026-06_个人月度工时.xlsx');
    const bytes = new Uint8Array(await blob.arrayBuffer());
    expect(Array.from(bytes.slice(0, 2))).toEqual([0x50, 0x4B]);
  });

  function demo(month = '2026-09') {
    return getCustomerReportDemo({ month, department: '全部部门', employee: '全部员工' });
  }

  async function sheetOf(blob: Blob): Promise<ExcelJS.Worksheet> {
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await blob.arrayBuffer());
    return workbook.worksheets[0]!;
  }

  function headerIndex(sheet: ExcelJS.Worksheet, title: string): number {
    let found = -1;
    sheet.getRow(1).eachCell((cell, column) => {
      if (cell.value === title) found = column;
    });
    return found;
  }

  function cached(cell: ExcelJS.Cell): unknown {
    const value = cell.value as ExcelJS.CellFormulaValue;
    return value.result;
  }

  it('refuses a truncated load, so a partial roster never reaches a workbook', async () => {
    const report = demo();
    report.metadata.truncated = true;
    await expect(buildCustomerReportWorkbook({
      reportKey: 'work-hours', reportTitle: '个人月度工时', month: '2026-09', report,
    })).rejects.toThrow(INCOMPLETE_REPORT_EXPORT_MESSAGE);
  });

  it('exports a complete load that user filters reduced to zero rows', async () => {
    const report = demo();
    report.metadata.truncated = false;
    report.metadata.rowCount = 42;
    report.workHoursRows = [];
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'work-hours', reportTitle: '个人月度工时', month: '2026-09', report,
    });
    const sheet = await sheetOf(blob);
    expect(sheet.getRow(2).getCell(1).value).toBe('当前筛选条件下暂无记录');
  });

  it('finance overtime buckets formulas and cached results by the same business day type', async () => {
    const report = demo();
    report.metadata.truncated = false;
    const base = report.financeOvertimeRows[0]!;
    // 9/26 is a Saturday made a workday; 10/1 is a statutory holiday; 9/27 is a plain Sunday.
    report.financeOvertimeRows = [{
      ...base,
      weekdayOvertimeHours: 99, weekendOvertimeHours: 99, holidayOvertimeHours: 99,
      days: [
        { date: '2026-09-25', hours: 1.5, dayType: 'WORKDAY' },
        { date: '2026-09-26', hours: 2, dayType: 'ADJUSTED_WORKDAY' },
        { date: '2026-09-27', hours: 3.25, dayType: 'SUNDAY' },
        { date: '2026-10-01', hours: 4, dayType: 'PUBLIC_HOLIDAY' },
      ],
    }];
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'finance-overtime', reportTitle: '财务加班', month: '2026-09', report,
    });
    const sheet = await sheetOf(blob);
    const weekday = sheet.getRow(2).getCell(headerIndex(sheet, '平时加班'));
    const weekend = sheet.getRow(2).getCell(headerIndex(sheet, '周末加班'));
    const holiday = sheet.getRow(2).getCell(headerIndex(sheet, '节假日加班'));
    const col = (title: string) => headerIndex(sheet, title);
    const letter = (n: number) => sheet.getColumn(n).letter;
    // The cache must equal what Excel computes from the very cells the formula names.
    expect(cached(weekday)).toBe(3.5);
    expect((weekday.value as ExcelJS.CellFormulaValue).formula)
      .toBe(`SUM(${letter(col('9月25日'))}2,${letter(col('9月26日'))}2)`);
    expect(cached(weekend)).toBe(3.25);
    expect((weekend.value as ExcelJS.CellFormulaValue).formula).toBe(`${letter(col('9月27日'))}2`);
    expect(cached(holiday)).toBe(4);
    expect((holiday.value as ExcelJS.CellFormulaValue).formula).toBe(`${letter(col('10月1日'))}2`);
    // The total row also carries cached results equal to its SUM.
    const total = sheet.getRow(3);
    expect(total.getCell(1).value).toBe('总计');
    expect(cached(total.getCell(headerIndex(sheet, '平时加班')))).toBe(3.5);
    expect(cached(total.getCell(headerIndex(sheet, '9月26日')))).toBe(2);
  });

  it('carries voluntary overtime from the API through the workbook so its formula matches the cache', async () => {
    const projection = {
      rows: [
        ['SZ001', '甲', 8, 1.5, 9.5],
        ['SZ002', '乙', 8, 0, 8],
        ['SZ003', '丙', 8, 0.42, 8.42],
      ].map(([no, name, scheduled, voluntary, actual], i) => ({
        rowKey: `row-${i}`,
        values: {
          'employee-number': no, 'employee-name': name, organization: '制造中心',
          'scheduled-hours': String(scheduled), 'paid-overtime-hours': '0',
          'voluntary-overtime-hours': String(voluntary), 'leave-hours': '0',
          'annual-leave-hours': '0', 'compensatory-overtime-hours': '0',
          'time-off-hours': '0', 'actual-work-hours': String(actual),
        },
      })),
    } as unknown as Parameters<typeof toWorkHoursRows>[0];
    const rows = toWorkHoursRows(projection);
    expect(rows.map((row) => row.voluntaryOvertimeHours)).toEqual([1.5, 0, 0.42]);
    const report = demo();
    report.metadata.truncated = false;
    // A live company scope: the backend already applied authorization, so no demo name list.
    report.metadata.isDemo = false;
    report.metadata.dataScope = {
      reference: '41000000-0000-0000-0000-000000000003', type: 'COMPANY', label: '公司',
      actorLabel: '授权范围', allowedDepartments: [], allowedEmployees: [],
    };
    report.workHoursRows = rows;
    const { blob } = await buildCustomerReportWorkbook({
      reportKey: 'work-hours', reportTitle: '个人月度工时', month: '2026-09', report,
    });
    const sheet = await sheetOf(blob);
    const voluntaryCol = headerIndex(sheet, '义务加班');
    const actualCol = headerIndex(sheet, '个人实际出勤工时');
    rows.forEach((row, i) => {
      const excelRow = sheet.getRow(2 + i);
      expect(excelRow.getCell(voluntaryCol).value).toBe(row.voluntaryOvertimeHours);
      // Recomputing the visible parts reproduces the cached actual hours.
      const parts = [3, 4, 5].reduce((sum, c) => sum + Number(excelRow.getCell(c).value), 0)
        - [6, 7].reduce((sum, c) => sum + Number(excelRow.getCell(c).value), 0)
        + Number(excelRow.getCell(8).value) - Number(excelRow.getCell(9).value);
      expect(Number(parts.toFixed(4))).toBe(cached(excelRow.getCell(actualCol)));
    });
    expect(cached(sheet.getRow(5).getCell(voluntaryCol))).toBe(1.92);
  });
});


import ExcelJS from 'exceljs';
import { describe, expect, it } from 'vitest';

import { getCustomerReportDemo } from './customerReportDemo';
import { persistAppearance } from '../../shared/appearance/appearance';
import {
  buildCustomerReportWorkbook,
  workHoursExportHeaders,
} from './customerReportWorkbook';

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
});

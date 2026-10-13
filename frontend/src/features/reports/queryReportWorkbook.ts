import ExcelJS from 'exceljs';

import { triggerBrowserDownload } from '../../shared/api/apiClient';
import { excelWrappedDepartment } from './departmentPath';
import type { FinanceOvertimeDayCell } from './financeOvertimeLayout';
import { financeOvertimeDateBucket } from './financeOvertimeLayout';

const WHITE = 'FFFFFFFF';
const HEADER = 'FFDCEBF2';
const INK = 'FF24344D';

type CellValue = string | { formula: string; result: number };

export function buildWorkHoursExportRow(
  row: Record<string, unknown>,
  columnKeys: readonly string[],
  excelRow: number,
  formatOther: (key: string, value: unknown) => string,
): CellValue[] {
  // Build formula, numeric terms, and cached result from the same source
  const terms = [
    ['scheduledHours', 'D', 1], ['paidOvertimeHours', 'E', 1],
    ['voluntaryOvertimeHours', 'F', 1], ['leaveHours', 'G', -1],
    ['annualLeaveHours', 'H', -1], ['compensatoryOvertimeHours', 'I', 1],
    ['timeOffHours', 'J', -1],
  ] as const;
  const numeric = new Map(terms.map(([key]) => [key, Number(row[key] ?? 0)]));
  const formula = terms.map(([, col, sign], index) =>
    `${index === 0 ? '' : sign > 0 ? '+' : '-'}${col}${excelRow}`
  ).join('');
  const result = terms.reduce((sum, [key, , sign]) =>
    sum + sign * numeric.get(key)!, 0);
  return columnKeys.map((key) => {
    if (key === 'actualHours') return { formula, result };
    const term = terms.find(([hourKey]) => hourKey === key);
    if (term) return String(numeric.get(term[0])!);
    return formatOther(key, row[key]);
  });
}

export async function downloadQuerySheetWorkbook(request: {
  title: string;
  month: string;
  headers: string[];
  rows: Array<Array<CellValue>>;
  financeOvertime?: {
    dates: string[];
    dayRows: FinanceOvertimeDayCell[][];
  };
}): Promise<void> {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet(request.title.slice(0, 31), {
    views: [{ showGridLines: true, state: 'frozen', ySplit: 1 }],
  });
  const header = sheet.addRow(request.headers);
  header.eachCell((cell) => {
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: HEADER } };
    cell.font = { bold: true, color: { argb: INK } };
    cell.border = {
      top: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      left: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      bottom: { style: 'thin', color: { argb: 'FFD0D7DE' } },
      right: { style: 'thin', color: { argb: 'FFD0D7DE' } },
    };
  });
  const departmentIndex = request.headers.indexOf('部门');
  if (request.rows.length === 0) {
    sheet.addRow(['当前筛选条件下没有记录']);
  }
  // Pre-compute date→bucket map per row if financeOvertime metadata provided.
  const { financeOvertime } = request;
  const dateStart = financeOvertime
    ? request.headers.length - financeOvertime.dates.length + 1  // 1-based column
    : -1;

  request.rows.forEach((values, rowIndex) => {
    const excelRow = sheet.addRow(values.map((value, column) => (
      column === departmentIndex && typeof value === 'string' ? excelWrappedDepartment(value) : value
    )));
    excelRow.eachCell((cell, columnNumber) => {
      const raw = values[columnNumber - 1];
      if (typeof raw === 'object' && raw !== null && 'formula' in raw && 'result' in raw) {
        cell.value = { formula: raw.formula, result: raw.result };
      } else if (typeof raw === 'string' && raw.startsWith('=')) {
        // A formula without a cached result reads as an empty cell in any viewer that does
        // not recalculate, so a formula has to arrive as { formula, result }.
        throw new TypeError(
          `导出公式缺少计算结果，请改用 { formula, result }：第${excelRow.number}行第${columnNumber}列`,
        );
      } else if (typeof raw === 'string' && raw !== '' && /^-?\d+(\.\d+)?$/.test(raw)) {
        cell.value = Number(raw);
      }
      cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: WHITE } };
      cell.font = { color: { argb: INK } };
      if (departmentIndex >= 0 && columnNumber === departmentIndex + 1) {
        cell.alignment = { wrapText: true, vertical: 'middle', horizontal: 'left' };
      }
    });

    // Write SUM formulas for weekday/weekend/holiday summary cells.
    if (financeOvertime && dateStart > 0) {
      const dayRow = financeOvertime.dayRows[rowIndex] ?? [];
      const dayTypeByDate = new Map<string, string | undefined>();
      for (const cell of dayRow) {
        dayTypeByDate.set(String(cell.date).slice(0, 10), cell.dayType);
      }
      const weekdayCols: number[] = [];
      const weekendCols: number[] = [];
      const holidayCols: number[] = [];
      financeOvertime.dates.forEach((date, d) => {
        const colNum = dateStart + d;
        const dayType = dayTypeByDate.get(date);
        const bucket = financeOvertimeDateBucket(date, dayType);
        if (bucket === 'holiday') holidayCols.push(colNum);
        else if (bucket === 'weekend') weekendCols.push(colNum);
        else weekdayCols.push(colNum);
      });
      // Summary columns: weekday=4(D), weekend=5(E), holiday=6(F) — 1-based
      const r = excelRow.number;
      // Compute numeric sum from already-written date cells so result matches formula.
      const numericSum = (cols: number[]) =>
        cols.reduce((acc, c) => {
          const v = excelRow.getCell(c).value;
          return acc + (typeof v === 'number' ? v : 0);
        }, 0);
      const formulaStr = (cols: number[]) =>
        cols.map((c) => `${columnLetter(c)}${r}`).join('+');

      const weekdayCell = excelRow.getCell(4);
      const weekendCell = excelRow.getCell(5);
      const holidayCell = excelRow.getCell(6);

      if (weekdayCols.length > 0) {
        weekdayCell.value = { formula: formulaStr(weekdayCols), result: numericSum(weekdayCols) };
      } else {
        weekdayCell.value = 0;
      }
      if (weekendCols.length > 0) {
        weekendCell.value = { formula: formulaStr(weekendCols), result: numericSum(weekendCols) };
      } else {
        weekendCell.value = 0;
      }
      if (holidayCols.length > 0) {
        holidayCell.value = { formula: formulaStr(holidayCols), result: numericSum(holidayCols) };
      } else {
        holidayCell.value = 0;
      }
    }
  });
  const buffer = await workbook.xlsx.writeBuffer();
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  triggerBrowserDownload(blob, `${request.month}_${request.title}.xlsx`);
}

function columnLetter(col: number): string {
  let s = '';
  let n = col;
  while (n > 0) {
    s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

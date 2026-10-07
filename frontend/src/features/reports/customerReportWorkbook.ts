import ExcelJS from 'exceljs';

import { triggerBrowserDownload } from '../../shared/api/apiClient';
import { assertCustomerReportExportable } from './customerReportAccess';
import {
  REPORT_BADGE_COLORS,
  type AttendanceDayCell,
  type AttendanceStatusKey,
  type CustomerReportDemo,
  type CustomerReportKey,
} from './customerReportDemo';
import { excelWrappedDepartment } from './departmentPath';
import { isoWeekdayBucket, overtimeFeeColumns } from './financeOvertimeLayout';

const WHITE = 'FFFFFFFF';
const HEADER = 'FFDCEBF2';
const INK = 'FF24344D';

const STATUS_TO_BADGE: Partial<Record<AttendanceStatusKey, string>> = {
  late: 'LATE',
  early: 'EARLY_DEPARTURE',
  missed: 'MISSING_PUNCH',
  overtime: 'RECOGNIZED_OVERTIME',
  'time-off': 'TIME_OFF',
  out: 'OUTING',
  trip: 'TRIP',
  'personal-leave': 'PERSONAL_LEAVE',
  'sick-leave': 'SICK_LEAVE',
  'annual-leave': 'ANNUAL_LEAVE',
  'marriage-leave': 'MARRIAGE_LEAVE',
  'maternity-leave': 'MATERNITY_LEAVE',
  'paternity-leave': 'PATERNITY_LEAVE',
  'bereavement-leave': 'BEREAVEMENT_LEAVE',
  'work-injury-leave': 'WORK_INJURY_LEAVE',
  'nursing-leave': 'NURSING_LEAVE',
  'breastfeeding-leave': 'BREASTFEEDING_LEAVE',
  'prenatal-exam-leave': 'PRENATAL_EXAM_LEAVE',
  'family-planning-leave': 'FAMILY_PLANNING_LEAVE',
  'rest-day': 'REST_DAY',
  corrected: 'PUNCH_CORRECTION',
};

export interface CustomerReportWorkbookRequest {
  reportKey: CustomerReportKey;
  reportTitle: string;
  month: string;
  report: CustomerReportDemo;
  overtimeType?: string;
}

export async function buildCustomerReportWorkbook(
  request: CustomerReportWorkbookRequest,
): Promise<{ blob: Blob; fileName: string }> {
  assertReportRowsWithinScope(request.report);
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet(sheetName(request.reportTitle), {
    views: [{ showGridLines: true }],
    properties: { tabColor: { argb: HEADER } },
  });
  paintSheet(sheet, request);

  const buffer = await workbook.xlsx.writeBuffer();
  return {
    blob: new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
    fileName: `${request.month}_${safeFileName(request.reportTitle)}.xlsx`,
  };
}

export async function downloadCustomerReportWorkbook(
  request: CustomerReportWorkbookRequest,
): Promise<void> {
  const { blob, fileName } = await buildCustomerReportWorkbook(request);
  triggerBrowserDownload(blob, fileName);
}

function paintSheet(
  sheet: ExcelJS.Worksheet,
  request: CustomerReportWorkbookRequest,
): void {
  const { headers, rows, departmentColumn, matrix, matrixBands, mergeIdentityPairs } =
    visibleSheet(request);
  sheet.addRow(headers);
  styleHeader(sheet.getRow(1));
  if (rows.length === 0) {
    const empty = sheet.addRow(['当前筛选条件下暂无记录']);
    paintWhite(empty);
    return;
  }
  rows.forEach((values, rowIndex) => {
    const excelRow = sheet.addRow(values.map((value, column) => (
      column === departmentColumn ? excelWrappedDepartment(String(value)) : value
    )));
    applyRowFormulas(excelRow, request.reportKey, request.month, headers);
    paintWhite(excelRow);
    if (departmentColumn >= 0) {
      const cell = excelRow.getCell(departmentColumn + 1);
      cell.alignment = { wrapText: true, vertical: 'middle', horizontal: 'left' };
    }
    if (matrix) {
      applyMatrixFills(
        excelRow,
        matrix[rowIndex] ?? [],
        matrixBands?.[rowIndex] ?? 'in',
      );
    }
  });
  if (mergeIdentityPairs) {
    const pairCount = Math.floor(rows.length / 2);
    for (let pair = 0; pair < pairCount; pair += 1) {
      const start = 2 + pair * 2;
      const end = start + 1;
      sheet.mergeCells(start, 1, end, 1);
      sheet.mergeCells(start, 2, end, 2);
      sheet.mergeCells(start, 3, end, 3);
      [1, 2, 3].forEach((column) => {
        sheet.getRow(start).getCell(column).alignment = {
          wrapText: true,
          vertical: 'middle',
          horizontal: column === 3 ? 'left' : 'center',
        };
      });
    }
  }
  appendTotalRow(sheet, request.reportKey, headers, rows.length);
  sheet.views = [{
    state: 'frozen',
    ySplit: 1,
    xSplit: matrix ? (mergeIdentityPairs ? 4 : 3) : 0,
  }];
}

function applyRowFormulas(
  excelRow: ExcelJS.Row,
  reportKey: CustomerReportKey,
  month: string,
  headers: Array<string | number>,
) {
  const rowNum = excelRow.number;
  if (reportKey === 'work-hours') {
    const actual = headers.indexOf('个人实际出勤工时') + 1;
    if (actual > 0) {
      const result = Number(excelRow.getCell(actual).value ?? 0);
      excelRow.getCell(actual).value = {
        formula: `C${rowNum}+D${rowNum}+E${rowNum}-F${rowNum}-G${rowNum}+H${rowNum}-I${rowNum}`,
        result,
      };
    }
    return;
  }
  if (reportKey !== 'finance-overtime') {
    return;
  }
  const dateStart = headers.findIndex((header) => /月\d+日/.test(String(header)));
  if (dateStart < 0) {
    return;
  }
  const weekday: number[] = [];
  const weekend: number[] = [];
  headers.forEach((header, index) => {
    if (index < dateStart) {
      return;
    }
    const bucket = isoWeekdayBucket(financeHeaderDate(String(header), month));
    (bucket === 'weekend' ? weekend : weekday).push(index + 1);
  });
  const weekdayCol = headers.indexOf('平时加班') + 1;
  const weekendCol = headers.indexOf('周末加班') + 1;
  const holidayCol = headers.indexOf('节假日加班') + 1;
  if (weekdayCol > 0) {
    excelRow.getCell(weekdayCol).value = {
      formula: sumFormula(weekday, rowNum),
      result: Number(excelRow.getCell(weekdayCol).value ?? 0),
    };
  }
  if (weekendCol > 0) {
    excelRow.getCell(weekendCol).value = {
      formula: sumFormula(weekend, rowNum),
      result: Number(excelRow.getCell(weekendCol).value ?? 0),
    };
  }
  if (holidayCol > 0) {
    excelRow.getCell(holidayCol).value = { formula: '0', result: Number(excelRow.getCell(holidayCol).value ?? 0) };
  }
}

function appendTotalRow(
  sheet: ExcelJS.Worksheet,
  reportKey: CustomerReportKey,
  headers: Array<string | number>,
  dataRows: number,
) {
  if (dataRows <= 0) {
    return;
  }
  if (reportKey !== 'work-hours' && reportKey !== 'finance-overtime') {
    return;
  }
  const first = 2;
  const last = 1 + dataRows;
  const skip = new Set(['部门', '工号', '姓名', '加班人', '备注', '日期']);
  const total = sheet.addRow(headers.map((header, index) => {
    if (index === 0) {
      return '总计';
    }
    const title = String(header);
    if (skip.has(title) || title === '加班人') {
      return '';
    }
    if (
      title.includes('工时')
      || title.includes('假期')
      || title === '年假'
      || title.includes('调休')
      || title === '加班时数'
      || title === '义务加班'
      || title === '加班费'
      || title === '平时加班'
      || title === '周末加班'
      || title === '节假日加班'
      || /月\d+日/.test(title)
    ) {
      const col = excelColumn(index);
      return { formula: `SUM(${col}${first}:${col}${last})` };
    }
    return '';
  }));
  paintWhite(total);
  total.font = { bold: true, color: { argb: INK } };
}

function financeHeaderDate(label: string, month: string): string {
  const match = label.match(/(\d+)月(\d+)日/);
  if (!match) {
    return `${month}-01`;
  }
  return `${month.slice(0, 4)}-${String(match[1]).padStart(2, '0')}-${String(match[2]).padStart(2, '0')}`;
}

function sumFormula(columns: number[], rowNum: number): string {
  if (columns.length === 0) {
    return '0';
  }
  if (columns.length === 1) {
    return `${excelColumn(columns[0]! - 1)}${rowNum}`;
  }
  return `SUM(${columns.map((column) => `${excelColumn(column - 1)}${rowNum}`).join(',')})`;
}

function excelColumn(index: number): string {
  let remaining = index + 1;
  let letters = '';
  while (remaining > 0) {
    const modulo = (remaining - 1) % 26;
    letters = String.fromCharCode(65 + modulo) + letters;
    remaining = Math.floor((remaining - 1) / 26);
  }
  return letters;
}

function visibleSheet(request: CustomerReportWorkbookRequest): {
  headers: Array<string | number>;
  rows: Array<Array<string | number>>;
  departmentColumn: number;
  matrix?: AttendanceDayCell[][];
  matrixBands?: Array<'in' | 'out'>;
  mergeIdentityPairs?: boolean;
} {
  const { report, reportKey } = request;
  switch (reportKey) {
    case 'attendance-detail': {
      const days = report.attendanceRows[0]?.days ?? [];
      return {
        headers: [
          '工号',
          '姓名',
          '部门',
          '签到/签退',
          ...days.map((day) => `${String(day.day).padStart(2, '0')}日/周${day.weekday}`),
        ],
        rows: report.attendanceRows.flatMap((row) => [
          [
            row.employeeNo,
            row.employee,
            row.department,
            '签到',
            ...row.days.map((day) => (
              day.merged ? (day.mergedLabel || day.primary) : (day.primary || '')
            )),
          ],
          [
            row.employeeNo,
            row.employee,
            row.department,
            '签退',
            ...row.days.map((day) => (
              day.merged ? (day.mergedLabel || day.primary) : (day.secondary || '')
            )),
          ],
        ]),
        departmentColumn: 2,
        matrix: report.attendanceRows.flatMap((row) => [row.days, row.days]),
        matrixBands: report.attendanceRows.flatMap(() => ['in' as const, 'out' as const]),
        mergeIdentityPairs: true,
      };
    }
    case 'leave':
      return {
        headers: ['序号', '部门', '姓名', '类型', '时间（小时数）', '请假期间', '备注', '审批状态'],
        rows: report.leaveRows.map((row) => [
          row.id, row.department, row.employee, row.type,
          row.hours.toFixed(1), row.period, row.remark ?? '', row.approvalState,
        ]),
        departmentColumn: 1,
      };
    case 'overtime':
      return {
        headers: ['部门', '员工', '计薪加班', '转调休加班', '义务加班', '汇总加班'],
        rows: report.overtimeRows.map((row) => [
          row.department,
          row.employee,
          optionalHours(row.paidHours),
          optionalHours(row.compensatoryHours),
          optionalHours(row.voluntaryHours),
          optionalHours(row.totalHours),
        ]),
        departmentColumn: 0,
      };
    case 'work-hours':
      return {
        headers: [
          '姓名',
          '部门',
          `${Number.parseInt(request.month.slice(5, 7), 10)}月应出勤工时`,
          '加班时数',
          '义务加班',
          '事假+病假+其他假期',
          '年假',
          '加班换调休',
          '实际调休',
          '个人实际出勤工时',
          '备注',
        ],
        rows: report.workHoursRows.map((row) => [
          row.employee,
          row.department,
          row.plannedHours,
          row.overtimeHours,
          row.voluntaryOvertimeHours ?? 0,
          row.leaveHours,
          row.annualLeaveHours ?? 0,
          row.exchangedHours ?? 0,
          row.usedTimeOffHours ?? 0,
          row.actualHours,
          row.note ?? '',
        ]),
        departmentColumn: 1,
      };
    case 'exceptions':
      return {
        headers: [
          '考勤日期', '异常类型', '工号', '姓名', '部门', '详情', '处理状态',
        ],
        rows: report.attendanceExceptionRows.map((row) => [
          row.businessDate, row.exceptionType, row.employeeNo,
          row.employee, row.department, row.details, row.state,
        ]),
        departmentColumn: 4,
      };
    case 'late':
      return exceptionSheet(report.lateRows);
    case 'missed-punch':
      return exceptionSheet(report.missedPunchRows);
    case 'attendance-rate':
      return {
        headers: ['序号', '部门', '姓名', '应出勤天数', '实际出勤天数', '病假天数', '出勤率', '口径说明'],
        rows: report.attendanceRateRows.map((row) => [
          row.id, row.department, row.employee,
          row.scheduledDays ?? '—', row.actualDays ?? '—', row.sickLeaveDays ?? '—',
          row.rate, row.note ?? '',
        ]),
        departmentColumn: 1,
      };
    case 'annual-leave':
      return {
        headers: ['序号', '部门', '姓名', '入职日期', '公司工龄', '可休天数', '可休小时数', '实际剩余天数', '备注'],
        rows: report.annualLeaveRows.map((row) => [
          row.id, row.department, row.employee, row.joinedOn ?? '—',
          row.companySeniority?.toFixed(1) ?? '—',
          row.availableDays.toFixed(1), row.availableHours.toFixed(1),
          row.remainingDays.toFixed(1), row.note ?? '',
        ]),
        departmentColumn: 1,
      };
    case 'overtime-daily': {
      const feeColumns = overtimeFeeColumns(request.overtimeType);
      return {
        headers: [
          '工号', '部门', '姓名', '日期', '工作日加班', '周末加班', '节假日加班',
          ...(feeColumns.paid ? ['加班费'] : []),
          ...(feeColumns.compensatory ? ['转调休'] : []),
          ...(feeColumns.voluntary ? ['义务加班'] : []),
        ],
        rows: report.overtimeDailyRows.map((row) => [
          row.employeeNo, row.department, row.employee, row.businessDate,
          row.weekdayOvertimeHours, row.weekendOvertimeHours, row.holidayOvertimeHours,
          ...(feeColumns.paid ? [row.paidOvertimeHours ?? 0] : []),
          ...(feeColumns.compensatory ? [row.compensatoryOvertimeHours ?? 0] : []),
          ...(feeColumns.voluntary ? [row.voluntaryOvertimeHours ?? 0] : []),
        ]),
        departmentColumn: 1,
      };
    }
    case 'finance-overtime': {
      const dates = [...new Set(
        report.financeOvertimeRows.flatMap((row) => row.days.map((day) => day.date.slice(0, 10))),
      )].sort();
      const feeColumns = overtimeFeeColumns(request.overtimeType);
      return {
        headers: [
          '部门', '工号', '加班人', '平时加班', '周末加班', '节假日加班',
          ...(feeColumns.paid ? ['加班费'] : []),
          ...(feeColumns.compensatory ? ['转调休'] : []),
          ...(feeColumns.voluntary ? ['义务加班'] : []),
          ...dates.map((date) => `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`),
        ],
        rows: report.financeOvertimeRows.map((row) => {
          const byDate = new Map(row.days.map((day) => [day.date.slice(0, 10), day.hours]));
          return [
            row.department, row.employeeNo, row.employee,
            row.weekdayOvertimeHours, row.weekendOvertimeHours, row.holidayOvertimeHours,
            ...(feeColumns.paid ? [row.paidOvertimeHours ?? 0] : []),
            ...(feeColumns.compensatory ? [row.compensatoryOvertimeHours ?? 0] : []),
            ...(feeColumns.voluntary ? [row.voluntaryOvertimeHours ?? 0] : []),
            ...dates.map((date) => byDate.get(date) ?? ''),
          ];
        }),
        departmentColumn: 0,
      };
    }
    case 'daily-journal':
      return {
        headers: ['序号', '部门', '工号', '姓名', '日期', '班次', '上班', '下班', '迟到', '早退', '旷工', '请假', '加班', '备注'],
        rows: report.dailyJournalRows.map((row) => [
          row.sequence, row.department, row.employeeNo, row.employee, row.businessDate,
          row.shiftLabel, row.onDuty, row.offDuty, row.lateHours, row.earlyHours,
          row.absenceHours, row.leaveType, row.overtimeHours, row.remark,
        ]),
        departmentColumn: 1,
      };
  }
}

function exceptionSheet(rows: CustomerReportDemo['lateRows']) {
  return {
    headers: ['序号', '部门', '姓名', '次数', '发生明细', '复核人', '处理状态'],
    rows: rows.map((row) => [
      row.id, row.department, row.employee, row.count,
      row.details, row.reviewer ?? '', row.state ?? '',
    ]),
    departmentColumn: 1,
  };
}

function applyMatrixFills(
  excelRow: ExcelJS.Row,
  days: AttendanceDayCell[],
  band: 'in' | 'out' = 'in',
): void {
  days.forEach((day, index) => {
    const status = day.merged
      ? day.mergedStatus
      : (band === 'in' ? day.primaryStatus : day.secondaryStatus);
    const hex = statusColor(status);
    if (!hex) return;
    const cell = excelRow.getCell(index + 5);
    cell.fill = {
      type: 'pattern',
      pattern: 'solid',
      fgColor: { argb: `FF${hex.replace('#', '').toUpperCase()}` },
    };
    cell.alignment = { wrapText: true, vertical: 'middle', horizontal: 'center' };
  });
}

function statusColor(status: AttendanceStatusKey | undefined): string | undefined {
  if (!status) return undefined;
  const badge = STATUS_TO_BADGE[status];
  return badge ? REPORT_BADGE_COLORS[badge] : undefined;
}

function styleHeader(row: ExcelJS.Row): void {
  row.eachCell((cell) => {
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: HEADER } };
    cell.font = { bold: true, color: { argb: INK } };
    cell.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
  });
}

function paintWhite(row: ExcelJS.Row): void {
  row.eachCell((cell) => {
    if (!cell.fill) {
      cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: WHITE } };
    }
    cell.font = { ...(cell.font ?? {}), color: { argb: INK } };
  });
}

function optionalHours(value: number | undefined): string {
  return value !== undefined ? value.toFixed(1) : '—';
}

function sheetName(title: string): string {
  const safe = title.replaceAll(/[\\/*?:[\]]/g, '_');
  return safe.slice(0, 31) || '考勤报表';
}

function safeFileName(value: string): string {
  return value.replaceAll(/[\\/:*?"<>|]/g, '-').trim() || '考勤报表';
}

function assertReportRowsWithinScope(report: CustomerReportDemo): void {
  assertCustomerReportExportable(
    [
      ...report.attendanceRows,
      ...report.leaveRows,
      ...report.overtimeRows,
      ...report.workHoursRows,
      ...report.attendanceExceptionRows,
      ...report.lateRows,
      ...report.missedPunchRows,
      ...report.attendanceRateRows,
      ...report.annualLeaveRows,
      ...report.dailyJournalRows,
      ...report.overtimeDailyRows,
      ...report.financeOvertimeRows,
    ],
    report.metadata.dataScope,
    report.metadata.isDemo,
  );
}

export function workHoursExportHeaders(month: string): string[] {
  return [
    '姓名',
    '部门',
    `${Number.parseInt(month.slice(5, 7), 10)}月应出勤工时`,
    '加班时数',
    '义务加班',
    '事假+病假+其他假期',
    '年假',
    '加班换调休',
    '实际调休',
    '个人实际出勤工时',
    '备注',
  ];
}

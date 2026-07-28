import {
  attendanceLegend,
  formatMonth,
  type CustomerReportDemo,
  type CustomerReportKey,
} from './customerReportDemo';
import { isWithinCustomerReportScope } from './customerReportAccess';

type CsvCell = string | number;

export interface CustomerReportCsvRequest {
  reportKey: CustomerReportKey;
  reportTitle: string;
  month: string;
  department: string;
  employee: string;
  generatedAt: string;
  reportFilters: Record<string, string>;
  report: CustomerReportDemo;
}

export interface CustomerReportCsvDownload {
  csv: string;
  fileName: string;
}

const UTF8_BOM = '\uFEFF';

export function buildCustomerReportCsv(
  request: CustomerReportCsvRequest,
): CustomerReportCsvDownload {
  assertReportRowsWithinScope(request.report);
  const { headers, rows } = visibleRowsForReport(request.report, request.reportKey);
  const filterRows = Object.entries(request.reportFilters);
  const csvRows: CsvCell[][] = [
    ['报表信息'],
    ['报表名称', request.reportTitle],
    ['公司', request.report.metadata.company],
    ['月份', formatMonth(request.month)],
    ['部门', request.department],
    ['员工', request.employee],
    ['数据权限角色', request.report.metadata.dataScope.actorLabel],
    ['数据权限范围', request.report.metadata.dataScope.label],
    ['当前可见记录数', rows.length],
    ['生成时间', request.generatedAt],
    [],
    ['专属筛选条件'],
    ...(filterRows.length > 0 ? filterRows : [['无', '全部']]),
    [],
    ['当前筛选后的可见数据'],
    headers,
    ...(rows.length > 0 ? rows : [['当前筛选条件下暂无记录']]),
  ];

  return {
    csv: `${UTF8_BOM}${csvRows.map(toCsvRow).join('\r\n')}\r\n`,
    fileName: `${request.month}_${safeFileName(request.reportTitle)}.csv`,
  };
}

export function downloadCustomerReportCsv({
  csv,
  fileName,
}: CustomerReportCsvDownload): void {
  if (
    typeof document === 'undefined'
    || typeof URL === 'undefined'
    || typeof URL.createObjectURL !== 'function'
  ) {
    return;
  }

  const objectUrl = URL.createObjectURL(new Blob([csv], {
    type: 'text/csv;charset=utf-8',
  }));
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(objectUrl);
}

function visibleRowsForReport(
  report: CustomerReportDemo,
  reportKey: CustomerReportKey,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  switch (reportKey) {
    case 'attendance-detail':
      return attendanceDetailRows(report);
    case 'leave':
      return {
        headers: ['序号', '部门', '姓名', '类型', '时间（小时数）', '请假期间', '备注', '审批状态'],
        rows: report.leaveRows.map((row) => [
          row.id,
          row.department,
          row.employee,
          row.type,
          row.hours.toFixed(1),
          row.period,
          row.remark,
          row.approvalState,
        ]),
      };
    case 'overtime':
      return overtimeReportRows(report);
    case 'work-hours':
      return {
        headers: [
          '姓名',
          '部门',
          '应出勤工时',
          '加班时数',
          '事假+病假+其他假期',
          '年假',
          '加班换调休',
          '个人实际出勤工时',
          '备注',
        ],
        rows: report.workHoursRows.map((row) => [
          row.employee,
          row.department,
          row.plannedHours.toFixed(1),
          row.overtimeHours.toFixed(1),
          row.leaveHours.toFixed(1),
          row.annualLeaveHours.toFixed(1),
          row.exchangedHours.toFixed(1),
          row.actualHours.toFixed(1),
          row.note,
        ]),
      };
    case 'exceptions':
      return {
        headers: [
          '考勤日期',
          '级别',
          '异常类型',
          '工号',
          '姓名',
          '部门',
          '班次',
          '应出勤',
          '打卡摘要',
          '异常分钟',
          '证据摘要',
          '处理状态',
          '负责人',
          '处理时限',
        ],
        rows: report.attendanceExceptionRows.map((row) => [
          row.businessDate,
          row.severity,
          row.exceptionType,
          row.employeeNo,
          row.employee,
          row.department,
          row.shiftLabel,
          row.scheduledWindow,
          row.punchSummary,
          row.exceptionMinutes ?? '',
          row.evidenceSummary,
          row.state,
          row.owner,
          row.dueAt,
        ]),
      };
    case 'late':
      return exceptionRows(report.lateRows);
    case 'missed-punch':
      return exceptionRows(report.missedPunchRows);
    case 'attendance-rate':
      return {
        headers: ['序号', '部门', '姓名', '主要缺勤类型', '缺勤时间（小时）', '出勤率', '口径说明'],
        rows: report.attendanceRateRows.map((row) => [
          row.id,
          row.department,
          row.employee,
          row.type,
          row.hours.toFixed(1),
          row.rate,
          row.note,
        ]),
      };
    case 'annual-leave':
      return annualLeaveReportRows(report);
  }
}

function assertReportRowsWithinScope(report: CustomerReportDemo): void {
  const rows = [
    ...report.attendanceRows,
    ...report.leaveRows,
    ...report.overtimeRows,
    ...report.workHoursRows,
    ...report.attendanceExceptionRows,
    ...report.lateRows,
    ...report.missedPunchRows,
    ...report.attendanceRateRows,
    ...report.annualLeaveRows,
  ];
  if (rows.some((row) => !isWithinCustomerReportScope(
    row,
    report.metadata.dataScope,
  ))) {
    throw new TypeError('报表包含超出当前数据权限范围的记录');
  }
}

function attendanceDetailRows(
  report: CustomerReportDemo,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  const days = report.attendanceRows[0]?.days ?? [];
  return {
    headers: [
      '工号',
      '姓名',
      '部门',
      '职位',
      ...days.map((day) => `${String(day.day).padStart(2, '0')}日/周${day.weekday}`),
    ],
    rows: report.attendanceRows.map((row) => [
      row.employeeNo,
      row.employee,
      row.department,
      row.position,
      ...row.days.map((day) => {
        const status = day.status
          ? attendanceLegend.find((item) => item.key === day.status)?.label
          : '';
        return [
          day.primary,
          day.secondary,
          status,
          day.note,
        ].filter(Boolean).join(' / ');
      }),
    ]),
  };
}

function overtimeReportRows(
  report: CustomerReportDemo,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  const dayCount = report.overtimeRows[0]?.dailyHours.length ?? daysInMonth(report.metadata.month);
  return {
    headers: [
      '部门',
      '员工',
      '平时加班',
      '周末加班',
      '法定节假日加班',
      '加班换调休',
      ...Array.from({ length: dayCount }, (_, index) => `${index + 1}日`),
    ],
    rows: report.overtimeRows.map((row) => [
      row.department,
      row.employee,
      row.weekdayHours.toFixed(1),
      row.weekendHours.toFixed(1),
      row.statutoryHours.toFixed(1),
      row.exchangedHours.toFixed(1),
      ...row.dailyHours.map((hours) => hours || ''),
    ]),
  };
}

function exceptionRows(
  rows: CustomerReportDemo['lateRows'],
): { headers: CsvCell[]; rows: CsvCell[][] } {
  return {
    headers: ['序号', '部门', '姓名', '次数', '发生明细', '复核人', '处理状态'],
    rows: rows.map((row) => [
      row.id,
      row.department,
      row.employee,
      row.count,
      row.details,
      row.reviewer,
      row.state,
    ]),
  };
}

function annualLeaveReportRows(
  report: CustomerReportDemo,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  return {
    headers: [
      '序号',
      '一级部门',
      '二级部门',
      '姓名',
      '入职日期',
      '公司工龄',
      '公司外已证明工龄',
      '累计工龄',
      '法定年休假天数',
      '新员工折算天数',
      '可休天数',
      '可休小时数',
      ...Array.from({ length: 12 }, (_, index) => `${index + 1}月`),
      '实际剩余天数',
      '备注',
    ],
    rows: report.annualLeaveRows.map((row) => [
      row.id,
      row.departmentLevelOne,
      row.departmentLevelTwo,
      row.employee,
      row.joinedOn,
      row.companySeniority.toFixed(1),
      row.priorSeniority.toFixed(1),
      row.totalSeniority.toFixed(1),
      row.statutoryDays.toFixed(1),
      row.newHireDays.toFixed(1),
      row.availableDays.toFixed(1),
      row.availableHours.toFixed(1),
      ...row.monthlyUsedDays.map((days) => days.toFixed(1)),
      row.remainingDays.toFixed(1),
      row.note,
    ]),
  };
}

function toCsvRow(row: CsvCell[]): string {
  return row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(',');
}

function safeFileName(value: string): string {
  return value.replaceAll(/[\\/:*?"<>|]/g, '-').trim() || '考勤报表';
}

function daysInMonth(month: string): number {
  const [year, monthNumber] = month.split('-').map(Number);
  return new Date(year!, monthNumber!, 0).getDate();
}

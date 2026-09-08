import { triggerBrowserDownload } from '../../shared/api/apiClient';
import {
  formatMonth,
  type CustomerReportDemo,
  type CustomerReportKey,
} from './customerReportDemo';
import { assertCustomerReportExportable } from './customerReportAccess';

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

  triggerBrowserDownload(new Blob([csv], {
    type: 'text/csv;charset=utf-8',
  }), fileName);
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
          row.remark ?? '',
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
          '实际调休',
          '个人实际出勤工时',
          '备注',
        ],
        rows: report.workHoursRows.map((row) => [
          row.employee,
          row.department,
          row.plannedHours.toFixed(1),
          row.overtimeHours.toFixed(1),
          row.leaveHours.toFixed(1),
          row.annualLeaveHours !== undefined ? row.annualLeaveHours.toFixed(1) : '—',
          row.exchangedHours !== undefined ? row.exchangedHours.toFixed(1) : '—',
          row.usedTimeOffHours !== undefined ? row.usedTimeOffHours.toFixed(1) : '—',
          row.actualHours.toFixed(1),
          row.note ?? '',
        ]),
      };
    case 'exceptions':
      return {
        headers: [
          '考勤日期',
          '异常类型',
          '工号',
          '姓名',
          '部门',
          '详情',
          '处理状态',
        ],
        rows: report.attendanceExceptionRows.map((row) => [
          row.businessDate,
          row.exceptionType,
          row.employeeNo,
          row.employee,
          row.department,
          row.details,
          row.state,
        ]),
      };
    case 'late':
      return exceptionRows(report.lateRows);
    case 'missed-punch':
      return exceptionRows(report.missedPunchRows);
    case 'attendance-rate':
      return {
        headers: ['序号', '部门', '姓名', '应出勤天数', '实际出勤天数', '病假天数', '出勤率', '口径说明'],
        rows: report.attendanceRateRows.map((row) => [
          row.id,
          row.department,
          row.employee,
          optionalDays(row.scheduledDays),
          optionalDays(row.actualDays),
          optionalDays(row.sickLeaveDays),
          row.rate,
          row.note ?? '',
        ]),
      };
    case 'annual-leave':
      return annualLeaveReportRows(report);
    case 'overtime-daily':
      return {
        headers: ['工号', '部门', '姓名', '日期', '工作日加班', '周末加班', '节假日加班', '加班费', '转调休'],
        rows: report.overtimeDailyRows.map((row) => [
          row.employeeNo, row.department, row.employee, row.businessDate,
          row.weekdayOvertimeHours, row.weekendOvertimeHours, row.holidayOvertimeHours,
          row.paidOvertimeHours ?? 0, row.compensatoryOvertimeHours ?? 0,
        ]),
      };
    case 'finance-overtime': {
      const dates = uniqueFinanceDates(report);
      return {
        headers: [
          '部门', '工号', '加班人', '平时加班', '周末加班', '节假日加班', '加班费', '转调休',
          ...dates.map((date) => `${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`),
        ],
        rows: report.financeOvertimeRows.map((row) => {
          const byDate = new Map(row.days.map((day) => [day.date.slice(0, 10), day.hours]));
          return [
            row.department, row.employeeNo, row.employee,
            row.weekdayOvertimeHours, row.weekendOvertimeHours, row.holidayOvertimeHours,
            row.paidOvertimeHours ?? 0, row.compensatoryOvertimeHours ?? 0,
            ...dates.map((date) => byDate.get(date) ?? ''),
          ];
        }),
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
      };
  }
}

function uniqueFinanceDates(report: CustomerReportDemo): string[] {
  const dates = new Set<string>();
  report.financeOvertimeRows.forEach((row) => {
    row.days.forEach((day) => {
      const key = day.date.slice(0, 10);
      if (key) dates.add(key);
    });
  });
  return [...dates].sort();
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
      row.position ?? '—',
      ...row.days.map((day) => {
        if (day.merged) {
          return day.mergedLabel || day.primary;
        }
        return [day.primary, day.secondary].filter(Boolean).join('\n');
      }),
    ]),
  };
}

function overtimeReportRows(
  report: CustomerReportDemo,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  return {
    headers: [
      '工号',
      '部门',
      '姓名',
      '加班类型',
      '加班时段',
      '认定小时',
      '审批状态',
      '来源',
    ],
    rows: report.overtimeRows.map((row) => [
      row.employeeNo ?? '',
      row.department,
      row.employee,
      row.overtimeType,
      row.period,
      row.hours.toFixed(1),
      row.approvalState,
      row.source,
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
      row.reviewer ?? '',
      row.state ?? '',
    ]),
  };
}

function annualLeaveReportRows(
  report: CustomerReportDemo,
): { headers: CsvCell[]; rows: CsvCell[][] } {
  return {
    headers: [
      '序号',
      '部门',
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
      row.department,
      row.employee,
      row.joinedOn ?? '—',
      optionalHours(row.companySeniority),
      optionalHours(row.priorSeniority),
      optionalHours(row.totalSeniority),
      optionalHours(row.statutoryDays),
      optionalHours(row.newHireDays),
      row.availableDays.toFixed(1),
      row.availableHours.toFixed(1),
      ...(row.monthlyUsedDays ?? []).map((days) => days.toFixed(1)),
      row.remainingDays.toFixed(1),
      row.note ?? '',
    ]),
  };
}

/** Optional numeric column: return a formatted string or a dash placeholder. */
function optionalHours(value: number | undefined): CsvCell {
  return value !== undefined ? value.toFixed(1) : '—';
}

function optionalDays(value: number | undefined): CsvCell {
  return value ?? '—';
}

function toCsvRow(row: CsvCell[]): string {
  return row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(',');
}

function safeFileName(value: string): string {
  return value.replaceAll(/[\\/:*?"<>|]/g, '-').trim() || '考勤报表';
}



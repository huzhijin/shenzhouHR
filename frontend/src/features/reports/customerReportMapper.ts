import type {
  AttendanceMonthMatrixBadgeCode,
  AttendanceMonthMatrixProjection,
  ReportColumnKey,
  ReportProjection,
  ReportRowProjection,
} from '../wave7/wave7Contracts';
import type {
  AnnualLeaveReportRow,
  AttendanceDayCell,
  AttendanceDetailRow,
  AttendanceExceptionReportRow,
  AttendanceExceptionSeverity,
  AttendanceExceptionState,
  AttendanceExceptionType,
  AttendanceRateReportRow,
  AttendanceStatusKey,
  ExceptionReportRow,
  LeaveReportRow,
  OvertimeReportRow,
  WorkHoursReportRow,
} from './customerReportDemo';

/**
 * The backend projection is narrower than the nine designed report sheets: some
 * designed columns have no field in `AttendanceReportModels.ReportField` yet.
 * Those stay `undefined` so the sheet renders an explicit dash instead of a
 * fabricated zero — see docs/reporting/task-b-attendance-reporting-prd.md (B-1b).
 */

type RowValues = ReportRowProjection['values'];

function reportIdentity(row: ReportRowProjection) {
  return {
    rowKey: row.rowReference,
  };
}

function text(values: RowValues, key: ReportColumnKey): string | undefined {
  const value = values[key];
  if (value === undefined || value === '') return undefined;
  return typeof value === 'number' ? String(value) : value;
}

function required(values: RowValues, key: ReportColumnKey): string {
  return text(values, key) ?? '—';
}

function decimal(values: RowValues, key: ReportColumnKey): number | undefined {
  const value = values[key];
  if (value === undefined || value === '') return undefined;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function count(values: RowValues, key: ReportColumnKey): number {
  return decimal(values, key) ?? 0;
}

/** `DOCUMENT_END` is an exclusive boundary; render it as the closing instant. */
function period(values: RowValues): string {
  const start = text(values, 'document-start');
  const end = text(values, 'document-end');
  if (start === undefined && end === undefined) return '—';
  return `${localDateTime(start)} ~ ${localDateTime(end)}`;
}

function localDateTime(value: string | undefined): string {
  if (value === undefined) return '—';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

function localDate(value: string | undefined): string {
  if (value === undefined) return '—';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

const leaveTypeLabels: Readonly<Record<string, string>> = {
  ANNUAL: '年假',
  SICK: '病假',
  MARRIAGE: '婚假',
  MATERNITY: '产假',
  PATERNITY: '陪产假',
  BEREAVEMENT: '丧假',
  WORK_INJURY: '工伤假',
  PRENATAL_NURSING: '孕检/哺乳假',
  PERSONAL: '事假',
  COMPENSATORY: '调休',
};

function leaveType(values: RowValues): string {
  const raw = required(values, 'document-type');
  return raw === '—' ? raw : leaveTypeLabels[raw.toUpperCase()] ?? raw;
}

export function toLeaveRows(
  response: ReportProjection,
): LeaveReportRow[] {
  return response.rows.map((row, index) => ({
    ...reportIdentity(row),
    id: index + 1,
    employee: required(row.values, 'employee-name'),
    department: required(row.values, 'organization'),
    type: leaveType(row.values),
    hours: count(row.values, 'recognized-hours'),
    period: period(row.values),
    approvalState: required(row.values, 'approval-state'),
  }));
}

export function toOvertimeRows(
  response: ReportProjection,
): OvertimeReportRow[] {
  return response.rows.map((row) => {
    const paidHours = decimal(row.values, 'paid-overtime-hours');
    const compensatoryHours = decimal(row.values, 'compensatory-overtime-hours');
    const voluntaryHours = decimal(row.values, 'voluntary-overtime-hours');
    const classificationAvailable = [
      paidHours,
      compensatoryHours,
      voluntaryHours,
    ].every((value) => value !== undefined);
    const classifiedTotal = decimal(row.values, 'total-overtime-hours')
      ?? (classificationAvailable
        ? (paidHours ?? 0) + (compensatoryHours ?? 0) + (voluntaryHours ?? 0)
        : undefined);

    // Old deployments only expose calendar buckets. They remain unclassified:
    // never reinterpret weekday/weekend/holiday as paid/compensatory/voluntary.
    const legacyParts = [
      decimal(row.values, 'weekday-overtime-hours'),
      decimal(row.values, 'saturday-overtime-hours'),
      decimal(row.values, 'sunday-overtime-hours'),
      decimal(row.values, 'holiday-overtime-hours'),
    ];
    const legacyCalendarTotal = legacyParts.some((value) => value !== undefined)
      ? legacyParts.reduce<number>((total, value) => total + (value ?? 0), 0)
      : undefined;
    const legacyTotal = decimal(row.values, 'recognized-overtime-hours')
      ?? legacyCalendarTotal;
    return {
      ...reportIdentity(row),
      employee: required(row.values, 'employee-name'),
      department: required(row.values, 'organization'),
      paidHours,
      compensatoryHours,
      voluntaryHours,
      totalHours: classifiedTotal ?? legacyTotal,
      classificationAvailable,
    };
  });
}

export function toWorkHoursRows(
  response: ReportProjection,
): WorkHoursReportRow[] {
  return response.rows.map((row) => ({
    ...reportIdentity(row),
    employee: required(row.values, 'employee-name'),
    department: required(row.values, 'organization'),
    plannedHours: count(row.values, 'scheduled-hours'),
    overtimeHours: count(row.values, 'recognized-overtime-hours'),
    leaveHours: count(row.values, 'leave-hours'),
    actualHours: count(row.values, 'actual-work-hours'),
  }));
}

export function toAttendanceRateRows(
  response: ReportProjection,
): AttendanceRateReportRow[] {
  return response.rows.map((row, index) => {
    const rawRate = required(row.values, 'attendance-rate');
    const hasDayMetrics = decimal(row.values, 'scheduled-attendance-days') !== undefined
      || decimal(row.values, 'actual-attendance-days') !== undefined;
    return {
      ...reportIdentity(row),
      id: index + 1,
      employee: required(row.values, 'employee-name'),
      department: required(row.values, 'organization'),
      hours: decimal(row.values, 'confirmed-hours'),
      scheduledDays: decimal(row.values, 'scheduled-attendance-days'),
      actualDays: decimal(row.values, 'actual-attendance-days'),
      sickLeaveDays: decimal(row.values, 'sick-leave-days'),
      rate: rawRate === '—'
        || rawRate.toUpperCase() === 'N/A'
        || rawRate.endsWith('%')
        ? rawRate
        : `${rawRate}%`,
      note: hasDayMetrics ? '按实际出勤天数 ÷ 应出勤天数' : undefined,
    };
  });
}

export function toLateRows(
  response: ReportProjection,
): ExceptionReportRow[] {
  return response.rows.map((row, index) => {
    const lateMinutes = decimal(row.values, 'late-minutes');
    const penalized = decimal(row.values, 'penalized-late-minutes');
    const details = [
      lateMinutes === undefined ? undefined : `累计 ${lateMinutes} 分钟`,
      penalized === undefined ? undefined : `计罚 ${penalized} 分钟`,
    ].filter((value): value is string => value !== undefined);
    return {
      ...reportIdentity(row),
      id: index + 1,
      employee: required(row.values, 'employee-name'),
      department: required(row.values, 'organization'),
      count: count(row.values, 'late-event-count'),
      details: details.length > 0 ? details.join(' · ') : '—',
      lateMinutes,
    };
  });
}

export function toMissedPunchRows(
  response: ReportProjection,
): ExceptionReportRow[] {
  return response.rows.map((row, index) => ({
    ...reportIdentity(row),
    id: index + 1,
    employee: required(row.values, 'employee-name'),
    department: required(row.values, 'organization'),
    count: count(row.values, 'missing-punch-count'),
    details: '—',
  }));
}

/** Keys are `AttendanceExceptionModels.ExceptionType` names, all 22 of them. */
const exceptionTypeLabels: Record<string, AttendanceExceptionType> = {
  LATE: '迟到',
  EARLY_DEPARTURE: '早退',
  MISSING_PUNCH_PENDING: '缺卡待补签',
  MISSING_PUNCH_OVERDUE: '缺卡超期',
  ABSENCE: '旷工',
  EVIDENCE_CONFLICT: '证据冲突',
  LEAVE_PUNCH_CONFLICT: '请假与打卡冲突',
  OUTING_OR_TRIP_INCOMPLETE: '外出出差不完整',
  OA_APPROVAL_STATUS_UNKNOWN: 'OA审批状态未知',
  OA_PERSON_REFERENCE_INVALID: 'OA人员引用无效',
  EMPLOYEE_UNMATCHED: '员工未匹配',
  DUPLICATE_SOURCE_RECORD: '来源记录重复',
  SOURCE_SCHEMA_CHANGED: '来源结构变更',
  SOURCE_SYNC_STALE: '来源同步过期',
  NO_ATTENDANCE_GROUP: '无考勤组',
  NO_SHIFT_OR_CALENDAR: '无班次或日历',
  AMBIGUOUS_PUNCH_MATCH: '打卡配对歧义',
  CROSS_MIDNIGHT_REVIEW_REQUIRED: '跨午夜待复核',
  OVERTIME_DOCUMENT_MISSING_OR_LATE: '加班未审批',
  EARLY_RETURN_CANDIDATE: '提前返回待确认',
  POST_CLOSE_SOURCE_CHANGE: '月结后来源变更',
  INPUT_INTEGRITY_ERROR: '输入完整性错误',
};

const severityLabels: Record<string, AttendanceExceptionSeverity> = {
  ERROR: '高',
  WARNING: '中',
  INFO: '低',
};

const stateLabels: Record<string, AttendanceExceptionState> = {
  OPEN: '待处理',
  PENDING_EVIDENCE: '待员工说明',
  PENDING_REVIEW: '处理中',
  RESOLVED: '已处理',
};

export function toAttendanceExceptionRows(
  response: ReportProjection,
): AttendanceExceptionReportRow[] {
  return response.rows.map((row, index) => {
    const rawType = text(row.values, 'exception-type');
    const rawSeverity = text(row.values, 'exception-severity');
    const rawState = text(row.values, 'exception-state');
    return {
      ...reportIdentity(row),
      id: index + 1,
      businessDate: localDate(text(row.values, 'business-date')),
      employeeNo: required(row.values, 'employee-number'),
      employee: required(row.values, 'employee-name'),
      department: required(row.values, 'organization'),
      exceptionType: rawType === undefined
        ? '未识别异常类型'
        : exceptionTypeLabels[rawType.toUpperCase()] ?? '未识别异常类型',
      severity: rawSeverity === undefined
        ? '低'
        : severityLabels[rawSeverity.toUpperCase()] ?? '低',
      exceptionMinutes: decimal(row.values, 'exception-minutes'),
      evidenceSummary: required(row.values, 'evidence-summary'),
      state: rawState === undefined
        ? '待处理'
        : stateLabels[rawState.toUpperCase()] ?? '待处理',
    };
  });
}

const accountTypeLabels: Record<string, string> = {
  ANNUAL_LEAVE: '年假',
  TIME_OFF: '调休',
  RECOGNIZED_OVERTIME: '认可加班',
};

const HOURS_PER_LEAVE_DAY = 8;

export function toAnnualLeaveRows(
  response: ReportProjection,
): AnnualLeaveReportRow[] {
  return response.rows
    .filter((row) => text(row.values, 'account-type')?.toUpperCase() !== 'RECOGNIZED_OVERTIME')
    .map((row, index) => {
      const balanceHours = count(row.values, 'balance-hours');
      const equivalentDays = decimal(row.values, 'equivalent-days')
        ?? balanceHours / HOURS_PER_LEAVE_DAY;
      const accountType = text(row.values, 'account-type');
      return {
        ...reportIdentity(row),
        id: index + 1,
        department: required(row.values, 'organization'),
        employee: required(row.values, 'employee-name'),
        availableHours: balanceHours,
        availableDays: equivalentDays,
        remainingDays: equivalentDays,
        note: accountType === undefined
          ? undefined
          : accountTypeLabels[accountType.toUpperCase()] ?? accountType,
      };
    });
}

const badgeStatuses: Partial<Record<AttendanceMonthMatrixBadgeCode, AttendanceStatusKey>> = {
  LATE: 'late',
  EARLY_DEPARTURE: 'early',
  MISSING_PUNCH: 'missed',
  RECOGNIZED_OVERTIME: 'overtime',
  TIME_OFF: 'time-off',
  OUTING: 'out',
  TRIP: 'trip',
  PERSONAL_LEAVE: 'personal-leave',
  SICK_LEAVE: 'sick-leave',
  ANNUAL_LEAVE: 'annual-leave',
  REST_DAY: 'rest-day',
  PUNCH_CORRECTION: 'corrected',
};

/** Badges without a legend colour still need to be visible, so they go to `note`. */
const badgeNotes: Partial<Record<AttendanceMonthMatrixBadgeCode, string>> = {
  ABSENCE: '旷工',
  OTHER_LEAVE: '其他假别',
  LEAVE_REVOCATION: '销假',
  OVERTIME_APPLICATION: '加班单',
  EXEMPT_PUNCH: '免打卡',
  OTHER_ATTENDANCE_DOCUMENT: '其他单据',
  OTHER_EXCEPTION: '其他异常',
};

const WEEKDAY_LABELS = ['日', '一', '二', '三', '四', '五', '六'] as const;

function punchTime(value: string | null): string {
  if (value === null) return '';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '';
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export function toAttendanceDetailRows(
  response: AttendanceMonthMatrixProjection,
): AttendanceDetailRow[] {
  return response.rows.map((employeeRow) => ({
    employeeId: employeeRow.employeeId,
    employeeNo: employeeRow.employeeNumber,
    organizationId: employeeRow.organizationId,
    department: employeeRow.organizationName,
    employee: employeeRow.employeeName,
    days: employeeRow.days.map((cell): AttendanceDayCell => {
      // Business days are Asia/Shanghai calendar dates; read them as UTC so the
      // weekday never shifts with the viewer's own timezone.
      const parsed = new Date(`${cell.date}T00:00:00Z`);
      const status = cell.badges
        .map((badge) => badgeStatuses[badge])
        .find((value): value is AttendanceStatusKey => value !== undefined);
      const notes = cell.badges
        .map((badge) => badgeNotes[badge])
        .filter((value): value is string => value !== undefined);
      return {
        day: Number(cell.date.slice(8, 10)),
        weekday: WEEKDAY_LABELS[parsed.getUTCDay()] ?? '',
        primary: punchTime(cell.firstPunchAt),
        secondary: punchTime(cell.lastPunchAt),
        status,
        note: notes.length > 0 ? notes.join('/') : undefined,
      };
    }),
  }));
}

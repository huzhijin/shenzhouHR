export type Wave7PeriodState = 'OPEN' | 'FROZEN' | 'CLOSED' | 'REOPENED';
export type Wave7ScopeType = 'COMPANY' | 'ORGANIZATION' | 'ATTENDANCE_GROUP' | 'SELF';
export type AttendanceReportScopeType =
  Extract<Wave7ScopeType, 'COMPANY' | 'ORGANIZATION' | 'SELF'>;
export type Wave7AllowedAction =
  | 'DASHBOARD_DRILL_DOWN'
  | 'FEEDBACK_CREATE'
  | 'REPORT_DRILL_DOWN'
  | 'REPORT_EXPORT_CREATE'
  | 'REPORT_EXPORT_DOWNLOAD';

export interface Wave7Scope {
  type: Wave7ScopeType;
  reference: string;
  label: string;
}

export interface Wave7ProjectionMetadata {
  projectionVersion: string;
  sourceVersions: string[];
  dataAsOf: string;
  timeZone: string;
  periodLabel: string;
  periodState: Wave7PeriodState;
  scope: Wave7Scope;
  allowedActions: Wave7AllowedAction[];
}

export interface TodayProjection {
  kind: 'TODAY';
  metadata: Wave7ProjectionMetadata;
  businessDate: string;
  shiftLabel?: string;
  firstEffectivePunch?: string;
  lastEffectivePunch?: string;
  attendanceStatus: string;
  confirmedMinutes: number;
  issueLabels: string[];
}

export interface AttendanceRecordProjection {
  businessDate: string;
  shiftLabel: string;
  confirmedMinutes: number;
  statusLabel: string;
  issueLabels: string[];
  explanationReference?: string;
}

export interface AttendanceRecordsProjection {
  kind: 'RECORDS';
  metadata: Wave7ProjectionMetadata;
  summary: {
    scheduledMinutes: number;
    confirmedMinutes: number;
    recognizedOvertimeMinutes: number;
    leaveMinutes: number;
  };
  records: AttendanceRecordProjection[];
}

export interface TimeAccountProjection {
  accountReference: string;
  label: string;
  unit: 'HOURS';
  grantedHours: number;
  openingHours: number;
  usedHours: number;
  remainingHours: number;
  expiresOn?: string;
  equivalentDays?: number;
  ledgerVersion: string;
}

export interface LeaveProjection {
  kind: 'LEAVE';
  metadata: Wave7ProjectionMetadata;
  accounts: TimeAccountProjection[];
}

export interface SelfAttendanceDashboardMetadata {
  projectionVersion: string;
  sourceVersions: string[];
  dataAsOf: string;
  timeZone: 'Asia/Shanghai';
  periodLabel: string;
  periodState: Wave7PeriodState;
  scope: {
    type: 'SELF';
    reference: 'current-principal';
    label: '本人';
  };
}

export interface SelfAttendanceDashboardSummary {
  scheduledMinutes: number;
  confirmedMinutes: number;
  recognizedOvertimeMinutes: number;
  leaveMinutes: number;
  unresolvedExceptionCount: number;
}

export interface SelfAttendanceDashboardTrendPoint {
  businessDate: string;
  scheduledMinutes: number;
  confirmedMinutes: number;
  recognizedOvertimeMinutes: number;
  leaveMinutes: number;
  issueCount: number;
}

export interface SelfAttendanceDashboardToday {
  shiftLabel: string | null;
  firstPunchAt: string | null;
  lastPunchAt: string | null;
  statusLabel: string;
  confirmedMinutes: number;
  issueLabels: string[];
}

export interface SelfAttendanceExceptionTypeDistribution {
  type: string;
  count: number;
}

export interface SelfAttendanceRecentException {
  businessDate: string;
  type: string;
  severity: DashboardAnomalySeverity;
  state: DashboardAnomalyState;
  minutes: number;
  safeEvidenceSummary: string;
}

export interface SelfAttendanceDashboardProjection {
  kind: 'SELF_ATTENDANCE_DASHBOARD';
  metadata: SelfAttendanceDashboardMetadata;
  businessDate: string;
  summary: SelfAttendanceDashboardSummary;
  dailyTrend: SelfAttendanceDashboardTrendPoint[];
  today: SelfAttendanceDashboardToday | null;
  exceptionTypeDistribution: SelfAttendanceExceptionTypeDistribution[];
  recentExceptions: SelfAttendanceRecentException[];
}

export type FeedbackState = 'SUBMITTED' | 'IN_PROGRESS' | 'RESOLVED';

export interface FeedbackProgressProjection {
  sequence: number;
  label: string;
  occurredAt: string;
}

export interface FeedbackItemProjection {
  feedbackReference: string;
  attendanceDate: string;
  problemTypeLabel: string;
  content: string;
  state: FeedbackState;
  replyText?: string;
  linkedAdjustmentResult?: string;
  progress: FeedbackProgressProjection[];
}

export interface FeedbackProjection {
  kind: 'FEEDBACK';
  metadata: Wave7ProjectionMetadata;
  items: FeedbackItemProjection[];
}

export interface DashboardMetricProjection {
  key:
    | 'attendance-rate'
    | 'exception-rate'
    | 'confirmed-work'
    | 'recognized-overtime'
    | 'leave'
    | 'unsettled-periods'
    | 'freshness';
  label: string;
  displayValue?: string;
  suppressed: boolean;
  suppressionLabel?: string;
  drillDownReference?: string;
}

export interface DashboardCompanyOption {
  companyId: string;
  companyName: string;
}

export interface DashboardAnomalySummaryProjection {
  unresolvedCount: number;
  affectedEmployeeCount: number;
  blockingCount: number;
}

export type DashboardAnomalySeverity = 'INFO' | 'WARNING' | 'ERROR';
export type DashboardAnomalyState =
  | 'OPEN'
  | 'PENDING_EVIDENCE'
  | 'PENDING_REVIEW';

export interface DashboardAnomalyProjection {
  exceptionReference: string;
  employeeNumber: string;
  employeeName: string;
  organizationName: string;
  businessDate: string;
  exceptionType: string;
  severity: DashboardAnomalySeverity;
  state: DashboardAnomalyState;
  exceptionMinutes: number;
  evidenceSummary: string;
}

export interface DashboardDailyTrendProjection {
  businessDate: string;
  exceptionCount: number;
  blockingCount: number;
  affectedEmployeeCount: number;
}

export interface DashboardSeverityDistributionProjection {
  severity: DashboardAnomalySeverity;
  count: number;
}

export interface DashboardTypeDistributionProjection {
  exceptionType: string;
  count: number;
}

export interface DashboardOrganizationRankingProjection {
  organizationName: string;
  exceptionCount: number;
  blockingCount: number;
}

export interface DashboardAnalyticsProjection {
  dailyTrend: DashboardDailyTrendProjection[];
  severityDistribution: DashboardSeverityDistributionProjection[];
  typeDistribution: DashboardTypeDistributionProjection[];
  organizationRanking: DashboardOrganizationRankingProjection[];
}

export interface DashboardProjection {
  kind: 'DASHBOARD';
  metadata: Wave7ProjectionMetadata;
  title: string;
  metrics: DashboardMetricProjection[];
  businessDate?: string;
  selectedCompanyId?: string;
  summary?: DashboardAnomalySummaryProjection;
  exceptions?: DashboardAnomalyProjection[];
  companies?: DashboardCompanyOption[];
  analytics?: DashboardAnalyticsProjection;
}

export interface LiveDashboardProjection extends DashboardProjection {
  businessDate: string;
  selectedCompanyId: string;
  summary: DashboardAnomalySummaryProjection;
  exceptions: DashboardAnomalyProjection[];
  companies: DashboardCompanyOption[];
  analytics: DashboardAnalyticsProjection;
}

export interface DashboardCompanySelectionProjection {
  kind: 'DASHBOARD_COMPANY_SELECTION';
  title: string;
  businessDate: string;
  selectedCompanyId: null;
  companies: DashboardCompanyOption[];
  message: string;
}

export type DashboardLoadResult =
  | DashboardProjection
  | DashboardCompanySelectionProjection;

export const attendanceReportTypes = [
  'ATTENDANCE_DETAIL',
  'LEAVE',
  'OVERTIME',
  'WORK_HOURS',
  'EXCEPTIONS',
  'LATE',
  'MISSED_PUNCH',
  'ATTENDANCE_RATE',
  'ANNUAL_LEAVE',
] as const;

export type AttendanceReportType = typeof attendanceReportTypes[number];

export const reportColumnKeys = [
  'business-date',
  'employee-number',
  'employee-name',
  'organization',
  'shift',
  'scheduled-hours',
  'confirmed-hours',
  'recognized-overtime-hours',
  'leave-hours',
  'absence-hours',
  'actual-work-hours',
  'late-minutes',
  'penalized-late-minutes',
  'early-minutes',
  'missing-punch-count',
  'first-punch',
  'last-punch',
  'document-type',
  'document-reference',
  'document-start',
  'document-end',
  'approval-state',
  'recognized-hours',
  'weekday-overtime-hours',
  'saturday-overtime-hours',
  'sunday-overtime-hours',
  'holiday-overtime-hours',
  'exception-type',
  'exception-severity',
  'exception-state',
  'exception-minutes',
  'evidence-summary',
  'late-event-count',
  'attendance-rate',
  'rate-formula-version',
  'account-type',
  'opening-hours',
  'granted-hours',
  'overtime-credit-hours',
  'manual-increase-hours',
  'used-hours',
  'expired-hours',
  'returned-hours',
  'manual-deduction-hours',
  'balance-hours',
  'equivalent-days',
  // Retained only for the isolated Wave 7 demo/contract fixtures.
  'scope',
  'late-count',
  'early-count',
  'missing-count',
] as const;

export type ReportColumnKey = typeof reportColumnKeys[number];

export interface ReportRowProjection {
  rowReference: string;
  values: Partial<Record<ReportColumnKey, string | number>>;
  drillDownReference?: string | null;
}

export interface ReportFilterProjection {
  period: string;
  scopeReference: string;
  companyId?: string | null;
  organizationId?: string | null;
  employeeId?: string | null;
  status?: string | null;
}

export interface ReportProjection {
  kind: 'REPORT';
  metadata: Omit<Wave7ProjectionMetadata, 'scope'> & {
    scope: Omit<Wave7Scope, 'type'> & {
      type: AttendanceReportScopeType;
    };
  };
  reportTitle: string;
  queryFingerprint: string;
  filters: ReportFilterProjection;
  columns: Array<{ key: ReportColumnKey; label: string }>;
  exportFieldAllowlist: ReportColumnKey[];
  rowCount: number;
  rows: ReportRowProjection[];
}

export interface AttendanceReportPageMetadata {
  reportType: AttendanceReportType;
  formulaVersion: string;
  page: number;
  size: number;
  totalPages: number;
}

export type LiveReportProjection = ReportProjection
  & AttendanceReportPageMetadata
  & {
    filters: ReportFilterProjection & { companyId: string };
  };

export const attendanceMonthMatrixBadgeCodes = [
  'LATE',
  'EARLY_DEPARTURE',
  'MISSING_PUNCH',
  'ABSENCE',
  'RECOGNIZED_OVERTIME',
  'TIME_OFF',
  'OUTING',
  'TRIP',
  'PERSONAL_LEAVE',
  'SICK_LEAVE',
  'ANNUAL_LEAVE',
  'PUNCH_CORRECTION',
  'REST_DAY',
  'OTHER_LEAVE',
  'LEAVE_REVOCATION',
  'OVERTIME_APPLICATION',
  'EXEMPT_PUNCH',
  'OTHER_ATTENDANCE_DOCUMENT',
  'OTHER_EXCEPTION',
] as const;

export type AttendanceMonthMatrixBadgeCode =
  typeof attendanceMonthMatrixBadgeCodes[number];

export interface AttendanceMonthMatrixDay {
  date: string;
  organizationName: string | null;
  shiftLabel: string | null;
  firstPunchAt: string | null;
  lastPunchAt: string | null;
  badges: AttendanceMonthMatrixBadgeCode[];
}

export interface AttendanceMonthMatrixEmployeeRow {
  employeeId: string;
  employeeNumber: string;
  employeeName: string;
  organizationId: string;
  organizationName: string;
  days: AttendanceMonthMatrixDay[];
}

export interface AttendanceMonthMatrixProjection {
  kind: 'ATTENDANCE_MONTH_MATRIX';
  metadata: Omit<Wave7ProjectionMetadata, 'scope'> & {
    scope: Omit<Wave7Scope, 'type'> & {
      type: AttendanceReportScopeType;
    };
  };
  queryFingerprint: string;
  formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V1';
  filters: Omit<ReportFilterProjection, 'status'> & {
    companyId: string;
  };
  dates: string[];
  employeeCount: number;
  rows: AttendanceMonthMatrixEmployeeRow[];
  page: number;
  size: number;
  totalPages: number;
}

export interface AttendanceReportCompanyOption {
  companyId: string;
  companyName: string;
}

export interface AttendanceReportCompanyDirectory {
  period: string;
  companies: AttendanceReportCompanyOption[];
}

export function hasNoReportIdentityFilter(
  filters: ReportFilterProjection,
): boolean {
  return (filters.employeeId ?? null) === null;
}

export const attendanceReportExportDeliveryModes = [
  'SYNC',
  'ASYNC',
] as const;

export type AttendanceReportExportDeliveryMode =
  typeof attendanceReportExportDeliveryModes[number];

export const attendanceReportExportStatuses = [
  'QUEUED',
  'BUILDING',
  'READY',
  'FAILED',
  'EXPIRED',
] as const;

export type AttendanceReportExportStatus =
  typeof attendanceReportExportStatuses[number];

export interface AttendanceReportExportView {
  exportId: string;
  reportType: AttendanceReportType;
  period: string;
  companyId: string;
  deliveryMode: AttendanceReportExportDeliveryMode;
  status: AttendanceReportExportStatus;
  purpose: string;
  rowCount: number;
  expiresAt: string;
  completedAt?: string | null;
}

export type ReportExportState =
  | 'PREPARING'
  | 'QUEUED'
  | 'RUNNING'
  | 'READY'
  | 'FAILED'
  | 'EXPIRED'
  | 'REVOKED';

export interface ReportExportProjection {
  exportReference: string;
  state: ReportExportState;
  delivery: 'SYNCHRONOUS' | 'ASYNCHRONOUS';
  queryFingerprint: string;
  projectionVersion: string;
  scopeReference: string;
  selectedFields: ReportColumnKey[];
  purpose: string;
  requesterLabel: string;
  createdAt: string;
  generatedAt?: string;
  expiresAt?: string;
  auditReference: string;
  canDownload: boolean;
}

export interface ReportExportRequest {
  queryFingerprint: string;
  projectionVersion: string;
  scopeReference: string;
  filters: ReportFilterProjection;
  selectedFields: ReportColumnKey[];
  purpose: string;
}

export type Wave7Projection =
  | TodayProjection
  | AttendanceRecordsProjection
  | LeaveProjection
  | FeedbackProjection
  | DashboardProjection
  | ReportProjection;

const periodStates: readonly Wave7PeriodState[] = ['OPEN', 'FROZEN', 'CLOSED', 'REOPENED'];
const scopeTypes: readonly Wave7ScopeType[] = ['COMPANY', 'ORGANIZATION', 'ATTENDANCE_GROUP', 'SELF'];
const attendanceReportScopeTypes: readonly AttendanceReportScopeType[] = [
  'COMPANY',
  'ORGANIZATION',
  'SELF',
];
const allowedActions: readonly Wave7AllowedAction[] = [
  'DASHBOARD_DRILL_DOWN',
  'FEEDBACK_CREATE',
  'REPORT_DRILL_DOWN',
  'REPORT_EXPORT_CREATE',
  'REPORT_EXPORT_DOWNLOAD',
];

export function assertWave7Projection(value: unknown): asserts value is Wave7Projection {
  const candidate = asRecord(value, 'projection');
  assertString(candidate.kind, 'projection.kind');
  assertProjectionMetadata(candidate.metadata);
  if (candidate.kind === 'TODAY') {
    assertOnlyKeys(
      candidate,
      [
        'kind',
        'metadata',
        'businessDate',
        'shiftLabel',
        'firstEffectivePunch',
        'lastEffectivePunch',
        'attendanceStatus',
        'confirmedMinutes',
        'issueLabels',
      ],
      'today projection',
    );
    assertString(candidate.businessDate, 'today.businessDate');
    assertString(candidate.attendanceStatus, 'today.attendanceStatus');
    assertNumber(candidate.confirmedMinutes, 'today.confirmedMinutes');
    assertStringArray(candidate.issueLabels, 'today.issueLabels');
    return;
  }
  if (candidate.kind === 'RECORDS') {
    assertOnlyKeys(
      candidate,
      ['kind', 'metadata', 'summary', 'records'],
      'records projection',
    );
    asRecord(candidate.summary, 'records.summary');
    assertArray(candidate.records, 'records.records');
    return;
  }
  if (candidate.kind === 'LEAVE') {
    assertOnlyKeys(
      candidate,
      ['kind', 'metadata', 'accounts'],
      'leave projection',
    );
    assertArray(candidate.accounts, 'leave.accounts');
    return;
  }
  if (candidate.kind === 'FEEDBACK') {
    assertOnlyKeys(
      candidate,
      ['kind', 'metadata', 'items'],
      'feedback projection',
    );
    assertArray(candidate.items, 'feedback.items');
    return;
  }
  if (candidate.kind === 'DASHBOARD') {
    assertDashboardProjection(candidate);
    return;
  }
  if (candidate.kind === 'REPORT') {
    assertReportProjection(candidate);
    return;
  }
  throw new TypeError(`Unsupported Wave 7 projection kind: ${candidate.kind}`);
}

export function parseAttendanceDashboardResponse(
  value: unknown,
): LiveDashboardProjection | DashboardCompanySelectionProjection {
  const candidate = asRecord(value, 'attendance dashboard response');
  if (candidate.kind === 'DASHBOARD_COMPANY_SELECTION') {
    assertOnlyKeys(
      candidate,
      [
        'kind',
        'title',
        'businessDate',
        'selectedCompanyId',
        'companies',
        'message',
      ],
      'dashboard company selection',
    );
    assertString(candidate.title, 'dashboard selection.title');
    assertDate(candidate.businessDate, 'dashboard selection.businessDate');
    if (candidate.selectedCompanyId !== null) {
      throw new TypeError(
        'dashboard selection.selectedCompanyId must be null',
      );
    }
    assertDashboardCompanies(
      candidate.companies,
      'dashboard selection.companies',
      true,
    );
    if ((candidate.companies as unknown[]).length < 2) {
      throw new TypeError(
        'dashboard company selection requires multiple companies',
      );
    }
    assertBoundedString(
      candidate.message,
      'dashboard selection.message',
      500,
    );
    return candidate as unknown as DashboardCompanySelectionProjection;
  }

  assertString(candidate.kind, 'dashboard.kind');
  if (candidate.kind !== 'DASHBOARD') {
    throw new TypeError('attendance dashboard response kind is invalid');
  }
  assertProjectionMetadata(candidate.metadata);
  assertDashboardProjection(candidate, false);
  assertLiveDashboardFields(candidate);
  return {
    ...(candidate as unknown as Omit<LiveDashboardProjection, 'metrics'>),
    metrics: Array.isArray(candidate.metrics)
      ? candidate.metrics as DashboardMetricProjection[]
      : [],
  };
}

export function parseSelfAttendanceDashboardResponse(
  value: unknown,
): SelfAttendanceDashboardProjection {
  const candidate = asRecord(
    value,
    'self attendance dashboard response',
  );
  assertOnlyKeys(
    candidate,
    [
      'kind',
      'metadata',
      'businessDate',
      'summary',
      'dailyTrend',
      'today',
      'exceptionTypeDistribution',
      'recentExceptions',
    ],
    'self attendance dashboard response',
  );
  if (candidate.kind !== 'SELF_ATTENDANCE_DASHBOARD') {
    throw new TypeError(
      'self attendance dashboard response kind is invalid',
    );
  }

  const metadata = assertSelfAttendanceDashboardMetadata(
    candidate.metadata,
  );
  assertDate(
    candidate.businessDate,
    'self attendance dashboard.businessDate',
  );
  if (!(candidate.businessDate as string).startsWith(metadata.periodLabel)) {
    throw new TypeError(
      'self attendance dashboard businessDate is outside the period',
    );
  }

  const summary = asRecord(
    candidate.summary,
    'self attendance dashboard.summary',
  );
  assertOnlyKeys(
    summary,
    [
      'scheduledMinutes',
      'confirmedMinutes',
      'recognizedOvertimeMinutes',
      'leaveMinutes',
      'unresolvedExceptionCount',
    ],
    'self attendance dashboard.summary',
  );
  for (const key of [
    'scheduledMinutes',
    'confirmedMinutes',
    'recognizedOvertimeMinutes',
    'leaveMinutes',
    'unresolvedExceptionCount',
  ] as const) {
    assertNonNegativeSafeInteger(
      summary[key],
      `self attendance dashboard.summary.${key}`,
    );
  }

  assertSelfAttendanceDashboardTrend(
    candidate.dailyTrend,
    metadata.periodLabel,
    candidate.businessDate as string,
  );
  assertSelfAttendanceDashboardToday(candidate.today);
  assertSelfAttendanceExceptionTypeDistribution(
    candidate.exceptionTypeDistribution,
    summary.unresolvedExceptionCount as number,
  );
  assertSelfAttendanceRecentExceptions(
    candidate.recentExceptions,
    metadata.periodLabel,
    candidate.businessDate as string,
    summary.unresolvedExceptionCount as number,
  );

  return candidate as unknown as SelfAttendanceDashboardProjection;
}

export function hasLiveDashboardProjection(
  projection: DashboardProjection,
): projection is LiveDashboardProjection {
  return projection.businessDate !== undefined
    && projection.selectedCompanyId !== undefined
    && projection.summary !== undefined
    && projection.exceptions !== undefined
    && projection.companies !== undefined
    && projection.analytics !== undefined;
}

export function assertLiveReportProjection(
  value: unknown,
): asserts value is LiveReportProjection {
  assertWave7Projection(value);
  const candidate = asRecord(value, 'attendance report response');
  if (candidate.kind !== 'REPORT') {
    throw new TypeError('attendance report response kind must be REPORT');
  }
  const metadata = asRecord(candidate.metadata, 'report.metadata');
  const scope = asRecord(metadata.scope, 'report.metadata.scope');
  if (
    !attendanceReportScopeTypes.includes(
      scope.type as AttendanceReportScopeType,
    )
  ) {
    throw new TypeError('report.metadata.scope.type is invalid');
  }
  if (!attendanceReportTypes.includes(candidate.reportType as AttendanceReportType)) {
    throw new TypeError('report.reportType is invalid');
  }
  if (
    typeof candidate.queryFingerprint !== 'string'
    || !/^[a-f0-9]{64}$/.test(candidate.queryFingerprint)
  ) {
    throw new TypeError('report.queryFingerprint is invalid');
  }
  assertBoundedString(candidate.reportTitle, 'report.reportTitle', 100);
  assertBoundedString(candidate.formulaVersion, 'report.formulaVersion', 128);
  const filters = asRecord(candidate.filters, 'report.filters');
  assertYearMonth(filters.period, 'report.filters.period');
  assertBoundedString(
    filters.scopeReference,
    'report.filters.scopeReference',
    128,
  );
  assertBoundedString(
    filters.companyId,
    'report.filters.companyId',
    36,
  );
  assertNullableBoundedString(
    filters.organizationId,
    'report.filters.organizationId',
    36,
  );
  assertNullableBoundedString(
    filters.employeeId,
    'report.filters.employeeId',
    36,
  );
  assertNullableBoundedString(
    filters.status,
    'report.filters.status',
    32,
  );

  const exportFieldAllowlist = candidate.exportFieldAllowlist as unknown[];
  if (
    exportFieldAllowlist.length === 0
    || new Set(exportFieldAllowlist).size !== exportFieldAllowlist.length
  ) {
    throw new TypeError(
      'report.exportFieldAllowlist must be non-empty and unique',
    );
  }

  const rowReferences = new Set<string>();
  for (const [index, value] of (candidate.rows as unknown[]).entries()) {
    const row = asRecord(value, `report.rows[${index}]`);
    assertBoundedString(
      row.rowReference,
      `report.rows[${index}].rowReference`,
      256,
    );
    if (rowReferences.has(row.rowReference)) {
      throw new TypeError('report.rows contains a duplicate rowReference');
    }
    rowReferences.add(row.rowReference);
    if (
      row.drillDownReference !== undefined
      && row.drillDownReference !== null
    ) {
      assertBoundedString(
        row.drillDownReference,
        `report.rows[${index}].drillDownReference`,
        256,
      );
    }
    const values = asRecord(row.values, `report.rows[${index}].values`);
    if (Object.values(values).some((cell) => typeof cell !== 'string')) {
      throw new TypeError('live report row value must be a string');
    }
  }

  assertNonNegativeInteger(candidate.page, 'report.page');
  assertPositiveInteger(candidate.size, 'report.size');
  assertNonNegativeInteger(candidate.totalPages, 'report.totalPages');
  if ((candidate.size as number) > 200) {
    throw new TypeError('report.size exceeds the service limit');
  }
  const rows = candidate.rows as unknown[];
  const rowCount = candidate.rowCount as number;
  const expectedTotalPages = rowCount === 0
    ? 0
    : Math.ceil(rowCount / (candidate.size as number));
  if (
    rows.length > (candidate.size as number)
    || rows.length > rowCount
    || candidate.totalPages !== expectedTotalPages
  ) {
    throw new TypeError('report pagination is inconsistent');
  }
}

export function assertAttendanceMonthMatrixProjection(
  value: unknown,
): asserts value is AttendanceMonthMatrixProjection {
  const candidate = asRecord(value, 'attendance month matrix');
  assertOnlyKeys(
    candidate,
    [
      'kind',
      'metadata',
      'queryFingerprint',
      'formulaVersion',
      'filters',
      'dates',
      'employeeCount',
      'rows',
      'page',
      'size',
      'totalPages',
    ],
    'attendance month matrix',
  );
  if (candidate.kind !== 'ATTENDANCE_MONTH_MATRIX') {
    throw new TypeError('attendance month matrix kind is invalid');
  }
  assertProjectionMetadata(candidate.metadata);
  const metadata = asRecord(
    candidate.metadata,
    'attendance month matrix.metadata',
  );
  const scope = asRecord(
    metadata.scope,
    'attendance month matrix.metadata.scope',
  );
  if (!attendanceReportScopeTypes.includes(
    scope.type as AttendanceReportScopeType,
  )) {
    throw new TypeError('attendance month matrix scope type is invalid');
  }
  if (
    typeof candidate.queryFingerprint !== 'string'
    || !/^[a-f0-9]{64}$/.test(candidate.queryFingerprint)
  ) {
    throw new TypeError('attendance month matrix fingerprint is invalid');
  }
  if (candidate.formulaVersion !== 'ATTENDANCE_MONTH_MATRIX_V1') {
    throw new TypeError('attendance month matrix formula is invalid');
  }
  const filters = asRecord(
    candidate.filters,
    'attendance month matrix.filters',
  );
  assertOnlyKeys(
    filters,
    [
      'period',
      'scopeReference',
      'companyId',
      'organizationId',
      'employeeId',
    ],
    'attendance month matrix.filters',
  );
  assertYearMonth(filters.period, 'attendance month matrix.filters.period');
  assertBoundedString(
    filters.scopeReference,
    'attendance month matrix.filters.scopeReference',
    128,
  );
  assertBoundedString(
    filters.companyId,
    'attendance month matrix.filters.companyId',
    36,
  );
  assertNullableString(
    filters.organizationId,
    'attendance month matrix.filters.organizationId',
  );
  assertNullableString(
    filters.employeeId,
    'attendance month matrix.filters.employeeId',
  );
  if (metadata.periodLabel !== filters.period) {
    throw new TypeError('attendance month matrix periods are inconsistent');
  }
  assertArray(candidate.dates, 'attendance month matrix.dates');
  const expectedDates = datesInMonth(filters.period as string);
  if (
    candidate.dates.length !== expectedDates.length
    || !candidate.dates.every((date, index) => {
      assertDate(date, `attendance month matrix.dates[${index}]`);
      return date === expectedDates[index];
    })
  ) {
    throw new TypeError('attendance month matrix dates are inconsistent');
  }
  assertNonNegativeSafeInteger(
    candidate.employeeCount,
    'attendance month matrix.employeeCount',
  );
  assertNonNegativeInteger(candidate.page, 'attendance month matrix.page');
  assertPositiveInteger(candidate.size, 'attendance month matrix.size');
  assertNonNegativeInteger(
    candidate.totalPages,
    'attendance month matrix.totalPages',
  );
  assertArray(candidate.rows, 'attendance month matrix.rows');
  if ((candidate.rows as unknown[]).length > (candidate.size as number)) {
    throw new TypeError('attendance month matrix page exceeds its size');
  }
  const expectedTotalPages = candidate.employeeCount === 0
    ? 0
    : Math.ceil(
      (candidate.employeeCount as number) / (candidate.size as number),
    );
  if (
    candidate.totalPages !== expectedTotalPages
    || (candidate.rows as unknown[]).length
      > (candidate.employeeCount as number)
  ) {
    throw new TypeError('attendance month matrix pagination is inconsistent');
  }
  const employeeIds = new Set<string>();
  for (const [rowIndex, value] of candidate.rows.entries()) {
    const row = asRecord(
      value,
      `attendance month matrix.rows[${rowIndex}]`,
    );
    assertOnlyKeys(
      row,
      [
        'employeeId',
        'employeeNumber',
        'employeeName',
        'organizationId',
        'organizationName',
        'days',
      ],
      `attendance month matrix.rows[${rowIndex}]`,
    );
    assertBoundedString(row.employeeId, 'matrix employeeId', 36);
    assertBoundedString(row.employeeNumber, 'matrix employeeNumber', 64);
    assertBoundedString(row.employeeName, 'matrix employeeName', 100);
    assertBoundedString(row.organizationId, 'matrix organizationId', 36);
    assertBoundedString(
      row.organizationName,
      'matrix organizationName',
      200,
    );
    if (employeeIds.has(row.employeeId as string)) {
      throw new TypeError('attendance month matrix has duplicate employees');
    }
    employeeIds.add(row.employeeId as string);
    assertArray(row.days, `attendance month matrix.rows[${rowIndex}].days`);
    if (row.days.length !== expectedDates.length) {
      throw new TypeError('attendance month matrix row dates are incomplete');
    }
    row.days.forEach((value, dayIndex) => {
      const day = asRecord(
        value,
        `attendance month matrix.rows[${rowIndex}].days[${dayIndex}]`,
      );
      assertOnlyKeys(
        day,
        [
          'date',
          'organizationName',
          'shiftLabel',
          'firstPunchAt',
          'lastPunchAt',
          'badges',
        ],
        `attendance month matrix.rows[${rowIndex}].days[${dayIndex}]`,
      );
      assertDate(day.date, 'attendance month matrix day date');
      if (day.date !== expectedDates[dayIndex]) {
        throw new TypeError('attendance month matrix day order is invalid');
      }
      assertNullableString(day.organizationName, 'matrix organizationName');
      assertNullableString(day.shiftLabel, 'matrix shiftLabel');
      for (const key of ['firstPunchAt', 'lastPunchAt'] as const) {
        if (day[key] !== null) {
          assertInstant(day[key], `attendance month matrix day ${key}`);
        }
      }
      if (
        typeof day.firstPunchAt === 'string'
        && typeof day.lastPunchAt === 'string'
        && Date.parse(day.firstPunchAt) > Date.parse(day.lastPunchAt)
      ) {
        throw new TypeError('attendance month matrix punch order is invalid');
      }
      assertArray(day.badges, 'attendance month matrix day badges');
      const badgeSet = new Set(day.badges);
      if (
        badgeSet.size !== day.badges.length
        || !day.badges.every((code) => attendanceMonthMatrixBadgeCodes.includes(
          code as AttendanceMonthMatrixBadgeCode,
        ))
      ) {
        throw new TypeError('attendance month matrix badges are invalid');
      }
    });
  }
}

export function hasLiveReportMetadata(
  projection: ReportProjection,
): projection is LiveReportProjection {
  return 'reportType' in projection
    && 'formulaVersion' in projection
    && 'page' in projection
    && 'size' in projection
    && 'totalPages' in projection;
}

export function assertAttendanceReportCompanyDirectory(
  value: unknown,
): asserts value is AttendanceReportCompanyDirectory {
  const candidate = asRecord(value, 'attendance report company directory');
  assertOnlyKeys(
    candidate,
    ['period', 'companies'],
    'attendance report company directory',
  );
  assertYearMonth(
    candidate.period,
    'attendance report company directory period',
  );
  assertArray(
    candidate.companies,
    'attendance report company directory companies',
  );
  const identifiers = new Set<string>();
  for (const [index, value] of candidate.companies.entries()) {
    const option = asRecord(
      value,
      `attendance report company directory companies[${index}]`,
    );
    assertOnlyKeys(
      option,
      ['companyId', 'companyName'],
      `attendance report company directory companies[${index}]`,
    );
    assertBoundedString(
      option.companyId,
      `attendance report company directory companies[${index}].companyId`,
      36,
    );
    assertBoundedString(
      option.companyName,
      `attendance report company directory companies[${index}].companyName`,
      200,
    );
    if (identifiers.has(option.companyId as string)) {
      throw new TypeError(
        'attendance report company directory contains duplicate ids',
      );
    }
    identifiers.add(option.companyId as string);
  }
}

export function assertAttendanceReportExportView(
  value: unknown,
): asserts value is AttendanceReportExportView {
  const candidate = asRecord(value, 'attendance report export');
  assertOnlyKeys(
    candidate,
    [
      'exportId',
      'reportType',
      'period',
      'companyId',
      'deliveryMode',
      'status',
      'purpose',
      'rowCount',
      'expiresAt',
      'completedAt',
    ],
    'attendance report export',
  );
  if (
    typeof candidate.exportId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      candidate.exportId,
    )
  ) {
    throw new TypeError('attendance report export id is invalid');
  }
  if (!attendanceReportTypes.includes(candidate.reportType as AttendanceReportType)) {
    throw new TypeError('attendance report export type is invalid');
  }
  assertYearMonth(candidate.period, 'attendance report export period');
  assertBoundedString(
    candidate.companyId,
    'attendance report export companyId',
    36,
  );
  if (
    !attendanceReportExportDeliveryModes.includes(
      candidate.deliveryMode as AttendanceReportExportDeliveryMode,
    )
  ) {
    throw new TypeError('attendance report export delivery mode is invalid');
  }
  if (
    !attendanceReportExportStatuses.includes(
      candidate.status as AttendanceReportExportStatus,
    )
  ) {
    throw new TypeError('attendance report export status is invalid');
  }
  const purpose = normalizeReportExportPurpose(candidate.purpose);
  if (purpose !== candidate.purpose) {
    throw new TypeError('attendance report export purpose must be normalized');
  }
  assertNonNegativeSafeInteger(
    candidate.rowCount,
    'attendance report export rowCount',
  );
  assertInstant(candidate.expiresAt, 'attendance report export expiresAt');
  if (candidate.completedAt !== undefined && candidate.completedAt !== null) {
    assertInstant(
      candidate.completedAt,
      'attendance report export completedAt',
    );
  }
  if (
    candidate.deliveryMode === 'SYNC'
    && candidate.status !== 'READY'
    && candidate.status !== 'EXPIRED'
  ) {
    throw new TypeError('synchronous report export status is inconsistent');
  }
  if (
    (candidate.status === 'QUEUED' || candidate.status === 'BUILDING')
    && candidate.completedAt !== undefined
    && candidate.completedAt !== null
  ) {
    throw new TypeError('pending report export cannot be completed');
  }
  if (
    (candidate.status === 'READY' || candidate.status === 'FAILED')
    && (candidate.completedAt === undefined || candidate.completedAt === null)
  ) {
    throw new TypeError('completed report export requires completedAt');
  }
}

export function normalizeReportExportPurpose(value: unknown): string {
  if (typeof value !== 'string') {
    throw new TypeError('导出用途必须是文本');
  }
  const normalized = value.trim();
  if (
    normalized.length < 2
    || normalized.length > 200
    || hasUnsafeTextControl(normalized)
  ) {
    throw new TypeError('导出用途必须为 2 至 200 个安全字符');
  }
  return normalized;
}

function hasUnsafeTextControl(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined
      && (
        codePoint <= 0x1f
        || (codePoint >= 0x7f && codePoint <= 0x9f)
        || codePoint === 0x2028
        || codePoint === 0x2029
      );
  });
}

function assertReportProjection(candidate: Record<string, unknown>): void {
  assertOnlyKeys(
    candidate,
    [
      'kind',
      'metadata',
      'reportTitle',
      'queryFingerprint',
      'filters',
      'columns',
      'exportFieldAllowlist',
      'rowCount',
      'rows',
      'reportType',
      'formulaVersion',
      'page',
      'size',
      'totalPages',
    ],
    'report projection',
  );
  assertString(candidate.reportTitle, 'report.reportTitle');
  assertString(candidate.queryFingerprint, 'report.queryFingerprint');
  const filters = asRecord(candidate.filters, 'report.filters');
  assertOnlyKeys(
    filters,
    [
      'period',
      'scopeReference',
      'companyId',
      'organizationId',
      'employeeId',
      'status',
    ],
    'report.filters',
  );
  assertYearMonth(filters.period, 'report.filters.period');
  assertString(filters.scopeReference, 'report.filters.scopeReference');
  assertNullableString(filters.companyId, 'report.filters.companyId');
  assertNullableString(filters.organizationId, 'report.filters.organizationId');
  assertNullableString(filters.employeeId, 'report.filters.employeeId');
  assertNullableString(filters.status, 'report.filters.status');

  assertArray(candidate.columns, 'report.columns');
  const visibleColumns = new Set<ReportColumnKey>();
  for (const [index, value] of candidate.columns.entries()) {
    const column = asRecord(value, `report.columns[${index}]`);
    assertOnlyKeys(
      column,
      ['key', 'label'],
      `report.columns[${index}]`,
    );
    assertReportColumnKey(column.key, `report.columns[${index}].key`);
    assertString(column.label, `report.columns[${index}].label`);
    if (visibleColumns.has(column.key as ReportColumnKey)) {
      throw new TypeError('report.columns contains a duplicate key');
    }
    visibleColumns.add(column.key as ReportColumnKey);
  }

  assertArray(candidate.exportFieldAllowlist, 'report.exportFieldAllowlist');
  for (const [index, key] of candidate.exportFieldAllowlist.entries()) {
    assertReportColumnKey(key, `report.exportFieldAllowlist[${index}]`);
    if (!visibleColumns.has(key as ReportColumnKey)) {
      throw new TypeError('report.exportFieldAllowlist must use visible columns');
    }
  }

  assertNonNegativeSafeInteger(candidate.rowCount, 'report.rowCount');
  assertArray(candidate.rows, 'report.rows');
  for (const [index, value] of candidate.rows.entries()) {
    const row = asRecord(value, `report.rows[${index}]`);
    assertOnlyKeys(
      row,
      ['rowReference', 'values', 'drillDownReference'],
      `report.rows[${index}]`,
    );
    assertString(row.rowReference, `report.rows[${index}].rowReference`);
    const values = asRecord(row.values, `report.rows[${index}].values`);
    for (const [key, cell] of Object.entries(values)) {
      assertReportColumnKey(key, `report.rows[${index}].values key`);
      if (!visibleColumns.has(key as ReportColumnKey)) {
        throw new TypeError('report row contains a non-visible column');
      }
      if (
        (typeof cell !== 'string' && typeof cell !== 'number')
        || (typeof cell === 'number' && !Number.isFinite(cell))
      ) {
        throw new TypeError('report row value must be a string or finite number');
      }
    }
    if (row.drillDownReference !== undefined && row.drillDownReference !== null) {
      assertString(
        row.drillDownReference,
        `report.rows[${index}].drillDownReference`,
      );
    }
  }
}

function assertDashboardProjection(
  candidate: Record<string, unknown>,
  requireMetrics = true,
): void {
  assertOnlyKeys(
    candidate,
    [
      'kind',
      'metadata',
      'title',
      'metrics',
      'businessDate',
      'selectedCompanyId',
      'summary',
      'exceptions',
      'companies',
      'analytics',
    ],
    'dashboard projection',
  );
  assertString(candidate.title, 'dashboard.title');
  if (requireMetrics || candidate.metrics !== undefined) {
    assertDashboardMetrics(candidate.metrics);
  }
  const liveKeys = [
    'businessDate',
    'selectedCompanyId',
    'summary',
    'exceptions',
    'companies',
    'analytics',
  ] as const;
  const presentLiveKeys = liveKeys.filter(
    (key) => candidate[key] !== undefined,
  );
  if (
    presentLiveKeys.length !== 0
    && presentLiveKeys.length !== liveKeys.length
  ) {
    throw new TypeError(
      'dashboard live anomaly projection is incomplete',
    );
  }
  if (presentLiveKeys.length === liveKeys.length) {
    assertLiveDashboardFields(candidate);
  }
}

function assertDashboardMetrics(value: unknown): void {
  assertArray(value, 'dashboard.metrics');
  for (const [index, metricValue] of value.entries()) {
    const metric = asRecord(
      metricValue,
      `dashboard.metrics[${index}]`,
    );
    assertOnlyKeys(
      metric,
      [
        'key',
        'label',
        'displayValue',
        'suppressed',
        'suppressionLabel',
        'drillDownReference',
      ],
      `dashboard.metrics[${index}]`,
    );
    if (![
      'attendance-rate',
      'exception-rate',
      'confirmed-work',
      'recognized-overtime',
      'leave',
      'unsettled-periods',
      'freshness',
    ].includes(metric.key as string)) {
      throw new TypeError(`dashboard.metrics[${index}].key is invalid`);
    }
    assertString(metric.label, `dashboard.metrics[${index}].label`);
    if (typeof metric.suppressed !== 'boolean') {
      throw new TypeError(
        `dashboard.metrics[${index}].suppressed must be boolean`,
      );
    }
    if (metric.displayValue !== undefined) {
      assertBoundedString(
        metric.displayValue,
        `dashboard.metrics[${index}].displayValue`,
        200,
      );
    }
    if (metric.suppressionLabel !== undefined) {
      assertBoundedString(
        metric.suppressionLabel,
        `dashboard.metrics[${index}].suppressionLabel`,
        200,
      );
    }
    if (metric.drillDownReference !== undefined) {
      assertBoundedString(
        metric.drillDownReference,
        `dashboard.metrics[${index}].drillDownReference`,
        500,
      );
    }
  }
}

function assertLiveDashboardFields(
  candidate: Record<string, unknown>,
): void {
  assertDate(candidate.businessDate, 'dashboard.businessDate');
  assertBoundedString(
    candidate.selectedCompanyId,
    'dashboard.selectedCompanyId',
    36,
  );
  const summary = asRecord(candidate.summary, 'dashboard.summary');
  assertOnlyKeys(
    summary,
    ['unresolvedCount', 'affectedEmployeeCount', 'blockingCount'],
    'dashboard.summary',
  );
  assertNonNegativeSafeInteger(
    summary.unresolvedCount,
    'dashboard.summary.unresolvedCount',
  );
  assertNonNegativeSafeInteger(
    summary.affectedEmployeeCount,
    'dashboard.summary.affectedEmployeeCount',
  );
  assertNonNegativeSafeInteger(
    summary.blockingCount,
    'dashboard.summary.blockingCount',
  );
  if (
    (summary.affectedEmployeeCount as number)
      > (summary.unresolvedCount as number)
    || (summary.blockingCount as number)
      > (summary.unresolvedCount as number)
  ) {
    throw new TypeError(
      'dashboard.summary counts are internally inconsistent',
    );
  }

  assertArray(candidate.exceptions, 'dashboard.exceptions');
  const detailMetadata = asRecord(
    candidate.metadata,
    'dashboard.metadata',
  );
  const metadataActions = detailMetadata.allowedActions as unknown[];
  if (
    !metadataActions.includes('DASHBOARD_DRILL_DOWN')
    && candidate.exceptions.length > 0
  ) {
    throw new TypeError(
      'dashboard.exceptions requires dashboard drill-down permission',
    );
  }
  if (candidate.exceptions.length > 10) {
    throw new TypeError(
      'dashboard.exceptions must contain at most 10 items',
    );
  }
  if (
    candidate.exceptions.length
      > (summary.unresolvedCount as number)
  ) {
    throw new TypeError(
      'dashboard.exceptions cannot exceed the unresolved count',
    );
  }
  const exceptionReferences = new Set<string>();
  for (const [index, value] of candidate.exceptions.entries()) {
    const exception = asRecord(
      value,
      `dashboard.exceptions[${index}]`,
    );
    assertOnlyKeys(
      exception,
      [
        'exceptionReference',
        'employeeNumber',
        'employeeName',
        'organizationName',
        'businessDate',
        'exceptionType',
        'severity',
        'state',
        'exceptionMinutes',
        'evidenceSummary',
      ],
      `dashboard.exceptions[${index}]`,
    );
    assertBoundedString(
      exception.exceptionReference,
      `dashboard.exceptions[${index}].exceptionReference`,
      128,
    );
    if (exceptionReferences.has(exception.exceptionReference)) {
      throw new TypeError(
        'dashboard.exceptions contains duplicate references',
      );
    }
    exceptionReferences.add(exception.exceptionReference);
    assertBoundedString(
      exception.employeeNumber,
      `dashboard.exceptions[${index}].employeeNumber`,
      128,
    );
    assertBoundedString(
      exception.employeeName,
      `dashboard.exceptions[${index}].employeeName`,
      200,
    );
    assertBoundedString(
      exception.organizationName,
      `dashboard.exceptions[${index}].organizationName`,
      200,
    );
    assertDate(
      exception.businessDate,
      `dashboard.exceptions[${index}].businessDate`,
    );
    if (exception.businessDate !== candidate.businessDate) {
      throw new TypeError(
        'dashboard exception date must match dashboard business date',
      );
    }
    assertBoundedString(
      exception.exceptionType,
      `dashboard.exceptions[${index}].exceptionType`,
      64,
    );
    if (!['INFO', 'WARNING', 'ERROR'].includes(
      exception.severity as string,
    )) {
      throw new TypeError(
        `dashboard.exceptions[${index}].severity is invalid`,
      );
    }
    if (![
      'OPEN',
      'PENDING_EVIDENCE',
      'PENDING_REVIEW',
    ].includes(exception.state as string)) {
      throw new TypeError(
        `dashboard.exceptions[${index}].state is invalid`,
      );
    }
    assertNonNegativeSafeInteger(
      exception.exceptionMinutes,
      `dashboard.exceptions[${index}].exceptionMinutes`,
    );
    assertBoundedString(
      exception.evidenceSummary,
      `dashboard.exceptions[${index}].evidenceSummary`,
      500,
    );
  }

  const metadata = asRecord(candidate.metadata, 'dashboard.metadata');
  assertYearMonth(
    metadata.periodLabel,
    'dashboard.metadata.periodLabel',
  );
  if (
    (candidate.businessDate as string).slice(0, 7)
      !== metadata.periodLabel
  ) {
    throw new TypeError(
      'dashboard business date must match the projection period',
    );
  }
  assertDashboardAnalytics(
    candidate.analytics,
    candidate.businessDate as string,
    metadata.periodLabel as string,
    summary,
  );

  assertDashboardCompanies(
    candidate.companies,
    'dashboard.companies',
    true,
  );
  const companyIds = new Set(
    (candidate.companies as DashboardCompanyOption[])
      .map((company) => company.companyId),
  );
  if (!companyIds.has(candidate.selectedCompanyId as string)) {
    throw new TypeError(
      'dashboard.selectedCompanyId is not in dashboard.companies',
    );
  }
}

function assertDashboardAnalytics(
  value: unknown,
  businessDate: string,
  period: string,
  summary: Record<string, unknown>,
): void {
  const analytics = asRecord(value, 'dashboard.analytics');
  assertOnlyKeys(
    analytics,
    [
      'dailyTrend',
      'severityDistribution',
      'typeDistribution',
      'organizationRanking',
    ],
    'dashboard.analytics',
  );
  assertDashboardDailyTrend(
    analytics.dailyTrend,
    businessDate,
    period,
    summary,
  );
  assertDashboardSeverityDistribution(
    analytics.severityDistribution,
    summary,
  );
  assertDashboardTypeDistribution(
    analytics.typeDistribution,
    summary,
  );
  assertDashboardOrganizationRanking(
    analytics.organizationRanking,
    summary,
  );
}

function assertDashboardDailyTrend(
  value: unknown,
  businessDate: string,
  period: string,
  summary: Record<string, unknown>,
): void {
  assertArray(value, 'dashboard.analytics.dailyTrend');
  if (value.length < 1 || value.length > 7) {
    throw new TypeError(
      'dashboard.analytics.dailyTrend must contain 1 to 7 items',
    );
  }
  const businessDay = dateOrdinal(businessDate);
  const periodStart = dateOrdinal(`${period}-01`);
  const expectedStart = Math.max(periodStart, businessDay - 6);
  if (value.length !== businessDay - expectedStart + 1) {
    throw new TypeError(
      'dashboard.analytics.dailyTrend window is incomplete',
    );
  }

  for (const [index, itemValue] of value.entries()) {
    const item = asRecord(
      itemValue,
      `dashboard.analytics.dailyTrend[${index}]`,
    );
    assertOnlyKeys(
      item,
      [
        'businessDate',
        'exceptionCount',
        'blockingCount',
        'affectedEmployeeCount',
      ],
      `dashboard.analytics.dailyTrend[${index}]`,
    );
    assertDate(
      item.businessDate,
      `dashboard.analytics.dailyTrend[${index}].businessDate`,
    );
    if (dateOrdinal(item.businessDate) !== expectedStart + index) {
      throw new TypeError(
        'dashboard.analytics.dailyTrend must be consecutive and ascending',
      );
    }
    assertNonNegativeSafeInteger(
      item.exceptionCount,
      `dashboard.analytics.dailyTrend[${index}].exceptionCount`,
    );
    assertNonNegativeSafeInteger(
      item.blockingCount,
      `dashboard.analytics.dailyTrend[${index}].blockingCount`,
    );
    assertNonNegativeSafeInteger(
      item.affectedEmployeeCount,
      `dashboard.analytics.dailyTrend[${index}].affectedEmployeeCount`,
    );
    if (
      (item.blockingCount as number) > (item.exceptionCount as number)
      || (item.affectedEmployeeCount as number)
        > (item.exceptionCount as number)
    ) {
      throw new TypeError(
        'dashboard.analytics.dailyTrend counts are inconsistent',
      );
    }
  }

  const today = asRecord(
    value[value.length - 1],
    'dashboard.analytics.dailyTrend current day',
  );
  if (
    today.businessDate !== businessDate
    || today.exceptionCount !== summary.unresolvedCount
    || today.blockingCount !== summary.blockingCount
    || today.affectedEmployeeCount !== summary.affectedEmployeeCount
  ) {
    throw new TypeError(
      'dashboard.analytics.dailyTrend current day must match summary',
    );
  }
}

function assertDashboardSeverityDistribution(
  value: unknown,
  summary: Record<string, unknown>,
): void {
  assertArray(value, 'dashboard.analytics.severityDistribution');
  const expectedSeverities: readonly DashboardAnomalySeverity[] = [
    'INFO',
    'WARNING',
    'ERROR',
  ];
  if (value.length !== expectedSeverities.length) {
    throw new TypeError(
      'dashboard.analytics.severityDistribution must contain all severities',
    );
  }
  let total = 0;
  for (const [index, itemValue] of value.entries()) {
    const item = asRecord(
      itemValue,
      `dashboard.analytics.severityDistribution[${index}]`,
    );
    assertOnlyKeys(
      item,
      ['severity', 'count'],
      `dashboard.analytics.severityDistribution[${index}]`,
    );
    if (item.severity !== expectedSeverities[index]) {
      throw new TypeError(
        'dashboard.analytics.severityDistribution order is invalid',
      );
    }
    assertNonNegativeSafeInteger(
      item.count,
      `dashboard.analytics.severityDistribution[${index}].count`,
    );
    total += item.count;
  }
  const errorCount = asRecord(
    value[2],
    'dashboard.analytics.severityDistribution[2]',
  ).count;
  if (
    total !== summary.unresolvedCount
    || errorCount !== summary.blockingCount
  ) {
    throw new TypeError(
      'dashboard.analytics.severityDistribution must match summary',
    );
  }
}

function assertDashboardTypeDistribution(
  value: unknown,
  summary: Record<string, unknown>,
): void {
  assertArray(value, 'dashboard.analytics.typeDistribution');
  if (value.length > 10) {
    throw new TypeError(
      'dashboard.analytics.typeDistribution must contain at most 10 items',
    );
  }
  if (
    ((summary.unresolvedCount as number) === 0) !== (value.length === 0)
  ) {
    throw new TypeError(
      'dashboard.analytics.typeDistribution emptiness is inconsistent',
    );
  }
  const types = new Set<string>();
  let total = 0;
  let previous:
    | { exceptionType: string; count: number }
    | undefined;
  for (const [index, itemValue] of value.entries()) {
    const item = asRecord(
      itemValue,
      `dashboard.analytics.typeDistribution[${index}]`,
    );
    assertOnlyKeys(
      item,
      ['exceptionType', 'count'],
      `dashboard.analytics.typeDistribution[${index}]`,
    );
    assertBoundedString(
      item.exceptionType,
      `dashboard.analytics.typeDistribution[${index}].exceptionType`,
      64,
    );
    assertNonNegativeSafeInteger(
      item.count,
      `dashboard.analytics.typeDistribution[${index}].count`,
    );
    if ((item.count as number) === 0) {
      throw new TypeError(
        'dashboard.analytics.typeDistribution counts must be positive',
      );
    }
    if (types.has(item.exceptionType)) {
      throw new TypeError(
        'dashboard.analytics.typeDistribution contains duplicate types',
      );
    }
    const current = {
      exceptionType: item.exceptionType,
      count: item.count as number,
    };
    if (
      previous !== undefined
      && (
        previous.count < current.count
        || (
          previous.count === current.count
          && previous.exceptionType > current.exceptionType
        )
      )
    ) {
      throw new TypeError(
        'dashboard.analytics.typeDistribution order is invalid',
      );
    }
    types.add(item.exceptionType);
    total += current.count;
    previous = current;
  }
  if (total > (summary.unresolvedCount as number)) {
    throw new TypeError(
      'dashboard.analytics.typeDistribution exceeds summary',
    );
  }
}

function assertDashboardOrganizationRanking(
  value: unknown,
  summary: Record<string, unknown>,
): void {
  assertArray(value, 'dashboard.analytics.organizationRanking');
  if (value.length > 5) {
    throw new TypeError(
      'dashboard.analytics.organizationRanking must contain at most 5 items',
    );
  }
  if (
    ((summary.unresolvedCount as number) === 0) !== (value.length === 0)
  ) {
    throw new TypeError(
      'dashboard.analytics.organizationRanking emptiness is inconsistent',
    );
  }
  const rankingItems = new Set<string>();
  let total = 0;
  let previous:
    | {
        organizationName: string;
        exceptionCount: number;
        blockingCount: number;
      }
    | undefined;
  for (const [index, itemValue] of value.entries()) {
    const item = asRecord(
      itemValue,
      `dashboard.analytics.organizationRanking[${index}]`,
    );
    assertOnlyKeys(
      item,
      ['organizationName', 'exceptionCount', 'blockingCount'],
      `dashboard.analytics.organizationRanking[${index}]`,
    );
    assertBoundedString(
      item.organizationName,
      `dashboard.analytics.organizationRanking[${index}].organizationName`,
      200,
    );
    assertNonNegativeSafeInteger(
      item.exceptionCount,
      `dashboard.analytics.organizationRanking[${index}].exceptionCount`,
    );
    assertNonNegativeSafeInteger(
      item.blockingCount,
      `dashboard.analytics.organizationRanking[${index}].blockingCount`,
    );
    if (
      (item.exceptionCount as number) === 0
      || (item.blockingCount as number)
        > (item.exceptionCount as number)
    ) {
      throw new TypeError(
        'dashboard.analytics.organizationRanking counts are inconsistent',
      );
    }
    const current = {
      organizationName: item.organizationName,
      exceptionCount: item.exceptionCount as number,
      blockingCount: item.blockingCount as number,
    };
    const itemKey = JSON.stringify(current);
    if (rankingItems.has(itemKey)) {
      throw new TypeError(
        'dashboard.analytics.organizationRanking contains duplicate items',
      );
    }
    if (
      previous !== undefined
      && (
        previous.exceptionCount < current.exceptionCount
        || (
          previous.exceptionCount === current.exceptionCount
          && previous.blockingCount < current.blockingCount
        )
        || (
          previous.exceptionCount === current.exceptionCount
          && previous.blockingCount === current.blockingCount
          && previous.organizationName > current.organizationName
        )
      )
    ) {
      throw new TypeError(
        'dashboard.analytics.organizationRanking order is invalid',
      );
    }
    rankingItems.add(itemKey);
    total += current.exceptionCount;
    previous = current;
  }
  if (total > (summary.unresolvedCount as number)) {
    throw new TypeError(
      'dashboard.analytics.organizationRanking exceeds summary',
    );
  }
}

function assertDashboardCompanies(
  value: unknown,
  label: string,
  requireNonEmpty: boolean,
): asserts value is DashboardCompanyOption[] {
  assertArray(value, label);
  if (requireNonEmpty && value.length === 0) {
    throw new TypeError(`${label} must not be empty`);
  }
  const identifiers = new Set<string>();
  for (const [index, companyValue] of value.entries()) {
    const company = asRecord(companyValue, `${label}[${index}]`);
    assertOnlyKeys(
      company,
      ['companyId', 'companyName'],
      `${label}[${index}]`,
    );
    assertBoundedString(
      company.companyId,
      `${label}[${index}].companyId`,
      36,
    );
    assertBoundedString(
      company.companyName,
      `${label}[${index}].companyName`,
      200,
    );
    if (identifiers.has(company.companyId as string)) {
      throw new TypeError(`${label} contains duplicate ids`);
    }
    identifiers.add(company.companyId as string);
  }
}

function assertSelfAttendanceDashboardMetadata(
  value: unknown,
): SelfAttendanceDashboardMetadata {
  const metadata = asRecord(
    value,
    'self attendance dashboard.metadata',
  );
  assertOnlyKeys(
    metadata,
    [
      'projectionVersion',
      'sourceVersions',
      'dataAsOf',
      'timeZone',
      'periodLabel',
      'periodState',
      'scope',
    ],
    'self attendance dashboard.metadata',
  );
  assertBoundedString(
    metadata.projectionVersion,
    'self attendance dashboard.metadata.projectionVersion',
    128,
  );
  assertArray(
    metadata.sourceVersions,
    'self attendance dashboard.metadata.sourceVersions',
  );
  if (metadata.sourceVersions.length > 50) {
    throw new TypeError(
      'self attendance dashboard.metadata.sourceVersions is too large',
    );
  }
  const sourceVersions = new Set<string>();
  for (const [index, sourceVersion] of metadata.sourceVersions.entries()) {
    assertBoundedString(
      sourceVersion,
      `self attendance dashboard.metadata.sourceVersions[${index}]`,
      256,
    );
    if (sourceVersions.has(sourceVersion)) {
      throw new TypeError(
        'self attendance dashboard.metadata.sourceVersions contains duplicates',
      );
    }
    sourceVersions.add(sourceVersion);
  }
  assertInstant(
    metadata.dataAsOf,
    'self attendance dashboard.metadata.dataAsOf',
  );
  if (metadata.timeZone !== 'Asia/Shanghai') {
    throw new TypeError(
      'self attendance dashboard.metadata.timeZone must be Asia/Shanghai',
    );
  }
  assertYearMonth(
    metadata.periodLabel,
    'self attendance dashboard.metadata.periodLabel',
  );
  if (!periodStates.includes(metadata.periodState as Wave7PeriodState)) {
    throw new TypeError(
      'self attendance dashboard.metadata.periodState is invalid',
    );
  }
  const scope = asRecord(
    metadata.scope,
    'self attendance dashboard.metadata.scope',
  );
  assertOnlyKeys(
    scope,
    ['type', 'reference', 'label'],
    'self attendance dashboard.metadata.scope',
  );
  if (
    scope.type !== 'SELF'
    || scope.reference !== 'current-principal'
    || scope.label !== '本人'
  ) {
    throw new TypeError(
      'self attendance dashboard.metadata.scope must identify the current principal',
    );
  }
  assertBoundedString(
    scope.reference,
    'self attendance dashboard.metadata.scope.reference',
    128,
  );
  assertBoundedString(
    scope.label,
    'self attendance dashboard.metadata.scope.label',
    200,
  );
  return metadata as unknown as SelfAttendanceDashboardMetadata;
}

function assertSelfAttendanceDashboardTrend(
  value: unknown,
  period: string,
  businessDate: string,
): void {
  assertArray(value, 'self attendance dashboard.dailyTrend');
  if (value.length > 31) {
    throw new TypeError(
      'self attendance dashboard.dailyTrend is too large',
    );
  }
  let previousDate = '';
  for (const [index, pointValue] of value.entries()) {
    const point = asRecord(
      pointValue,
      `self attendance dashboard.dailyTrend[${index}]`,
    );
    assertOnlyKeys(
      point,
      [
        'businessDate',
        'scheduledMinutes',
        'confirmedMinutes',
        'recognizedOvertimeMinutes',
        'leaveMinutes',
        'issueCount',
      ],
      `self attendance dashboard.dailyTrend[${index}]`,
    );
    assertDate(
      point.businessDate,
      `self attendance dashboard.dailyTrend[${index}].businessDate`,
    );
    if (
      !(point.businessDate as string).startsWith(period)
      || (point.businessDate as string) > businessDate
      || (point.businessDate as string) <= previousDate
    ) {
      throw new TypeError(
        'self attendance dashboard.dailyTrend dates are invalid',
      );
    }
    previousDate = point.businessDate as string;
    for (const key of [
      'scheduledMinutes',
      'confirmedMinutes',
      'recognizedOvertimeMinutes',
      'leaveMinutes',
      'issueCount',
    ] as const) {
      assertNonNegativeSafeInteger(
        point[key],
        `self attendance dashboard.dailyTrend[${index}].${key}`,
      );
    }
  }
}

function assertSelfAttendanceDashboardToday(value: unknown): void {
  if (value === null) return;
  const today = asRecord(value, 'self attendance dashboard.today');
  assertOnlyKeys(
    today,
    [
      'shiftLabel',
      'firstPunchAt',
      'lastPunchAt',
      'statusLabel',
      'confirmedMinutes',
      'issueLabels',
    ],
    'self attendance dashboard.today',
  );
  assertNullableString(
    today.shiftLabel,
    'self attendance dashboard.today.shiftLabel',
  );
  if (typeof today.shiftLabel === 'string') {
    assertBoundedString(
      today.shiftLabel,
      'self attendance dashboard.today.shiftLabel',
      200,
    );
  }
  for (const key of ['firstPunchAt', 'lastPunchAt'] as const) {
    assertNullableString(
      today[key],
      `self attendance dashboard.today.${key}`,
    );
    if (typeof today[key] === 'string') {
      assertInstant(
        today[key],
        `self attendance dashboard.today.${key}`,
      );
    }
  }
  assertBoundedString(
    today.statusLabel,
    'self attendance dashboard.today.statusLabel',
    64,
  );
  assertNonNegativeSafeInteger(
    today.confirmedMinutes,
    'self attendance dashboard.today.confirmedMinutes',
  );
  assertArray(
    today.issueLabels,
    'self attendance dashboard.today.issueLabels',
  );
  if (today.issueLabels.length > 50) {
    throw new TypeError(
      'self attendance dashboard.today.issueLabels is too large',
    );
  }
  const issueLabels = new Set<string>();
  for (const [index, issueLabel] of today.issueLabels.entries()) {
    assertBoundedString(
      issueLabel,
      `self attendance dashboard.today.issueLabels[${index}]`,
      64,
    );
    if (issueLabels.has(issueLabel)) {
      throw new TypeError(
        'self attendance dashboard.today.issueLabels contains duplicates',
      );
    }
    issueLabels.add(issueLabel);
  }
}

function assertSelfAttendanceExceptionTypeDistribution(
  value: unknown,
  unresolvedExceptionCount: number,
): void {
  assertArray(
    value,
    'self attendance dashboard.exceptionTypeDistribution',
  );
  if (value.length > 50) {
    throw new TypeError(
      'self attendance dashboard.exceptionTypeDistribution is too large',
    );
  }
  let total = 0;
  let previous: { type: string; count: number } | undefined;
  const types = new Set<string>();
  for (const [index, itemValue] of value.entries()) {
    const item = asRecord(
      itemValue,
      `self attendance dashboard.exceptionTypeDistribution[${index}]`,
    );
    assertOnlyKeys(
      item,
      ['type', 'count'],
      `self attendance dashboard.exceptionTypeDistribution[${index}]`,
    );
    assertBoundedString(
      item.type,
      `self attendance dashboard.exceptionTypeDistribution[${index}].type`,
      64,
    );
    assertPositiveInteger(
      item.count,
      `self attendance dashboard.exceptionTypeDistribution[${index}].count`,
    );
    const current = {
      type: item.type as string,
      count: item.count as number,
    };
    if (
      types.has(current.type)
      || (
        previous !== undefined
        && (
          previous.count < current.count
          || (
            previous.count === current.count
            && previous.type > current.type
          )
        )
      )
    ) {
      throw new TypeError(
        'self attendance dashboard.exceptionTypeDistribution is inconsistent',
      );
    }
    types.add(current.type);
    total += current.count;
    previous = current;
  }
  if (total !== unresolvedExceptionCount) {
    throw new TypeError(
      'self attendance dashboard exception totals are inconsistent',
    );
  }
}

function assertSelfAttendanceRecentExceptions(
  value: unknown,
  period: string,
  businessDate: string,
  unresolvedExceptionCount: number,
): void {
  assertArray(value, 'self attendance dashboard.recentExceptions');
  if (
    value.length > 10
    || value.length > unresolvedExceptionCount
  ) {
    throw new TypeError(
      'self attendance dashboard.recentExceptions is too large',
    );
  }
  for (const [index, exceptionValue] of value.entries()) {
    const exception = asRecord(
      exceptionValue,
      `self attendance dashboard.recentExceptions[${index}]`,
    );
    assertOnlyKeys(
      exception,
      [
        'businessDate',
        'type',
        'severity',
        'state',
        'minutes',
        'safeEvidenceSummary',
      ],
      `self attendance dashboard.recentExceptions[${index}]`,
    );
    assertDate(
      exception.businessDate,
      `self attendance dashboard.recentExceptions[${index}].businessDate`,
    );
    if (
      !(exception.businessDate as string).startsWith(period)
      || (exception.businessDate as string) > businessDate
    ) {
      throw new TypeError(
        'self attendance dashboard.recentExceptions date is invalid',
      );
    }
    assertBoundedString(
      exception.type,
      `self attendance dashboard.recentExceptions[${index}].type`,
      64,
    );
    if (
      exception.severity !== 'INFO'
      && exception.severity !== 'WARNING'
      && exception.severity !== 'ERROR'
    ) {
      throw new TypeError(
        'self attendance dashboard recent exception severity is invalid',
      );
    }
    if (
      exception.state !== 'OPEN'
      && exception.state !== 'PENDING_EVIDENCE'
      && exception.state !== 'PENDING_REVIEW'
    ) {
      throw new TypeError(
        'self attendance dashboard recent exception state is invalid',
      );
    }
    assertNonNegativeSafeInteger(
      exception.minutes,
      `self attendance dashboard.recentExceptions[${index}].minutes`,
    );
    assertBoundedString(
      exception.safeEvidenceSummary,
      `self attendance dashboard.recentExceptions[${index}].safeEvidenceSummary`,
      500,
    );
  }
}

function assertProjectionMetadata(value: unknown): asserts value is Wave7ProjectionMetadata {
  const metadata = asRecord(value, 'projection.metadata');
  assertOnlyKeys(
    metadata,
    [
      'projectionVersion',
      'sourceVersions',
      'dataAsOf',
      'timeZone',
      'periodLabel',
      'periodState',
      'scope',
      'allowedActions',
    ],
    'projection.metadata',
  );
  assertString(metadata.projectionVersion, 'metadata.projectionVersion');
  assertStringArray(metadata.sourceVersions, 'metadata.sourceVersions');
  assertString(metadata.dataAsOf, 'metadata.dataAsOf');
  if (metadata.timeZone !== 'Asia/Shanghai') {
    throw new TypeError('metadata.timeZone must be Asia/Shanghai');
  }
  assertString(metadata.periodLabel, 'metadata.periodLabel');
  if (!periodStates.includes(metadata.periodState as Wave7PeriodState)) {
    throw new TypeError('metadata.periodState is invalid');
  }
  const scope = asRecord(metadata.scope, 'metadata.scope');
  assertOnlyKeys(
    scope,
    ['type', 'reference', 'label'],
    'metadata.scope',
  );
  if (!scopeTypes.includes(scope.type as Wave7ScopeType)) {
    throw new TypeError('metadata.scope.type is invalid');
  }
  assertString(scope.reference, 'metadata.scope.reference');
  assertString(scope.label, 'metadata.scope.label');
  assertStringArray(metadata.allowedActions, 'metadata.allowedActions');
  if (!metadata.allowedActions.every((action) => allowedActions.includes(action as Wave7AllowedAction))) {
    throw new TypeError('metadata.allowedActions contains an unsupported action');
  }
}

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function assertOnlyKeys(
  value: Record<string, unknown>,
  allowedKeys: readonly string[],
  label: string,
): void {
  const allowed = new Set(allowedKeys);
  if (Object.keys(value).some((key) => !allowed.has(key))) {
    throw new TypeError(`${label} contains an unsupported property`);
  }
}

function assertString(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new TypeError(`${label} must be a non-empty string`);
  }
}

function assertBoundedString(
  value: unknown,
  label: string,
  maximumLength: number,
): asserts value is string {
  assertString(value, label);
  if (
    value.length > maximumLength
    || hasUnsafeTextControl(value)
  ) {
    throw new TypeError(
      `${label} must be at most ${maximumLength} safe characters`,
    );
  }
}

function assertNumber(value: unknown, label: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new TypeError(`${label} must be a finite number`);
  }
}

function assertNonNegativeInteger(
  value: unknown,
  label: string,
): asserts value is number {
  if (!Number.isInteger(value) || (value as number) < 0) {
    throw new TypeError(`${label} must be a non-negative integer`);
  }
}

function assertPositiveInteger(
  value: unknown,
  label: string,
): asserts value is number {
  if (!Number.isInteger(value) || (value as number) < 1) {
    throw new TypeError(`${label} must be a positive integer`);
  }
}

function assertNonNegativeSafeInteger(
  value: unknown,
  label: string,
): asserts value is number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new TypeError(`${label} must be a non-negative safe integer`);
  }
}

function assertArray(value: unknown, label: string): asserts value is unknown[] {
  if (!Array.isArray(value)) {
    throw new TypeError(`${label} must be an array`);
  }
}

function assertStringArray(value: unknown, label: string): asserts value is string[] {
  assertArray(value, label);
  if (!value.every((item) => typeof item === 'string')) {
    throw new TypeError(`${label} must contain strings`);
  }
}

function assertNullableString(value: unknown, label: string): void {
  if (value !== undefined && value !== null && typeof value !== 'string') {
    throw new TypeError(`${label} must be a string or null`);
  }
}

function assertNullableBoundedString(
  value: unknown,
  label: string,
  maximumLength: number,
): void {
  assertNullableString(value, label);
  if (
    typeof value === 'string'
    && (
      value.length > maximumLength
      || hasUnsafeTextControl(value)
    )
  ) {
    throw new TypeError(
      `${label} must be at most ${maximumLength} safe characters or null`,
    );
  }
}

function assertYearMonth(value: unknown, label: string): asserts value is string {
  if (
    typeof value !== 'string'
    || !/^\d{4}-(0[1-9]|1[0-2])$/.test(value)
  ) {
    throw new TypeError(`${label} must use YYYY-MM`);
  }
}

function datesInMonth(period: string): string[] {
  const [yearValue, monthValue] = period.split('-');
  const year = Number(yearValue);
  const month = Number(monthValue);
  const count = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return Array.from({ length: count }, (_, index) => (
    `${period}-${String(index + 1).padStart(2, '0')}`
  ));
}

function assertDate(value: unknown, label: string): asserts value is string {
  if (typeof value !== 'string') {
    throw new TypeError(`${label} must use YYYY-MM-DD`);
  }
  const match = /^(\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/
    .exec(value);
  if (match === null) {
    throw new TypeError(`${label} must use YYYY-MM-DD`);
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (
    parsed.getUTCFullYear() !== year
    || parsed.getUTCMonth() !== month - 1
    || parsed.getUTCDate() !== day
  ) {
    throw new TypeError(`${label} must use YYYY-MM-DD`);
  }
}

function dateOrdinal(value: string): number {
  return Math.floor(
    Date.parse(`${value}T00:00:00Z`) / 86_400_000,
  );
}

function assertInstant(value: unknown, label: string): asserts value is string {
  if (
    typeof value !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
    || !Number.isFinite(Date.parse(value))
  ) {
    throw new TypeError(`${label} must be an ISO-8601 UTC instant`);
  }
}

function assertReportColumnKey(
  value: unknown,
  label: string,
): asserts value is ReportColumnKey {
  if (!reportColumnKeys.includes(value as ReportColumnKey)) {
    throw new TypeError(`${label} is unsupported`);
  }
}

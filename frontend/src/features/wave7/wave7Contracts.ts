export type Wave7PeriodState = 'OPEN' | 'FROZEN' | 'CLOSED' | 'REOPENED';
export type Wave7ScopeType = 'COMPANY' | 'ORGANIZATION' | 'ATTENDANCE_GROUP' | 'SELF';
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
  timeZone: 'Asia/Shanghai';
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

export interface DashboardProjection {
  kind: 'DASHBOARD';
  metadata: Wave7ProjectionMetadata;
  title: string;
  metrics: DashboardMetricProjection[];
}

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
  legalEntityId?: string | null;
  organizationId?: string | null;
  employeeId?: string | null;
  status?: string | null;
}

export interface ReportProjection {
  kind: 'REPORT';
  metadata: Wave7ProjectionMetadata;
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
    filters: ReportFilterProjection & { legalEntityId: string };
  };

export interface AttendanceReportLegalEntityOption {
  legalEntityId: string;
  name: string;
}

export interface AttendanceReportLegalEntityDirectory {
  period: string;
  legalEntities: AttendanceReportLegalEntityOption[];
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
  legalEntityId: string;
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
    assertString(candidate.businessDate, 'today.businessDate');
    assertString(candidate.attendanceStatus, 'today.attendanceStatus');
    assertNumber(candidate.confirmedMinutes, 'today.confirmedMinutes');
    assertStringArray(candidate.issueLabels, 'today.issueLabels');
    return;
  }
  if (candidate.kind === 'RECORDS') {
    asRecord(candidate.summary, 'records.summary');
    assertArray(candidate.records, 'records.records');
    return;
  }
  if (candidate.kind === 'LEAVE') {
    assertArray(candidate.accounts, 'leave.accounts');
    return;
  }
  if (candidate.kind === 'FEEDBACK') {
    assertArray(candidate.items, 'feedback.items');
    return;
  }
  if (candidate.kind === 'DASHBOARD') {
    assertString(candidate.title, 'dashboard.title');
    assertArray(candidate.metrics, 'dashboard.metrics');
    return;
  }
  if (candidate.kind === 'REPORT') {
    assertReportProjection(candidate);
    return;
  }
  throw new TypeError(`Unsupported Wave 7 projection kind: ${candidate.kind}`);
}

export function assertLiveReportProjection(
  value: unknown,
): asserts value is LiveReportProjection {
  assertWave7Projection(value);
  const candidate = asRecord(value, 'attendance report response');
  if (candidate.kind !== 'REPORT') {
    throw new TypeError('attendance report response kind must be REPORT');
  }
  if (!attendanceReportTypes.includes(candidate.reportType as AttendanceReportType)) {
    throw new TypeError('report.reportType is invalid');
  }
  assertString(candidate.formulaVersion, 'report.formulaVersion');
  const filters = asRecord(candidate.filters, 'report.filters');
  assertBoundedString(
    filters.legalEntityId,
    'report.filters.legalEntityId',
    36,
  );
  assertNonNegativeInteger(candidate.page, 'report.page');
  assertPositiveInteger(candidate.size, 'report.size');
  assertNonNegativeInteger(candidate.totalPages, 'report.totalPages');
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

export function assertAttendanceReportLegalEntityDirectory(
  value: unknown,
): asserts value is AttendanceReportLegalEntityDirectory {
  const candidate = asRecord(value, 'attendance report legal entity directory');
  assertYearMonth(
    candidate.period,
    'attendance report legal entity directory period',
  );
  assertArray(
    candidate.legalEntities,
    'attendance report legal entity directory legalEntities',
  );
  const identifiers = new Set<string>();
  for (const [index, value] of candidate.legalEntities.entries()) {
    const option = asRecord(
      value,
      `attendance report legal entity directory legalEntities[${index}]`,
    );
    assertBoundedString(
      option.legalEntityId,
      `attendance report legal entity directory legalEntities[${index}].legalEntityId`,
      36,
    );
    assertBoundedString(
      option.name,
      `attendance report legal entity directory legalEntities[${index}].name`,
      200,
    );
    if (identifiers.has(option.legalEntityId as string)) {
      throw new TypeError(
        'attendance report legal entity directory contains duplicate ids',
      );
    }
    identifiers.add(option.legalEntityId as string);
  }
}

export function assertAttendanceReportExportView(
  value: unknown,
): asserts value is AttendanceReportExportView {
  const candidate = asRecord(value, 'attendance report export');
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
    candidate.legalEntityId,
    'attendance report export legalEntityId',
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
  assertString(candidate.reportTitle, 'report.reportTitle');
  assertString(candidate.queryFingerprint, 'report.queryFingerprint');
  const filters = asRecord(candidate.filters, 'report.filters');
  assertYearMonth(filters.period, 'report.filters.period');
  assertString(filters.scopeReference, 'report.filters.scopeReference');
  assertNullableString(filters.legalEntityId, 'report.filters.legalEntityId');
  assertNullableString(filters.organizationId, 'report.filters.organizationId');
  assertNullableString(filters.employeeId, 'report.filters.employeeId');
  assertNullableString(filters.status, 'report.filters.status');

  assertArray(candidate.columns, 'report.columns');
  const visibleColumns = new Set<ReportColumnKey>();
  for (const [index, value] of candidate.columns.entries()) {
    const column = asRecord(value, `report.columns[${index}]`);
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

  assertNonNegativeInteger(candidate.rowCount, 'report.rowCount');
  assertArray(candidate.rows, 'report.rows');
  for (const [index, value] of candidate.rows.entries()) {
    const row = asRecord(value, `report.rows[${index}]`);
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

function assertProjectionMetadata(value: unknown): asserts value is Wave7ProjectionMetadata {
  const metadata = asRecord(value, 'projection.metadata');
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

function assertYearMonth(value: unknown, label: string): asserts value is string {
  if (
    typeof value !== 'string'
    || !/^\d{4}-(0[1-9]|1[0-2])$/.test(value)
  ) {
    throw new TypeError(`${label} must use YYYY-MM`);
  }
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

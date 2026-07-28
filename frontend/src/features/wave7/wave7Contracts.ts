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

export type ReportColumnKey =
  | 'scope'
  | 'scheduled-hours'
  | 'confirmed-hours'
  | 'late-count'
  | 'early-count'
  | 'missing-count'
  | 'absence-hours'
  | 'recognized-overtime-hours'
  | 'leave-hours';

export interface ReportRowProjection {
  rowReference: string;
  values: Partial<Record<ReportColumnKey, string | number>>;
  drillDownReference?: string;
}

export interface ReportFilterProjection {
  period: string;
  scopeReference: string;
  status?: string;
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
    assertString(candidate.reportTitle, 'report.reportTitle');
    assertString(candidate.queryFingerprint, 'report.queryFingerprint');
    assertArray(candidate.columns, 'report.columns');
    assertStringArray(candidate.exportFieldAllowlist, 'report.exportFieldAllowlist');
    assertNumber(candidate.rowCount, 'report.rowCount');
    assertArray(candidate.rows, 'report.rows');
    return;
  }
  throw new TypeError(`Unsupported Wave 7 projection kind: ${candidate.kind}`);
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

function assertNumber(value: unknown, label: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new TypeError(`${label} must be a finite number`);
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

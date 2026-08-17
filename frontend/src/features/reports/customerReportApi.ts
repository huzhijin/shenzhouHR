import { ApiRequestError, saveDownloadedFile } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  AttendanceMonthMatrixProjection,
  AttendanceReportType,
  ReportProjection,
} from '../wave7/wave7Contracts';
import { normalizeReportExportPurpose } from '../wave7/wave7Contracts';
import type { CustomerReportDataScope } from './customerReportAccess';
import { defaultCustomerReportDataScope } from './customerReportAccess';
import type {
  CustomerReportDemo,
  CustomerReportFilters,
  CustomerReportKey,
} from './customerReportDemo';
import { formatMonth, getCustomerReportDemo } from './customerReportDemo';
import {
  toAnnualLeaveRows,
  toAttendanceDetailRows,
  toAttendanceExceptionRows,
  toAttendanceRateRows,
  toLateRows,
  toLeaveRows,
  toMissedPunchRows,
  toOvertimeRows,
  toWorkHoursRows,
} from './customerReportMapper';

export const ALL_DEPARTMENTS = '全部部门';
export const ALL_EMPLOYEES = '全部员工';

export interface CustomerReportDirectoryEntry {
  employeeId: string;
  employeeNo: string;
  employee: string;
  organizationId: string;
  department: string;
}

/** `AttendanceReportQueryService` rejects `size > 200`, so wide months need paging. */
const PAGE_SIZE = 200;

/**
 * Page-loop ceiling. Reaching it means the projection is wider than one view can
 * hold; the sheet then says so instead of silently showing a partial company.
 */
const MAX_PAGES = 50;

const reportTypes: Record<CustomerReportKey, AttendanceReportType> = {
  'attendance-detail': 'ATTENDANCE_DETAIL',
  leave: 'LEAVE',
  overtime: 'OVERTIME',
  'work-hours': 'WORK_HOURS',
  exceptions: 'EXCEPTIONS',
  late: 'LATE',
  'missed-punch': 'MISSED_PUNCH',
  'attendance-rate': 'ATTENDANCE_RATE',
  'annual-leave': 'ANNUAL_LEAVE',
};

export function isCustomerReportDemoMode(): boolean {
  return isDemoMode();
}

/**
 * Authorized companies for the period become the scope selector's options. The
 * backend already restricts the directory to the caller, so no client-side
 * allow-list is applied on top of it.
 */
export async function loadCustomerReportScopes(
  period: string,
): Promise<readonly CustomerReportDataScope[]> {
  if (isDemoMode()) {
    const { customerReportDemoScopes } = await import('./customerReportAccess');
    return customerReportDemoScopes;
  }
  const directory = await wave7ProjectionGateway.loadReportCompanies(period);
  return directory.companies.map((company) => ({
    reference: company.companyId,
    type: 'COMPANY' as const,
    label: company.companyName,
    actorLabel: '授权范围',
    allowedDepartments: [],
    allowedEmployees: [],
  }));
}

interface PagedRows<T> {
  rows: T[];
  truncated: boolean;
}

async function collectReportPages(
  reportType: AttendanceReportType,
  period: string,
  companyId: string,
  identityFilters: Pick<CustomerReportFilters, 'organizationId' | 'employeeId'> = {},
): Promise<PagedRows<ReportProjection['rows'][number]> & {
  head: ReportProjection;
}> {
  const rows: ReportProjection['rows'] = [];
  let head: ReportProjection | undefined;
  let page = 0;
  let truncated = false;
  for (; page < MAX_PAGES; page += 1) {
    const projection = await wave7ProjectionGateway.loadReport({
      reportType,
      period,
      companyId,
      organizationId: identityFilters.organizationId,
      employeeId: identityFilters.employeeId,
      page,
      size: PAGE_SIZE,
    });
    head ??= projection;
    rows.push(...projection.rows);
    if (rows.length >= projection.rowCount || projection.rows.length === 0) {
      break;
    }
  }
  if (head === undefined) {
    throw new Error('report projection returned no page');
  }
  if (rows.length < head.rowCount) {
    truncated = true;
  }
  return { rows, truncated, head };
}

/**
 * Demo and legacy callers still filter by labels. Formal callers send stable
 * identities to the server and do not rely on ambiguous presentation names.
 */
function matchesFilters(
  row: { department: string; employee: string },
  filters: CustomerReportFilters,
): boolean {
  const departmentOk = filters.department === ALL_DEPARTMENTS
    || row.department === filters.department;
  const employeeOk = filters.employee === ALL_EMPLOYEES
    || row.employee === filters.employee;
  return departmentOk && employeeOk;
}

async function collectMatrixPages(
  period: string,
  companyId: string,
  identityFilters: Pick<CustomerReportFilters, 'organizationId' | 'employeeId'> = {},
): Promise<PagedRows<AttendanceMonthMatrixProjection['rows'][number]> & {
  head: AttendanceMonthMatrixProjection;
}> {
  const loadMatrix = wave7ProjectionGateway.loadAttendanceMonthMatrix;
  if (loadMatrix === undefined) {
    throw new ApiRequestError(501, {
      code: 'MONTH_MATRIX_UNAVAILABLE',
      retryable: false,
    });
  }
  const rows: AttendanceMonthMatrixProjection['rows'] = [];
  let head: AttendanceMonthMatrixProjection | undefined;
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const projection = await loadMatrix.call(wave7ProjectionGateway, {
      period,
      companyId,
      organizationId: identityFilters.organizationId,
      employeeId: identityFilters.employeeId,
      page,
      size: PAGE_SIZE,
    });
    head ??= projection;
    rows.push(...projection.rows);
    if (rows.length >= projection.employeeCount || projection.rows.length === 0) {
      break;
    }
  }
  if (head === undefined) {
    throw new Error('month matrix projection returned no page');
  }
  if (rows.length < head.employeeCount) {
    truncated = true;
  }
  return { rows, truncated, head };
}

/** Complete server-authorized directory, independent of active report filters. */
export async function loadCustomerReportDirectory(
  period: string,
  companyId: string,
): Promise<readonly CustomerReportDirectoryEntry[]> {
  if (isDemoMode()) return [];
  const { rows, truncated } = await collectMatrixPages(period, companyId);
  if (truncated) {
    throw new Error('授权员工目录超过当前可加载上限，请联系管理员。');
  }
  return Array.from(new Map(rows.map((row) => [row.employeeId, {
    employeeId: row.employeeId,
    employeeNo: row.employeeNumber,
    employee: row.employeeName,
    organizationId: row.organizationId,
    department: row.organizationName,
  }])).values());
}

function emptyReport(
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope,
): CustomerReportDemo {
  return {
    metadata: {
      isDemo: false,
      company: scope.label,
      generatedAt: new Date().toISOString(),
      month: filters.month,
      monthLabel: formatMonth(filters.month),
      rowCount: 0,
      dataScope: scope,
    },
    attendanceRows: [],
    leaveRows: [],
    overtimeRows: [],
    workHoursRows: [],
    attendanceExceptionRows: [],
    lateRows: [],
    missedPunchRows: [],
    attendanceRateRows: [],
    annualLeaveRows: [],
  };
}

/** Only the active sheet is fetched; switching tabs triggers its own load. */
function sheetFor(
  reportKey: Exclude<CustomerReportKey, 'attendance-detail'>,
  projection: ReportProjection,
  filters: CustomerReportFilters,
): Partial<CustomerReportDemo> {
  const keep = <T extends { department: string; employee: string }>(rows: T[]) =>
    rows.filter((row) => matchesFilters(row, filters));
  switch (reportKey) {
    case 'leave':
      return { leaveRows: keep(toLeaveRows(projection)) };
    case 'overtime':
      return { overtimeRows: keep(toOvertimeRows(projection)) };
    case 'work-hours':
      return { workHoursRows: keep(toWorkHoursRows(projection)) };
    case 'exceptions':
      return { attendanceExceptionRows: keep(toAttendanceExceptionRows(projection)) };
    case 'late':
      return { lateRows: keep(toLateRows(projection)) };
    case 'missed-punch':
      return { missedPunchRows: keep(toMissedPunchRows(projection)) };
    case 'attendance-rate':
      return { attendanceRateRows: keep(toAttendanceRateRows(projection)) };
    case 'annual-leave':
      return { annualLeaveRows: keep(toAnnualLeaveRows(projection)) };
  }
}

function sheetRowCount(sheet: Partial<CustomerReportDemo>): number {
  return Object.values(sheet).reduce(
    (total, rows) => total + (Array.isArray(rows) ? rows.length : 0),
    0,
  );
}

export async function loadCustomerReport(
  reportKey: CustomerReportKey,
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope = defaultCustomerReportDataScope,
): Promise<CustomerReportDemo> {
  if (isDemoMode()) {
    return getCustomerReportDemo(filters, scope);
  }
  const base = emptyReport(filters, scope);
  if (reportKey === 'attendance-detail') {
    const { rows, truncated, head } = await collectMatrixPages(
      filters.month,
      scope.reference,
      filters,
    );
    const attendanceRows = toAttendanceDetailRows({ ...head, rows });
    return {
      ...base,
      metadata: {
        ...base.metadata,
        generatedAt: head.metadata.dataAsOf,
        rowCount: attendanceRows.length,
        periodState: head.metadata.periodState,
        truncated,
      },
      attendanceRows,
    };
  }
  const { rows, truncated, head } = await collectReportPages(
    reportTypes[reportKey],
    filters.month,
    scope.reference,
    filters,
  );
  const sheet = sheetFor(reportKey, { ...head, rows }, {
    ...filters,
    department: ALL_DEPARTMENTS,
    employee: ALL_EMPLOYEES,
  });
  return {
    ...base,
    ...sheet,
    metadata: {
      ...base.metadata,
      generatedAt: head.metadata.dataAsOf,
      rowCount: sheetRowCount(sheet),
      periodState: head.metadata.periodState,
      truncated,
    },
  };
}

/**
 * These columns exist for backend reconciliation and audit only; the customer
 * sheet and its export deliberately omit their opaque values. Kept local so this
 * module never imports a page component.
 */
const internalExportColumns: readonly string[] = [
  'document-reference',
  'rate-formula-version',
];

export interface CustomerReportExportSpec {
  reportKey: CustomerReportKey;
  period: string;
  companyId: string;
  reportTitle: string;
  organizationId?: string;
  employeeId?: string;
}

/** Ceiling on status polls before the caller is told the job did not finish. */
const MAX_EXPORT_POLLS = 30;
const EXPORT_POLL_INTERVAL_MS = 2000;

/**
 * Creates a server-side XLSX export and downloads it once ready.
 *
 * The export binding must match the projection the server is serving right now,
 * so the current page of the report is fetched first to read its
 * `projectionVersion`, `queryFingerprint` and scope reference. A stale binding is
 * rejected by the backend rather than silently exporting different numbers.
 *
 * Demo mode and environments whose gateway lacks the export stubs fall back to
 * the caller's CSV writer.
 */
export async function exportCustomerReport(
  spec: CustomerReportExportSpec,
  csvFallback: () => void,
): Promise<void> {
  const gateway = wave7ProjectionGateway;
  if (
    isDemoMode()
    || gateway.createReportExport === undefined
    || gateway.downloadReportExport === undefined
  ) {
    csvFallback();
    return;
  }

  const reportType = reportTypes[spec.reportKey];
  const projection = await gateway.loadReport({
    reportType,
    period: spec.period,
    companyId: spec.companyId,
    organizationId: spec.organizationId,
    employeeId: spec.employeeId,
    page: 0,
    size: 1,
  });
  const selectedFields = projection.exportFieldAllowlist.filter(
    (field) => !internalExportColumns.includes(field),
  );
  if (selectedFields.length === 0) {
    throw new Error('当前报表没有可导出的字段。');
  }
  const scopeReference = projection.metadata.scope.reference;

  let view = await gateway.createReportExport({
    reportType,
    projectionVersion: projection.metadata.projectionVersion,
    queryFingerprint: projection.queryFingerprint,
    scopeReference,
    filters: {
      ...projection.filters,
      scopeReference,
      companyId: spec.companyId,
    },
    selectedFields,
    purpose: normalizeReportExportPurpose(`${spec.reportTitle}导出`),
  });

  for (
    let poll = 0;
    view.status !== 'READY'
      && view.status !== 'FAILED'
      && poll < MAX_EXPORT_POLLS;
    poll += 1
  ) {
    if (gateway.loadReportExport === undefined) break;
    await delay(EXPORT_POLL_INTERVAL_MS);
    view = await gateway.loadReportExport(view.exportId);
  }
  if (view.status !== 'READY') {
    throw new Error('报表生成超时或失败，请重试。');
  }
  saveDownloadedFile(await gateway.downloadReportExport(view.exportId));
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

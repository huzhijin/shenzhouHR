import { ApiRequestError, requestFile, requestJson, saveDownloadedFile } from '../../shared/api/apiClient';
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
import { isWholeCalendarMonth } from './queryPeriod';
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

/** `AttendanceReportQueryService` rejects `size > 200`. Matrix first paint uses a smaller page. */
const PAGE_SIZE = 200;
const MATRIX_FIRST_PAGE_SIZE = 50;

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
  'overtime-daily': 'OVERTIME',
  'finance-overtime': 'OVERTIME',
  'daily-journal': 'ATTENDANCE_DETAIL',
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
  identityFilters: Pick<
    CustomerReportFilters,
    'organizationId' | 'employeeId' | 'fromDate' | 'toDate'
  > = {},
  expectedProjectionVersion?: string,
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
      fromDate: identityFilters.fromDate,
      toDate: identityFilters.toDate,
      expectedProjectionVersion:
        head?.metadata.projectionVersion ?? expectedProjectionVersion,
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
    throw new Error('realtime report snapshot returned no page');
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
  identityFilters: Pick<
    CustomerReportFilters,
    'organizationId' | 'employeeId' | 'fromDate' | 'toDate'
  > = {},
  expectedProjectionVersion?: string,
  onPartial?: (
    rows: AttendanceMonthMatrixProjection['rows'],
    head: AttendanceMonthMatrixProjection,
    truncated: boolean,
  ) => void,
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
  let truncated: boolean;
  const pageSize = identityFilters.employeeId
    ? PAGE_SIZE
    : MATRIX_FIRST_PAGE_SIZE;
  const wholeMonth = isWholeCalendarMonth(
    period,
    identityFilters.fromDate,
    identityFilters.toDate,
  );
  const fromDate = wholeMonth ? undefined : identityFilters.fromDate;
  const toDate = wholeMonth ? undefined : identityFilters.toDate;
  const first = await loadMatrix.call(wave7ProjectionGateway, {
    period,
    companyId,
    organizationId: identityFilters.organizationId,
    employeeId: identityFilters.employeeId,
    fromDate,
    toDate,
    expectedProjectionVersion,
    page: 0,
    size: pageSize,
  });
  const head = first;
  rows.push(...first.rows);
  let complete = rows.length >= first.employeeCount || first.rows.length === 0;
  truncated = !complete;
  onPartial?.(rows, head, truncated);
  if (!complete) {
    const stride = first.rows.length > 0 && first.rows.length < pageSize
      ? first.rows.length
      : pageSize;
    const extraPages = Math.min(
      MAX_PAGES - 1,
      Math.ceil((first.employeeCount - rows.length) / stride),
    );
    for (let index = 0; index < extraPages; index += 1) {
      const projection = await loadMatrix.call(wave7ProjectionGateway, {
        period,
        companyId,
        organizationId: identityFilters.organizationId,
        employeeId: identityFilters.employeeId,
        fromDate,
        toDate,
        expectedProjectionVersion: first.metadata.projectionVersion,
        page: index + 1,
        size: pageSize,
      });
      rows.push(...projection.rows);
      complete = rows.length >= first.employeeCount;
      truncated = !complete;
      onPartial?.(rows, head, truncated);
      if (complete || projection.rows.length === 0) {
        break;
      }
    }
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
  try {
    const directory = await requestJson<{
      employees: Array<{
        employeeId: string;
        employeeNumber: string;
        employeeName: string;
        organizationId: string;
        organizationName: string;
      }>;
    }>(`/api/v1/attendance-report-queries/directory?period=${encodeURIComponent(period)}&companyId=${encodeURIComponent(companyId)}`);
    return (directory.employees ?? []).map((row) => ({
      employeeId: row.employeeId,
      employeeNo: row.employeeNumber,
      employee: row.employeeName,
      organizationId: row.organizationId,
      department: row.organizationName,
    }));
  } catch (error: unknown) {
    throw error instanceof Error
      ? error
      : new Error('加载授权部门与员工失败，请重试。');
  }
}

async function loadFinanceQuerySheet(
  reportKey: 'daily-journal' | 'overtime-daily' | 'finance-overtime',
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope,
  base: CustomerReportDemo,
): Promise<CustomerReportDemo> {
  const parameters = new URLSearchParams();
  parameters.set('companyId', scope.reference);
  if (filters.fromDate && filters.toDate) {
    parameters.set('fromDate', filters.fromDate);
    parameters.set('toDate', filters.toDate);
  } else {
    parameters.set('period', filters.month);
  }
  if (filters.organizationId) {
    parameters.set('organizationId', filters.organizationId);
  }
  if (filters.employeeId) {
    parameters.set('employeeId', filters.employeeId);
  }
  parameters.set('size', '200');
  const collected: Array<Record<string, unknown>> = [];
  let page = 0;
  let total: number | undefined;
  let dataAsOf = '';
  while (page < MAX_PAGES) {
    parameters.set('page', String(page));
    const body = await requestJson<{
      rows?: Array<Record<string, unknown>>;
      rowCount?: number;
      dataAsOf?: string;
    }>(`/api/v1/attendance-report-queries/${reportKey}?${parameters.toString()}`);
    const batch = body.rows ?? [];
    collected.push(...batch);
    total = body.rowCount ?? collected.length;
    dataAsOf = body.dataAsOf ?? dataAsOf;
    if ((total != null && collected.length >= total) || batch.length < 200) {
      break;
    }
    page += 1;
  }
  const metadata = {
    ...base.metadata,
    generatedAt: dataAsOf,
    rowCount: collected.length,
  };
  if (reportKey === 'finance-overtime') {
    return {
      ...base,
      metadata,
      financeOvertimeRows: collected.map((row) => ({
        employeeNo: String(row.employeeNumber ?? ''),
        department: String(row.department ?? ''),
        employee: String(row.employeeName ?? ''),
        weekdayOvertimeHours: Number(row.weekdayOvertimeHours ?? 0),
        weekendOvertimeHours: Number(row.weekendOvertimeHours ?? 0),
        holidayOvertimeHours: Number(row.holidayOvertimeHours ?? 0),
        paidOvertimeHours: Number(row.paidOvertimeHours ?? 0),
        compensatoryOvertimeHours: Number(row.compensatoryOvertimeHours ?? 0),
        days: Array.isArray(row.days)
          ? (row.days as Array<Record<string, unknown>>).map((day) => ({
            date: String(day.date ?? '').slice(0, 10),
            hours: Number(day.hours ?? 0),
            dayType: day.dayType == null ? undefined : String(day.dayType),
            treatment: day.treatment == null ? undefined : String(day.treatment),
            paidHours: Number(day.paidHours ?? 0),
            compensatoryHours: Number(day.compensatoryHours ?? 0),
            voluntaryHours: Number(day.voluntaryHours ?? 0),
          }))
          : [],
      })),
    };
  }
  if (reportKey === 'daily-journal') {
    return {
      ...base,
      metadata,
      dailyJournalRows: collected.map((row, index) => ({
        sequence: Number(row.sequence ?? index + 1),
        employeeNo: String(row.employeeNumber ?? ''),
        department: String(row.department ?? ''),
        employee: String(row.employeeName ?? ''),
        businessDate: String(row.businessDate ?? ''),
        shiftLabel: String(row.shiftLabel ?? ''),
        onDuty: String(row.onDuty ?? ''),
        offDuty: String(row.offDuty ?? ''),
        lateHours: (row.lateHours as string | number) ?? '',
        earlyHours: (row.earlyHours as string | number) ?? '',
        absenceHours: (row.absenceHours as string | number) ?? '',
        leaveType: String(row.leaveType ?? ''),
        overtimeHours: (row.overtimeHours as string | number) ?? '',
        remark: String(row.remark ?? ''),
      })),
    };
  }
  return {
    ...base,
    metadata,
    overtimeDailyRows: collected.map((row) => ({
      employeeNo: String(row.employeeNumber ?? ''),
      department: String(row.department ?? ''),
      employee: String(row.employeeName ?? ''),
      businessDate: String(row.businessDate ?? ''),
      weekdayOvertimeHours: Number(row.weekdayOvertimeHours ?? 0),
      weekendOvertimeHours: Number(row.weekendOvertimeHours ?? 0),
      holidayOvertimeHours: Number(row.holidayOvertimeHours ?? 0),
      paidOvertimeHours: Number(row.paidOvertimeHours ?? 0),
      compensatoryOvertimeHours: Number(row.compensatoryOvertimeHours ?? 0),
    })),
  };
}

function emptyReport(
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope,
): CustomerReportDemo {
  return {
    metadata: {
      isDemo: false,
      company: scope.label,
      generatedAt: '',
      month: filters.month,
      monthLabel: formatMonth(filters.month),
      rowCount: 0,
      dataScope: scope,
    },
    attendanceRows: [],
    dailyJournalRows: [],
    overtimeDailyRows: [],
    financeOvertimeRows: [],
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
    case 'overtime-daily':
    case 'finance-overtime':
    case 'daily-journal':
      return {};
  }
}

function sheetRowCount(sheet: Partial<CustomerReportDemo>): number {
  return Object.values(sheet).reduce(
    (total, rows) => total + (Array.isArray(rows) ? rows.length : 0),
    0,
  );
}

export async function recalculateCustomerReport(
  companyId: string,
  period: string,
  window: 'LAST_3_DAYS' | 'LAST_7_DAYS' | 'MONTH' = 'MONTH',
): Promise<void> {
  const gateway = wave7ProjectionGateway;
  if (gateway.recalculateAttendanceReport === undefined) {
    throw new Error('重新计算接口尚未接入');
  }
  await gateway.recalculateAttendanceReport({ companyId, period, window });
}

export async function loadCustomerReport(
  reportKey: CustomerReportKey,
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope = defaultCustomerReportDataScope,
  expectedProjectionVersion?: string,
  onPartial?: (report: CustomerReportDemo) => void,
): Promise<CustomerReportDemo> {
  if (isDemoMode()) {
    return getCustomerReportDemo(filters, scope);
  }
  const base = emptyReport(filters, scope);
  if (reportKey === 'daily-journal' || reportKey === 'overtime-daily' || reportKey === 'finance-overtime') {
    return loadFinanceQuerySheet(reportKey, filters, scope, base);
  }
  if (reportKey === 'attendance-detail') {
    const { rows, truncated, head } = await collectMatrixPages(
      filters.month,
      scope.reference,
      filters,
      expectedProjectionVersion,
      onPartial === undefined
        ? undefined
        : (partialRows, partialHead, partialTruncated) => {
            const attendanceRows = toAttendanceDetailRows({
              ...partialHead,
              rows: partialRows,
            });
            onPartial({
              ...base,
              metadata: {
                ...base.metadata,
                generatedAt: partialHead.metadata.dataAsOf,
                sourceVersions: partialHead.metadata.sourceVersions,
                rowCount: partialTruncated
                  ? partialHead.employeeCount
                  : attendanceRows.length,
                periodState: partialHead.metadata.periodState,
                truncated: partialTruncated,
                allowedActions: partialHead.metadata.allowedActions,
                sourcesNewerThanPin: partialHead.metadata.sourcesNewerThanPin,
              },
              attendanceRows,
            });
          },
    );
    const attendanceRows = toAttendanceDetailRows({ ...head, rows });
    return {
      ...base,
      metadata: {
        ...base.metadata,
        generatedAt: head.metadata.dataAsOf,
        sourceVersions: head.metadata.sourceVersions,
        rowCount: attendanceRows.length,
        periodState: head.metadata.periodState,
        truncated,
        allowedActions: head.metadata.allowedActions,
        sourcesNewerThanPin: head.metadata.sourcesNewerThanPin,
      },
      attendanceRows,
    };
  }
  const { rows, truncated, head } = await collectReportPages(
    reportTypes[reportKey],
    filters.month,
    scope.reference,
    filters,
    expectedProjectionVersion,
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
      sourceVersions: head.metadata.sourceVersions,
      rowCount: sheetRowCount(sheet),
      periodState: head.metadata.periodState,
      truncated,
      allowedActions: head.metadata.allowedActions,
      sourcesNewerThanPin: head.metadata.sourcesNewerThanPin,
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
  fromDate?: string;
  toDate?: string;
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
  csvFallback: () => void | Promise<void>,
): Promise<'live' | 'screen'> {
  if (spec.reportKey === 'attendance-detail') {
    await csvFallback();
    return 'screen';
  }
  if (spec.reportKey === 'finance-overtime' && !isDemoMode()) {
    const parameters = new URLSearchParams();
    parameters.set('companyId', spec.companyId);
    if (spec.fromDate && spec.toDate) {
      parameters.set('fromDate', spec.fromDate);
      parameters.set('toDate', spec.toDate);
    } else {
      parameters.set('period', spec.period);
    }
    if (spec.organizationId) {
      parameters.set('organizationId', spec.organizationId);
    }
    if (spec.employeeId) {
      parameters.set('employeeId', spec.employeeId);
    }
    saveDownloadedFile(await requestFile(
      `/api/v1/attendance-report-queries/finance-overtime/export?${parameters.toString()}`,
    ));
    return 'live';
  }
  const gateway = wave7ProjectionGateway;
  if (
    isDemoMode()
    || gateway.createReportExport === undefined
    || gateway.downloadReportExport === undefined
  ) {
    await csvFallback();
    return 'screen';
  }

  try {
    const reportType = reportTypes[spec.reportKey];
    const projection = await gateway.loadReport({
      reportType,
      period: spec.period,
      companyId: spec.companyId,
      organizationId: spec.organizationId,
      employeeId: spec.employeeId,
      fromDate: spec.fromDate,
      toDate: spec.toDate,
      page: 0,
      size: 1,
    });
    const selectedFields = projection.exportFieldAllowlist.filter(
      (field) => !internalExportColumns.includes(field),
    );
    if (selectedFields.length === 0) {
      await csvFallback();
      return 'screen';
    }
    const scopeReference = projection.metadata.scope.reference;

    let view = await gateway.createReportExport({
      reportType,
      projectionVersion: projection.metadata.projectionVersion,
      queryFingerprint: projection.queryFingerprint,
      scopeReference,
      filters: {
        period: projection.filters.period,
        scopeReference,
        companyId: spec.companyId,
        organizationId: projection.filters.organizationId ?? spec.organizationId ?? null,
        employeeId: projection.filters.employeeId ?? spec.employeeId ?? null,
        status: projection.filters.status ?? null,
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
      await csvFallback();
      return 'screen';
    }
    saveDownloadedFile(await gateway.downloadReportExport(view.exportId));
    return 'live';
  } catch (error: unknown) {
    try {
      await csvFallback();
      return 'screen';
    } catch (fallbackError: unknown) {
      throw fallbackError instanceof Error ? fallbackError : error;
    }
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms); });
}

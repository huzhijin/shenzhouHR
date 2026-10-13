import dayjs from 'dayjs';
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

/** nginx limits /api/ to 12 concurrent connections per IP; stay well under that. */
const PARALLEL_PAGE_LIMIT = 4;

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
  // Fetch page 0 first to learn rowCount, then fire remaining pages in parallel.
  const first = await wave7ProjectionGateway.loadReport({
    reportType,
    period,
    companyId,
    organizationId: identityFilters.organizationId,
    employeeId: identityFilters.employeeId,
    fromDate: identityFilters.fromDate,
    toDate: identityFilters.toDate,
    expectedProjectionVersion,
    page: 0,
    size: PAGE_SIZE,
  });
  const rows: ReportProjection['rows'] = [...first.rows];
  const head = first;
  if (rows.length < first.rowCount && first.rows.length > 0) {
    const extraPages = Math.min(
      MAX_PAGES - 1,
      Math.ceil((first.rowCount - rows.length) / PAGE_SIZE),
    );
    const pageNumbers = Array.from({ length: extraPages }, (_, i) => i + 1);
    for (let i = 0; i < pageNumbers.length; i += PARALLEL_PAGE_LIMIT) {
      const chunk = pageNumbers.slice(i, i + PARALLEL_PAGE_LIMIT);
      const chunkResults = await Promise.all(
        chunk.map((p) =>
          wave7ProjectionGateway.loadReport({
            reportType,
            period,
            companyId,
            organizationId: identityFilters.organizationId,
            employeeId: identityFilters.employeeId,
            fromDate: identityFilters.fromDate,
            toDate: identityFilters.toDate,
            expectedProjectionVersion: first.metadata.projectionVersion,
            page: p,
            size: PAGE_SIZE,
          }),
        ),
      );
      for (const projection of chunkResults) {
        rows.push(...projection.rows);
      }
    }
  }
  const truncated = rows.length < head.rowCount;
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
    const pageNumbers = Array.from({ length: extraPages }, (_, i) => i + 1);
    for (let i = 0; i < pageNumbers.length; i += PARALLEL_PAGE_LIMIT) {
      const chunk = pageNumbers.slice(i, i + PARALLEL_PAGE_LIMIT);
      const chunkResults = await Promise.all(
        chunk.map((index) =>
          loadMatrix.call(wave7ProjectionGateway, {
            period,
            companyId,
            organizationId: identityFilters.organizationId,
            employeeId: identityFilters.employeeId,
            fromDate,
            toDate,
            expectedProjectionVersion: first.metadata.projectionVersion,
            page: index,
            size: pageSize,
          }),
        ),
      );
      let done = false;
      for (const projection of chunkResults) {
        rows.push(...projection.rows);
        complete = rows.length >= first.employeeCount;
        truncated = !complete;
        onPartial?.(rows, head, truncated);
        if (complete || projection.rows.length === 0) {
          done = true;
          break;
        }
      }
      if (done) break;
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
  expectedProjectionVersion?: string,
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
  parameters.set('size', String(PAGE_SIZE));
  const fromDate = filters.fromDate && filters.toDate
    ? filters.fromDate : `${filters.month}-01`;
  const toDate = filters.fromDate && filters.toDate
    ? filters.toDate : dayjs(fromDate).endOf('month').format('YYYY-MM-DD');
  const collected: Array<Record<string, unknown>> = [];
  // These row-oriented queries can exceed 10,000 employee-days in one month.
  // The limit is an explicit failure, never a successful partial report.
  const maxRows = 100_000;
  let page = 0;
  let total: number;
  let snapshot: string | undefined;
  let dataAsOf: string;
  let periodState: CustomerReportDemo['metadata']['periodState'];
  do {
    parameters.set('page', String(page));
    const body = await requestJson<{
      sheet: string; companyId: string; fromDate: string; toDate: string;
      page: number; size: number; rows: Array<Record<string, unknown>>; rowCount: number;
      projectionVersion?: string | null; dataAsOf?: string | null;
      periodState?: CustomerReportDemo['metadata']['periodState'] | null;
    }>(`/api/v1/attendance-report-queries/${reportKey}?${parameters.toString()}`);
    // The server's no-published-month response uses its default size (50).
    const noPublishedMonth = page === 0 && body.rowCount === 0
      && Array.isArray(body.rows) && body.rows.length === 0 && !body.projectionVersion;
    if (body.sheet !== reportKey || body.companyId !== scope.reference
      || body.fromDate !== fromDate || body.toDate !== toDate
      || body.page !== page || (!noPublishedMonth && body.size !== PAGE_SIZE)
      || !Number.isSafeInteger(body.rowCount) || body.rowCount < 0
      || !Array.isArray(body.rows)) {
      throw new Error('报表分页或查询范围不一致，请重新查询。');
    }
    if (body.rowCount > maxRows) {
      throw new Error('报表超过 100000 条，请缩小日期或部门范围后查询；未展示不完整结果。');
    }
    if ((body.rowCount > 0 && (!body.projectionVersion || !body.dataAsOf))
      || (expectedProjectionVersion && body.projectionVersion !== expectedProjectionVersion)) {
      throw new Error('报表核算版本已变化或缺失，请重新查询。');
    }
    const currentSnapshot = JSON.stringify([
      body.projectionVersion, body.dataAsOf, body.periodState, body.rowCount,
    ]);
    if (snapshot !== undefined && snapshot !== currentSnapshot) {
      throw new Error('加载期间报表核算版本或总数已变化，请重新查询。');
    }
    snapshot = currentSnapshot;
    total = body.rowCount;
    if (body.rows.length !== Math.min(PAGE_SIZE, total - collected.length)) {
      throw new Error('报表分页记录不完整，请重新查询。');
    }
    collected.push(...body.rows);
    dataAsOf = body.dataAsOf ?? '';
    periodState = body.periodState ?? undefined;
    page += 1;
  } while (collected.length < total);
  const metadata = {
    ...base.metadata,
    generatedAt: dataAsOf,
    periodState,
    rowCount: total,
    truncated: false,
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
        voluntaryOvertimeHours: Number(row.voluntaryOvertimeHours ?? 0),
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
      voluntaryOvertimeHours: Number(row.voluntaryOvertimeHours ?? 0),
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

/** The latest published projection for one company and month, or null before the first publish. */
export interface PublishedReportPin {
  projectionVersion: string;
  dataAsOf: string;
}

const PIN_READ_TIMEOUT_MS = 20_000;

/**
 * Reads the published pin for a company and month. The makeup sheet is the cheapest query
 * sheet that reports the same company/month pin as every other sheet, so one row is enough.
 * The response must cover exactly the requested company and whole month with no omitted
 * month, otherwise it is rejected rather than read as this month's pin. The read is bounded
 * by its own timeout and by the caller's signal.
 */
export async function loadPublishedReportPin(
  companyId: string,
  period: string,
  signal?: AbortSignal,
): Promise<PublishedReportPin | null> {
  const fromDate = dayjs(`${period}-01`).format('YYYY-MM-DD');
  const toDate = dayjs(`${period}-01`).endOf('month').format('YYYY-MM-DD');
  const parameters = new URLSearchParams({ companyId, period, page: '0', size: '1' });
  const body = await requestJson<{
    sheet?: string;
    companyId?: string;
    fromDate?: string;
    toDate?: string;
    omittedMonths?: unknown[] | null;
    projectionVersion?: string | null;
    dataAsOf?: string | null;
  }>(`/api/v1/attendance-report-queries/makeup?${parameters.toString()}`, {
    signal: boundedSignal(signal, PIN_READ_TIMEOUT_MS),
  });
  if (
    body.sheet !== 'makeup'
    || body.companyId !== companyId
    || body.fromDate !== fromDate
    || body.toDate !== toDate
    || (Array.isArray(body.omittedMonths) && body.omittedMonths.length > 0)
  ) {
    throw new Error('报表版本读取结果与所选公司或月份不一致。');
  }
  if (!body.projectionVersion || !body.dataAsOf) {
    return null;
  }
  return { projectionVersion: body.projectionVersion, dataAsOf: body.dataAsOf };
}

/**
 * Whether a recalculation submit ended without a definite answer: the connection dropped,
 * the client stopped waiting, or a gateway gave up. The server may still have accepted it.
 * A permission, validation or business rejection, and an explicit server error, are definite.
 */
export function isRecalculationOutcomeUnknown(error: unknown): boolean {
  if (!(error instanceof ApiRequestError)) {
    return false;
  }
  return error.status === 0
    || error.status === 408
    || error.status === 502
    || error.status === 503
    || error.status === 504
    || error.code === 'REQUEST_TIMEOUT'
    || error.code === 'NETWORK_REQUEST_FAILED'
    || error.code === 'ATTENDANCE_REPORT_QUERY_TIMEOUT';
}

/**
 * - `completed`: the submit itself returned a published projection, so it belongs to this run.
 * - `updated`: the submit's outcome is unknown, and the company/month later showed a version
 *   other than the one read before submitting. That proves the data changed, not that this run
 *   produced it; another recalculation or an automatic refresh could have published it.
 * - `unconfirmed`: the submit's outcome is unknown and no new version appeared in time. That
 *   says nothing about whether the server is still running or failed.
 */
/**
 * After an unknown submit, the user's manual refresh is a read-only check. Only a check that
 * actually read the pin decides anything: a version different from the one before the submit
 * proves the month changed (still not that this request produced it), and the same version means
 * nothing was published, so a new submit is the user's informed choice. A failed or cancelled
 * read leaves the outcome unknown and the recalculate actions locked.
 */
export type UnknownSubmitCheck =
  | { unlock: true; message: string }
  | { unlock: false; message: string };

export function checkAfterUnknownSubmit(
  beforeVersion: string | null,
  read: { ok: true; pin: PublishedReportPin | null } | { ok: false },
): UnknownSubmitCheck {
  if (!read.ok) {
    return {
      unlock: false,
      message: '核对未完成：读取报表版本失败，重新计算结果仍未知。请稍后再次点击“刷新数据”，确认前请勿重复提交。',
    };
  }
  const version = read.pin?.projectionVersion ?? null;
  if (version !== null && version !== beforeVersion) {
    return {
      unlock: true,
      message: '核对完成：该公司本月报表已有新版本。由于未收到提交的明确答复，无法确认它就是这次重新计算的结果。',
    };
  }
  // Still the old version only means no publish has been seen yet. It does not prove the earlier
  // submit has finished or failed, so the message makes no claim that resubmitting is safe.
  return {
    unlock: true,
    message: '核对完成：目前仍是提交前的报表版本，尚未看到新结果发布。这不能说明上次提交已结束；'
      + '如需再次提交，请先确认没有正在进行的重新计算。',
  };
}

export type RecalculationOutcome =
  | { kind: 'completed'; projectionVersion: string }
  | { kind: 'updated'; projectionVersion: string }
  | { kind: 'unconfirmed'; beforeVersion: string | null };

const PUBLISH_POLL_INTERVAL_MS = 15_000;
const PUBLISH_POLL_LIMIT = 40;

/**
 * Submits one recalculation, sent exactly once. A definite failure rejects with the server's
 * error. When the outcome is unknown, only read-only pin checks follow, never a second submit.
 * Aborting the signal stops everything and rejects with an AbortError, so a caller that moved
 * on to another company or month, or unmounted, never receives this run's outcome.
 */
export async function recalculateCustomerReportAndWait(
  companyId: string,
  period: string,
  window: 'LAST_3_DAYS' | 'LAST_7_DAYS' | 'MONTH',
  options: {
    signal: AbortSignal;
    onWaiting?: (beforeVersion: string | null) => void;
    pollIntervalMs?: number;
    pollLimit?: number;
    sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  },
): Promise<RecalculationOutcome> {
  const { signal } = options;
  const sleep = options.sleep ?? abortableSleep;
  const gateway = wave7ProjectionGateway;
  if (gateway.recalculateAttendanceReport === undefined) {
    throw new Error('重新计算接口尚未接入');
  }
  const before = await loadPublishedReportPin(companyId, period, signal);
  throwIfAborted(signal);
  try {
    const result = await gateway.recalculateAttendanceReport({ companyId, period, window });
    throwIfAborted(signal);
    return { kind: 'completed', projectionVersion: result.projectionVersion };
  } catch (error: unknown) {
    throwIfAborted(signal);
    if (!isRecalculationOutcomeUnknown(error)) {
      throw error;
    }
  }
  options.onWaiting?.(before?.projectionVersion ?? null);
  const limit = options.pollLimit ?? PUBLISH_POLL_LIMIT;
  for (let attempt = 0; attempt < limit; attempt += 1) {
    await sleep(options.pollIntervalMs ?? PUBLISH_POLL_INTERVAL_MS, signal);
    throwIfAborted(signal);
    let current: PublishedReportPin | null;
    try {
      current = await loadPublishedReportPin(companyId, period, signal);
    } catch (error: unknown) {
      throwIfAborted(signal);
      // The submit's outcome is already unknown. A failed read-only check says nothing about
      // the recalculation, so it never turns into a definite failure; keep checking instead.
      void error;
      continue;
    }
    throwIfAborted(signal);
    if (current !== null && current.projectionVersion !== before?.projectionVersion) {
      return { kind: 'updated', projectionVersion: current.projectionVersion };
    }
  }
  return { kind: 'unconfirmed', beforeVersion: before?.projectionVersion ?? null };
}

function throwIfAborted(signal: AbortSignal): void {
  if (signal.aborted) {
    throw new DOMException('recalculation tracking cancelled', 'AbortError');
  }
}

/** Aborts when the caller's signal aborts or after the timeout, whichever comes first. */
function boundedSignal(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const forward = () => controller.abort();
  if (signal?.aborted) {
    controller.abort();
  } else {
    signal?.addEventListener('abort', forward, { once: true });
  }
  controller.signal.addEventListener('abort', () => {
    clearTimeout(timer);
    signal?.removeEventListener('abort', forward);
  }, { once: true });
  return controller.signal;
}

function abortableSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('recalculation tracking cancelled', 'AbortError'));
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('recalculation tracking cancelled', 'AbortError'));
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
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
    return loadFinanceQuerySheet(reportKey, filters, scope, base, expectedProjectionVersion);
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

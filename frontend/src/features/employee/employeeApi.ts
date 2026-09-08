import {
  requestJson,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  adjustDemoPriorService,
  createDemoEmployee,
  createDemoEmploymentPeriod,
  getDemoEmployeeDetail,
  getDemoEmployeePage,
  getDemoEmployeeVersions,
  getDemoEmploymentPeriods,
  getDemoPriorServiceRecords,
  recalculateDemoPriorService,
  updateDemoEmployee,
  updateDemoEmploymentPeriod,
  getDemoPunchExemption,
  setDemoPunchExemption,
} from './demoEmployees';

export type EmployeeStatus = 'ACTIVE' | 'INACTIVE' | 'TERMINATED';
export type PeopleSourceAuthority = 'INITIAL_EXCEL' | 'LOCAL';

export interface EmployeeSummary {
  employeeId: string;
  employeeVersionId: string;
  employeeNumber: string;
  displayName: string;
  employmentStatus: EmployeeStatus;
  organizationId: string | null;
  organizationName: string | null;
  organizationCode: string | null;
  seeyonOaCode: string | null;
  bindingStatus: string | null;
  assignmentEffectiveFrom: string | null;
  assignmentEffectiveTo: string | null;
  sourceAuthority: PeopleSourceAuthority;
  rowVersion: number;
}

export interface EmployeePage {
  items: EmployeeSummary[];
  total: number;
  page: number;
  size: number;
}

export interface EmployeeVersionSummary {
  employeeVersionId: string;
  employeeId: string;
  employeeNumber: string;
  displayName: string;
  status: EmployeeStatus;
  externalEmployeeId?: string | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  sourceAuthority: PeopleSourceAuthority;
  sourceBatchId?: string | null;
  rowVersion: number;
  changeReason?: string | null;
  createdBy: string;
  createdAt: string;
}

export interface EmploymentPeriodView {
  employmentPeriodId: string;
  employeeId: string;
  organizationId: string;
  positionId?: string | null;
  startDate: string;
  terminationDate?: string | null;
  endExclusive?: string | null;
  sourceBatchId?: string | null;
  rowVersion: number;
  changeReason?: string | null;
  createdBy: string;
  createdAt: string;
}

export interface PriorServiceReplayView {
  employeeId: string;
  totalDays: number;
  recordCount: number;
  replayDigest: string;
  recalculatedAt: string;
}

export interface EmployeeDetail extends EmployeeVersionSummary {
  employmentPeriods: EmploymentPeriodView[];
  priorService: PriorServiceReplayView;
  auditResourceId: string;
}

export interface EmployeeVersionPage {
  items: EmployeeVersionSummary[];
  total: number;
  page: number;
  size: number;
}

export interface EmploymentPeriodPage {
  items: EmploymentPeriodView[];
  total: number;
  page: number;
  size: number;
}

export interface PriorServiceRecordView {
  priorServiceRecordId: string;
  employeeId: string;
  recordType: 'OPENING_IMPORT' | 'ADJUSTMENT' | 'REVERSAL';
  amountDays: number;
  reason: string;
  businessDate: string;
  sourceBatchId?: string | null;
  reversalOfRecordId?: string | null;
  resultingTotalDays: number;
  actorId: string;
  occurredAt: string;
  requestId: string;
}

export interface PriorServiceRecordPage {
  items: PriorServiceRecordView[];
  totalDays: number;
  replayDigest: string;
  total: number;
  page: number;
  size: number;
}

export interface EmployeeCreateRequest {
  companyId: string;
  employeeNumber: string;
  displayName: string;
  externalEmployeeId?: string | null;
  effectiveFrom: string;
  reason: string;
}

export interface EmployeeUpdateRequest {
  employeeNumber: string;
  displayName: string;
  status: EmployeeStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface EmploymentPeriodCreateRequest {
  organizationId: string;
  positionId?: string | null;
  startDate: string;
  terminationDate?: string | null;
  reason: string;
}

export interface EmploymentPeriodUpdateRequest extends EmploymentPeriodCreateRequest {
  terminationDate: string | null;
}

export interface PriorServiceAdjustmentRequest {
  amountDays: number;
  businessDate: string;
  reason: string;
}

export interface EmployeeFilters {
  query?: string;
  organizationId?: string;
  includeDescendants?: boolean;
  companyId?: string;
  status?: EmployeeStatus;
  sort?: 'employeeNumber' | 'displayName' | 'employmentStatus' | 'organizationName' | 'updatedAt';
}

const basePath = '/api/v1/employees';

export function getEmployees(
  page: number,
  size: number,
  filters: EmployeeFilters = {},
): Promise<EmployeePage> {
  if (isDemoMode()) return Promise.resolve(getDemoEmployeePage(page, size, filters));
  const parameters = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.query) parameters.set('query', filters.query);
  if (filters.organizationId) parameters.set('organizationId', filters.organizationId);
  if (filters.includeDescendants) parameters.set('includeDescendants', 'true');
  if (filters.companyId) parameters.set('companyId', filters.companyId);
  if (filters.status) parameters.set('status', filters.status);
  if (filters.sort) parameters.set('sort', filters.sort);
  return requestJson<EmployeePage>(`${basePath}?${parameters}`);
}

export function getEmployee(employeeId: string): Promise<EmployeeDetail> {
  if (isDemoMode()) return Promise.resolve(getDemoEmployeeDetail(employeeId));
  return requestJson<EmployeeDetail>(`${basePath}/${encodeURIComponent(employeeId)}`);
}

export function listEmployeeVersions(employeeId: string): Promise<EmployeeVersionPage> {
  if (isDemoMode()) return Promise.resolve(getDemoEmployeeVersions(employeeId));
  return requestJson<EmployeeVersionPage>(
    `${basePath}/${encodeURIComponent(employeeId)}/versions?page=0&size=20`,
  );
}

export function createLocalEmployee(
  request: EmployeeCreateRequest,
  idempotencyKey: string,
): Promise<EmployeeDetail> {
  if (isDemoMode()) return Promise.resolve(createDemoEmployee(request));
  return requestJson<EmployeeDetail>(basePath, {
    method: 'POST',
    headers: versionHeaders(0, idempotencyKey),
    body: JSON.stringify(request),
  });
}

export interface PunchExemptionStatus {
  standingExempt: boolean;
  executiveExempt: boolean;
}

export function getPunchExemption(employeeId: string): Promise<PunchExemptionStatus> {
  if (isDemoMode()) return Promise.resolve(getDemoPunchExemption(employeeId));
  return requestJson<PunchExemptionStatus>(
    `${basePath}/${encodeURIComponent(employeeId)}/punch-exemption`,
  );
}

export function setPunchExemption(
  employeeId: string,
  standingExempt: boolean,
): Promise<PunchExemptionStatus> {
  if (isDemoMode()) return Promise.resolve(setDemoPunchExemption(employeeId, standingExempt));
  return requestJson<PunchExemptionStatus>(
    `${basePath}/${encodeURIComponent(employeeId)}/punch-exemption`,
    {
      method: 'PUT',
      body: JSON.stringify({ standingExempt }),
    },
  );
}

export function updateLocalEmployee(
  employeeId: string,
  request: EmployeeUpdateRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<EmployeeDetail> {
  if (isDemoMode()) return Promise.resolve(updateDemoEmployee(employeeId, request));
  return requestJson<EmployeeDetail>(`${basePath}/${encodeURIComponent(employeeId)}`, {
    method: 'PATCH',
    headers: versionHeaders(rowVersion, idempotencyKey),
    body: JSON.stringify(request),
  });
}

export function listEmploymentPeriods(employeeId: string): Promise<EmploymentPeriodPage> {
  if (isDemoMode()) return Promise.resolve(getDemoEmploymentPeriods(employeeId));
  return requestJson<EmploymentPeriodPage>(
    `${basePath}/${encodeURIComponent(employeeId)}/employment-periods?page=0&size=100`,
  );
}

export function createEmploymentPeriod(
  employeeId: string,
  request: EmploymentPeriodCreateRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<EmploymentPeriodView> {
  if (isDemoMode()) return Promise.resolve(createDemoEmploymentPeriod(employeeId, request));
  return requestJson<EmploymentPeriodView>(
    `${basePath}/${encodeURIComponent(employeeId)}/employment-periods`,
    {
      method: 'POST',
      headers: versionHeaders(rowVersion, idempotencyKey),
      body: JSON.stringify(request),
    },
  );
}

export function updateEmploymentPeriod(
  employeeId: string,
  employmentPeriodId: string,
  request: EmploymentPeriodUpdateRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<EmploymentPeriodView> {
  if (isDemoMode()) {
    return Promise.resolve(updateDemoEmploymentPeriod(employeeId, employmentPeriodId, request));
  }
  return requestJson<EmploymentPeriodView>(
    `${basePath}/${encodeURIComponent(employeeId)}/employment-periods/${encodeURIComponent(employmentPeriodId)}`,
    {
      method: 'PATCH',
      headers: versionHeaders(rowVersion, idempotencyKey),
      body: JSON.stringify(request),
    },
  );
}

export function listPriorServiceRecords(employeeId: string): Promise<PriorServiceRecordPage> {
  if (isDemoMode()) return Promise.resolve(getDemoPriorServiceRecords(employeeId));
  return requestJson<PriorServiceRecordPage>(
    `${basePath}/${encodeURIComponent(employeeId)}/prior-service-records?page=0&size=100`,
  );
}

export function createPriorServiceAdjustment(
  employeeId: string,
  request: PriorServiceAdjustmentRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<PriorServiceRecordView> {
  if (isDemoMode()) return Promise.resolve(adjustDemoPriorService(employeeId, request));
  return requestJson<PriorServiceRecordView>(
    `${basePath}/${encodeURIComponent(employeeId)}/prior-service-adjustments`,
    {
      method: 'POST',
      headers: versionHeaders(rowVersion, idempotencyKey),
      body: JSON.stringify(request),
    },
  );
}

export function recalculatePriorService(
  employeeId: string,
  rowVersion: number,
  reason: string,
  idempotencyKey: string,
): Promise<PriorServiceReplayView> {
  if (isDemoMode()) return Promise.resolve(recalculateDemoPriorService(employeeId));
  return requestJson<PriorServiceReplayView>(
    `${basePath}/${encodeURIComponent(employeeId)}/prior-service/recalculate`,
    {
      method: 'POST',
      headers: versionHeaders(rowVersion, idempotencyKey),
      body: JSON.stringify({ reason }),
    },
  );
}

// ── 年假管理 API ───────────────────────────────────────────────────────────────

export interface AnnualLeaveLedgerEntry {
  entryId: string;
  entryType: string;
  entryTypeLabel: string;
  amountHours: number;
  sourceType: string;
  businessDate: string;
  effectiveFrom: string;
  expiresOn: string | null;
  occurredAt: string;
}

export interface AnnualLeaveAccount {
  accountId: string | null;
  employeeId: string;
  year: number;
  balanceHours: number;
  equivalentDays: number;
  rowVersion: number;
  entries: AnnualLeaveLedgerEntry[];
  totalEntries: number;
}

export type LeaveAccountKind = 'ANNUAL_LEAVE' | 'TIME_OFF';

function leaveAccountBasePath(employeeId: string, kind: LeaveAccountKind): string {
  const suffix = kind === 'TIME_OFF' ? 'time-off' : 'annual-leave';
  return `${basePath}/${encodeURIComponent(employeeId)}/${suffix}`;
}

function demoLeaveAccount(
  employeeId: string,
  year: number,
  kind: LeaveAccountKind,
): AnnualLeaveAccount {
  const hours = kind === 'TIME_OFF' ? 16 : 40;
  return {
    accountId: `demo-account-${kind}-${employeeId}-${year}`,
    employeeId,
    year,
    balanceHours: hours,
    equivalentDays: hours / 8,
    rowVersion: 0,
    entries: [
      {
        entryId: `demo-entry-${kind}`,
        entryType: 'OPENING',
        entryTypeLabel: '期初录入',
        amountHours: hours,
        sourceType: 'HR_OPENING_IMPORT',
        businessDate: `${year}-08-01`,
        effectiveFrom: `${year}-08-01`,
        expiresOn: `${year}-12-31`,
        occurredAt: `${year}-08-01T08:00:00Z`,
      },
    ],
    totalEntries: 1,
  };
}

export function getAnnualLeaveAccount(
  employeeId: string,
  year: number,
): Promise<AnnualLeaveAccount> {
  return getLeaveAccount(employeeId, year, 'ANNUAL_LEAVE');
}

export function getTimeOffAccount(
  employeeId: string,
  year: number,
): Promise<AnnualLeaveAccount> {
  return getLeaveAccount(employeeId, year, 'TIME_OFF');
}

export function getLeaveAccount(
  employeeId: string,
  year: number,
  kind: LeaveAccountKind,
): Promise<AnnualLeaveAccount> {
  if (isDemoMode()) {
    return Promise.resolve(demoLeaveAccount(employeeId, year, kind));
  }
  return requestJson<AnnualLeaveAccount>(
    `${leaveAccountBasePath(employeeId, kind)}?year=${year}&page=0&size=20`,
  );
}

export function setAnnualLeaveOpeningBalance(
  employeeId: string,
  balanceHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
): Promise<AnnualLeaveAccount> {
  return setLeaveOpeningBalance(employeeId, balanceHours, year, reason, idempotencyKey, 'ANNUAL_LEAVE');
}

export function setTimeOffOpeningBalance(
  employeeId: string,
  balanceHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
): Promise<AnnualLeaveAccount> {
  return setLeaveOpeningBalance(employeeId, balanceHours, year, reason, idempotencyKey, 'TIME_OFF');
}

export function setLeaveOpeningBalance(
  employeeId: string,
  balanceHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
  kind: LeaveAccountKind,
): Promise<AnnualLeaveAccount> {
  if (isDemoMode()) {
    return getLeaveAccount(employeeId, year, kind);
  }
  return requestJson<AnnualLeaveAccount>(
    `${leaveAccountBasePath(employeeId, kind)}/opening`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ balanceHours, year, reason }),
    },
  );
}

export function adjustAnnualLeaveBalance(
  employeeId: string,
  adjustmentHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
): Promise<AnnualLeaveAccount> {
  return adjustLeaveBalance(employeeId, adjustmentHours, year, reason, idempotencyKey, 'ANNUAL_LEAVE');
}

export function adjustTimeOffBalance(
  employeeId: string,
  adjustmentHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
): Promise<AnnualLeaveAccount> {
  return adjustLeaveBalance(employeeId, adjustmentHours, year, reason, idempotencyKey, 'TIME_OFF');
}

export function adjustLeaveBalance(
  employeeId: string,
  adjustmentHours: number,
  year: number,
  reason: string,
  idempotencyKey: string,
  kind: LeaveAccountKind,
): Promise<AnnualLeaveAccount> {
  if (isDemoMode()) {
    return getLeaveAccount(employeeId, year, kind);
  }
  return requestJson<AnnualLeaveAccount>(
    `${leaveAccountBasePath(employeeId, kind)}/adjust`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ adjustmentHours, year, reason }),
    },
  );
}

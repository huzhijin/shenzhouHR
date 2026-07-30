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

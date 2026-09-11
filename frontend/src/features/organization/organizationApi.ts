import {
  requestJson,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  createDemoOrganization,
  getDemoOrganizationDetail,
  getDemoOrganizationTree,
  getDemoOrganizationVersions,
  updateDemoOrganization,
} from './demoOrganization';

export interface OrganizationNode {
  organizationId: string;
  organizationVersionId?: string;
  code: string;
  name: string;
  organizationType: 'COMPANY' | 'DEPARTMENT' | 'TEAM';
  status: OrganizationStatus;
  sourceOrganizationId: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  sourceAuthority?: 'INITIAL_EXCEL' | 'LOCAL';
  rowVersion?: number;
  children: OrganizationNode[];
}

export type OrganizationStatus = 'ACTIVE' | 'INACTIVE';

export interface OrganizationVersionSummary {
  organizationVersionId: string;
  organizationId: string;
  parentOrganizationId?: string | null;
  code: string;
  name: string;
  organizationType: OrganizationNode['organizationType'];
  status: OrganizationStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  sourceAuthority: 'INITIAL_EXCEL' | 'LOCAL';
  sourceBatchId?: string | null;
  rowVersion: number;
  changeReason?: string | null;
  createdBy: string;
  createdAt: string;
}

export interface OrganizationDetail extends OrganizationVersionSummary {
  childCount: number;
  auditResourceId: string;
}

export interface OrganizationVersionPage {
  items: OrganizationVersionSummary[];
  total: number;
  page: number;
  size: number;
}

export interface OrganizationCreateRequest {
  companyId: string;
  parentOrganizationId?: string | null;
  code: string;
  name: string;
  organizationType: OrganizationNode['organizationType'];
  effectiveFrom: string;
  reason: string;
}

export interface OrganizationUpdateRequest {
  parentOrganizationId?: string | null;
  code: string;
  name: string;
  organizationType: OrganizationNode['organizationType'];
  status: OrganizationStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

const basePath = '/api/v1/organization-units';

export function getCurrentOrganizationTree(includeInactive = true): Promise<OrganizationNode[]> {
  if (isDemoMode()) {
    return Promise.resolve(getDemoOrganizationTree());
  }
  return requestJson<OrganizationNode[]>(`${basePath}?includeInactive=${includeInactive}`);
}

export function getOrganization(organizationId: string): Promise<OrganizationDetail> {
  if (isDemoMode()) return Promise.resolve(getDemoOrganizationDetail(organizationId));
  return requestJson<OrganizationDetail>(`${basePath}/${encodeURIComponent(organizationId)}`);
}

export function listOrganizationVersions(organizationId: string): Promise<OrganizationVersionPage> {
  if (isDemoMode()) return Promise.resolve(getDemoOrganizationVersions(organizationId));
  return requestJson<OrganizationVersionPage>(
    `${basePath}/${encodeURIComponent(organizationId)}/versions?page=0&size=20`,
  );
}

export function createLocalOrganization(
  request: OrganizationCreateRequest,
  idempotencyKey: string,
): Promise<OrganizationDetail> {
  if (isDemoMode()) return Promise.resolve(createDemoOrganization(request));
  return requestJson<OrganizationDetail>(basePath, {
    method: 'POST',
    headers: versionHeaders(0, idempotencyKey),
    body: JSON.stringify(request),
  });
}

export function updateLocalOrganization(
  organizationId: string,
  request: OrganizationUpdateRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<OrganizationDetail> {
  if (isDemoMode()) return Promise.resolve(updateDemoOrganization(organizationId, request));
  return requestJson<OrganizationDetail>(`${basePath}/${encodeURIComponent(organizationId)}`, {
    method: 'PATCH',
    headers: versionHeaders(rowVersion, idempotencyKey),
    body: JSON.stringify(request),
  });
}

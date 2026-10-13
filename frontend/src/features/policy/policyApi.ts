import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';

export type PolicyVersionStatus = 'DRAFT' | 'PUBLISHED' | 'INACTIVE' | 'ROLLED_BACK';
export type ScopeType = 'COMPANY' | 'LOCATION' | 'ATTENDANCE_GROUP' | 'POLICY_GROUP';

export interface PolicyTemplateSummary {
  templateId: string;
  code: string;
  name: string;
  status: string;
  latestVersionNumber: number;
  rowVersion: number;
}

export interface PolicyTemplateDetail extends PolicyTemplateSummary {
  description?: string;
  fieldDefinitions?: Array<{
    key: string;
    label: string;
    valueType: string;
    required: boolean;
    enumValues?: string[];
  }>;
}

export interface PolicyVersionSummary {
  versionId: string;
  templateId: string;
  versionNumber: number;
  status: PolicyVersionStatus;
  effectiveFrom: string;
  effectiveTo?: string | null;
  changeReason: string;
  createdBy?: string;
  createdAt?: string;
  publishedAt?: string | null;
  rowVersion: number;
}

export interface ScopeBinding {
  bindingId?: string;
  scopeType: ScopeType;
  scopeResourceId: string;
  priority: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

export interface PolicyVersionDetail extends PolicyVersionSummary {
  parameters?: Array<{ key: string; value: unknown }>;
  scopeBindings?: ScopeBinding[];
  snapshot?: Record<string, unknown>;
}

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface ValidationIssue {
  severity: 'ERROR' | 'WARNING';
  field?: string;
  code: string;
  message: string;
}

export interface PolicyValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
  validatedAt?: string;
}

export interface PolicyConflictResult {
  hasConflicts: boolean;
  conflicts: Array<{
    code: string;
    conflictingVersionId: string;
    scopeType: string;
    scopeResourceId: string;
    priority: number;
    effectiveFrom: string;
    effectiveTo?: string | null;
  }>;
}

export interface PolicyImpactPreview {
  scopeCount: number;
  affectedObjectCount: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  frozenPeriodProtected: boolean;
  warnings: string[];
}

export interface PolicySimulationResult {
  matched: boolean;
  resolvedParameters: Array<{ key: string; value: unknown }>;
  explanation: string[];
}

const demoTemplate: PolicyTemplateDetail = {
  templateId: '9500000000000000001',
  code: 'GENERIC_CONTROLLED_POLICY',
  name: '通用受控规则模板',
  description: '用于维护通用业务判定方式、阈值和适用范围。',
  status: 'ACTIVE',
  latestVersionNumber: 2,
  rowVersion: 3,
  fieldDefinitions: [
    { key: 'decisionMode', label: '判定模式', valueType: 'ENUM', required: true, enumValues: ['STRICT', 'BALANCED'] },
    { key: 'threshold', label: '阈值', valueType: 'INTEGER', required: true },
  ],
};

const demoVersions: PolicyVersionDetail[] = [
  {
    versionId: '9600000000000000001',
    templateId: demoTemplate.templateId,
    versionNumber: 1,
    status: 'PUBLISHED',
    effectiveFrom: '2026-07-01',
    effectiveTo: null,
    changeReason: '建立通用规则',
    createdBy: '9100000000000000001',
    createdAt: '2026-06-28T08:00:00Z',
    publishedAt: '2026-06-30T08:00:00Z',
    rowVersion: 2,
    parameters: [{ key: 'decisionMode', value: 'STRICT' }, { key: 'threshold', value: 1 }],
    scopeBindings: [{ bindingId: '9650000000000000001', scopeType: 'COMPANY', scopeResourceId: '9700000000000000001', priority: 100, effectiveFrom: '2026-07-01', effectiveTo: null }],
  },
  {
    versionId: '9600000000000000002',
    templateId: demoTemplate.templateId,
    versionNumber: 2,
    status: 'DRAFT',
    effectiveFrom: '2026-08-01',
    effectiveTo: null,
    changeReason: '优化规则判定与适用范围',
    createdBy: '9100000000000000002',
    createdAt: '2026-07-23T08:00:00Z',
    rowVersion: 1,
    parameters: [{ key: 'decisionMode', value: 'BALANCED' }, { key: 'threshold', value: 2 }],
    scopeBindings: [{ bindingId: '9650000000000000002', scopeType: 'LOCATION', scopeResourceId: '9700000000000000002', priority: 120, effectiveFrom: '2026-08-01', effectiveTo: null }],
  },
];

export function listPolicyTemplates(filters: { q?: string; page?: number; size?: number } = {}): Promise<Page<PolicyTemplateSummary>> {
  if (isDemoMode()) {
    const items = !filters.q || `${demoTemplate.code}${demoTemplate.name}`.includes(filters.q)
      ? [demoTemplate]
      : [];
    return Promise.resolve({ items, total: items.length, page: 0, size: filters.size ?? 20 });
  }
  const params = new URLSearchParams();
  if (filters.q) params.set('query', filters.q);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 20));
  return requestJson<Page<PolicyTemplateSummary>>(`/api/v1/policy-templates?${params}`);
}

export function getPolicyTemplate(templateId: string): Promise<PolicyTemplateDetail> {
  if (isDemoMode()) return Promise.resolve({ ...demoTemplate, templateId });
  return requestJson<PolicyTemplateDetail>(`/api/v1/policy-templates/${encodeURIComponent(templateId)}`);
}

export function createPolicyTemplate(input: {
  code: string;
  name: string;
  description: string;
  fieldDefinitions: PolicyTemplateDetail['fieldDefinitions'];
}): Promise<PolicyTemplateDetail> {
  if (isDemoMode()) return Promise.resolve({ ...demoTemplate, ...input });
  return requestJson<PolicyTemplateDetail>('/api/v1/policy-templates', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function listPolicyVersions(templateId: string): Promise<Page<PolicyVersionSummary>> {
  if (isDemoMode()) return Promise.resolve({ items: demoVersions, total: demoVersions.length, page: 0, size: 20 });
  return requestJson<Page<PolicyVersionSummary>>(`/api/v1/policy-templates/${encodeURIComponent(templateId)}/versions`);
}

export function getPolicyVersion(templateId: string, versionId: string): Promise<PolicyVersionDetail> {
  if (isDemoMode()) {
    const version = demoVersions.find((item) => item.versionId === versionId) ?? demoVersions[0];
    if (!version) return Promise.reject(new Error('DEMO_POLICY_VERSION_NOT_FOUND'));
    return Promise.resolve({ ...version, templateId });
  }
  return requestJson<PolicyVersionDetail>(
    `/api/v1/policy-templates/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(versionId)}`,
  );
}

export function createDraft(templateId: string, input: {
  effectiveFrom: string;
  effectiveTo?: string | null;
  changeReason: string;
}): Promise<PolicyVersionDetail> {
  if (isDemoMode()) return getPolicyVersion(templateId, '9600000000000000002');
  return requestJson<PolicyVersionDetail>(`/api/v1/policy-templates/${encodeURIComponent(templateId)}/versions`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateDraft(
  templateId: string,
  versionId: string,
  input: Pick<PolicyVersionDetail, 'effectiveFrom' | 'effectiveTo' | 'changeReason' | 'parameters'> & { expectedVersion: number },
): Promise<PolicyVersionDetail> {
  if (isDemoMode()) return getPolicyVersion(templateId, versionId);
  return requestJson<PolicyVersionDetail>(versionPath(templateId, versionId), {
    method: 'PATCH',
    body: JSON.stringify(input),
  });
}

export function updateScopeBindings(
  templateId: string,
  versionId: string,
  scopeBindings: ScopeBinding[],
  expectedVersion: number,
): Promise<PolicyVersionDetail> {
  if (isDemoMode()) return getPolicyVersion(templateId, versionId);
  return requestJson<PolicyVersionDetail>(`${versionPath(templateId, versionId)}/scope-bindings`, {
    method: 'PUT',
    body: JSON.stringify({
      bindings: scopeBindings.map(({ scopeType, scopeResourceId, priority, effectiveFrom, effectiveTo }) => ({
        scopeType,
        scopeResourceId,
        priority,
        effectiveFrom,
        effectiveTo,
      })),
      expectedVersion,
    }),
  });
}

export function validatePolicy(templateId: string, versionId: string): Promise<PolicyValidationResult> {
  if (isDemoMode()) return Promise.resolve({ valid: true, issues: [], validatedAt: '2026-07-24T08:40:00Z' });
  return requestJson<PolicyValidationResult>(`${versionPath(templateId, versionId)}/validate`, { method: 'POST' });
}

export function checkPolicyConflicts(templateId: string, versionId: string): Promise<PolicyConflictResult> {
  if (isDemoMode()) {
    return Promise.resolve({
      hasConflicts: true,
      conflicts: [{
        code: 'POLICY_SCOPE_CONFLICT',
        conflictingVersionId: '9600000000000000001',
        scopeType: 'LOCATION',
        scopeResourceId: '9700000000000000002',
        priority: 120,
        effectiveFrom: '2026-08-01',
        effectiveTo: null,
      }],
    });
  }
  return requestJson<PolicyConflictResult>(`${versionPath(templateId, versionId)}/conflicts`, { method: 'POST' });
}

export function previewPolicyImpact(templateId: string, versionId: string): Promise<PolicyImpactPreview> {
  if (isDemoMode()) return Promise.resolve({
    scopeCount: 1,
    affectedObjectCount: 4,
    effectiveFrom: '2026-08-01',
    effectiveTo: null,
    frozenPeriodProtected: true,
    warnings: ['所选期间已冻结，不能直接发布变更。'],
  });
  return requestJson<PolicyImpactPreview>(`${versionPath(templateId, versionId)}/impact-preview`, { method: 'POST' });
}

export function simulatePolicy(
  templateId: string,
  versionId: string,
  sampleName: string,
  inputs: Record<string, unknown>,
): Promise<PolicySimulationResult> {
  if (isDemoMode()) return Promise.resolve({
    matched: true,
    resolvedParameters: demoVersions[1]?.parameters ?? [],
    explanation: ['命中合成一号厂区作用范围。', '采用优先级 120 的草稿版本。'],
  });
  return requestJson<PolicySimulationResult>(`${versionPath(templateId, versionId)}/simulate`, {
    method: 'POST',
    body: JSON.stringify({ sampleName, inputs }),
  });
}

export function publishPolicy(templateId: string, versionId: string, expectedVersion: number, reason: string): Promise<void> {
  return policyOperation(templateId, versionId, 'publish', { expectedVersion, reason });
}

export function deactivatePolicy(templateId: string, versionId: string, expectedVersion: number, reason: string): Promise<void> {
  return policyOperation(templateId, versionId, 'deactivate', { expectedVersion, reason });
}

export function rollbackPolicy(
  templateId: string,
  versionId: string,
  targetVersionId: string,
  expectedVersion: number,
  reason: string,
): Promise<void> {
  return policyOperation(templateId, versionId, 'rollback', { targetVersionId, expectedVersion, reason });
}

function policyOperation(
  templateId: string,
  versionId: string,
  operation: 'publish' | 'deactivate' | 'rollback',
  body: { targetVersionId?: string; expectedVersion: number; reason: string },
): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`${versionPath(templateId, versionId)}/${operation}`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

function versionPath(templateId: string, versionId: string): string {
  return `/api/v1/policy-templates/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(versionId)}`;
}

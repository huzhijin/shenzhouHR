import type {
  OrganizationCreateRequest,
  OrganizationDetail,
  OrganizationNode,
  OrganizationUpdateRequest,
  OrganizationVersionPage,
  OrganizationVersionSummary,
} from './organizationApi';

const EFFECTIVE_FROM = '2026-01-01T00:00:00Z';
const SYNTHETIC_ACTOR = 'synthetic-people-admin';
let syntheticVersion = 1;

export function getDemoOrganizationTree(): OrganizationNode[] {
  return [
    organization('c0000000-0000-0000-0000-000000000001', 'SZSC', '江苏神州半导体科技股份有限公司', 'COMPANY', '900719925474099312345', [
      organization('c0000000-0000-0000-0000-000000000010', 'MGT', '经营管理中心', 'DEPARTMENT', '900719925474099312410', [
        organization('c0000000-0000-0000-0000-000000000011', 'HR', '人力资源部', 'DEPARTMENT', '900719925474099312411'),
        organization('c0000000-0000-0000-0000-000000000012', 'FIN', '财务管理部', 'DEPARTMENT', '900719925474099312412'),
        organization('c0000000-0000-0000-0000-000000000013', 'IT', '数字化与信息部', 'DEPARTMENT', '900719925474099312413'),
      ]),
      organization('c0000000-0000-0000-0000-000000000020', 'RND', '研发中心', 'DEPARTMENT', '900719925474099312420', [
        organization('c0000000-0000-0000-0000-000000000021', 'ICD', '集成电路设计部', 'DEPARTMENT', '900719925474099312421'),
        organization('c0000000-0000-0000-0000-000000000022', 'PE', '工艺研发部', 'DEPARTMENT', '900719925474099312422'),
        organization('c0000000-0000-0000-0000-000000000023', 'LAB', '可靠性实验室', 'DEPARTMENT', '900719925474099312423'),
      ]),
      organization('c0000000-0000-0000-0000-000000000030', 'MFG', '制造中心', 'DEPARTMENT', '900719925474099312430', [
        organization('c0000000-0000-0000-0000-000000000031', 'FAB1', '一厂制造部', 'DEPARTMENT', '900719925474099312431', [
          organization('c0000000-0000-0000-0000-000000000032', 'FAB1-A', '一厂甲班', 'TEAM', '900719925474099312432'),
          organization('c0000000-0000-0000-0000-000000000033', 'FAB1-B', '一厂乙班', 'TEAM', '900719925474099312433'),
        ]),
        organization('c0000000-0000-0000-0000-000000000034', 'QA', '质量保证部', 'DEPARTMENT', '900719925474099312434'),
      ]),
    ]),
  ];
}

export function getDemoOrganizationDetail(organizationId: string): OrganizationDetail {
  const node = flatten(getDemoOrganizationTree())
    .find((candidate) => candidate.organizationId === organizationId);
  if (!node) throw new Error('SYNTHETIC_ORGANIZATION_NOT_FOUND');
  return {
    organizationId: node.organizationId,
    organizationVersionId: node.organizationVersionId ?? `${node.organizationId}-version`,
    parentOrganizationId: parentId(getDemoOrganizationTree(), organizationId),
    code: node.code,
    name: node.name,
    organizationType: node.organizationType,
    status: node.status,
    effectiveFrom: node.effectiveFrom.slice(0, 10),
    effectiveTo: node.effectiveTo?.slice(0, 10) ?? null,
    sourceAuthority: node.sourceAuthority ?? 'LOCAL',
    sourceBatchId: 'synthetic-opening-batch',
    rowVersion: node.rowVersion ?? 1,
    changeReason: '合成期初组织发布',
    createdBy: SYNTHETIC_ACTOR,
    createdAt: EFFECTIVE_FROM,
    childCount: node.children.length,
    auditResourceId: node.organizationId,
  };
}

export function getDemoOrganizationVersions(organizationId: string): OrganizationVersionPage {
  const detail = getDemoOrganizationDetail(organizationId);
  return { items: [detail], total: 1, page: 0, size: 20 };
}

export function createDemoOrganization(request: OrganizationCreateRequest): OrganizationDetail {
  const organizationId = `synthetic-local-organization-${syntheticVersion++}`;
  return versionFromRequest(organizationId, 0, request);
}

export function updateDemoOrganization(
  organizationId: string,
  request: OrganizationUpdateRequest,
): OrganizationDetail {
  return versionFromRequest(organizationId, syntheticVersion++, request);
}

function organization(
  organizationId: string,
  code: string,
  name: string,
  organizationType: OrganizationNode['organizationType'],
  sourceOrganizationId: string,
  children: OrganizationNode[] = [],
): OrganizationNode {
  return {
    organizationId,
    organizationVersionId: `${organizationId}-v1`,
    code,
    name,
    organizationType,
    status: 'ACTIVE',
    sourceOrganizationId,
    effectiveFrom: EFFECTIVE_FROM,
    effectiveTo: null,
    sourceAuthority: 'LOCAL',
    rowVersion: 1,
    children,
  };
}

function flatten(nodes: OrganizationNode[]): OrganizationNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children)]);
}

function parentId(nodes: OrganizationNode[], organizationId: string): string | null {
  for (const node of nodes) {
    if (node.children.some((child) => child.organizationId === organizationId)) {
      return node.organizationId;
    }
    const nested = parentId(node.children, organizationId);
    if (nested) return nested;
  }
  return null;
}

function versionFromRequest(
  organizationId: string,
  rowVersion: number,
  request: OrganizationCreateRequest | OrganizationUpdateRequest,
): OrganizationDetail {
  const version: OrganizationVersionSummary = {
    organizationId,
    organizationVersionId: `${organizationId}-v${rowVersion + 1}`,
    parentOrganizationId: request.parentOrganizationId ?? null,
    code: request.code,
    name: request.name,
    organizationType: request.organizationType,
    status: 'status' in request ? request.status : 'ACTIVE',
    effectiveFrom: request.effectiveFrom,
    effectiveTo: 'effectiveTo' in request ? request.effectiveTo ?? null : null,
    sourceAuthority: 'LOCAL',
    sourceBatchId: null,
    rowVersion: rowVersion + 1,
    changeReason: request.reason,
    createdBy: SYNTHETIC_ACTOR,
    createdAt: '2026-07-25T03:30:00Z',
  };
  return { ...version, childCount: 0, auditResourceId: organizationId };
}

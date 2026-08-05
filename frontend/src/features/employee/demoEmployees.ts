import { getDemoOrganizationTree } from '../organization/demoOrganization';
import type { OrganizationNode } from '../organization/organizationApi';
import type {
  EmployeeCreateRequest,
  EmployeeDetail,
  EmployeeFilters,
  EmployeePage,
  EmployeeSummary,
  EmployeeUpdateRequest,
  EmployeeVersionPage,
  EmploymentPeriodCreateRequest,
  EmploymentPeriodPage,
  EmploymentPeriodUpdateRequest,
  EmploymentPeriodView,
  PriorServiceAdjustmentRequest,
  PriorServiceRecordPage,
  PriorServiceRecordView,
  PriorServiceReplayView,
} from './employeeApi';

interface DemoOrganization {
  id: string;
  code: string;
  name: string;
}

interface DemoEmployeeSeed {
  displayName: string;
  employmentStatus: string;
  organization: DemoOrganization | null;
  seeyonOaCode: string | null;
  bindingStatus: string | null;
  assignmentEffectiveFrom: string | null;
  assignmentEffectiveTo?: string | null;
}

const organizations = {
  humanResources: organization('11', 'HR', '人力资源部'),
  finance: organization('12', 'FIN', '财务管理部'),
  informationTechnology: organization('13', 'IT', '数字化与信息部'),
  integratedCircuitDesign: organization('21', 'ICD', '集成电路设计部'),
  processEngineering: organization('22', 'PE', '工艺研发部'),
  reliabilityLaboratory: organization('23', 'LAB', '可靠性实验室'),
  fabFirstShift: organization('32', 'FAB1-A', '一厂甲班'),
  fabSecondShift: organization('33', 'FAB1-B', '一厂乙班'),
  qualityAssurance: organization('34', 'QA', '质量保证部'),
};

const employeeSeeds: DemoEmployeeSeed[] = [
  seed('周明', 'ACTIVE', organizations.humanResources, 'SZ1001', 'CONFIRMED', '2022-03-14'),
  seed('孙悦', 'ACTIVE', organizations.humanResources, 'SZ1002', 'CONFIRMED', '2023-06-01'),
  seed('陈思远', 'PROBATION', organizations.humanResources, 'SZ1026', 'PENDING', '2026-06-15'),
  seed('林嘉禾', 'ACTIVE', organizations.finance, 'SZ1003', 'CONFIRMED', '2021-11-08'),
  seed('郑雨桐', 'ACTIVE', organizations.finance, 'SZ1004', 'CONFIRMED', '2024-02-19'),
  seed('吴昊', 'ACTIVE', organizations.informationTechnology, 'SZ1005', 'CONFIRMED', '2020-09-21'),
  seed('李欣然', 'ACTIVE', organizations.informationTechnology, 'SZ1006', 'CONFIRMED', '2024-08-12'),
  seed('赵文博', 'ACTIVE', organizations.integratedCircuitDesign, 'SZ1007', 'CONFIRMED', '2019-05-06'),
  seed('黄子涵', 'ACTIVE', organizations.integratedCircuitDesign, 'SZ1008', 'CONFIRMED', '2022-07-18'),
  seed('徐静', 'PROBATION', organizations.integratedCircuitDesign, 'SZ1027', 'PENDING', '2026-07-01'),
  seed('高远', 'ACTIVE', organizations.processEngineering, 'SZ1009', 'CONFIRMED', '2018-04-23'),
  seed('罗晨', 'ACTIVE', organizations.processEngineering, 'SZ1010', 'CONFIRMED', '2023-03-13'),
  seed('蒋宁', 'ACTIVE', organizations.reliabilityLaboratory, 'SZ1011', 'CONFIRMED', '2021-01-11'),
  seed('何佳', 'ACTIVE', organizations.reliabilityLaboratory, null, 'PENDING', '2025-10-08'),
  seed('马骏', 'ACTIVE', organizations.fabFirstShift, 'SZ1013', 'CONFIRMED', '2017-08-28'),
  seed('唐婧', 'ACTIVE', organizations.fabFirstShift, 'SZ1014', 'CONFIRMED', '2020-12-07'),
  seed('方旭', 'ACTIVE', organizations.fabFirstShift, 'SZ1015', 'CONFIRMED', '2022-10-17'),
  seed('梁雪', 'ACTIVE', organizations.fabSecondShift, 'SZ1016', 'CONFIRMED', '2019-06-03'),
  seed('宋扬', 'ACTIVE', organizations.fabSecondShift, 'SZ1017', 'CONFIRMED', '2021-09-27'),
  seed('谢楠', 'ACTIVE', organizations.fabSecondShift, 'SZ1018', 'CONFLICT', '2025-04-14'),
  seed('韩磊', 'ACTIVE', organizations.qualityAssurance, 'SZ1019', 'CONFIRMED', '2018-11-19'),
  seed('秦岚', 'LEAVE_PENDING', organizations.qualityAssurance, 'SZ1020', 'CONFIRMED', '2020-02-10'),
  seed('冯硕', 'ACTIVE', organizations.qualityAssurance, null, null, '2026-02-23'),
  seed('于晴', 'INACTIVE', null, 'SZ0968', 'CONFIRMED', null, '2026-05-31'),
];

const demoEmployees: EmployeeSummary[] = employeeSeeds.map((employee, index) => ({
  employeeId: `d0000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
  employeeVersionId: `dv000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`,
  employeeNumber: `SYN-${String(index + 1).padStart(4, '0')}`,
  displayName: employee.displayName,
  employmentStatus: normalizeStatus(employee.employmentStatus),
  organizationId: employee.organization?.id ?? null,
  organizationName: employee.organization?.name ?? null,
  organizationCode: employee.organization?.code ?? null,
  seeyonOaCode: employee.seeyonOaCode,
  bindingStatus: employee.bindingStatus,
  assignmentEffectiveFrom: employee.assignmentEffectiveFrom,
  assignmentEffectiveTo: employee.assignmentEffectiveTo ?? null,
  sourceAuthority: 'LOCAL',
  rowVersion: 1,
}));

const demoPeriods = new Map<string, EmploymentPeriodView[]>();
const demoPriorRecords = new Map<string, PriorServiceRecordView[]>();
let syntheticSequence = 100;

export function getDemoEmployeePage(
  page: number,
  size: number,
  filters: EmployeeFilters = {},
): EmployeePage {
  const visibleOrganizationIds = filters.organizationId
    ? demoOrganizationScope(filters.organizationId, filters.includeDescendants)
    : undefined;
  const filtered = demoEmployees.filter((employee) => (
    (!filters.query
      || employee.employeeNumber.includes(filters.query)
      || employee.displayName.includes(filters.query))
    && (!visibleOrganizationIds || (
      employee.organizationId !== null
      && visibleOrganizationIds.has(employee.organizationId)
    ))
    && (!filters.status || employee.employmentStatus === filters.status)
  ));
  const offset = page * size;
  return {
    items: filtered.slice(offset, offset + size),
    total: filtered.length,
    page,
    size,
  };
}

function demoOrganizationScope(
  organizationId: string,
  includeDescendants = false,
): Set<string> {
  if (!includeDescendants) return new Set([organizationId]);
  const selected = findDemoOrganization(getDemoOrganizationTree(), organizationId);
  return new Set(selected ? flattenDemoOrganizationIds(selected) : [organizationId]);
}

function findDemoOrganization(
  nodes: OrganizationNode[],
  organizationId: string,
): OrganizationNode | undefined {
  for (const node of nodes) {
    if (node.organizationId === organizationId) return node;
    const nested = findDemoOrganization(node.children, organizationId);
    if (nested) return nested;
  }
  return undefined;
}

function flattenDemoOrganizationIds(node: OrganizationNode): string[] {
  return [
    node.organizationId,
    ...node.children.flatMap(flattenDemoOrganizationIds),
  ];
}

export function getDemoEmployeeDetail(employeeId: string): EmployeeDetail {
  const employee = demoEmployees.find((item) => item.employeeId === employeeId);
  if (!employee) throw new Error('SYNTHETIC_EMPLOYEE_NOT_FOUND');
  const periods = getDemoEmploymentPeriods(employeeId).items;
  const priorService = recalculateDemoPriorService(employeeId);
  return {
    employeeVersionId: employee.employeeVersionId,
    employeeId: employee.employeeId,
    employeeNumber: employee.employeeNumber,
    displayName: employee.displayName,
    status: employee.employmentStatus,
    externalEmployeeId: employee.seeyonOaCode,
    effectiveFrom: employee.assignmentEffectiveFrom?.slice(0, 10) ?? '2026-01-01',
    effectiveTo: employee.assignmentEffectiveTo?.slice(0, 10) ?? null,
    sourceAuthority: employee.sourceAuthority,
    sourceBatchId: 'synthetic-opening-batch',
    rowVersion: employee.rowVersion,
    changeReason: '合成人员期初发布',
    createdBy: 'synthetic-people-admin',
    createdAt: '2026-07-25T03:30:00Z',
    employmentPeriods: periods,
    priorService,
    auditResourceId: employee.employeeId,
  };
}

export function getDemoEmployeeVersions(employeeId: string): EmployeeVersionPage {
  const detail = getDemoEmployeeDetail(employeeId);
  return { items: [detail], total: 1, page: 0, size: 20 };
}

export function createDemoEmployee(request: EmployeeCreateRequest): EmployeeDetail {
  const employeeId = `synthetic-local-employee-${syntheticSequence++}`;
  return employeeDetailFromRequest(employeeId, 1, request);
}

export function updateDemoEmployee(
  employeeId: string,
  request: EmployeeUpdateRequest,
): EmployeeDetail {
  return employeeDetailFromRequest(employeeId, syntheticSequence++, request);
}

export function getDemoEmploymentPeriods(employeeId: string): EmploymentPeriodPage {
  let periods = demoPeriods.get(employeeId);
  if (!periods) {
    periods = [
      period(employeeId, 1, '2024-07-15', null, null),
      period(employeeId, 2, '2020-02-10', '2022-11-30', '2022-12-01'),
    ];
    demoPeriods.set(employeeId, periods);
  }
  return { items: periods, total: periods.length, page: 0, size: 100 };
}

export function createDemoEmploymentPeriod(
  employeeId: string,
  request: EmploymentPeriodCreateRequest,
): EmploymentPeriodView {
  const periods = getDemoEmploymentPeriods(employeeId).items;
  const created = period(
    employeeId,
    syntheticSequence++,
    request.startDate,
    request.terminationDate ?? null,
    request.terminationDate ? addDay(request.terminationDate) : null,
    request.organizationId,
    request.positionId,
    request.reason,
  );
  demoPeriods.set(employeeId, [created, ...periods]);
  return created;
}

export function updateDemoEmploymentPeriod(
  employeeId: string,
  employmentPeriodId: string,
  request: EmploymentPeriodUpdateRequest,
): EmploymentPeriodView {
  const periods = getDemoEmploymentPeriods(employeeId).items;
  const current = periods.find((item) => item.employmentPeriodId === employmentPeriodId);
  if (!current) throw new Error('SYNTHETIC_EMPLOYMENT_PERIOD_NOT_FOUND');
  const updated: EmploymentPeriodView = {
    ...current,
    organizationId: request.organizationId,
    positionId: request.positionId ?? null,
    startDate: request.startDate,
    terminationDate: request.terminationDate,
    endExclusive: request.terminationDate ? addDay(request.terminationDate) : null,
    rowVersion: current.rowVersion + 1,
    changeReason: request.reason,
    createdAt: '2026-07-25T03:40:00Z',
  };
  demoPeriods.set(employeeId, periods.map((item) => (
    item.employmentPeriodId === employmentPeriodId ? updated : item
  )));
  return updated;
}

export function getDemoPriorServiceRecords(employeeId: string): PriorServiceRecordPage {
  let items = demoPriorRecords.get(employeeId);
  if (!items) {
    items = [{
      priorServiceRecordId: `${employeeId}-prior-opening`,
      employeeId,
      recordType: 'OPENING_IMPORT',
      amountDays: 2920,
      reason: '合成期初工龄确认',
      businessDate: '2026-07-01',
      sourceBatchId: 'synthetic-opening-batch',
      reversalOfRecordId: null,
      resultingTotalDays: 2920,
      actorId: 'synthetic-people-admin',
      occurredAt: '2026-07-25T03:30:00Z',
      requestId: 'synthetic-request-prior-opening',
    }];
    demoPriorRecords.set(employeeId, items);
  }
  const totalDays = items.reduce((total, item) => total + item.amountDays, 0);
  return {
    items,
    totalDays,
    replayDigest: 'b'.repeat(64),
    total: items.length,
    page: 0,
    size: 100,
  };
}

export function adjustDemoPriorService(
  employeeId: string,
  request: PriorServiceAdjustmentRequest,
): PriorServiceRecordView {
  const page = getDemoPriorServiceRecords(employeeId);
  const created: PriorServiceRecordView = {
    priorServiceRecordId: `${employeeId}-prior-${syntheticSequence++}`,
    employeeId,
    recordType: 'ADJUSTMENT',
    amountDays: request.amountDays,
    reason: request.reason,
    businessDate: request.businessDate,
    sourceBatchId: null,
    reversalOfRecordId: null,
    resultingTotalDays: page.totalDays + request.amountDays,
    actorId: 'synthetic-people-admin',
    occurredAt: '2026-07-25T03:45:00Z',
    requestId: `synthetic-request-${syntheticSequence}`,
  };
  demoPriorRecords.set(employeeId, [...page.items, created]);
  return created;
}

export function recalculateDemoPriorService(employeeId: string): PriorServiceReplayView {
  const page = getDemoPriorServiceRecords(employeeId);
  return {
    employeeId,
    totalDays: page.totalDays,
    recordCount: page.total,
    replayDigest: page.replayDigest,
    recalculatedAt: '2026-07-25T03:46:00Z',
  };
}

function organization(suffix: string, code: string, name: string): DemoOrganization {
  return {
    id: `c0000000-0000-0000-0000-${suffix.padStart(12, '0')}`,
    code,
    name,
  };
}

function seed(
  displayName: string,
  employmentStatus: string,
  demoOrganization: DemoOrganization | null,
  seeyonOaCode: string | null,
  bindingStatus: string | null,
  assignmentEffectiveFrom: string | null,
  assignmentEffectiveTo: string | null = null,
): DemoEmployeeSeed {
  return {
    displayName,
    employmentStatus,
    organization: demoOrganization,
    seeyonOaCode,
    bindingStatus,
    assignmentEffectiveFrom,
    assignmentEffectiveTo,
  };
}

function normalizeStatus(value: string): EmployeeSummary['employmentStatus'] {
  if (value === 'INACTIVE') return 'INACTIVE';
  if (value === 'LEAVE_PENDING') return 'TERMINATED';
  return 'ACTIVE';
}

function period(
  employeeId: string,
  sequence: number,
  startDate: string,
  terminationDate: string | null,
  endExclusive: string | null,
  organizationId = organizations.fabFirstShift.id,
  positionId: string | null = `synthetic-position-${sequence}`,
  reason = '合成任职周期',
): EmploymentPeriodView {
  return {
    employmentPeriodId: `${employeeId}-employment-${sequence}`,
    employeeId,
    organizationId,
    positionId,
    startDate,
    terminationDate,
    endExclusive,
    sourceBatchId: 'synthetic-opening-batch',
    rowVersion: sequence,
    changeReason: reason,
    createdBy: 'synthetic-people-admin',
    createdAt: '2026-07-25T03:30:00Z',
  };
}

function addDay(value: string): string {
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime())) {
    throw new Error('INVALID_SYNTHETIC_EMPLOYEE_DATE');
  }
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

function employeeDetailFromRequest(
  employeeId: string,
  rowVersion: number,
  request: EmployeeCreateRequest | EmployeeUpdateRequest,
): EmployeeDetail {
  const replay = recalculateDemoPriorService(employeeId);
  return {
    employeeId,
    employeeVersionId: `${employeeId}-version-${rowVersion}`,
    employeeNumber: request.employeeNumber,
    displayName: request.displayName,
    status: 'status' in request ? request.status : 'ACTIVE',
    externalEmployeeId: 'externalEmployeeId' in request ? request.externalEmployeeId : null,
    effectiveFrom: request.effectiveFrom,
    effectiveTo: 'effectiveTo' in request ? request.effectiveTo ?? null : null,
    sourceAuthority: 'LOCAL',
    sourceBatchId: null,
    rowVersion,
    changeReason: request.reason,
    createdBy: 'synthetic-people-admin',
    createdAt: '2026-07-25T03:40:00Z',
    employmentPeriods: getDemoEmploymentPeriods(employeeId).items,
    priorService: replay,
    auditResourceId: employeeId,
  };
}

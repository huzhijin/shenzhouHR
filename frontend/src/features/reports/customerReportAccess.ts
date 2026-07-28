export type CustomerReportScopeType = 'LEGAL_ENTITY' | 'ORGANIZATION' | 'SELF';

export interface CustomerReportDataScope {
  reference: string;
  type: CustomerReportScopeType;
  label: string;
  actorLabel: string;
  allowedDepartments: readonly string[];
  allowedEmployees: readonly string[];
}

export interface CustomerReportPerson {
  department: string;
  employee: string;
}

const employeeDirectory: readonly CustomerReportPerson[] = [
  { employee: '陈思远', department: '制造中心' },
  { employee: '周晴', department: '制造中心' },
  { employee: '张伟', department: '研发中心' },
  { employee: '林晓雯', department: '研发中心' },
  { employee: '赵凯', department: '制造中心' },
  { employee: '蒋宁', department: '职能中心' },
  { employee: '吴昊', department: '研发中心' },
  { employee: '沈佳', department: '职能中心' },
] as const;

export const customerReportDemoScopes: readonly CustomerReportDataScope[] = [
  {
    reference: 'demo-scope:legal-entity:shenzhou',
    type: 'LEGAL_ENTITY',
    label: '江苏神州半导体科技有限公司 · 全部组织',
    actorLabel: '公司 HR',
    allowedDepartments: ['制造中心', '研发中心', '职能中心'],
    allowedEmployees: employeeDirectory.map((person) => person.employee),
  },
  {
    reference: 'demo-scope:legal-entity:executive',
    type: 'LEGAL_ENTITY',
    label: '江苏神州半导体科技有限公司 · 管理驾驶舱',
    actorLabel: '高管',
    allowedDepartments: ['制造中心', '研发中心', '职能中心'],
    allowedEmployees: employeeDirectory.map((person) => person.employee),
  },
  {
    reference: 'demo-scope:organization:manufacturing',
    type: 'ORGANIZATION',
    label: '制造中心 · 含下级组织',
    actorLabel: '部门负责人',
    allowedDepartments: ['制造中心'],
    allowedEmployees: ['陈思远', '周晴', '赵凯'],
  },
  {
    reference: 'demo-scope:self:sz0261',
    type: 'SELF',
    label: '本人 · 陈思远',
    actorLabel: '本人',
    allowedDepartments: ['制造中心'],
    allowedEmployees: ['陈思远'],
  },
] as const;

export const defaultCustomerReportDataScope = customerReportDemoScopes[0]!;

export function isWithinCustomerReportScope(
  row: CustomerReportPerson,
  scope: CustomerReportDataScope,
): boolean {
  return scope.allowedDepartments.includes(row.department)
    && scope.allowedEmployees.includes(row.employee);
}

export function isCustomerReportQueryWithinScope(
  department: string,
  employee: string,
  scope: CustomerReportDataScope,
): boolean {
  const departmentAllowed = department === '全部部门'
    || scope.allowedDepartments.includes(department);
  const employeeAllowed = employee === '全部员工'
    || scope.allowedEmployees.includes(employee);
  if (!departmentAllowed || !employeeAllowed) return false;
  if (department === '全部部门' || employee === '全部员工') return true;
  return employeeDirectory.some((person) => (
    person.department === department
    && person.employee === employee
    && isWithinCustomerReportScope(person, scope)
  ));
}

export function authorizedDepartmentOptions(
  scope: CustomerReportDataScope,
): Array<{ value: string; label: string }> {
  return [
    { value: '全部部门', label: scope.type === 'LEGAL_ENTITY' ? '全部授权部门' : '当前授权范围' },
    ...scope.allowedDepartments.map((department) => ({
      value: department,
      label: department,
    })),
  ];
}

export function authorizedEmployeeOptions(
  scope: CustomerReportDataScope,
  department: string,
): Array<{ value: string; label: string }> {
  const people = employeeDirectory.filter((person) => (
    isWithinCustomerReportScope(person, scope)
    && (department === '全部部门' || person.department === department)
  ));
  return [
    { value: '全部员工', label: scope.type === 'SELF' ? '本人' : '全部授权员工' },
    ...people.map((person) => ({
      value: person.employee,
      label: person.employee,
    })),
  ];
}

export function scopeTypeLabel(type: CustomerReportScopeType): string {
  if (type === 'LEGAL_ENTITY') return '法人范围';
  if (type === 'ORGANIZATION') return '组织范围';
  return '本人范围';
}

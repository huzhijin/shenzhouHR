import type { OrganizationNode } from '../organization/organizationApi';

export interface ReferenceOption {
  value: string;
  label: string;
  searchText: string;
}

export function employeeReferenceLabel(employee: {
  displayName: string;
  employeeNumber: string;
  organizationName?: string | null;
}): string {
  return `${employee.displayName}（${employee.employeeNumber}）· ${
    employee.organizationName?.trim() || '未分配部门'
  }`;
}

export function flattenOrganizationOptions(
  nodes: readonly OrganizationNode[],
  parentNames: readonly string[] = [],
): ReferenceOption[] {
  return nodes.flatMap((node) => {
    const path = [...parentNames, node.name];
    return [{
      value: node.organizationId,
      label: path.join(' / '),
      // Organization codes remain searchable for administrators, but the
      // operator-facing option label contains business names only.
      searchText: `${path.join(' ')} ${node.code}`,
    }, ...flattenOrganizationOptions(node.children, path)];
  });
}

export function namedCodeLabel(name: string, code: string): string {
  return code.trim() ? `${name}（${code}）` : name;
}

export function lifecycleStatusLabel(status: string): string {
  return ({
    ACTIVE: '启用',
    INACTIVE: '停用',
    DRAFT: '草稿',
    VALIDATED: '已校验',
    PUBLISHED: '已发布',
  } as Readonly<Record<string, string>>)[status] ?? '其他状态';
}

export function effectivePeriodLabel(
  effectiveFrom: string,
  effectiveTo?: string | null,
): string {
  return `${effectiveFrom} 至 ${effectiveTo ?? '长期'}`;
}

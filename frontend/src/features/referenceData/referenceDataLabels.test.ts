import { describe, expect, it } from 'vitest';

import type { OrganizationNode } from '../organization/organizationApi';
import {
  employeeReferenceLabel,
  flattenOrganizationOptions,
} from './referenceDataLabels';

describe('reference data display labels', () => {
  it('shows an employee as name, employee number and department', () => {
    expect(employeeReferenceLabel({
      displayName: '张三',
      employeeNumber: 'SZ0008',
      organizationName: '制造一部',
    })).toBe('张三（SZ0008）· 制造一部');
  });

  it('uses a human-readable fallback when an employee has no department', () => {
    expect(employeeReferenceLabel({
      displayName: '李四',
      employeeNumber: 'SZ0009',
      organizationName: null,
    })).toBe('李四（SZ0009）· 未分配部门');
  });

  it('shows organization name paths while retaining IDs only as values', () => {
    const tree: OrganizationNode[] = [
      organization('company-id', '神州半导体', 'SZSC', [
        organization('department-id', '制造一部', 'MFG-01'),
      ]),
    ];

    expect(flattenOrganizationOptions(tree)).toEqual([
      {
        value: 'company-id',
        label: '神州半导体',
        searchText: '神州半导体 SZSC',
      },
      {
        value: 'department-id',
        label: '神州半导体 / 制造一部',
        searchText: '神州半导体 制造一部 MFG-01',
      },
    ]);
  });
});

function organization(
  organizationId: string,
  name: string,
  code: string,
  children: OrganizationNode[] = [],
): OrganizationNode {
  return {
    organizationId,
    code,
    name,
    organizationType: children.length ? 'COMPANY' : 'DEPARTMENT',
    status: 'ACTIVE',
    sourceOrganizationId: null,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    children,
  };
}

import { describe, expect, it } from 'vitest';

import { getDemoEmployeePage } from './demoEmployees';

describe('demo employees', () => {
  it('provides stable unique records and real pagination', () => {
    const firstPage = getDemoEmployeePage(0, 20);
    const secondPage = getDemoEmployeePage(1, 20);
    const allIds = [...firstPage.items, ...secondPage.items]
      .map((employee) => employee.employeeId);

    expect(firstPage.total).toBe(24);
    expect(firstPage.items).toHaveLength(20);
    expect(secondPage.items).toHaveLength(4);
    expect(new Set(allIds).size).toBe(24);
    expect(firstPage.items.some((employee) => employee.bindingStatus === 'CONFLICT')).toBe(true);
    expect(firstPage.items.some((employee) => employee.seeyonOaCode === null)).toBe(true);
  });

  it('includes child departments when the directory requests an organization subtree', () => {
    const managementCenter = getDemoEmployeePage(0, 20, {
      organizationId: 'c0000000-0000-0000-0000-000000000010',
      includeDescendants: true,
    });

    expect(managementCenter.total).toBe(7);
    expect(new Set(managementCenter.items.map((employee) => employee.organizationName)))
      .toEqual(new Set(['人力资源部', '财务管理部', '数字化与信息部']));
  });
});

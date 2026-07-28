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
});

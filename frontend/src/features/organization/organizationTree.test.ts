import { describe, expect, it } from 'vitest';

import type { OrganizationNode } from './organizationApi';
import { toOrganizationTreeData } from './organizationTree';

describe('toOrganizationTreeData', () => {
  it('preserves precise external IDs as strings', () => {
    const sourceOrganizationId = '92233720368547758071234567890';
    const input: OrganizationNode[] = [{
      organizationId: '00000000-0000-0000-0000-000000000001',
      code: 'ROOT',
      name: '江苏神州半导体科技股份有限公司',
      organizationType: 'COMPANY',
      status: 'ACTIVE',
      sourceOrganizationId,
      effectiveFrom: '2026-01-01T00:00:00Z',
      effectiveTo: null,
      children: [],
    }];

    const result = toOrganizationTreeData(input);

    expect(result[0]?.unit.sourceOrganizationId).toBe(sourceOrganizationId);
    expect(typeof result[0]?.unit.sourceOrganizationId).toBe('string');
  });
});

import { describe, expect, it } from 'vitest';

import { grantableOrganizationOptions } from './GrantableScopeSelects';

describe('grantable organization options', () => {
  it('shows the full visible hierarchy path and keeps the path searchable', () => {
    const options = grantableOrganizationOptions([
      organization('manufacturing', null, '制造中心', 'MFG'),
      organization('production', 'manufacturing', '生产部', 'PROD'),
      organization('line-one', 'production', '一车间', 'LINE-1'),
    ]);

    expect(options[2]).toEqual({
      value: 'line-one',
      label: '制造中心 / 生产部 / 一车间（LINE-1）',
      searchText: '制造中心 / 生产部 / 一车间 LINE-1',
    });
  });

  it('starts at the authorized root when its parent is outside the directory', () => {
    const options = grantableOrganizationOptions([
      organization('authorized-root', 'hidden-parent', '跨公司协作部', 'CROSS'),
      organization('team', 'authorized-root', '核对组', 'CHECK'),
    ]);

    expect(options[1]?.label).toBe('跨公司协作部 / 核对组（CHECK）');
  });
});

function organization(
  organizationId: string,
  parentOrganizationId: string | null,
  name: string,
  code: string,
) {
  return {
    organizationId,
    companyId: 'company-a',
    parentOrganizationId,
    name,
    code,
    canIncludeDescendants: true,
  };
}

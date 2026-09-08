import { describe, expect, it } from 'vitest';

import { pickPreferredCompany } from './preferredCompany';

describe('pickPreferredCompany', () => {
  it('returns the only authorized company', () => {
    expect(pickPreferredCompany(
      [{ id: 'yangzhou', name: '扬州神州半导体科技有限公司' }],
      (company) => company.name,
    )?.id).toBe('yangzhou');
  });

  it('prefers 江苏神州半导体 over 上海昇州 when both are authorized', () => {
    expect(pickPreferredCompany(
      [
        { id: 'shanghai', name: '上海昇州半导体科技有限公司', code: 'SZSH' },
        { id: 'jiangsu', name: '江苏神州半导体科技股份有限公司', code: 'SZSC' },
      ],
      (company) => company.name,
      (company) => company.code,
    )?.id).toBe('jiangsu');
  });

  it('matches the short 神州半导体 label used by report directories', () => {
    expect(pickPreferredCompany(
      [
        { id: 'company-b', name: '神州科技' },
        { id: 'company-a', name: '神州半导体' },
      ],
      (company) => company.name,
    )?.id).toBe('company-a');
  });

  it('does not invent a default when 神州半导体 is not authorized', () => {
    expect(pickPreferredCompany(
      [
        { id: 'shanghai', name: '上海昇州半导体科技有限公司' },
        { id: 'other', name: '苏州神芯科技有限公司' },
      ],
      (company) => company.name,
    )).toBeUndefined();
  });
});

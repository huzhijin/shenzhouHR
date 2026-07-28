import { describe, expect, it } from 'vitest';

import {
  policyEnumLabel,
  policyScopeTypeLabel,
} from './PolicyComponents';

describe('policy display labels', () => {
  it('translates controlled enum options while preserving unknown values', () => {
    expect(policyEnumLabel('STRICT')).toBe('严格模式');
    expect(policyEnumLabel('BALANCED')).toBe('均衡模式');
    expect(policyEnumLabel('CUSTOM')).toBe('CUSTOM');
  });

  it('translates policy scope types', () => {
    expect(policyScopeTypeLabel('COMPANY')).toBe('公司');
    expect(policyScopeTypeLabel('ATTENDANCE_GROUP')).toBe('考勤组');
  });
});

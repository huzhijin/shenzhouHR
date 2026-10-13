import { describe, expect, it } from 'vitest';

import {
  financeOvertimeLockedTreatment,
  formatFinanceHours,
  isFinanceOvertimeCalendarSheet,
  overtimeCellFill,
  overtimeTreatmentFill,
} from './financeOvertimeLayout';

describe('finance overtime calendar sheets', () => {
  it('locks each dedicated query to one overtime type', () => {
    expect(isFinanceOvertimeCalendarSheet('overtime-fee-daily')).toBe(true);
    expect(financeOvertimeLockedTreatment('overtime-fee-daily')).toBe('加班费');
    expect(financeOvertimeLockedTreatment('overtime-voluntary-daily')).toBe('义务加班');
    expect(financeOvertimeLockedTreatment('overtime-comp-daily')).toBe('转调休');
    expect(financeOvertimeLockedTreatment('finance-overtime')).toBeUndefined();
  });
});

describe('formatFinanceHours', () => {
  it('prints half-hour grid values without float tails', () => {
    expect(formatFinanceHours(2.5)).toBe('2.5');
    expect(formatFinanceHours(63.5)).toBe('63.5');
    expect(formatFinanceHours(63.400000000000006)).not.toContain('000000');
    expect(formatFinanceHours(0, true)).toBe('');
  });
});

describe('overtimeCellFill', () => {
  it('uses a distinct color for 义务加班 and splits mixed days', () => {
    expect(overtimeTreatmentFill('VOLUNTARY')?.background).toBe('#6b4e9b');
    expect(overtimeCellFill({ paidHours: 4.5, voluntaryHours: 3.5 })?.background)
      .toContain('linear-gradient');
    expect(overtimeCellFill(
      { paidHours: 4.5, voluntaryHours: 3.5 },
      '义务加班',
    )?.background).toBe('#6b4e9b');
    expect(overtimeCellFill({ voluntaryHours: 3.5 })?.background).toBe('#6b4e9b');
  });
});

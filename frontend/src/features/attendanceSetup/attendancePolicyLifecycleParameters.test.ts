import { describe, expect, it } from 'vitest';

import { policyParametersForSave } from './AttendancePolicyLifecyclePanel';
import { demoPolicyCatalog, requiredDemoItem } from './attendanceSetupDemo';
import type { PolicyFieldDefinition } from './attendanceSetupTypes';

const fields: PolicyFieldDefinition[] = [
  {
    key: 'mealWindowStart',
    label: '晚餐窗口开始',
    valueType: 'LOCAL_TIME',
    required: true,
  },
  {
    key: 'saturdayMealWindowStart',
    label: '周六晚餐窗口开始',
    valueType: 'LOCAL_TIME',
    required: false,
  },
  {
    key: 'saturdayDeductionMinutes',
    label: '周六扣除分钟',
    valueType: 'INTEGER',
    required: false,
  },
];

describe('attendance policy lifecycle parameters', () => {
  it('exposes weekend and holiday meal groups as optional catalog fields', () => {
    const mealTemplate = requiredDemoItem(demoPolicyCatalog);
    expect(mealTemplate.fields.slice(6).map((field) => [
      field.key,
      field.required,
    ])).toEqual([
      ['saturdayMealWindowStart', false],
      ['saturdayMealWindowEnd', false],
      ['saturdayDeductionMinutes', false],
      ['saturdayTriggerMinutes', false],
      ['saturdayLunchWindowStart', false],
      ['saturdayLunchWindowEnd', false],
      ['saturdayLunchDeductionMinutes', false],
      ['saturdayLunchTriggerMinutes', false],
      ['sundayMealWindowStart', false],
      ['sundayMealWindowEnd', false],
      ['sundayDeductionMinutes', false],
      ['sundayTriggerMinutes', false],
      ['sundayLunchWindowStart', false],
      ['sundayLunchWindowEnd', false],
      ['sundayLunchDeductionMinutes', false],
      ['sundayLunchTriggerMinutes', false],
      ['publicHolidayMealWindowStart', false],
      ['publicHolidayMealWindowEnd', false],
      ['publicHolidayDeductionMinutes', false],
      ['publicHolidayTriggerMinutes', false],
      ['publicHolidayLunchWindowStart', false],
      ['publicHolidayLunchWindowEnd', false],
      ['publicHolidayLunchDeductionMinutes', false],
      ['publicHolidayLunchTriggerMinutes', false],
    ]);
  });

  it('omits blank optional overrides so legacy policy versions remain editable', () => {
    expect(policyParametersForSave(fields, {
      mealWindowStart: '18:00',
      saturdayMealWindowStart: '',
      saturdayDeductionMinutes: '   ',
    })).toEqual([
      { key: 'mealWindowStart', value: '18:00' },
    ]);
  });

  it('serializes entered optional override values with catalog types', () => {
    expect(policyParametersForSave(fields, {
      mealWindowStart: '18:00',
      saturdayMealWindowStart: '17:30',
      saturdayDeductionMinutes: '45',
    })).toEqual([
      { key: 'mealWindowStart', value: '18:00' },
      { key: 'saturdayMealWindowStart', value: '17:30' },
      { key: 'saturdayDeductionMinutes', value: 45 },
    ]);
  });
});

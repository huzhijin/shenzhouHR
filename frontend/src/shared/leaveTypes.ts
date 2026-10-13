/** The 13 leave types returned by the attendance calculation and OA APIs. */
export const LEAVE_TYPE_OPTIONS = [
  { value: 'ANNUAL', label: '年假' },
  { value: 'SICK', label: '病假' },
  { value: 'MARRIAGE', label: '婚假' },
  { value: 'MATERNITY', label: '产假' },
  { value: 'PATERNITY', label: '陪产假' },
  { value: 'BEREAVEMENT', label: '丧假' },
  { value: 'WORK_INJURY', label: '工伤假' },
  { value: 'PRENATAL_NURSING', label: '孕检假' },
  { value: 'BREASTFEEDING', label: '哺乳假' },
  { value: 'PERSONAL', label: '事假' },
  { value: 'COMPENSATORY', label: '调休' },
  { value: 'FAMILY_PLANNING', label: '计生假' },
  { value: 'OTHER', label: '其他假' },
];

const labels: Readonly<Record<string, string>> = Object.fromEntries(
  LEAVE_TYPE_OPTIONS.map(({ value, label }) => [value, label]),
);

// Older source/export records can contain the database leave code.
const aliases: Readonly<Record<string, string>> = {
  ANNUAL_LEAVE: 'ANNUAL',
  SICK_LEAVE: 'SICK',
  MARRIAGE_LEAVE: 'MARRIAGE',
  MATERNITY_LEAVE: 'MATERNITY',
  PATERNITY_LEAVE: 'PATERNITY',
  BEREAVEMENT_LEAVE: 'BEREAVEMENT',
  WORK_INJURY_LEAVE: 'WORK_INJURY',
  PRENATAL_EXAM_TIME: 'PRENATAL_NURSING',
  BREASTFEEDING_TIME: 'BREASTFEEDING',
  PERSONAL_LEAVE: 'PERSONAL',
  TIME_OFF: 'COMPENSATORY',
  FAMILY_PLANNING_LEAVE: 'FAMILY_PLANNING',
  OTHER_LEAVE: 'OTHER',
};

export function leaveTypeLabel(value: string): string {
  const code = value.trim().toUpperCase();
  return labels[aliases[code] ?? code] ?? value;
}

/** Preserve the existing combined filter while exposing each type separately. */
export function matchesLeaveTypeFilter(value: string, filter: string): boolean {
  if (filter === '全部类型') return true;
  const label = leaveTypeLabel(value);
  if (filter === '孕检/哺乳假') {
    return ['孕检假', '产检假', '哺乳假', '孕检/哺乳假'].includes(label);
  }
  return label === leaveTypeLabel(filter);
}

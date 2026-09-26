import dayjs from 'dayjs';

export interface FinanceOvertimeDayCell {
  date: string;
  hours: number;
  dayType?: string;
}

export function financeOvertimeDates(fromDate: string, toDate: string): string[] {
  const start = dayjs(fromDate);
  const end = dayjs(toDate);
  if (!start.isValid() || !end.isValid() || end.isBefore(start, 'day')) {
    return [];
  }
  const dates: string[] = [];
  for (let cursor = start; !cursor.isAfter(end, 'day'); cursor = cursor.add(1, 'day')) {
    dates.push(cursor.format('YYYY-MM-DD'));
  }
  return dates;
}

export function financeOvertimeDateLabel(date: string): string {
  const parsed = dayjs(date);
  return `${parsed.month() + 1}月${parsed.date()}日`;
}

/** ISO weekday: Monday=1 … Sunday=7 */
export function financeOvertimeWeekdayNumber(date: string): number {
  const day = dayjs(date).day();
  return day === 0 ? 7 : day;
}

export function financeOvertimeHoursByDate(
  days: unknown,
): Map<string, number> {
  const map = new Map<string, number>();
  if (!Array.isArray(days)) {
    return map;
  }
  days.forEach((item) => {
    if (item == null || typeof item !== 'object') {
      return;
    }
    const cell = item as Record<string, unknown>;
    const key = String(cell.date ?? '').slice(0, 10);
    if (!key) {
      return;
    }
    map.set(key, Number(cell.hours ?? 0));
  });
  return map;
}

export function formatFinanceHours(value: number, blankIfZero = false): string {
  if (!Number.isFinite(value) || (blankIfZero && value === 0)) {
    return '';
  }
  const half = Math.round(value * 2) / 2;
  return Number.isInteger(half) ? String(half) : half.toFixed(1);
}

export function isoWeekdayBucket(date: string): 'weekday' | 'weekend' {
  const weekday = financeOvertimeWeekdayNumber(date);
  return weekday >= 6 ? 'weekend' : 'weekday';
}

export const FINANCE_OVERTIME_CALENDAR_SHEETS = [
  'finance-overtime',
  'overtime-fee-daily',
  'overtime-voluntary-daily',
  'overtime-comp-daily',
] as const;

export type FinanceOvertimeCalendarSheet =
  (typeof FINANCE_OVERTIME_CALENDAR_SHEETS)[number];

export function isFinanceOvertimeCalendarSheet(
  sheet: string,
): sheet is FinanceOvertimeCalendarSheet {
  return (FINANCE_OVERTIME_CALENDAR_SHEETS as readonly string[]).includes(sheet);
}

export function financeOvertimeLockedTreatment(sheet: string): string | undefined {
  switch (sheet) {
    case 'overtime-fee-daily':
      return '加班费';
    case 'overtime-comp-daily':
      return '转调休';
    case 'overtime-voluntary-daily':
      return '义务加班';
    default:
      return undefined;
  }
}

export function overtimeFeeColumns(treatment?: string): {
  paid: boolean;
  compensatory: boolean;
  voluntary: boolean;
} {
  if (treatment === '加班费' || treatment === '计薪加班') {
    return { paid: true, compensatory: false, voluntary: false };
  }
  if (treatment === '转调休' || treatment === '转调休加班') {
    return { paid: false, compensatory: true, voluntary: false };
  }
  if (treatment === '义务加班') {
    return { paid: false, compensatory: false, voluntary: true };
  }
  return { paid: true, compensatory: true, voluntary: true };
}

export type OvertimeTreatment = 'PAID' | 'COMPENSATORY' | 'VOLUNTARY' | '';

export function overtimeTreatmentFromHours(
  paidHours: number,
  compensatoryHours: number,
  voluntaryHours: number,
): OvertimeTreatment {
  if (paidHours >= compensatoryHours && paidHours >= voluntaryHours && paidHours > 0) {
    return 'PAID';
  }
  if (compensatoryHours >= voluntaryHours && compensatoryHours > 0) {
    return 'COMPENSATORY';
  }
  if (voluntaryHours > 0) {
    return 'VOLUNTARY';
  }
  return '';
}

export function overtimeTreatmentFill(
  treatment: string,
): { background?: string; color?: string } | undefined {
  switch (treatment) {
    case 'PAID':
      return { background: '#3f8850', color: '#fff' };
    case 'COMPENSATORY':
      return { background: '#f1b83d', color: '#24344d' };
    case 'VOLUNTARY':
      return { background: '#6b4e9b', color: '#fff' };
    default:
      return undefined;
  }
}

export function isPaidOvertimeFilter(treatment?: string): boolean {
  return treatment === '加班费' || treatment === '计薪加班';
}

export function isCompensatoryOvertimeFilter(treatment?: string): boolean {
  return treatment === '转调休' || treatment === '转调休加班';
}

export function isVoluntaryOvertimeFilter(treatment?: string): boolean {
  return treatment === '义务加班';
}

export function overtimeCellFill(
  hours: {
    treatment?: string;
    paidHours?: number;
    compensatoryHours?: number;
    voluntaryHours?: number;
  },
  filter?: string,
): { background?: string; color?: string } | undefined {
  if (isPaidOvertimeFilter(filter)) {
    return overtimeTreatmentFill('PAID');
  }
  if (isCompensatoryOvertimeFilter(filter)) {
    return overtimeTreatmentFill('COMPENSATORY');
  }
  if (isVoluntaryOvertimeFilter(filter)) {
    return overtimeTreatmentFill('VOLUNTARY');
  }
  const types: OvertimeTreatment[] = [];
  if ((hours.paidHours ?? 0) > 0) types.push('PAID');
  if ((hours.compensatoryHours ?? 0) > 0) types.push('COMPENSATORY');
  if ((hours.voluntaryHours ?? 0) > 0) types.push('VOLUNTARY');
  if (types.length === 0) {
    return overtimeTreatmentFill(hours.treatment ?? '');
  }
  if (types.length === 1) {
    return overtimeTreatmentFill(types[0]!);
  }
  const stops = types.map((type, index) => {
    const color = overtimeTreatmentFill(type)?.background ?? '#888';
    const start = (index / types.length) * 100;
    const end = ((index + 1) / types.length) * 100;
    return `${color} ${start}% ${end}%`;
  });
  return {
    background: `linear-gradient(135deg, ${stops.join(', ')})`,
    color: '#fff',
  };
}

export function overtimeTreatmentHover(input: {
  paidHours?: number;
  compensatoryHours?: number;
  voluntaryHours?: number;
}): string {
  const parts: string[] = [];
  if ((input.paidHours ?? 0) > 0) {
    parts.push(`加班费 ${formatFinanceHours(input.paidHours ?? 0)}`);
  }
  if ((input.compensatoryHours ?? 0) > 0) {
    parts.push(`转调休 ${formatFinanceHours(input.compensatoryHours ?? 0)}`);
  }
  if ((input.voluntaryHours ?? 0) > 0) {
    parts.push(`义务加班 ${formatFinanceHours(input.voluntaryHours ?? 0)}`);
  }
  return parts.join('；');
}

export function readOvertimeDayHours(day: Record<string, unknown> | undefined): {
  paidHours: number;
  compensatoryHours: number;
  voluntaryHours: number;
  treatment: OvertimeTreatment | string;
} {
  const paidHours = Number(day?.paidHours ?? 0);
  const compensatoryHours = Number(day?.compensatoryHours ?? 0);
  const voluntaryHours = Number(day?.voluntaryHours ?? 0);
  const treatment = String(day?.treatment ?? '')
    || overtimeTreatmentFromHours(paidHours, compensatoryHours, voluntaryHours);
  return { paidHours, compensatoryHours, voluntaryHours, treatment };
}

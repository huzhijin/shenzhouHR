const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export function utcDateAfter(days: number): string {
  const value = new Date();
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function policyLifecycleMinimumDate(effectiveFrom: string): string {
  const tomorrow = utcDateAfter(1);
  const dayAfterVersionStart = addUtcDays(effectiveFrom, 1);
  return dayAfterVersionStart > tomorrow ? dayAfterVersionStart : tomorrow;
}

export function isPolicyLifecycleBoundaryValid(
  boundary: string,
  selected: { effectiveFrom: string; effectiveTo: string | null },
): boolean {
  if (!ISO_DATE_PATTERN.test(boundary)) return false;
  if (boundary < policyLifecycleMinimumDate(selected.effectiveFrom)) return false;
  return selected.effectiveTo === null || boundary < selected.effectiveTo;
}

function addUtcDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00.000Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

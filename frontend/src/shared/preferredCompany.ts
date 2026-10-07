const PREFERRED_COMPANY_CODE = 'SZSC';
const PREFERRED_COMPANY_NAME_MARKERS = [
  '江苏神州半导体',
  '神州半导体',
] as const;

/**
 * Operators almost always work in 神州半导体 first. When several companies are
 * authorized, pick that one instead of the alphabetically first row (上海昇州
 * currently sorts ahead of 江苏神州).
 */
export function pickPreferredCompany<T>(
  companies: readonly T[],
  nameOf: (company: T) => string,
  codeOf?: (company: T) => string | null | undefined,
): T | undefined {
  if (companies.length === 0) return undefined;
  if (companies.length === 1) return companies[0];
  if (codeOf !== undefined) {
    const byCode = companies.find((company) => (
      (codeOf(company) ?? '').trim().toUpperCase() === PREFERRED_COMPANY_CODE
    ));
    if (byCode !== undefined) return byCode;
  }
  for (const marker of PREFERRED_COMPANY_NAME_MARKERS) {
    const match = companies.find((company) => nameOf(company).includes(marker));
    if (match !== undefined) return match;
  }
  return undefined;
}

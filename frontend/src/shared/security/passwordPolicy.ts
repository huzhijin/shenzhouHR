export function passwordMeetsPolicy(value: unknown): value is string {
  if (typeof value !== 'string' || value.length < 12 || value.length > 256) {
    return false;
  }
  return /\p{Lu}/u.test(value)
    && /\p{Ll}/u.test(value)
    && /\p{Nd}/u.test(value)
    && /[^\p{L}\p{N}]/u.test(value);
}

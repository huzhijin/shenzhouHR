export type Appearance = 'day' | 'night';

export const APPEARANCE_STORAGE_KEY = 'shenzhouhr.appearance';

export function readStoredAppearance(): Appearance {
  try {
    return globalThis.localStorage?.getItem(APPEARANCE_STORAGE_KEY) === 'night'
      ? 'night'
      : 'day';
  } catch {
    return 'day';
  }
}

export function applyAppearance(appearance: Appearance): void {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = appearance;
}

export function persistAppearance(appearance: Appearance): void {
  try {
    globalThis.localStorage?.setItem(APPEARANCE_STORAGE_KEY, appearance);
  } catch {
    /* ignore quota / private mode */
  }
  applyAppearance(appearance);
}

import { afterEach, describe, expect, it } from 'vitest';

import {
  APPEARANCE_STORAGE_KEY,
  applyAppearance,
  persistAppearance,
  readStoredAppearance,
} from './appearance';

describe('appearance', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme');
    localStorage.removeItem(APPEARANCE_STORAGE_KEY);
  });

  it('defaults to day and persists night', () => {
    expect(readStoredAppearance()).toBe('day');
    persistAppearance('night');
    expect(readStoredAppearance()).toBe('night');
    expect(document.documentElement.dataset.theme).toBe('night');
  });

  it('applies without storing', () => {
    applyAppearance('night');
    expect(document.documentElement.dataset.theme).toBe('night');
    expect(readStoredAppearance()).toBe('day');
  });
});

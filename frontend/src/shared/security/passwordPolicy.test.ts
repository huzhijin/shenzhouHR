import { describe, expect, it } from 'vitest';

import { passwordMeetsPolicy } from './passwordPolicy';

describe('passwordMeetsPolicy', () => {
  it('accepts a password that satisfies every rule', () => {
    expect(passwordMeetsPolicy('Strong#Password123')).toBe(true);
    expect(passwordMeetsPolicy('Abcdefghi#12')).toBe(true);
    expect(passwordMeetsPolicy(`Aa1!${'x'.repeat(252)}`)).toBe(true);
    expect(passwordMeetsPolicy('Äbcdefghij#1')).toBe(true);
  });

  it.each([
    ['an empty value', ''],
    ['fewer than 12 characters', 'Short#1aA'],
    ['no uppercase letter', 'lowercase#123'],
    ['no lowercase letter', 'UPPERCASE#123'],
    ['no number', 'NoNumbers#Here'],
    ['no symbol', 'NoSymbolsHere123'],
    ['more than 256 characters', `Aa1!${'x'.repeat(253)}`],
  ])('rejects a password with %s', (_reason, value) => {
    expect(passwordMeetsPolicy(value)).toBe(false);
  });

  it('rejects non-string values', () => {
    expect(passwordMeetsPolicy(undefined)).toBe(false);
  });
});

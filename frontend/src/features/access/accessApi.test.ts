import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  isStrongTemporaryPassword,
  resetTemporaryPassword,
} from './accessApi';

describe('account temporary password policy', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('mirrors the backend strong-password rule for every temporary password', () => {
    expect(isStrongTemporaryPassword('Strong#Password123')).toBe(true);
    expect(isStrongTemporaryPassword('123456')).toBe(false);
    expect(isStrongTemporaryPassword('lowercase-password-123')).toBe(false);
  });

  it('always sends the administrator-selected strong temporary password', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await resetTemporaryPassword(
      'account/high',
      '管理员重置密码',
      'Strong#Password123',
    );

    const strongRequest = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(strongRequest[1].body))).toEqual({
      reason: '管理员重置密码',
      temporaryPassword: 'Strong#Password123',
    });
  });
});

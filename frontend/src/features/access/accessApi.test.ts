import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  accountRequiresStrongTemporaryPassword,
  isStrongTemporaryPassword,
  requiresStrongTemporaryPassword,
  resetTemporaryPassword,
} from './accessApi';

describe('account temporary password policy', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each(['SYSTEM_ADMIN', 'HR_ADMIN', 'AUDITOR'])(
    'requires a strong temporary password for %s',
    (roleCode) => {
      expect(requiresStrongTemporaryPassword(roleCode)).toBe(true);
    },
  );

  it('keeps ordinary roles on the server default password path', () => {
    expect(requiresStrongTemporaryPassword('EMPLOYEE_SELF')).toBe(false);
    expect(accountRequiresStrongTemporaryPassword([
      { roleCode: 'EMPLOYEE_SELF' },
      { roleCode: 'DEPARTMENT_HEAD' },
    ])).toBe(false);
    expect(accountRequiresStrongTemporaryPassword([
      { roleCode: 'EMPLOYEE_SELF' },
      { roleCode: 'AUDITOR' },
    ])).toBe(true);
  });

  it('mirrors the backend strong-password rule for privileged inputs', () => {
    expect(isStrongTemporaryPassword('Strong#Password123')).toBe(true);
    expect(isStrongTemporaryPassword('123456')).toBe(false);
    expect(isStrongTemporaryPassword('lowercase-password-123')).toBe(false);
  });

  it('omits the password for a default reset and sends an explicit strong override', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await resetTemporaryPassword('account/default', '普通账号重置');
    await resetTemporaryPassword(
      'account/high',
      '高权限账号重置',
      'Strong#Password123',
    );

    const defaultRequest = fetchMock.mock.calls[0] as [string, RequestInit];
    const strongRequest = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(defaultRequest[0]).toBe(
      '/api/v1/access/accounts/account%2Fdefault/temporary-password-reset',
    );
    expect(JSON.parse(String(defaultRequest[1].body))).toEqual({
      reason: '普通账号重置',
    });
    expect(JSON.parse(String(strongRequest[1].body))).toEqual({
      reason: '高权限账号重置',
      temporaryPassword: 'Strong#Password123',
    });
  });
});

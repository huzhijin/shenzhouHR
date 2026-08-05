import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createEmployeeAccounts,
  isStrongTemporaryPassword,
  listGrantableCompanies,
  listGrantableOrganizations,
  listEmployeeAccountCandidates,
  resetTemporaryPassword,
} from './accessApi';

describe('account access API', () => {
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

  it('sends employee-account candidate filters in the request URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [],
      total: 0,
      available: 0,
      alreadyProvisioned: 0,
      usernameConflicts: 0,
      page: 2,
      size: 50,
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await listEmployeeAccountCandidates({
      companyId: 'company/branch',
      query: '  张 三  ',
      page: 2,
      size: 50,
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/access/account-provisioning/candidates'
      + '?companyId=company%2Fbranch&page=2&size=50&query=%E5%BC%A0+%E4%B8%89',
    );
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      credentials: 'same-origin',
    });
  });

  it('posts only the selected employee ids when creating accounts', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      credentials: [],
      created: 2,
      replayed: false,
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await createEmployeeAccounts(
      ['employee-001', 'employee-002'],
      'employee-account-bulk:request-001',
      '0123456789abcdefghijklmnopqrstuvwxyzABCDEFG',
    );

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, request] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/access/account-provisioning/accounts');
    expect(request.method).toBe('POST');
    expect(new Headers(request.headers).get('Idempotency-Key'))
      .toBe('employee-account-bulk:request-001');
    expect(new Headers(request.headers).get('Provisioning-Recovery-Key'))
      .toBe('0123456789abcdefghijklmnopqrstuvwxyzABCDEFG');
    expect(JSON.parse(String(request.body))).toEqual({
      employeeIds: ['employee-001', 'employee-002'],
    });
  });

  it('loads only the server-authorized grant directory and scopes organizations by company', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('[]', { status: 200 }))
      .mockResolvedValueOnce(new Response('[]', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await listGrantableCompanies('COMPANY');
    await listGrantableOrganizations('company/branch');

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/access/grantable-scopes/companies?scopeType=COMPANY&usage=ROLE_ASSIGNMENT',
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/access/grantable-scopes/companies/company%2Fbranch/organizations?usage=ROLE_ASSIGNMENT',
    );
  });

  it('requests the dual-capability scope intersection for account creation', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('[]', { status: 200 }))
      .mockResolvedValueOnce(new Response('[]', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    await listGrantableCompanies('ORGANIZATION', 'ACCOUNT_CREATION');
    await listGrantableOrganizations('company-a', 'ACCOUNT_CREATION');

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/access/grantable-scopes/companies?scopeType=ORGANIZATION&usage=ACCOUNT_CREATION',
    );
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/v1/access/grantable-scopes/companies/company-a/organizations?usage=ACCOUNT_CREATION',
    );
  });
});

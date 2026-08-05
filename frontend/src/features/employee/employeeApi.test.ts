import { afterEach, describe, expect, it, vi } from 'vitest';

import { getEmployees } from './employeeApi';

describe('employee API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('adds the optional company filter without changing legacy filters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [],
      total: 0,
      page: 0,
      size: 20,
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await getEmployees(0, 20, {
      companyId: 'company-1',
      query: 'Alice',
      sort: 'employeeNumber',
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/employees?page=0&size=20&query=Alice&companyId=company-1&sort=employeeNumber',
    );
  });
});

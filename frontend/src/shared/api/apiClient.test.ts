import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError, apiResponseMetadata, requestJson } from './apiClient';

describe('API network failure handling', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('marks status-zero network errors as retryable', () => {
    const error = new ApiRequestError(0, { code: 'NETWORK_REQUEST_FAILED' });

    expect(error.retryable).toBe(true);
  });

  it('wraps a fetch network failure in a retryable ApiRequestError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    );

    const request = requestJson('/api/v1/me/capabilities');

    await expect(request).rejects.toBeInstanceOf(ApiRequestError);
    await expect(request).rejects.toMatchObject({
      status: 0,
      retryable: true,
    });
  });

  it('wraps an invalid successful response body in a retryable API error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('not-json', { status: 200 })),
    );

    const request = requestJson('/api/v1/me/capabilities');

    await expect(request).rejects.toBeInstanceOf(ApiRequestError);
    await expect(request).rejects.toMatchObject({
      status: 200,
      code: 'INVALID_RESPONSE_BODY',
      retryable: true,
    });
  });

  it('does not expose server error messages or technical validation details', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({
        code: 'VALIDATION_FAILED',
        correlationId: 'request-422-safe',
        retryable: false,
        message: '数据库约束 employee_id 失败，rowVersion=12',
        fieldErrors: [
          {
            field: 'effectiveFrom',
            code: 'FUTURE_REQUIRED',
            message: '生效日必须在未来',
          },
          {
            field: 'employeeId',
            code: 'REFERENCE_INVALID',
            message: 'employee_id=9b4ecb3d-2a44-48d5-9538-15f14b232691',
          },
        ],
      }), {
        status: 422,
        headers: { 'Content-Type': 'application/json' },
      })),
    );

    const caught = await requestJson('/api/v1/policy-templates')
      .catch((error: unknown) => error);

    expect(caught).toBeInstanceOf(ApiRequestError);
    expect(caught).toMatchObject({
      status: 422,
      code: 'VALIDATION_FAILED',
      correlationId: 'request-422-safe',
      retryable: false,
      message: '填写内容未通过校验，请检查后重试。',
      fieldErrors: [
        {
          field: 'effectiveFrom',
          code: 'FUTURE_REQUIRED',
          message: '生效日必须在未来',
        },
        {
          field: 'employeeId',
          code: 'REFERENCE_INVALID',
          message: '请检查相关填写内容。',
        },
      ],
    });
    expect(JSON.stringify(caught)).not.toContain('9b4ecb3d');
    expect((caught as Error).message).not.toContain('数据库约束');
  });

  it('keeps explicitly authored client-side messages unchanged', () => {
    const error = new ApiRequestError(400, {
      code: 'INVALID_SELECTION',
      message: '请选择有效的公司后重试。',
      retryable: false,
    });

    expect(error.message).toBe('请选择有效的公司后重试。');
  });

  it('captures the CSRF response header in memory and sends it on a write request', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ authenticated: true }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'synthetic-csrf-token-with-safe-test-length',
        },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accepted: true }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));
    vi.stubGlobal('fetch', fetchMock);

    await requestJson('/api/v1/auth/session');
    await requestJson('/api/v1/policy-templates', {
      method: 'POST',
      body: JSON.stringify({ templateCode: 'SYNTHETIC_POLICY' }),
    });

    const secondRequest = fetchMock.mock.calls[1] as [string, RequestInit];
    const headers = new Headers(secondRequest[1].headers);
    expect(headers.get('X-CSRF-TOKEN'))
      .toBe('synthetic-csrf-token-with-safe-test-length');
    expect(secondRequest[1].credentials).toBe('same-origin');
  });

  it('treats a successful 204 mutation as an empty response instead of invalid JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );

    await expect(requestJson<void>('/api/v1/auth/logout', { method: 'POST' }))
      .resolves.toBeUndefined();
  });

  it.each([
    ['true', true],
    ['false', false],
  ] as const)('exposes only the trusted idempotency replay response header (%s)', async (
    replayedHeader,
    expected,
  ) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ accepted: true }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Replayed': replayedHeader,
        },
      })),
    );

    const result = await requestJson<{ accepted: boolean }>(
      '/api/v1/policy-templates',
      { method: 'POST', body: '{}' },
    );

    expect(result).toEqual({ accepted: true });
    expect(apiResponseMetadata(result)).toEqual({
      idempotencyReplayed: expected,
    });
    expect(Object.keys(result)).toEqual(['accepted']);
  });

  it('does not infer replay metadata from an invalid or absent response header', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ replayed: true }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Replayed': 'TRUE',
        },
      })),
    );

    const result = await requestJson<{ replayed: boolean }>(
      '/api/v1/policy-templates',
      { method: 'POST', body: '{}' },
    );

    expect(apiResponseMetadata(result)).toEqual({});
  });

  it.each([
    '/api/v1/../admin',
    '/api/v1/%2e%2e/admin',
    '/api/v1/%2E%2E/admin',
    String.raw`/api/v1/\..\admin`,
    'https://attacker.example/api/v1/me/capabilities',
    '//attacker.example/api/v1/me/capabilities',
  ])('rejects an API target that escapes the canonical /api/v1/ boundary: %s', async (path) => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(requestJson(path)).rejects.toMatchObject({
      code: 'INVALID_API_TARGET',
      retryable: false,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

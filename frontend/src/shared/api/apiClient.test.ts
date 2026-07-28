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

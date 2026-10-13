import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import type { CurrentCapabilities } from './sessionApi';
import { SESSION_TOUCH_INTERVAL_MS, useSession } from './useSession';

const sessionApi = vi.hoisted(() => ({
  getCurrentSession: vi.fn(),
}));

vi.mock('./sessionApi', () => ({
  getCurrentSession: sessionApi.getCurrentSession,
}));

const readySession: CurrentCapabilities = {
  capabilities: ['MASTER_DATA:READ'],
  menu: [{ key: 'organization', label: '组织架构', path: '/organization' }],
};

describe('useSession', () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();
  });

  it('keeps the loading state until the capability request resolves', async () => {
    let resolveRequest: ((session: CurrentCapabilities) => void) | undefined;
    sessionApi.getCurrentSession.mockReturnValue(new Promise((resolve) => {
      resolveRequest = resolve;
    }));

    const { result } = renderHook(() => useSession());

    expect(result.current.state).toEqual({ status: 'loading' });

    await act(async () => {
      resolveRequest?.(readySession);
    });

    expect(result.current.state).toEqual({
      status: 'ready',
      session: readySession,
    });
  });

  it('preserves a 401 as a non-retryable session error', async () => {
    sessionApi.getCurrentSession.mockRejectedValue(
      new ApiRequestError(401, {
        code: 'AUTHENTICATION_REQUIRED',
        correlationId: 'request-401',
        retryable: false,
      }),
    );

    const { result } = renderHook(() => useSession());

    await waitFor(() => expect(result.current.state.status).toBe('error'));
    expect(result.current.state).toMatchObject({
      status: 'error',
      error: {
        status: 401,
        code: 'AUTHENTICATION_REQUIRED',
        correlationId: 'request-401',
        retryable: false,
      },
    });
  });

  it('touches the session while the tab stays open', async () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');
    sessionApi.getCurrentSession.mockResolvedValue(readySession);
    const { result, unmount } = renderHook(() => useSession());
    await waitFor(() => expect(result.current.state.status).toBe('ready'));
    expect(sessionApi.getCurrentSession).toHaveBeenCalledTimes(1);

    const tick = setIntervalSpy.mock.calls.find(
      (call) => call[1] === SESSION_TOUCH_INTERVAL_MS,
    )?.[0];
    expect(tick).toEqual(expect.any(Function));
    await act(async () => {
      (tick as () => void)();
    });
    expect(sessionApi.getCurrentSession).toHaveBeenCalledTimes(2);
    unmount();
    setIntervalSpy.mockRestore();
  });

  it('marks an unexpected API failure as retryable and reloads cleanly', async () => {
    sessionApi.getCurrentSession
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(readySession);

    const { result } = renderHook(() => useSession());

    await waitFor(() => expect(result.current.state.status).toBe('error'));
    expect(result.current.state).toMatchObject({
      status: 'error',
      error: {
        status: 0,
        code: 'SESSION_UNAVAILABLE',
        retryable: true,
      },
    });

    act(() => result.current.reload());

    await waitFor(() => expect(result.current.state.status).toBe('ready'));
    expect(result.current.state).toEqual({
      status: 'ready',
      session: readySession,
    });
    expect(sessionApi.getCurrentSession).toHaveBeenCalledTimes(2);
  });
});

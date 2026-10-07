import { useEffect, useState } from 'react';

import { ApiRequestError } from '../../shared/api/apiClient';
import { getCurrentSession, type SessionView } from './sessionApi';

export type SessionState =
  | { status: 'loading' }
  | { status: 'ready'; session: SessionView }
  | { status: 'error'; error: ApiRequestError };

/** Keep an open tab from going idle while the person is still looking at it. */
export const SESSION_TOUCH_INTERVAL_MS = 10 * 60 * 1000;

export function useSession(): { state: SessionState; reload: () => void } {
  const [state, setState] = useState<SessionState>({ status: 'loading' });
  const [reloadToken, setReloadToken] = useState(0);

  const reload = () => {
    setReloadToken((current) => current + 1);
  };

  useEffect(() => {
    setState({ status: 'loading' });
    void loadSession(setState);
  }, [reloadToken]);

  useEffect(() => {
    if (state.status !== 'ready') {
      return undefined;
    }
    const touch = () => {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        return;
      }
      void getCurrentSession().catch((error: unknown) => {
        if (
          error instanceof ApiRequestError
          && (error.status === 401 || error.status === 403 || error.status === 404)
        ) {
          setReloadToken((current) => current + 1);
        }
      });
    };
    const id = window.setInterval(touch, SESSION_TOUCH_INTERVAL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        touch();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(id);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [state.status]);

  return { state, reload };
}

async function loadSession(
  setState: (state: SessionState) => void,
): Promise<void> {
  try {
    // The session endpoint restores both authorization data and the CSRF token
    // after a hard reload or a directly opened protected route.
    const session = await getCurrentSession();
    setState({ status: 'ready', session });
  } catch (error: unknown) {
    const safeError = error instanceof ApiRequestError
      ? new ApiRequestError(error.status, {
        code: error.code,
        correlationId: error.correlationId,
        retryable: error.retryable,
      })
      : new ApiRequestError(0, { code: 'SESSION_UNAVAILABLE' });
    setState({ status: 'error', error: safeError });
  }
}

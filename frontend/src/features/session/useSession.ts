import { useEffect, useState } from 'react';

import { ApiRequestError } from '../../shared/api/apiClient';
import { getCurrentSession, type SessionView } from './sessionApi';

export type SessionState =
  | { status: 'loading' }
  | { status: 'ready'; session: SessionView }
  | { status: 'error'; error: ApiRequestError };

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

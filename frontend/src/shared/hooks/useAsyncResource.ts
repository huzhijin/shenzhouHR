import { useEffect, useState } from 'react';

import { ApiRequestError } from '../api/apiClient';

export type AsyncResource<T> =
  | { status: 'loading' | 'partial-loading' }
  | { status: 'ready'; data: T; stale?: boolean }
  | { status: 'empty' }
  | { status: 'error' | 'network-error' | '401' | '403' | '404' | 'conflict' | 'validation-error'; error: ApiRequestError };

export function useAsyncResource<T>(
  loader: () => Promise<T>,
  isEmpty: (value: T) => boolean = () => false,
  dependencies: ReadonlyArray<unknown> = [],
): { resource: AsyncResource<T>; reload: () => void } {
  const [reloadToken, setReloadToken] = useState(0);
  const [resource, setResource] = useState<AsyncResource<T>>({ status: 'loading' });
  const reload = () => setReloadToken((value) => value + 1);

  useEffect(() => {
    let cancelled = false;
    setResource((previous) => previous.status === 'ready'
      ? { status: 'partial-loading' }
      : { status: 'loading' });

    const load = async () => {
      try {
        const data = await loader();
        if (!cancelled) {
          setResource(isEmpty(data) ? { status: 'empty' } : { status: 'ready', data });
        }
      } catch (caught: unknown) {
        if (cancelled) return;
        const error = caught instanceof ApiRequestError
          ? caught
          : new ApiRequestError(0, { code: 'NETWORK_REQUEST_FAILED', retryable: true });
        setResource({ status: errorStatus(error), error });
      }
    };

    void load();
    return () => {
      cancelled = true;
    };
  }, [reloadToken, ...dependencies]);

  return { resource, reload };
}

function errorStatus(error: ApiRequestError): Extract<AsyncResource<never>, { error: ApiRequestError }>['status'] {
  if (error.status === 0) return 'network-error';
  if (error.status === 401) return '401';
  if (error.status === 403) return '403';
  if (error.status === 404) return '404';
  if (error.status === 409 || error.status === 412) return 'conflict';
  if (error.status === 422) return 'validation-error';
  return 'error';
}

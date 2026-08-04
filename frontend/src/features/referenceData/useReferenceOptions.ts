import { useEffect, useState } from 'react';

import type { ReferenceOption } from './referenceDataLabels';

export type ReferenceOptionsState =
  | { status: 'idle' | 'loading'; options: ReferenceOption[] }
  | { status: 'ready'; options: ReferenceOption[] }
  | { status: 'error'; options: ReferenceOption[] };

export function useReferenceOptions(
  loader: () => Promise<ReferenceOption[]>,
  dependencies: readonly unknown[],
  enabled = true,
): ReferenceOptionsState {
  const [state, setState] = useState<ReferenceOptionsState>({
    status: enabled ? 'loading' : 'idle',
    options: [],
  });

  useEffect(() => {
    let active = true;
    if (!enabled) {
      setState({ status: 'idle', options: [] });
      return () => {
        active = false;
      };
    }

    setState((current) => ({ status: 'loading', options: current.options }));
    void loader()
      .then((options) => {
        if (active) setState({ status: 'ready', options });
      })
      .catch(() => {
        if (active) setState({ status: 'error', options: [] });
      });

    return () => {
      active = false;
    };
  }, [enabled, ...dependencies]);

  return state;
}

export function useDebouncedValue(value: string, delay = 250): string {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(timer);
  }, [delay, value]);

  return debounced;
}

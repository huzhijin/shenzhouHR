import {
  createContext,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

import {
  applyAppearance,
  persistAppearance,
  readStoredAppearance,
  type Appearance,
} from './appearance';

const AppearanceContext = createContext<{
  appearance: Appearance;
  setAppearance: (appearance: Appearance) => void;
}>({
  appearance: 'day',
  setAppearance: () => undefined,
});

export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [appearance, setAppearanceState] = useState<Appearance>(() => {
    const initial = readStoredAppearance();
    applyAppearance(initial);
    return initial;
  });
  const setAppearance = (next: Appearance) => {
    persistAppearance(next);
    setAppearanceState(next);
  };
  const value = useMemo(
    () => ({ appearance, setAppearance }),
    [appearance],
  );
  return (
    <AppearanceContext.Provider value={value}>
      {children}
    </AppearanceContext.Provider>
  );
}

export function useAppearance() {
  return useContext(AppearanceContext);
}

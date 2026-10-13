export const DEMO_USERNAME = 'demo.admin';
export const DEMO_PASSWORD = 'Demo@2026';

const DEMO_SESSION_STORAGE_KEY = 'shenzhou-hr.demo-authenticated';
const AUTHENTICATED_VALUE = 'authenticated';

let memoryAuthenticated = false;

export function isDemoAuthenticated(): boolean {
  const storage = readSessionStorage();
  if (!storage) {
    return memoryAuthenticated;
  }
  try {
    return storage.getItem(DEMO_SESSION_STORAGE_KEY) === AUTHENTICATED_VALUE;
  } catch {
    return memoryAuthenticated;
  }
}

export function authenticateDemo(username: string, password: string): boolean {
  if (username.trim() !== DEMO_USERNAME || password !== DEMO_PASSWORD) {
    return false;
  }

  memoryAuthenticated = true;
  const storage = readSessionStorage();
  try {
    storage?.setItem(DEMO_SESSION_STORAGE_KEY, AUTHENTICATED_VALUE);
  } catch {
    // Sandboxed demo previews can deny Web Storage. The in-memory session still
    // keeps the current page usable without weakening the production path.
  }
  return true;
}

export function clearDemoAuthentication(): void {
  memoryAuthenticated = false;
  const storage = readSessionStorage();
  try {
    storage?.removeItem(DEMO_SESSION_STORAGE_KEY);
  } catch {
    // See authenticateDemo: an in-memory fallback is sufficient for demo mode.
  }
}

function readSessionStorage(): Storage | undefined {
  try {
    return typeof window === 'undefined' ? undefined : window.sessionStorage;
  } catch {
    return undefined;
  }
}

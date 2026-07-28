export function isDemoMode(): boolean {
  return import.meta.env.MODE === 'demo';
}

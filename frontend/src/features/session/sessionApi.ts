import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { getDemoSession } from './demoSession';

export interface MenuItem {
  key: string;
  label: string;
  path: string;
}

export interface CurrentCapabilities {
  capabilities: string[];
  menu: MenuItem[];
}

export function getCurrentCapabilities(): Promise<CurrentCapabilities> {
  if (isDemoMode()) {
    return Promise.resolve(getDemoSession());
  }
  return requestJson<CurrentCapabilities>('/api/v1/me/capabilities');
}

export interface SessionView extends CurrentCapabilities {
  sessionId: string;
  accountId: string;
  username: string;
  displayName: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  firstPasswordChangeRequired: boolean;
  issuedAt: string;
  lastSeenAt?: string;
  idleExpiresAt: string;
  absoluteExpiresAt: string;
}

export function getCurrentSession(): Promise<SessionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...getDemoSession(),
      sessionId: 'demo-session',
      accountId: 'demo-account',
      username: 'synthetic.admin',
      displayName: '合成系统管理员',
      status: 'ACTIVE',
      firstPasswordChangeRequired: false,
      issuedAt: '2026-07-24T00:00:00Z',
      idleExpiresAt: '2026-07-24T08:00:00Z',
      absoluteExpiresAt: '2026-07-25T00:00:00Z',
    });
  }
  return requestJson<SessionView>('/api/v1/auth/session');
}

export function logout(): Promise<void> {
  if (isDemoMode()) {
    return Promise.resolve();
  }
  return requestJson<void>('/api/v1/auth/logout', { method: 'POST' });
}

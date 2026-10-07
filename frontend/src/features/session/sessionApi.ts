import { ApiRequestError, requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  clearDemoAuthentication,
  isDemoAuthenticated,
} from './demoAuthSession';
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
    if (!isDemoAuthenticated()) {
      return Promise.reject(demoAuthenticationRequired());
    }
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
    if (!isDemoAuthenticated()) {
      return Promise.reject(demoAuthenticationRequired());
    }
    return Promise.resolve({
      ...getDemoSession(),
      sessionId: 'demo-session',
      accountId: 'demo-account',
      username: 'demo.admin',
      displayName: '演示系统管理员',
      status: 'ACTIVE',
      firstPasswordChangeRequired: false,
      issuedAt: '2026-07-28T00:00:00Z',
      lastSeenAt: new Date().toISOString(),
      idleExpiresAt: '2099-12-31T23:00:00Z',
      absoluteExpiresAt: '2099-12-31T23:59:59Z',
    });
  }
  return requestJson<SessionView>('/api/v1/auth/session');
}

export function logout(): Promise<void> {
  if (isDemoMode()) {
    clearDemoAuthentication();
    return Promise.resolve();
  }
  return requestJson<void>('/api/v1/auth/logout', { method: 'POST' });
}

function demoAuthenticationRequired(): ApiRequestError {
  return new ApiRequestError(401, {
    code: 'AUTHENTICATION_REQUIRED',
    retryable: false,
  });
}

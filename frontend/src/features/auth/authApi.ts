import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import type { SessionView } from '../session/sessionApi';

export type LoginResult =
  | SessionView
  | { code: 'FIRST_PASSWORD_CHANGE_REQUIRED'; resetGrantId?: string };

export async function login(username: string, password: string): Promise<LoginResult> {
  if (isDemoMode()) {
    return {
      sessionId: 'demo-session',
      accountId: 'demo-account',
      username,
      displayName: '合成系统管理员',
      status: 'ACTIVE',
      firstPasswordChangeRequired: false,
      issuedAt: '2026-07-24T00:00:00Z',
      idleExpiresAt: '2026-07-24T08:00:00Z',
      absoluteExpiresAt: '2026-07-25T00:00:00Z',
      capabilities: ['POLICY:READ', 'ACCOUNT:READ', 'ROLE:READ', 'AUDIT:READ'],
      menu: [],
    };
  }
  return requestJson<LoginResult>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

export function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>('/api/v1/auth/password/change', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export function completeFirstPasswordChange(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>('/api/v1/auth/password/first-change', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export function requestPasswordReset(username: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>('/api/v1/auth/password-reset-requests', {
    method: 'POST',
    body: JSON.stringify({ username }),
  });
}

export function completePasswordReset(grant: string, newPassword: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>('/api/v1/auth/password-resets', {
    method: 'POST',
    body: JSON.stringify({ grant, newPassword }),
  });
}

export function revokeSession(sessionId: string, reason: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`/api/v1/auth/sessions/${encodeURIComponent(sessionId)}/revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiRequestError } from '../../shared/api/apiClient';
import { login } from '../auth/authApi';
import {
  clearDemoAuthentication,
  DEMO_PASSWORD,
  DEMO_USERNAME,
} from './demoAuthSession';
import {
  getCurrentCapabilities,
  getCurrentSession,
  logout,
} from './sessionApi';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

describe('demo authentication session', () => {
  beforeEach(() => {
    clearDemoAuthentication();
  });

  afterEach(() => {
    clearDemoAuthentication();
    vi.restoreAllMocks();
  });

  it('starts unauthenticated and rejects a wrong demo password', async () => {
    await expect(getCurrentSession()).rejects.toMatchObject({
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
      retryable: false,
    });

    await expect(login(DEMO_USERNAME, 'wrong-password')).rejects.toEqual(
      expect.objectContaining<Partial<ApiRequestError>>({
        status: 401,
        code: 'INVALID_CREDENTIALS',
        retryable: false,
      }),
    );
  });

  it('opens the complete demo menu after login and returns to login after logout', async () => {
    const session = await login(DEMO_USERNAME, DEMO_PASSWORD);
    expect('code' in session).toBe(false);
    expect(session).toMatchObject({
      username: DEMO_USERNAME,
      displayName: '演示系统管理员',
      firstPasswordChangeRequired: false,
    });

    const capabilities = await getCurrentCapabilities();
    expect(capabilities.capabilities).toEqual(expect.arrayContaining([
      'ATTENDANCE_DASHBOARD:READ',
      'ATTENDANCE_REPORT:READ',
      'ATTENDANCE_SELF:READ',
      'LEAVE_SELF:READ',
    ]));
    expect(capabilities.menu.slice(0, 3)).toEqual([
      { key: 'workbench', label: '考勤工作台', path: '/workbench' },
      { key: 'attendance-screen', label: '考勤大屏', path: '/attendance/screen' },
      { key: 'attendance-reports', label: '统计报表', path: '/attendance/reports' },
    ]);

    await logout();
    await expect(getCurrentSession()).rejects.toMatchObject({
      status: 401,
      code: 'AUTHENTICATION_REQUIRED',
    });
  });
});

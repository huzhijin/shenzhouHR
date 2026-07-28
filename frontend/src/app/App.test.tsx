import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../shared/api/apiClient';
import { translate } from '../shared/i18n/messages';
import { App } from './App';

const sessionHook = vi.hoisted(() => ({
  useSession: vi.fn(),
}));

vi.mock('../features/session/useSession', () => ({
  useSession: sessionHook.useSession,
}));

vi.mock('../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

describe('App session and route authorization', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the loading state while the session is unresolved', () => {
    sessionHook.useSession.mockReturnValue({
      state: { status: 'loading' },
      reload: vi.fn(),
    });

    renderApp();

    expect(screen.getByLabelText('正在加载')).toBeInTheDocument();
  });

  it('redirects an unauthenticated request to /login without offering a retry action', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'error',
        error: new ApiRequestError(401, {
          code: 'AUTHENTICATION_REQUIRED',
          retryable: false,
        }),
      },
      reload: vi.fn(),
    });

    renderApp('/rules/templates');

    expect(screen.getByTestId('current-location')).toHaveTextContent('/login');
    expect(screen.getByRole('textbox', { name: '用户名' })).toBeInTheDocument();
    expect(screen.getByLabelText('密码')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
  });

  it('connects a retryable session failure to reload', () => {
    const reload = vi.fn();
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'error',
        error: new ApiRequestError(503, {
          code: 'SESSION_UNAVAILABLE',
          retryable: true,
        }),
      },
      reload,
    });

    renderApp();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(reload).toHaveBeenCalledOnce();
  });

  it('returns a first-change session to login instead of rendering protected routes', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: [],
          menu: [],
          firstPasswordChangeRequired: true,
        },
      },
      reload: vi.fn(),
    });

    renderApp('/rules/templates');

    expect(screen.getByTestId('current-location')).toHaveTextContent('/login');
    expect(screen.getByRole('textbox', { name: '用户名' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '规则中心' })).not.toBeInTheDocument();
  });

  it('shows an explicit unauthorized state for an empty menu', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: { capabilities: [], menu: [] },
      },
      reload: vi.fn(),
    });

    renderApp('/employees');

    expect(screen.getByText('当前账号没有已授权的功能菜单。'))
      .toBeInTheDocument();
  });

  it.each(['/', '/login'])(
    'shows the no-menu state at %s for an authenticated session with an empty menu',
    (path) => {
      sessionHook.useSession.mockReturnValue({
        state: {
          status: 'ready',
          session: { capabilities: [], menu: [] },
        },
        reload: vi.fn(),
      });

      renderApp(path);

      expect(screen.getByText('当前账号没有已授权的功能菜单。'))
        .toBeInTheDocument();
      expect(screen.getByTestId('current-location')).toHaveTextContent(path);
    },
  );

  it.each(['/', '/login'])(
    'redirects %s to the first capability-backed menu route',
    async (path) => {
      sessionHook.useSession.mockReturnValue({
        state: {
          status: 'ready',
          session: {
            capabilities: ['AUDIT:READ'],
            menu: [{ key: 'audit', label: '审计事件', path: '/access/audit' }],
          },
        },
        reload: vi.fn(),
      });

      renderApp(path);

      await waitFor(() => {
        expect(screen.getByTestId('current-location')).toHaveTextContent('/access/audit');
      });
    },
  );

  it('does not trust a server menu entry when its capability is absent', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: [],
          menu: [{ key: 'employees', label: '人员主数据', path: '/employees' }],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/employees');

    expect(screen.getByText('当前账号没有已授权的功能菜单。'))
      .toBeInTheDocument();
  });

  it('renders an explicit 403 for a known route outside the current capability set', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['AUDIT:READ'],
          menu: [{ key: 'audit', label: '审计事件', path: '/access/audit' }],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/access/accounts');

    expect(screen.getByRole('heading', { name: translate('state.forbiddenTitle') }))
      .toBeInTheDocument();
    expect(screen.getByText(translate('state.forbiddenDescription'))).toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent('/access/accounts');
  });

  it('renders a true 404 without redirecting an unknown route', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['AUDIT:READ'],
          menu: [{ key: 'audit', label: '审计事件', path: '/access/audit' }],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/not-a-real-route');

    expect(screen.getByRole('heading', { name: translate('state.notFound') }))
      .toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent('/not-a-real-route');
  });

  it('renders an explicit 403 for a direct WAVE-3 route without read capability', () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['AUDIT:READ'],
          menu: [{ key: 'audit', label: '审计事件', path: '/access/audit' }],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/rules/attendance-policy/version-1');

    expect(screen.getByRole('heading', { name: translate('state.forbiddenTitle') }))
      .toBeInTheDocument();
    expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/rules/attendance-policy/version-1');
  });

  it.each([
    '/rules/attendance-groups',
    '/rules/shifts',
    '/rules/calendars',
    '/rules/attendance-policy/version-1',
  ])('returns the same client 403 for known WAVE-3 route %s without read capability', (path) => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['AUDIT:READ'],
          menu: [{ key: 'audit', label: '审计事件', path: '/access/audit' }],
        },
      },
      reload: vi.fn(),
    });

    renderApp(path);

    expect(screen.getByRole('heading', { name: translate('state.forbiddenTitle') }))
      .toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent(path);
  });

  it.each([
    ['/rules/attendance-groups', '考勤组与人员归属'],
    ['/rules/shifts', '班次版本'],
    ['/rules/calendars', '工作日历'],
    ['/rules/attendance-policy', '考勤基础策略'],
  ])(
    'allows capability-backed direct WAVE-3 route %s even when the server menu is empty',
    async (path, heading) => {
      sessionHook.useSession.mockReturnValue({
        state: {
          status: 'ready',
          session: {
            capabilities: ['ATTENDANCE_SETUP:READ'],
            menu: [],
          },
        },
        reload: vi.fn(),
      });

      renderApp(path);

      expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: translate('state.forbiddenTitle') }))
        .not.toBeInTheDocument();
      expect(screen.queryByText('当前账号没有已授权的功能菜单。'))
        .not.toBeInTheDocument();
    },
  );

  it.each([
    ['HR_ADMIN', [
      'ATTENDANCE_SETUP:READ',
      'ATTENDANCE_SETUP:MANAGE_GROUP',
      'ATTENDANCE_SETUP:ASSIGN',
      'ATTENDANCE_SETUP:MANAGE_SHIFT',
      'ATTENDANCE_SETUP:MANAGE_CALENDAR',
      'ATTENDANCE_SETUP:MANAGE_POLICY',
    ]],
    ['AUDITOR', ['ATTENDANCE_SETUP:READ', 'AUDIT:READ']],
  ])(
    'redirects /rules to the WAVE-3 read route for the real %s capability set',
    async (_role, capabilities) => {
      sessionHook.useSession.mockReturnValue({
        state: {
          status: 'ready',
          session: {
            capabilities,
            menu: [{
              key: 'attendance-groups',
              label: '考勤组',
              path: '/rules/attendance-groups',
            }],
          },
        },
        reload: vi.fn(),
      });

      renderApp('/rules');

      await waitFor(() => {
        expect(screen.getByTestId('current-location'))
          .toHaveTextContent('/rules/attendance-groups');
      });
      expect(await screen.findByRole('heading', { name: '考勤组与人员归属' }))
        .toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: translate('state.forbiddenTitle') }))
        .not.toBeInTheDocument();
    },
  );

  it('keeps /rules on the generic rules home when SYSTEM_ADMIN has POLICY:READ', async () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['POLICY:READ', 'ATTENDANCE_SETUP:READ'],
          menu: [
            { key: 'rules', label: '规则中心', path: '/rules' },
            {
              key: 'attendance-groups',
              label: '考勤组',
              path: '/rules/attendance-groups',
            },
          ],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/rules');

    expect(await screen.findByRole('heading', { name: '规则中心' }))
      .toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent('/rules');
  });
});

function renderApp(initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="current-location">{location.pathname}</output>;
}

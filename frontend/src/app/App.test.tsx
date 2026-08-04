import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../shared/api/apiClient';
import { translate } from '../shared/i18n/messages';
import {
  reportFixture,
  selfDashboardFixture,
} from '../test/fixtures/wave7ContractFixtures';
import { App } from './App';

const sessionHook = vi.hoisted(() => ({
  useSession: vi.fn(),
}));

const runtimeMode = vi.hoisted(() => ({
  isDemoMode: vi.fn(() => false),
}));

vi.mock('../features/session/useSession', () => ({
  useSession: sessionHook.useSession,
}));

vi.mock('../shared/config/runtimeMode', () => ({
  isDemoMode: runtimeMode.isDemoMode,
}));

describe('App session and route authorization', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    runtimeMode.isDemoMode.mockReturnValue(false);
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

  it.each([
    [403, 'SESSION_REVOKED'],
    [404, 'SESSION_NOT_FOUND'],
  ])(
    'returns a session restoration failure (%s) to the login form',
    (status, code) => {
      sessionHook.useSession.mockReturnValue({
        state: {
          status: 'error',
          error: new ApiRequestError(status, {
            code,
            retryable: false,
          }),
        },
        reload: vi.fn(),
      });

      renderApp('/workbench');

      expect(screen.getByTestId('current-location')).toHaveTextContent('/login');
      expect(screen.getByRole('textbox', { name: '用户名' })).toBeInTheDocument();
      expect(screen.getByLabelText('密码')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: translate('error.requestFailed') }))
        .not.toBeInTheDocument();
    },
  );

  it('connects a retryable session failure to reload', () => {
    const reload = vi.fn();
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'error',
        error: new ApiRequestError(503, {
          code: 'SESSION_UNAVAILABLE',
          correlationId: 'session-request-internal-503',
          retryable: true,
        }),
      },
      reload,
    });

    renderApp();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(reload).toHaveBeenCalledOnce();
    expect(screen.queryByText(/session-request-internal-503/)).not.toBeInTheDocument();
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
    expect(screen.queryByRole('heading', { name: translate('rules.title') }))
      .not.toBeInTheDocument();
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

      await waitFor(
        () => {
          expect(screen.getByTestId('current-location')).toHaveTextContent('/access/audit');
        },
        { timeout: 5_000 },
      );
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

      expect(await screen.findByRole(
        'heading',
        { name: heading },
        { timeout: 5_000 },
      )).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: translate('state.forbiddenTitle') }))
        .not.toBeInTheDocument();
      expect(screen.queryByText('当前账号没有已授权的功能菜单。'))
      .not.toBeInTheDocument();
    },
  );

  it.each([
    ['/workbench', 'ATTENDANCE_DASHBOARD:READ'],
    ['/attendance/screen', 'ATTENDANCE_DASHBOARD:READ'],
    ['/attendance/reports', 'ATTENDANCE_REPORT:READ'],
    ['/me/today', 'ATTENDANCE_SELF:READ'],
    ['/me/records', 'ATTENDANCE_SELF:READ'],
    ['/me/leave', 'LEAVE_SELF:READ'],
    ['/me/feedback', 'ATTENDANCE_FEEDBACK:READ'],
  ])('returns a client 403 for direct WAVE-7 route %s without %s', (path) => {
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
    ['/me/leave', 'LEAVE_SELF:READ'],
    ['/me/feedback', 'ATTENDANCE_FEEDBACK:READ'],
  ])('fails closed at authorized WAVE-7 direct route %s until upstream wiring is available', async (path, capability) => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: [capability],
          menu: [],
        },
      },
      reload: vi.fn(),
    });

    renderApp(path);

    expect(await screen.findByText(
      '该功能的数据尚未准备好，请稍后再试。',
      {},
      { timeout: 5_000 },
    ))
      .toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: translate('state.forbiddenTitle') }))
      .not.toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent(path);
  });

  it('loads the self dashboard API on the authorized employee home', async () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['ATTENDANCE_SELF:READ'],
          menu: [],
        },
      },
      reload: vi.fn(),
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify(selfDashboardFixture),
      {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      },
    ));
    vi.stubGlobal('fetch', fetchMock);

    renderApp('/me/today');

    expect(await screen.findByRole(
      'heading',
      { level: 1, name: '我的考勤工作台' },
      { timeout: 5_000 },
    )).toBeInTheDocument();
    expect(screen.getByLabelText('本人考勤关键指标'))
      .toHaveTextContent('待处理异常');
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0])
      .toBe('/api/v1/me/attendance-dashboard');
    expect(screen.queryByRole('heading', {
      name: translate('state.forbiddenTitle'),
    })).not.toBeInTheDocument();
    expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/me/today');
  });

  it('lands a personal account on the strictly self-scoped /workbench dashboard', async () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['ATTENDANCE_SELF:READ'],
          menu: [{
            key: 'personal-workbench',
            label: '我的考勤工作台',
            path: '/workbench',
          }],
        },
      },
      reload: vi.fn(),
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify(selfDashboardFixture),
      {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      },
    ));
    vi.stubGlobal('fetch', fetchMock);

    renderApp('/');

    expect(await screen.findByRole(
      'heading',
      { level: 1, name: '我的考勤工作台' },
      { timeout: 5_000 },
    )).toBeInTheDocument();
    expect(screen.getByText('个人专属 · 仅本人可见')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '今日异常考勤列表' }))
      .not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0])
      .toBe('/api/v1/me/attendance-dashboard');
    expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/workbench');
  });

  it('loads the daily attendance dashboard API on the authorized workbench', async () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: [
            'ATTENDANCE_DASHBOARD:READ',
            'ATTENDANCE_SELF:READ',
          ],
          menu: [],
        },
      },
      reload: vi.fn(),
    });
    const fetchMock = vi.fn(
      async (input: RequestInfo | URL) => {
        void input;
        return new Response(
          JSON.stringify(attendanceDashboardResponse()),
          {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          },
        );
      },
    );
    vi.stubGlobal('fetch', fetchMock);

    renderApp('/workbench');

    expect(await screen.findByRole(
      'heading',
      { level: 1, name: '今日异常考勤' },
      { timeout: 5_000 },
    )).toBeInTheDocument();
    expect(screen.getByLabelText('今日异常汇总指标'))
      .toHaveTextContent('未处理异常');
    expect(screen.getByRole('region', { name: '今日异常考勤列表' }))
      .toHaveTextContent('张三');
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls[0]?.[0])
      .toBe('/api/v1/attendance-dashboards');
    expect(screen.queryByRole('heading', {
      name: translate('state.forbiddenTitle'),
    })).not.toBeInTheDocument();
    expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/workbench');
  });

  it('loads the formal attendance report API on the authorized production route', async () => {
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['ATTENDANCE_REPORT:READ'],
          menu: [],
        },
      },
      reload: vi.fn(),
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const target = new URL(String(input), window.location.origin);
      if (target.pathname === '/api/v1/attendance-reports/companies') {
        return new Response(JSON.stringify({
          period: target.searchParams.get('period'),
          companies: [{
            companyId: reportFixture.filters.companyId,
            companyName: '神州半导体',
          }],
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      const reportType = target.searchParams.get('reportType')!;
      const period = target.searchParams.get('period')!;
      const companyId = target.searchParams.get('companyId')!;
      const page = Number(target.searchParams.get('page'));
      const size = Number(target.searchParams.get('size'));
      return new Response(JSON.stringify({
        ...reportFixture,
        metadata: {
          ...reportFixture.metadata,
          periodLabel: period,
        },
        reportType,
        formulaVersion: `${reportType}_FORMULA_V1`,
        filters: {
          ...reportFixture.filters,
          period,
          companyId,
        },
        page,
        size,
        totalPages: 1,
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    renderApp('/attendance/reports');

    expect(await screen.findByRole(
      'heading',
      { name: reportFixture.reportTitle },
      { timeout: 5_000 },
    )).toBeInTheDocument();
    expect(screen.queryByText('ATTENDANCE_DETAIL_FORMULA_V1'))
      .not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('heading', { name: translate('state.forbiddenTitle') }))
      .not.toBeInTheDocument();
    expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/attendance/reports');
  });

  it('renders the customer report center for an authorized demo session', async () => {
    runtimeMode.isDemoMode.mockReturnValue(true);
    sessionHook.useSession.mockReturnValue({
      state: {
        status: 'ready',
        session: {
          capabilities: ['ATTENDANCE_REPORT:READ'],
          menu: [{
            key: 'reports',
            label: '统计报表',
            path: '/attendance/reports',
          }],
        },
      },
      reload: vi.fn(),
    });

    renderApp('/attendance/reports');

    expect(await screen.findByRole(
      'heading',
      { name: '考勤报表中心' },
      { timeout: 5_000 },
    ))
      .toBeInTheDocument();
    expect(screen.getByTestId('current-location')).toHaveTextContent('/attendance/reports');
  });

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
            { key: 'rules', label: '规则设置', path: '/rules' },
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

    expect(await screen.findByRole('heading', { name: translate('rules.title') }))
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

function attendanceDashboardResponse() {
  return {
    kind: 'DASHBOARD',
    title: '今日异常考勤',
    businessDate: '2026-07-30',
    selectedCompanyId: 'company-a',
    metadata: {
      projectionVersion: 'ATTENDANCE-DASHBOARD-2026-07-30-V1',
      sourceVersions: ['ATTENDANCE-CALC-V1'],
      dataAsOf: '2026-07-30T01:00:00Z',
      timeZone: 'Asia/Shanghai',
      periodLabel: '2026-07',
      periodState: 'OPEN',
      scope: {
        type: 'COMPANY',
        reference: 'authorized-scope-set:app-test',
        label: '公司授权范围',
      },
      allowedActions: [],
    },
    summary: {
      unresolvedCount: 1,
      affectedEmployeeCount: 1,
      blockingCount: 1,
    },
    exceptions: [{
      exceptionReference: 'exception-1',
      employeeNumber: 'SZ001',
      employeeName: '张三',
      organizationName: '制造一部',
      businessDate: '2026-07-30',
      exceptionType: 'MISSING_PUNCH_OVERDUE',
      severity: 'ERROR',
      state: 'PENDING_REVIEW',
      exceptionMinutes: 480,
      evidenceSummary: '下班卡缺失',
    }],
    analytics: {
      dailyTrend: Array.from({ length: 7 }, (_, index) => ({
        businessDate: `2026-07-${String(index + 24).padStart(2, '0')}`,
        exceptionCount: index === 6 ? 1 : 0,
        blockingCount: index === 6 ? 1 : 0,
        affectedEmployeeCount: index === 6 ? 1 : 0,
      })),
      severityDistribution: [
        { severity: 'INFO', count: 0 },
        { severity: 'WARNING', count: 0 },
        { severity: 'ERROR', count: 1 },
      ],
      typeDistribution: [{
        exceptionType: 'MISSING_PUNCH_OVERDUE',
        count: 1,
      }],
      organizationRanking: [{
        organizationName: '制造一部',
        exceptionCount: 1,
        blockingCount: 1,
      }],
    },
    companies: [{
      companyId: 'company-a',
      companyName: '神州半导体',
    }],
  };
}

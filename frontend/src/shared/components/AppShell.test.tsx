import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

import '../../shared/i18n/i18n';
import { changePassword } from '../../features/auth/authApi';
import { AppearanceProvider } from '../appearance/AppearanceProvider';
import { AppShell, ResponsiveNavigation } from './AppShell';

vi.mock('../../features/auth/authApi', () => ({
  changePassword: vi.fn(),
}));

vi.mock('../../features/session/sessionApi', () => ({
  logout: vi.fn(),
}));

vi.mock('../config/runtimeMode', () => ({
  isDemoMode: () => false,
}));

const changePasswordMock = vi.mocked(changePassword);
const passwordPolicy = '新密码须为 12 至 256 位，且至少包含 1 个大写字母、1 个小写字母、1 个数字和 1 个符号';

describe('AppShell password change', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows the complete policy before submission and blocks a weak password', async () => {
    render(
      <MemoryRouter>
        <AppShell menu={[]} onSessionChanged={vi.fn()}>
          <div>页面内容</div>
        </AppShell>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(passwordPolicy)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('当前密码'), {
      target: { value: 'Current-Password-2026!' },
    });
    fireEvent.change(within(dialog).getByLabelText('新密码'), {
      target: { value: 'lowercase#123' },
    });
    fireEvent.change(within(dialog).getByLabelText('确认新密码'), {
      target: { value: 'lowercase#123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认修改' }));

    await waitFor(() => {
      expect(within(dialog).getAllByText(passwordPolicy).length).toBeGreaterThan(1);
    });
    expect(changePasswordMock).not.toHaveBeenCalled();
  });
});

describe('AppShell navigation icons', () => {
  afterEach(cleanup);

  it('uses a personal icon for the personal attendance workbench', () => {
    render(
      <MemoryRouter>
        <ResponsiveNavigation
          menu={[{
            key: 'personal-workbench',
            label: '我的考勤工作台',
            path: '/workbench',
          }]}
          onOpen={vi.fn()}
        />
      </MemoryRouter>,
    );

    const menuItem = screen.getByText('我的考勤工作台').closest('li');
    expect(menuItem?.querySelector('.tabler-icon-user')).not.toBeNull();
  });

  it('groups business menus without changing their click keys', () => {
    const onOpen = vi.fn();
    render(
      <MemoryRouter>
        <ResponsiveNavigation
          menu={[
            { key: 'workbench', label: '考勤工作台', path: '/workbench' },
            { key: 'people-employees', label: '员工', path: '/people/employees' },
            { key: 'attendance-groups', label: '考勤组', path: '/rules/attendance-groups' },
            { key: 'attendance-sources-online', label: '考勤机数据', path: '/sources/online' },
            { key: 'accounts', label: '账号管理', path: '/access/accounts' },
          ]}
          onOpen={onOpen}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText('报表中心')).toBeInTheDocument();
    expect(screen.getByText('组织与人员')).toBeInTheDocument();
    expect(screen.getByText('考勤设置')).toBeInTheDocument();
    expect(screen.getByText('数据接入')).toBeInTheDocument();
    expect(screen.getByText('系统管理')).toBeInTheDocument();

    fireEvent.click(screen.getByText('员工'));
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ key: 'people-employees' }));
  });

  it('puts query reports second, directly under the report center group', () => {
    render(
      <MemoryRouter>
        <ResponsiveNavigation
          menu={[
            { key: 'workbench', label: '考勤工作台', path: '/workbench' },
            { key: 'attendance-reports', label: '考勤报表', path: '/attendance/reports' },
            { key: 'attendance-query-late', label: '迟到统计', path: '/attendance/queries/late' },
            { key: 'attendance-query-work-hours', label: '月度工时统计表', path: '/attendance/queries/work-hours' },
            { key: 'people-employees', label: '员工', path: '/people/employees' },
            { key: 'accounts', label: '账号管理', path: '/access/accounts' },
          ]}
          onOpen={vi.fn()}
        />
      </MemoryRouter>,
    );
    const reportCenter = screen.getByText('报表中心');
    const queries = screen.getByText('查询报表');
    const people = screen.getByText('组织与人员');
    const admin = screen.getByText('系统管理');
    expect(reportCenter.compareDocumentPosition(queries) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(queries.compareDocumentPosition(people) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(queries.compareDocumentPosition(admin) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(screen.getByText('考勤报表')).toBeInTheDocument();
    expect(screen.getByText('月度工时统计表')).toBeInTheDocument();
    expect(screen.queryByText('月度工时', { exact: true })).not.toBeInTheDocument();
  });
});

describe('AppShell topbar', () => {
  afterEach(cleanup);

  it('keeps company and system copy out of the topbar while retaining action alignment', () => {
    const view = render(
      <AppearanceProvider>
        <MemoryRouter>
          <AppShell
            menu={[{ key: 'accounts', label: '账号管理', path: '/access/accounts' }]}
            onSessionChanged={vi.fn()}
          >
            <div>页面内容</div>
          </AppShell>
        </MemoryRouter>
      </AppearanceProvider>,
    );

    const topbar = view.container.querySelector('.app-topbar');
    expect(topbar).not.toBeNull();
    expect(topbar?.querySelector('.app-topbar__title')).toBeNull();
    expect(topbar?.querySelector('.app-topbar__spacer')).not.toBeNull();
    expect(within(topbar as HTMLElement).queryByText(/可管理|当前公司|神州 HR 管理系统/))
      .not.toBeInTheDocument();
    expect(within(topbar as HTMLElement).getByRole('button', { name: '黑夜模式' }))
      .toBeInTheDocument();
    expect(within(topbar as HTMLElement).getByRole('button', { name: '修改密码' }))
      .toBeInTheDocument();
    expect(within(topbar as HTMLElement).getByRole('button', { name: '退出' }))
      .toBeInTheDocument();
  });
});

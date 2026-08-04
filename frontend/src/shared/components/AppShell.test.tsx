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
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

import '../../shared/i18n/i18n';
import { changePassword } from '../../features/auth/authApi';
import { listReferenceCompanies } from '../../features/referenceData/referenceDataApi';
import { AppShell, ResponsiveNavigation } from './AppShell';

vi.mock('../../features/auth/authApi', () => ({
  changePassword: vi.fn(),
}));

vi.mock('../../features/session/sessionApi', () => ({
  logout: vi.fn(),
}));

vi.mock('../../features/referenceData/referenceDataApi', () => ({
  listReferenceCompanies: vi.fn(),
}));

vi.mock('../config/runtimeMode', () => ({
  isDemoMode: () => false,
}));

const changePasswordMock = vi.mocked(changePassword);
const listReferenceCompaniesMock = vi.mocked(listReferenceCompanies);
const passwordPolicy = '新密码须为 12 至 256 位，且至少包含 1 个大写字母、1 个小写字母、1 个数字和 1 个符号';

beforeEach(() => {
  listReferenceCompaniesMock.mockResolvedValue([]);
});

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

    expect(screen.getByText('工作台与报表')).toBeInTheDocument();
    expect(screen.getByText('组织与人员')).toBeInTheDocument();
    expect(screen.getByText('考勤设置')).toBeInTheDocument();
    expect(screen.getByText('数据接入')).toBeInTheDocument();
    expect(screen.getByText('系统管理')).toBeInTheDocument();

    fireEvent.click(screen.getByText('员工'));
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ key: 'people-employees' }));
  });
});

describe('AppShell company context', () => {
  afterEach(cleanup);

  it('shows the single company visible to the current account', async () => {
    listReferenceCompaniesMock.mockResolvedValue([{
      companyId: 'company-a',
      companyName: '江苏神州半导体科技股份有限公司',
      companyCode: 'SZSC',
    }]);

    render(
      <MemoryRouter>
        <AppShell
          menu={[{ key: 'accounts', label: '账号管理', path: '/access/accounts' }]}
          onSessionChanged={vi.fn()}
        >
          <div>页面内容</div>
        </AppShell>
      </MemoryRouter>,
    );

    expect(await screen.findByText('当前公司：江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();
  });

  it('uses an honest company count when more than one company is visible', async () => {
    listReferenceCompaniesMock.mockResolvedValue([
      { companyId: 'company-a', companyName: '公司甲' },
      { companyId: 'company-b', companyName: '公司乙' },
    ]);

    render(
      <MemoryRouter>
        <AppShell
          menu={[{ key: 'accounts', label: '账号管理', path: '/access/accounts' }]}
          onSessionChanged={vi.fn()}
        >
          <div>页面内容</div>
        </AppShell>
      </MemoryRouter>,
    );

    expect(await screen.findByText('可管理 2 家公司'))
      .toBeInTheDocument();
  });

  it('clears company context when switching to an employee-only menu', async () => {
    listReferenceCompaniesMock.mockResolvedValue([{
      companyId: 'company-a',
      companyName: '江苏神州半导体科技股份有限公司',
      companyCode: 'SZSC',
    }]);

    const { rerender } = render(
      <MemoryRouter>
        <AppShell
          menu={[{ key: 'accounts', label: '账号管理', path: '/access/accounts' }]}
          onSessionChanged={vi.fn()}
        >
          <div>页面内容</div>
        </AppShell>
      </MemoryRouter>,
    );

    expect(await screen.findByText('当前公司：江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <AppShell
          menu={[{ key: 'personal-workbench', label: '我的考勤工作台', path: '/workbench' }]}
          onSessionChanged={vi.fn()}
        >
          <div>页面内容</div>
        </AppShell>
      </MemoryRouter>,
    );

    expect(screen.getByText('神州 HR 管理系统')).toBeInTheDocument();
    expect(screen.queryByText('当前公司：江苏神州半导体科技股份有限公司'))
      .not.toBeInTheDocument();
  });
});

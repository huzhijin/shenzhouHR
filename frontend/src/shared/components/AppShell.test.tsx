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
});

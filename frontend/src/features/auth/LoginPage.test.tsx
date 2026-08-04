import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import type { SessionView } from '../session/sessionApi';
import {
  completeFirstPasswordChange,
  login,
} from './authApi';
import { LoginPage } from './LoginPage';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => false,
}));

vi.mock('./authApi', () => ({
  completeFirstPasswordChange: vi.fn(),
  login: vi.fn(),
}));

const loginMock = vi.mocked(login);
const completeFirstPasswordChangeMock = vi.mocked(
  completeFirstPasswordChange,
);

describe('LoginPage first-password-change flow', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('keeps the first-change form visible and establishes a replacement session before authenticating the app', async () => {
    const initialPassword = 'Temporary-Password-2026!';
    const replacementPassword = 'Replacement-Password-2026!';
    const passwordChange = deferred<void>();
    const replacementLogin = deferred<SessionView>();
    const onAuthenticated = vi.fn();

    loginMock
      .mockResolvedValueOnce(sessionView({
        firstPasswordChangeRequired: true,
        capabilities: [],
        menu: [],
      }))
      .mockImplementationOnce(() => replacementLogin.promise);
    completeFirstPasswordChangeMock.mockReturnValueOnce(passwordChange.promise);

    renderLoginPage(onAuthenticated);
    submitCredentials('synthetic.local.admin', initialPassword);

    expect(
      await screen.findByRole('heading', { name: '首次登录修改密码' }),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: replacementPassword },
    });
    fireEvent.change(screen.getByLabelText('确认新密码'), {
      target: { value: replacementPassword },
    });
    fireEvent.click(screen.getByRole('button', { name: '完成修改并登录' }));

    await waitFor(() => {
      expect(completeFirstPasswordChangeMock).toHaveBeenCalledWith(
        initialPassword,
        replacementPassword,
      );
    });
    expect(
      screen.getByRole('heading', { name: '首次登录修改密码' }),
    ).toBeInTheDocument();
    expect(onAuthenticated).not.toHaveBeenCalled();
    expect(loginMock).toHaveBeenCalledTimes(1);

    passwordChange.resolve();

    await waitFor(() => {
      expect(loginMock).toHaveBeenNthCalledWith(
        2,
        'synthetic.local.admin',
        replacementPassword,
      );
    });
    expect(onAuthenticated).not.toHaveBeenCalled();

    replacementLogin.resolve(sessionView({
      firstPasswordChangeRequired: false,
    }));

    await waitFor(() => {
      expect(onAuthenticated).toHaveBeenCalledOnce();
    });
  });

  it('does not submit a weak replacement password', async () => {
    loginMock.mockResolvedValueOnce(sessionView({
      firstPasswordChangeRequired: true,
      capabilities: [],
      menu: [],
    }));

    renderLoginPage(vi.fn());
    submitCredentials('synthetic.local.admin', 'Temporary-Password-2026!');

    expect(
      await screen.findByRole('heading', { name: '首次登录修改密码' }),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: 'too-short' },
    });
    fireEvent.change(screen.getByLabelText('确认新密码'), {
      target: { value: 'too-short' },
    });
    fireEvent.click(screen.getByRole('button', { name: '完成修改并登录' }));

    expect(
      await screen.findByText(
        '新密码须为 12 至 256 位，且至少包含 1 个大写字母、1 个小写字母、1 个数字和 1 个符号',
      ),
    ).toBeInTheDocument();
    expect(completeFirstPasswordChangeMock).not.toHaveBeenCalled();
    expect(loginMock).toHaveBeenCalledTimes(1);
  });

  it('does not expose request correlation metadata after a failed login', async () => {
    loginMock.mockRejectedValueOnce(new ApiRequestError(401, {
      code: 'INVALID_CREDENTIALS',
      correlationId: 'login-request-internal-401',
      retryable: false,
    }));

    renderLoginPage(vi.fn());
    submitCredentials('synthetic.local.admin', 'incorrect-password');

    expect(await screen.findByText('用户名或密码错误')).toBeInTheDocument();
    expect(screen.queryByText(/login-request-internal-401/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关联 ID|关联标识/)).not.toBeInTheDocument();
  });

  it('keeps correlation metadata out of first-password-change failures', async () => {
    loginMock.mockResolvedValueOnce(sessionView({
      firstPasswordChangeRequired: true,
      capabilities: [],
      menu: [],
    }));
    completeFirstPasswordChangeMock.mockRejectedValueOnce(new ApiRequestError(503, {
      code: 'PASSWORD_CHANGE_UNAVAILABLE',
      correlationId: 'password-change-request-internal-503',
      retryable: true,
    }));

    renderLoginPage(vi.fn());
    submitCredentials('synthetic.local.admin', 'Temporary-Password-2026!');

    expect(
      await screen.findByRole('heading', { name: '首次登录修改密码' }),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('新密码'), {
      target: { value: 'Replacement-Password-2026!' },
    });
    fireEvent.change(screen.getByLabelText('确认新密码'), {
      target: { value: 'Replacement-Password-2026!' },
    });
    fireEvent.click(screen.getByRole('button', { name: '完成修改并登录' }));

    expect(
      await screen.findByText('无法连接到登录服务，请检查网络后重试。'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/password-change-request-internal-503/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关联 ID|关联标识/)).not.toBeInTheDocument();
  });
});

function renderLoginPage(onAuthenticated: () => void) {
  return render(
    <MemoryRouter initialEntries={['/login']}>
      <LoginPage onAuthenticated={onAuthenticated} />
    </MemoryRouter>,
  );
}

function submitCredentials(username: string, password: string) {
  fireEvent.change(screen.getByRole('textbox', { name: '用户名' }), {
    target: { value: username },
  });
  fireEvent.change(screen.getByLabelText('密码'), {
    target: { value: password },
  });
  fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));
}

function sessionView(
  overrides: Partial<SessionView> = {},
): SessionView {
  return {
    sessionId: 'session-1',
    accountId: 'account-1',
    username: 'synthetic.local.admin',
    displayName: '系统管理员',
    status: 'ACTIVE',
    firstPasswordChangeRequired: false,
    issuedAt: '2026-07-30T00:00:00Z',
    lastSeenAt: '2026-07-30T00:00:00Z',
    idleExpiresAt: '2026-07-30T00:30:00Z',
    absoluteExpiresAt: '2026-07-30T08:00:00Z',
    capabilities: ['ACCOUNT:READ'],
    menu: [{
      key: 'accounts',
      label: '账号管理',
      path: '/access/accounts',
    }],
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

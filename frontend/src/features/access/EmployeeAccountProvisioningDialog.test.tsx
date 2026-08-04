import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { Modal } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import {
  createProvisioningRecoveryKey,
  credentialCsvCell,
  EmployeeAccountProvisioningDialog,
} from './EmployeeAccountProvisioningDialog';

const api = vi.hoisted(() => ({
  createEmployeeAccounts: vi.fn(),
  listEmployeeAccountCandidates: vi.fn(),
}));

vi.mock('./accessApi', () => api);

vi.mock('../referenceData', () => ({
  CompanySelect: ({
    disabled,
    onChange,
  }: {
    disabled?: boolean;
    onChange?: (value?: string) => void;
  }) => (
    <>
      <button
        type="button"
        aria-label="选择测试公司"
        disabled={disabled}
        onClick={() => onChange?.('company-1')}
      >
        选择测试公司
      </button>
      <button
        type="button"
        aria-label="选择备用公司"
        disabled={disabled}
        onClick={() => onChange?.('company-2')}
      >
        选择备用公司
      </button>
    </>
  ),
}));

describe('EmployeeAccountProvisioningDialog', () => {
  beforeEach(() => {
    api.listEmployeeAccountCandidates.mockResolvedValue(candidatePage);
    api.createEmployeeAccounts.mockResolvedValue({
      credentials: [],
      created: 1,
      replayed: false,
    });
  });

  afterEach(() => {
    Modal.destroyAll();
    cleanup();
    document.querySelectorAll('.ant-modal-confirm').forEach((node) => {
      node.closest('.ant-modal-root')?.remove();
    });
    vi.restoreAllMocks();
    vi.clearAllMocks();
  });

  it('does not create any account until the operator confirms opening all accounts', async () => {
    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));

    const provisionAll = await screen.findByRole('button', { name: '开通全部 1 人' });
    expect(provisionAll).toBeEnabled();
    expect(api.createEmployeeAccounts).not.toHaveBeenCalled();

    fireEvent.click(provisionAll);

    await waitFor(() => {
      expect(document.querySelector('.ant-modal-confirm')).toBeInTheDocument();
    });
    expect(screen.getAllByText('确认开通 1 个员工账号？')).not.toHaveLength(0);
    expect(api.createEmployeeAccounts).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '返回核对' }));

    expect(api.createEmployeeAccounts).not.toHaveBeenCalled();
  });

  it('creates accounts with the fully prechecked employee ids after confirmation', async () => {
    const onCreated = vi.fn();
    renderDialog(onCreated);
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));

    fireEvent.click(await screen.findByRole('button', { name: '开通全部 1 人' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认开通 1 人' }));

    await waitFor(() => {
      expect(api.createEmployeeAccounts).toHaveBeenCalledWith(
        ['employee-001'],
        expect.stringMatching(/^employee-account-bulk:/),
        expect.stringMatching(/^[A-Za-z0-9_-]{43}$/),
      );
    });
    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledOnce();
    });
  });

  it('retries an uncertain request with the same idempotency key', async () => {
    api.createEmployeeAccounts
      .mockRejectedValueOnce(new ApiRequestError(0, {
        code: 'NETWORK_REQUEST_FAILED',
        retryable: true,
      }))
      .mockResolvedValueOnce({ credentials: [], created: 1, replayed: true });

    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));
    fireEvent.click(await screen.findByRole('button', { name: '开通全部 1 人' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认开通 1 人' }));

    await waitFor(() => expect(api.createEmployeeAccounts).toHaveBeenCalledTimes(2));
    const firstKey = api.createEmployeeAccounts.mock.calls[0]?.[1];
    const firstRecoveryKey = api.createEmployeeAccounts.mock.calls[0]?.[2];
    expect(firstKey).toMatch(/^employee-account-bulk:/);
    expect(firstRecoveryKey).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(api.createEmployeeAccounts.mock.calls[1]?.[1]).toBe(firstKey);
    expect(api.createEmployeeAccounts.mock.calls[1]?.[2]).toBe(firstRecoveryKey);
  });

  it('keeps an uncertain batch recoverable until the operator confirms it', async () => {
    const networkFailure = new ApiRequestError(0, {
      code: 'NETWORK_REQUEST_FAILED',
      retryable: true,
    });
    api.createEmployeeAccounts
      .mockRejectedValueOnce(networkFailure)
      .mockRejectedValueOnce(networkFailure);

    const onCreated = vi.fn();
    const onClose = vi.fn();
    renderDialog(onCreated, onClose);
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));
    fireEvent.click(await screen.findByRole('button', { name: '开通全部 1 人' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认开通 1 人' }));

    await waitFor(() => expect(api.createEmployeeAccounts).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('上次批次结果尚未确认')).toBeInTheDocument();
    expect(screen.getByText(/刷新或关闭后恢复密钥会清除，只能到账号页执行密码重置/))
      .toBeInTheDocument();
    const retry = await screen.findByRole('button', { name: '重新确认上次批次' });
    expect(screen.getByRole('button', { name: /关\s*闭/ })).toBeDisabled();
    fireEvent.keyDown(document, { key: 'Escape', code: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();

    const retried = deferred<{
      credentials: never[];
      created: number;
      replayed: boolean;
    }>();
    api.createEmployeeAccounts.mockReturnValue(retried.promise);
    fireEvent.click(retry);

    await waitFor(() => expect(api.createEmployeeAccounts).toHaveBeenCalledTimes(3));
    expect(api.createEmployeeAccounts.mock.calls[2]?.[1])
      .toBe(api.createEmployeeAccounts.mock.calls[0]?.[1]);
    expect(api.createEmployeeAccounts.mock.calls[2]?.[2])
      .toBe(api.createEmployeeAccounts.mock.calls[0]?.[2]);
    fireEvent.keyDown(document, { key: 'Escape', code: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();

    retried.resolve({
      credentials: [],
      created: 1,
      replayed: true,
    });

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledOnce();
      expect(screen.queryByText('上次批次结果尚未确认')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: /关\s*闭/ })).toBeEnabled();
    });
  });

  it('ignores a slower candidate response from the previously selected company', async () => {
    const firstCompany = deferred<typeof candidatePage>();
    const secondCompany = deferred<typeof candidatePage>();
    api.listEmployeeAccountCandidates.mockImplementation(({ companyId, size }) => {
      if (size !== 20) return Promise.resolve(candidatePage);
      return companyId === 'company-1' ? firstCompany.promise : secondCompany.promise;
    });

    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));
    await waitFor(() => expect(api.listEmployeeAccountCandidates).toHaveBeenCalledWith(
      expect.objectContaining({ companyId: 'company-1', size: 20 }),
    ));

    fireEvent.click(screen.getByRole('button', { name: '选择备用公司' }));
    await waitFor(() => expect(api.listEmployeeAccountCandidates).toHaveBeenCalledWith(
      expect.objectContaining({ companyId: 'company-2', size: 20 }),
    ));
    expect(screen.queryByText('测试员工')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^开通全部/ })).toBeDisabled();

    secondCompany.resolve(candidatePageFor('company-2', 'employee-002', '备用员工'));
    expect(await screen.findByText('备用员工')).toBeInTheDocument();

    await act(async () => {
      firstCompany.resolve(candidatePageFor('company-1', 'employee-001', '测试员工'));
      await firstCompany.promise;
    });
    expect(screen.queryByText('测试员工')).not.toBeInTheDocument();
    expect(screen.getByText('备用员工')).toBeInTheDocument();
  });

  it('fails closed when candidate rows do not belong to the selected company', async () => {
    api.listEmployeeAccountCandidates.mockResolvedValue(
      candidatePageFor('company-2', 'employee-002', '错误公司员工'),
    );

    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));

    expect(await screen.findByText('预检结果与当前公司不一致，已停止展示。请刷新后重试。'))
      .toBeInTheDocument();
    expect(screen.queryByText('错误公司员工')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^开通全部/ })).toBeDisabled();
  });

  it('reports a later chunk failure as a warning and refreshes the account list', async () => {
    const candidates = Array.from({ length: 21 }, (_, index) => ({
      employeeId: `employee-${String(index + 1).padStart(3, '0')}`,
      companyId: 'company-1',
      employeeNumber: String(index + 1).padStart(6, '0'),
      displayName: `员工 ${index + 1}`,
      organizationName: '测试部门',
      status: 'AVAILABLE' as const,
    }));
    api.listEmployeeAccountCandidates.mockImplementation(({ size }) => Promise.resolve({
      items: size === 100 ? candidates : candidates.slice(0, 20),
      total: candidates.length,
      available: candidates.length,
      alreadyProvisioned: 0,
      usernameConflicts: 0,
      page: 0,
      size,
    }));
    const createdCredentials = candidates.slice(0, 20).map((candidate) => ({
      employeeId: candidate.employeeId,
      employeeNumber: candidate.employeeNumber,
      displayName: candidate.displayName,
      organizationName: candidate.organizationName,
      username: candidate.employeeNumber,
      temporaryPassword: `Temp-${candidate.employeeNumber}!`,
    }));
    api.createEmployeeAccounts
      .mockResolvedValueOnce({
        credentials: createdCredentials,
        created: createdCredentials.length,
        replayed: false,
      })
      .mockRejectedValueOnce(new ApiRequestError(409, {
        code: 'USERNAME_CONFLICT',
        retryable: false,
      }));
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:credentials'),
    });
    Object.defineProperty(window.URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const onCreated = vi.fn();

    renderDialog(onCreated);
    fireEvent.click(screen.getByRole('button', { name: '选择测试公司' }));
    fireEvent.click(await screen.findByRole('button', { name: '开通全部 21 人' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认开通 21 人' }));

    const resultTitle = await screen.findByText(/已开通 20 个账号，后续批次未完成/);
    expect(resultTitle.closest('.ant-alert')).toHaveClass('ant-alert-warning');
    expect(screen.getByText(/临时密码仅在当前窗口临时保留/)).toBeInTheDocument();
    expect(onCreated).toHaveBeenCalledOnce();
  });
});

describe('credentialCsvCell', () => {
  it('neutralizes spreadsheet formulas, including whitespace-prefixed payloads', () => {
    expect(credentialCsvCell('=SUM(A1:A2)')).toBe('"\'=SUM(A1:A2)"');
    expect(credentialCsvCell('\t=cmd')).toBe('"\'\t=cmd"');
    expect(credentialCsvCell('\r+cmd')).toBe('"\'\r+cmd"');
    expect(credentialCsvCell('  @cmd')).toBe('"\'  @cmd"');
    expect(credentialCsvCell('普通"姓名')).toBe('"普通""姓名"');
  });
});

describe('createProvisioningRecoveryKey', () => {
  it('creates a fresh 32-byte base64url key without padding', () => {
    const first = createProvisioningRecoveryKey();
    const second = createProvisioningRecoveryKey();

    expect(first).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(second).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(second).not.toBe(first);
  });
});

const candidatePage = {
  items: [{
    employeeId: 'employee-001',
    companyId: 'company-1',
    employeeNumber: '000001',
    displayName: '测试员工',
    organizationName: '测试部门',
    status: 'AVAILABLE' as const,
  }],
  total: 1,
  available: 1,
  alreadyProvisioned: 0,
  usernameConflicts: 0,
  page: 0,
  size: 20,
};

function candidatePageFor(companyId: string, employeeId: string, displayName: string) {
  return {
    ...candidatePage,
    items: [{
      ...candidatePage.items[0]!,
      companyId,
      employeeId,
      displayName,
    }],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

function renderDialog(onCreated = vi.fn(), onClose = vi.fn()) {
  return render(
    <EmployeeAccountProvisioningDialog
      open
      onClose={onClose}
      onCreated={onCreated}
    />,
  );
}

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Modal } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import { AccountsPage } from './AccountsPage';

const api = vi.hoisted(() => ({
  createAccount: vi.fn(),
  listAccounts: vi.fn(),
  listGrantableCompanies: vi.fn(),
  listGrantableOrganizations: vi.fn(),
  listRoles: vi.fn(),
  lockAccount: vi.fn(),
}));

vi.mock('./accessApi', () => ({
  ...api,
  isStrongTemporaryPassword: (value: string) => value === 'Strong#Password123',
}));

vi.mock('./EmployeeAccountProvisioningDialog', () => ({
  EmployeeAccountProvisioningDialog: () => null,
}));

vi.mock('../referenceData', () => ({
  EmployeeSelect: () => <input aria-label="员工" />,
}));

vi.mock('./GrantableScopeSelects', () => ({
  GrantableCompanySelect: ({
    companies,
    onChange,
    value,
  }: {
    companies: Array<{ companyId: string; name: string }>;
    onChange?: (companyId: string) => void;
    value?: string;
  }) => (
    <div>
      <span data-testid="selected-company">{value ?? ''}</span>
      {companies.map((company) => (
        <button
          key={company.companyId}
          type="button"
          onClick={() => onChange?.(company.companyId)}
        >
          选择公司 {company.name}
        </button>
      ))}
    </div>
  ),
  GrantableOrganizationSelect: ({
    organizations,
    onChange,
  }: {
    organizations: Array<{ organizationId: string; name: string }>;
    onChange?: (organizationId: string) => void;
  }) => (
    <div>
      {organizations.map((organization) => (
        <button
          key={organization.organizationId}
          type="button"
          onClick={() => onChange?.(organization.organizationId)}
        >
          选择部门 {organization.name}
        </button>
      ))}
    </div>
  ),
}));

const companies = [
  { companyId: 'company-a', code: 'A', name: '甲公司' },
  { companyId: 'company-b', code: 'B', name: '乙公司' },
  { companyId: 'company-c', code: 'C', name: '丙公司' },
  { companyId: 'company-d', code: 'D', name: '丁公司' },
];

const roles = [
  {
    roleId: 'department-head',
    roleCode: 'DEPARTMENT_HEAD',
    roleName: '跨公司部门主管',
    capabilities: [],
  },
  {
    roleId: 'executive',
    roleCode: 'EXECUTIVE',
    roleName: '高管',
    capabilities: [],
  },
];

describe('AccountsPage role scope creation', () => {
  beforeEach(() => {
    api.listAccounts.mockResolvedValue({
      items: [], total: 0, page: 0, size: 20,
    });
    api.listRoles.mockResolvedValue(roles);
    api.listGrantableCompanies.mockResolvedValue(companies);
    api.listGrantableOrganizations.mockImplementation((companyId: string) => (
      Promise.resolve(companyId === 'company-a'
        ? [{
          organizationId: 'organization-a',
          companyId,
          parentOrganizationId: null,
          code: 'OA',
          name: '甲公司制造部',
          canIncludeDescendants: false,
        }]
        : [{
          organizationId: 'organization-b',
          companyId,
          parentOrganizationId: null,
          code: 'OB',
          name: '乙公司研发部',
          canIncludeDescendants: true,
        }])
    ));
    api.createAccount.mockResolvedValue({});
  });

  afterEach(() => {
    Modal.destroyAll();
    cleanup();
    vi.clearAllMocks();
  });

  it('creates two cross-company organization rows with independent explicit descendant choices', async () => {
    const dialog = await openCreateDialog();
    selectRole(dialog, '跨公司部门主管');

    await waitFor(() => {
      expect(within(roleRows(dialog)[0]!).getByRole(
        'button',
        { name: '选择公司 甲公司' },
      )).toBeInTheDocument();
    });
    let rows = roleRows(dialog);
    fireEvent.click(within(rows[0]!).getByRole('button', { name: '选择公司 甲公司' }));
    fireEvent.click(await within(rows[0]!).findByRole('button', { name: '选择部门 甲公司制造部' }));
    const firstDescendants = within(rows[0]!).getByRole('checkbox', { name: '包含下级部门' });
    expect(firstDescendants).not.toBeChecked();
    expect(firstDescendants).toBeDisabled();

    fireEvent.click(within(rows[0]!).getByRole(
      'button',
      { name: '为第 1 条角色授权添加范围' },
    ));
    await waitFor(() => {
      expect(roleRows(dialog)).toHaveLength(2);
      expect(within(roleRows(dialog)[1]!).getByRole(
        'button',
        { name: '选择公司 乙公司' },
      )).toBeInTheDocument();
    });
    rows = roleRows(dialog);
    fireEvent.click(within(rows[1]!).getByRole('button', { name: '选择公司 乙公司' }));
    fireEvent.click(await within(rows[1]!).findByRole('button', { name: '选择部门 乙公司研发部' }));
    const secondDescendants = within(rows[1]!).getByRole('checkbox', { name: '包含下级部门' });
    expect(secondDescendants).not.toBeChecked();
    await waitFor(() => expect(secondDescendants).toBeEnabled());
    fireEvent.click(secondDescendants);

    fireEvent.change(within(dialog).getByRole('textbox', { name: '用户名' }), {
      target: { value: 'cross.company.head' },
    });
    fireEvent.change(within(dialog).getByRole('textbox', { name: '显示名称' }), {
      target: { value: '跨公司部门主管' },
    });
    fireEvent.change(within(dialog).getByLabelText('初始密码'), {
      target: { value: 'Strong#Password123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /创\s*建/ }));

    await waitFor(() => expect(api.createAccount).toHaveBeenCalledOnce());
    const request = api.createAccount.mock.calls[0]?.[0];
    expect(request.roleAssignments).toEqual([
      expect.objectContaining({
        roleId: 'department-head',
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-a',
        includeDescendants: false,
      }),
      expect.objectContaining({
        roleId: 'department-head',
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-b',
        includeDescendants: true,
      }),
    ]);
    expect(api.listGrantableOrganizations).toHaveBeenCalledWith(
      'company-a',
      'ACCOUNT_CREATION',
    );
    expect(api.listGrantableOrganizations).toHaveBeenCalledWith(
      'company-b',
      'ACCOUNT_CREATION',
    );
  });

  it('defaults a first executive grant to all current companies and lets the operator remove one', async () => {
    const dialog = await openCreateDialog();
    selectRole(dialog, '高管');

    await waitFor(() => expect(roleRows(dialog)).toHaveLength(4));
    expect(within(dialog).getAllByTestId('selected-company').map((node) => node.textContent))
      .toEqual(companies.map((company) => company.companyId));

    fireEvent.click(within(roleRows(dialog)[0]!).getByRole(
      'button',
      { name: '删除第 1 条角色授权' },
    ));
    expect(roleRows(dialog)).toHaveLength(3);
    expect(within(dialog).getByText(/以后新增公司不会自动扩权/)).toBeInTheDocument();
  });
});

async function openCreateDialog(): Promise<HTMLElement> {
  render(
    <MemoryRouter>
      <AccountsPage capabilities={['ACCOUNT:CREATE', 'ROLE:ASSIGN', 'ROLE:READ']} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '单个创建账号' }));
  const dialog = await screen.findByRole('dialog');
  await waitFor(() => {
    expect(api.listGrantableCompanies).toHaveBeenCalledWith(
      'COMPANY',
      'ACCOUNT_CREATION',
    );
  });
  return dialog;
}

function selectRole(dialog: HTMLElement, roleName: string) {
  const roleSelect = within(dialog).getByRole('combobox', { name: '初始角色 1' });
  fireEvent.mouseDown(roleSelect);
  fireEvent.click(screen.getByText(roleName));
}

function roleRows(dialog: HTMLElement): HTMLElement[] {
  return Array.from(dialog.querySelectorAll<HTMLElement>('.role-assignment-row'));
}

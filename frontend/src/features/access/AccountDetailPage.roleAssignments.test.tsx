import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import { AccountDetailPage } from './AccountDetailPage';

const api = vi.hoisted(() => ({
  assignRoles: vi.fn(),
  getAccount: vi.fn(),
  listGrantableCompanies: vi.fn(),
  listGrantableOrganizations: vi.fn(),
  listRoles: vi.fn(),
  resetTemporaryPassword: vi.fn(),
  unlockAccount: vi.fn(),
  updateAccountStatus: vi.fn(),
}));

vi.mock('./accessApi', () => ({
  ...api,
  isStrongTemporaryPassword: () => true,
}));

vi.mock('../auth/authApi', () => ({ revokeSession: vi.fn() }));

vi.mock('./GrantableScopeSelects', () => ({
  GrantableCompanySelect: ({ value }: { value?: string }) => (
    <span data-testid="detail-company">{value}</span>
  ),
  GrantableOrganizationSelect: ({ value }: { value?: string }) => (
    <span data-testid="detail-organization">{value}</span>
  ),
}));

const role = {
  roleId: 'department-head',
  roleCode: 'DEPARTMENT_HEAD',
  roleName: '部门主管',
  capabilities: [],
};

describe('AccountDetailPage cross-company organization scopes', () => {
  beforeEach(() => {
    api.getAccount.mockResolvedValue({
      accountId: 'account-1',
      username: 'department.head',
      displayName: '跨公司部门主管',
      status: 'ACTIVE',
      firstPasswordChangeRequired: false,
      rowVersion: 3,
      sessions: [],
      roles: [
        assignment('assignment-a', 'company-a', 'organization-a', false),
        assignment('assignment-b', 'company-b', 'organization-b', true),
      ],
    });
    api.listRoles.mockResolvedValue([role]);
    api.listGrantableCompanies.mockResolvedValue([
      { companyId: 'company-a', code: 'A', name: '甲公司' },
      { companyId: 'company-b', code: 'B', name: '乙公司' },
    ]);
    api.listGrantableOrganizations.mockImplementation((companyId: string) => (
      Promise.resolve([{
        organizationId: companyId === 'company-a' ? 'organization-a' : 'organization-b',
        companyId,
        parentOrganizationId: null,
        code: companyId === 'company-a' ? 'OA' : 'OB',
        name: companyId === 'company-a' ? '制造部' : '研发部',
        canIncludeDescendants: companyId === 'company-b',
      }])
    ));
    api.assignRoles.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('loads, displays and saves two company-specific department rows without collapsing them', async () => {
    render(
      <MemoryRouter initialEntries={['/access/accounts/account-1']}>
        <Routes>
          <Route
            path="/access/accounts/:accountId"
            element={<AccountDetailPage capabilities={['ROLE:ASSIGN']} />}
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: '跨公司部门主管' }))
      .toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '加载角色' }));

    await waitFor(() => {
      expect(document.querySelectorAll('.role-assignment-row')).toHaveLength(2);
    });
    expect(screen.getAllByTestId('detail-company').map((node) => node.textContent))
      .toEqual(['company-a', 'company-b']);
    expect(screen.getAllByTestId('detail-organization').map((node) => node.textContent))
      .toEqual(['organization-a', 'organization-b']);

    const checkboxes = screen.getAllByRole('checkbox', { name: '包含下级部门' });
    expect(checkboxes[0]).not.toBeChecked();
    expect(checkboxes[0]).toBeDisabled();
    expect(checkboxes[1]).toBeChecked();
    expect(checkboxes[1]).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: '保存授权' }));
    await waitFor(() => expect(api.assignRoles).toHaveBeenCalledOnce());
    expect(api.assignRoles.mock.calls[0]?.[0]).toBe('account-1');
    expect(api.assignRoles.mock.calls[0]?.[1]).toEqual([
      expect.objectContaining({
        roleId: role.roleId,
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-a',
        includeDescendants: false,
      }),
      expect.objectContaining({
        roleId: role.roleId,
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-b',
        includeDescendants: true,
      }),
    ]);
  });
});

function assignment(
  assignmentId: string,
  companyId: string,
  organizationId: string,
  includeDescendants: boolean,
) {
  return {
    assignmentId,
    roleId: role.roleId,
    roleCode: role.roleCode,
    roleName: role.roleName,
    scopeType: 'ORGANIZATION',
    scopeResourceId: organizationId,
    scopeCompanyId: companyId,
    scopeCompanyName: companyId === 'company-a' ? '甲公司' : '乙公司',
    scopeResourceName: companyId === 'company-a' ? '制造部' : '研发部',
    scopeResourcePath: companyId === 'company-a' ? '制造中心 / 制造部' : '研发中心 / 研发部',
    includeDescendants,
    validFrom: '2026-01-01T00:00:00Z',
    validTo: null,
  };
}

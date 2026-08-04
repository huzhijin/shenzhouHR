import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import {
  AccountStatusPanel,
  capabilityLabel,
  PermissionMatrix,
  RoleScopeList,
  roleScopeLabel,
  SessionStatusPanel,
} from './AccessComponents';

vi.mock('../referenceData', () => ({
  CompanySelect: ({
    value,
    'aria-label': ariaLabel,
  }: { value?: string; 'aria-label'?: string }) => (
    <span aria-label={ariaLabel}>
      {value === 'company-internal-id' ? '江苏神州半导体科技股份有限公司' : '未知公司'}
    </span>
  ),
  OrganizationSelect: ({
    value,
    'aria-label': ariaLabel,
  }: { value?: string; 'aria-label'?: string }) => (
    <span aria-label={ariaLabel}>
      {value === 'department-internal-id' ? '人力资源部' : '未知部门'}
    </span>
  ),
}));

describe('PermissionMatrix', () => {
  it('renders role names from the backend RoleRecord contract', () => {
    render(
      <PermissionMatrix
        roles={[{
          roleId: 'role-1',
          roleCode: 'LOCAL_SYSTEM_ADMIN',
          roleName: '本地系统管理员',
          capabilities: ['ACCOUNT:READ'],
        }]}
        selectedRoleIds={['role-1']}
        readOnly
      />,
    );

    expect(screen.getByRole('columnheader', { name: '本地系统管理员' }))
      .toBeInTheDocument();
    expect(screen.getByRole('rowheader', { name: '账号 · 查看' }))
      .toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '本地系统管理员 账号 · 查看' }))
      .toBeChecked();
    expect(screen.queryByText('undefined')).not.toBeInTheDocument();
  });

  it('translates capability codes and role scopes without changing their values', () => {
    expect(capabilityLabel('ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE'))
      .toBe('考勤打卡导入 · 作废或冲正');
    expect(roleScopeLabel('COMPANY')).toBe('公司范围');
    expect(roleScopeLabel('ORGANIZATION')).toBe('部门范围');
    expect(roleScopeLabel('SELF')).toBe('仅本人');
  });
});

describe('AccountStatusPanel', () => {
  it('keeps the optimistic row version internal', () => {
    render(
      <AccountStatusPanel
        account={{
          accountId: 'account-internal-id',
          username: 'employee.one',
          displayName: '员工一',
          status: 'ACTIVE',
          firstPasswordChangeRequired: false,
          resetPending: false,
          lockedUntil: null,
          lastLoginAt: null,
          rowVersion: 73,
          roles: [],
          sessions: [],
        }}
      />,
    );

    expect(screen.queryByText('并发版本')).not.toBeInTheDocument();
    expect(screen.queryByText('73')).not.toBeInTheDocument();
  });
});

describe('SessionStatusPanel', () => {
  it('renders the backend createdAt contract instead of an empty issued time', () => {
    render(
      <SessionStatusPanel
        sessions={[{
          sessionId: 'session-1',
          status: 'ACTIVE',
          createdAt: '2026-07-24T08:00:00Z',
          lastSeenAt: '2026-07-24T08:32:00Z',
          idleExpiresAt: '2026-07-24T09:00:00Z',
          absoluteExpiresAt: '2026-07-24T16:00:00Z',
        }]}
      />,
    );

    expect(screen.getByText(/签发 2026/)).toBeInTheDocument();
    expect(screen.getByText(/签发 2026/)).not.toHaveTextContent('签发 —');
    expect(screen.getByText('会话 1')).toBeInTheDocument();
    expect(screen.queryByText('session-1')).not.toBeInTheDocument();
  });
});

describe('RoleScopeList', () => {
  it('shows company and department names without exposing scope ids', () => {
    render(
      <RoleScopeList
        assignments={[
          {
            assignmentId: 'assignment-company',
            roleId: 'role-company',
            roleCode: 'HR_ADMIN',
            roleName: '人事管理员',
            scopeType: 'COMPANY',
            scopeResourceId: 'company-internal-id',
            validFrom: '2026-07-01T00:00:00Z',
            validTo: null,
          },
          {
            assignmentId: 'assignment-department',
            roleId: 'role-department',
            roleCode: 'DEPARTMENT_HEAD',
            roleName: '部门负责人',
            scopeType: 'ORGANIZATION',
            scopeResourceId: 'department-internal-id',
            validFrom: '2026-07-01T00:00:00Z',
            validTo: null,
          },
        ]}
      />,
    );

    expect(screen.getByText('江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();
    expect(screen.getByText('人力资源部')).toBeInTheDocument();
    expect(screen.queryByText('company-internal-id')).not.toBeInTheDocument();
    expect(screen.queryByText('department-internal-id')).not.toBeInTheDocument();
  });
});

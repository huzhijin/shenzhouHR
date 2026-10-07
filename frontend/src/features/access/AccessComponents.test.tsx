import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import {
  AccountStatusPanel,
  capabilityDomainLabel,
  capabilityLabel,
  PermissionMatrix,
  RoleOverviewCards,
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

  it('uses plain labels for current attendance and leave capabilities', () => {
    expect(capabilityLabel('ATTENDANCE_LOCATION:READ')).toBe('考勤地点 · 查看');
    expect(capabilityLabel('ATTENDANCE_FEEDBACK:MANAGE')).toBe('考勤反馈 · 管理');
    expect(capabilityLabel('LEAVE_ACCOUNT:MATERIALIZE')).toBe('假期账户 · 生成账户');
    expect(capabilityLabel('LEAVE_MANAGEMENT:EXPORT')).toBe('假期管理 · 导出');
    expect(capabilityDomainLabel('ATTENDANCE_LOCATION:READ')).toBe('考勤地点');
  });
});

describe('RoleOverviewCards', () => {
  it('explains supported roles and hides retired manufacturing roles', () => {
    render(
      <RoleOverviewCards
        roles={[
          {
            roleId: 'system-admin-role',
            roleCode: 'SYSTEM_ADMIN',
            roleName: '系统管理员',
            capabilities: ['ACCOUNT:READ', 'ACCOUNT:EDIT', 'ROLE:ASSIGN'],
          },
          {
            roleId: 'retired-role',
            roleCode: 'MANUFACTURING_CENTER_SUPERVISOR',
            roleName: '制造中心主管',
            capabilities: ['EMPLOYEE:READ'],
          },
          {
            roleId: 'retired-name-alias',
            roleCode: 'LEGACY_CENTER_DIRECTOR',
            roleName: '制造中心主任',
            capabilities: ['EMPLOYEE:READ'],
          },
        ]}
      />,
    );

    expect(screen.getByRole('heading', { name: '各角色能做什么' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '系统管理员' })).toBeInTheDocument();
    expect(screen.getByText('维护系统配置、账号和权限。这是最高权限，只建议分配给系统负责人。')).toBeInTheDocument();
    expect(screen.getByText('公司范围')).toBeInTheDocument();
    expect(screen.getByText('3 项权限')).toBeInTheDocument();
    expect(screen.getByText('账号')).toBeInTheDocument();
    expect(screen.getByText('共 1 个角色')).toBeInTheDocument();
    expect(screen.queryByText('制造中心主管')).not.toBeInTheDocument();
    expect(screen.queryByText('制造中心主任')).not.toBeInTheDocument();
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
            scopeCompanyName: '江苏神州半导体科技股份有限公司',
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
            scopeCompanyName: '江苏神州半导体科技股份有限公司',
            scopeResourceName: '人力资源部',
            scopeResourcePath: '总部 / 人力资源部',
            includeDescendants: false,
            validFrom: '2026-07-01T00:00:00Z',
            validTo: null,
          },
        ]}
      />,
    );

    expect(screen.getByText('江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();
    expect(screen.getByText('江苏神州半导体科技股份有限公司 / 总部 / 人力资源部'))
      .toBeInTheDocument();
    expect(screen.getByText('仅所选部门')).toBeInTheDocument();
    expect(screen.queryByText('company-internal-id')).not.toBeInTheDocument();
    expect(screen.queryByText('department-internal-id')).not.toBeInTheDocument();
  });
});

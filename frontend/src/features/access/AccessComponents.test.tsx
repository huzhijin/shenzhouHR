import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../../shared/i18n/i18n';
import {
  capabilityLabel,
  PermissionMatrix,
  roleScopeLabel,
} from './AccessComponents';

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
    expect(roleScopeLabel('ORGANIZATION')).toBe('组织范围');
    expect(roleScopeLabel('SELF')).toBe('仅本人');
  });
});

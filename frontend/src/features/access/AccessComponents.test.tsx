import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import '../../shared/i18n/i18n';
import { PermissionMatrix } from './AccessComponents';

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
    expect(screen.getByRole('checkbox', { name: '本地系统管理员 ACCOUNT:READ' }))
      .toBeChecked();
    expect(screen.queryByText('undefined')).not.toBeInTheDocument();
  });
});

import { describe, expect, it } from 'vitest';

import type { RoleView } from './accessApi';
import {
  allowedScopeTypes,
  editableRoleAssignments,
  roleAssignmentRequests,
} from './roleScopePolicy';

describe('roleScopePolicy', () => {
  it.each([
    ['EMPLOYEE_SELF', ['SELF']],
    ['DEPARTMENT_HEAD', ['ORGANIZATION']],
    ['HR_ADMIN', ['COMPANY']],
    ['SYSTEM_ADMIN', ['COMPANY']],
    ['AUDITOR', ['COMPANY']],
    ['EXECUTIVE', ['COMPANY', 'ORGANIZATION']],
  ])('presents the backend role-scope matrix for %s', (roleCode, expected) => {
    expect(allowedScopeTypes(role(roleCode))).toEqual(expected);
  });

  it('fails closed for an unknown role code', () => {
    expect(allowedScopeTypes(role('UNSIGNED_FUTURE_ROLE'))).toEqual([]);
    expect(allowedScopeTypes(role('MANUFACTURING_CENTER_SUPERVISOR'))).toEqual([]);
  });

  it('round-trips multiple scopes for the same role without collapsing validity', () => {
    const assignments = [
      {
        assignmentId: 'assignment-1',
        roleId: 'executive-role',
        roleCode: 'EXECUTIVE',
        roleName: '高管',
        scopeType: 'ORGANIZATION' as const,
        scopeResourceId: 'organization-1',
        scopeCompanyId: 'company-1',
        includeDescendants: false,
        validFrom: '2026-07-01T00:00:00Z',
        validTo: '2026-12-31T16:00:00Z',
      },
      {
        assignmentId: 'assignment-2',
        roleId: 'executive-role',
        roleCode: 'EXECUTIVE',
        roleName: '高管',
        scopeType: 'ORGANIZATION' as const,
        scopeResourceId: 'organization-2',
        scopeCompanyId: 'company-2',
        includeDescendants: true,
        validFrom: '2026-08-01T00:00:00Z',
        validTo: null,
      },
    ];

    const rows = editableRoleAssignments(assignments);

    expect(rows).toHaveLength(2);
    expect(rows.map((row) => row.key)).toEqual(['assignment-1', 'assignment-2']);
    expect(roleAssignmentRequests(rows)).toEqual([
      {
        roleId: 'executive-role',
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-1',
        includeDescendants: false,
        validFrom: '2026-07-01T00:00:00Z',
        validTo: '2026-12-31T16:00:00Z',
      },
      {
        roleId: 'executive-role',
        scopeType: 'ORGANIZATION',
        scopeResourceId: 'organization-2',
        includeDescendants: true,
        validFrom: '2026-08-01T00:00:00Z',
        validTo: null,
      },
    ]);
  });
});

function role(roleCode: string): RoleView {
  return {
    roleId: `role-${roleCode}`,
    roleCode,
    roleName: roleCode,
    capabilities: [],
  };
}

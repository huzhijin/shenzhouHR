import type {
  RoleAssignmentRequest,
  RoleAssignmentView,
  RoleView,
} from './accessApi';

export type RoleScopeType = RoleAssignmentRequest['scopeType'];
export type EditableRoleAssignment = RoleAssignmentRequest & {
  key: string;
  scopeCompanyId: string | null;
  includeDescendants: boolean;
};

const roleScopeMatrix: Readonly<Record<string, readonly RoleScopeType[]>> = {
  EMPLOYEE: ['SELF'],
  EMPLOYEE_SELF: ['SELF'],
  DEPARTMENT_MANAGER: ['ORGANIZATION'],
  DEPARTMENT_HEAD: ['ORGANIZATION'],
  HR_ADMIN: ['COMPANY'],
  SYSTEM_ADMIN: ['COMPANY'],
  AUDITOR: ['COMPANY'],
  EXECUTIVE: ['COMPANY', 'ORGANIZATION'],
};

/**
 * UI affordance only. AccountAccessService independently enforces the same
 * fail-closed matrix and all resource/organizational containment checks.
 */
export function allowedScopeTypes(role: Pick<RoleView, 'roleCode'>): readonly RoleScopeType[] {
  return roleScopeMatrix[role.roleCode] ?? [];
}

export function editableRoleAssignments(
  assignments: RoleAssignmentView[],
): EditableRoleAssignment[] {
  return assignments.map((assignment) => ({
    key: assignment.assignmentId,
    roleId: assignment.roleId,
    scopeType: assignment.scopeType,
    scopeResourceId: assignment.scopeResourceId,
    scopeCompanyId: assignment.scopeCompanyId
      ?? (assignment.scopeType === 'COMPANY' ? assignment.scopeResourceId : null),
    includeDescendants: assignment.scopeType === 'ORGANIZATION'
      ? assignment.includeDescendants !== false
      : assignment.scopeType === 'COMPANY',
    validFrom: assignment.validFrom,
    validTo: assignment.validTo,
  }));
}

export function roleAssignmentRequests(
  assignments: EditableRoleAssignment[],
): RoleAssignmentRequest[] {
  return assignments.map(({
    roleId,
    scopeType,
    scopeResourceId,
    includeDescendants,
    validFrom,
    validTo,
  }) => ({
    roleId,
    scopeType,
    scopeResourceId,
    includeDescendants,
    validFrom,
    validTo,
  }));
}

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const detailSource = readFileSync(
  resolve(process.cwd(), 'src/features/access/AccountDetailPage.tsx'),
  'utf8',
);
const accountsSource = readFileSync(
  resolve(process.cwd(), 'src/features/access/AccountsPage.tsx'),
  'utf8',
);

describe('account role assignment source contract', () => {
  it('uses assignment rows and never collapses authorization state by role id', () => {
    expect(detailSource).toContain('EditableRoleAssignment[]');
    expect(detailSource).toContain('editableRoleAssignments(resource.data.roles)');
    expect(detailSource).toContain('roleAssignmentRequests(roleAssignments)');
    expect(detailSource).not.toContain("scopeResourceId: '9700000000000000001'");
    expect(detailSource).not.toContain('setSelectedRoles(Array.from(new Set');
  });

  it('sends explicit ISO instants and preserves loaded validity by default', () => {
    expect(detailSource).toContain('showTime');
    expect(detailSource).toContain('value?.toISOString()');
    expect(detailSource).not.toContain('dateString');
    expect(detailSource).not.toContain('validFrom: new Date().toISOString(),\\n        validTo: effectiveTo');
  });

  it('uses human-readable employee, company and department selectors for account creation', () => {
    expect(accountsSource).toContain('<EmployeeSelect');
    expect(accountsSource).toContain('<GrantableCompanySelect');
    expect(accountsSource).toContain('<GrantableOrganizationSelect');
    expect(accountsSource).toContain('<Form.List');
    expect(accountsSource).toContain("assignment.scopeType === 'SELF'");
    expect(accountsSource).toContain("assignment.scopeType === 'COMPANY'");
    expect(accountsSource).toContain("assignment.scopeType === 'ORGANIZATION'");
    expect(accountsSource).toContain('includeDescendants');
    expect(accountsSource).toContain('scopeCompanyId');
    expect(accountsSource).toContain("'ACCOUNT_CREATION'");
    expect(accountsSource).toContain('canIncludeDescendants');
    expect(accountsSource).toContain('companyScopeCompanies.map((company) => ({');
    expect(accountsSource).toContain('以后新增公司不会自动扩权');
  });

  it('uses scope-specific selectors when editing role assignments', () => {
    expect(detailSource).toContain("assignment.scopeType === 'COMPANY'");
    expect(detailSource).toContain('<GrantableCompanySelect');
    expect(detailSource).toContain("assignment.scopeType === 'ORGANIZATION'");
    expect(detailSource).toContain('<GrantableOrganizationSelect');
    expect(detailSource).toContain('包含下级部门');
    expect(detailSource).toContain('scopeCompanyId');
    expect(detailSource).toContain('companyScopeCompanies.map((company) => newAssignment(');
    expect(detailSource).toContain('以后新增公司需在这里手动添加');
    expect(detailSource).not.toContain(
      'value={assignment.scopeResourceId ?? \'\'}',
    );
  });
});

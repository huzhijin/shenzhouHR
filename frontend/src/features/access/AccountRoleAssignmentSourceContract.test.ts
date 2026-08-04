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
    expect(accountsSource).toContain('<CompanySelect');
    expect(accountsSource).toContain('<OrganizationSelect');
    expect(accountsSource).toContain("selectedScopeType === 'SELF'");
    expect(accountsSource).toContain("selectedScopeType === 'COMPANY'");
    expect(accountsSource).toContain("selectedScopeType === 'ORGANIZATION'");
  });

  it('uses scope-specific selectors when editing role assignments', () => {
    expect(detailSource).toContain("assignment.scopeType === 'COMPANY'");
    expect(detailSource).toContain('<CompanySelect');
    expect(detailSource).toContain("assignment.scopeType === 'ORGANIZATION'");
    expect(detailSource).toContain('<OrganizationSelect');
    expect(detailSource).not.toContain(
      'value={assignment.scopeResourceId ?? \'\'}',
    );
  });
});

import { describe, expect, it } from 'vitest';

import { canBulkProvisionEmployeeAccounts } from './AccountsPage';

describe('canBulkProvisionEmployeeAccounts', () => {
  it('requires both account creation and role assignment capabilities', () => {
    expect(canBulkProvisionEmployeeAccounts(['ACCOUNT:CREATE', 'ROLE:ASSIGN'])).toBe(true);
    expect(canBulkProvisionEmployeeAccounts(['ACCOUNT:CREATE'])).toBe(false);
    expect(canBulkProvisionEmployeeAccounts(['ROLE:ASSIGN'])).toBe(false);
    expect(canBulkProvisionEmployeeAccounts([])).toBe(false);
  });
});

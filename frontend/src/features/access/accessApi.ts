import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { passwordMeetsPolicy } from '../../shared/security/passwordPolicy';

export type AccountStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED';

export interface AccountSummary {
  accountId: string;
  username: string;
  displayName: string;
  status: AccountStatus;
  firstPasswordChangeRequired: boolean;
  resetPending?: boolean;
  lockedUntil?: string | null;
  lastLoginAt?: string | null;
  rowVersion: number;
}

export interface SessionSummary {
  sessionId: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  createdAt: string;
  lastSeenAt?: string;
  idleExpiresAt: string;
  absoluteExpiresAt: string;
  revokedAt?: string | null;
  revocationReason?: string | null;
}

export interface RoleAssignmentRequest {
  roleId: string;
  scopeType: 'COMPANY' | 'ORGANIZATION' | 'SELF';
  scopeResourceId: string | null;
  validFrom: string;
  validTo: string | null;
}

export interface RoleAssignmentView extends RoleAssignmentRequest {
  assignmentId: string;
  roleCode: string;
  roleName: string;
}

export interface AccountDetail extends AccountSummary {
  roles: RoleAssignmentView[];
  sessions: SessionSummary[];
}

export interface AccountPage {
  items: AccountSummary[];
  total: number;
  page: number;
  size: number;
}

export type EmployeeAccountCandidateStatus =
  | 'AVAILABLE'
  | 'ALREADY_PROVISIONED'
  | 'USERNAME_CONFLICT';

export interface EmployeeAccountCandidate {
  employeeId: string;
  companyId: string;
  employeeNumber: string;
  displayName: string;
  organizationName?: string | null;
  status: EmployeeAccountCandidateStatus;
}

export interface EmployeeAccountCandidatePage {
  items: EmployeeAccountCandidate[];
  total: number;
  available: number;
  alreadyProvisioned: number;
  usernameConflicts: number;
  page: number;
  size: number;
}

export interface TemporaryCredential {
  accountId: string;
  employeeId: string;
  employeeNumber: string;
  displayName: string;
  organizationName?: string | null;
  username: string;
  temporaryPassword: string;
}

export interface BulkAccountCreationResult {
  credentials: TemporaryCredential[];
  created: number;
  replayed: boolean;
}

export interface RoleView {
  roleId: string;
  roleCode: string;
  roleName: string;
  capabilities: string[];
}

export function isStrongTemporaryPassword(value: string): boolean {
  return passwordMeetsPolicy(value);
}

export interface AccountFilters {
  q?: string;
  status?: AccountStatus;
  page?: number;
  size?: number;
}

const demoAccounts: AccountSummary[] = [
  {
    accountId: '9100000000000000001',
    username: 'synthetic.admin',
    displayName: '合成系统管理员',
    status: 'ACTIVE',
    firstPasswordChangeRequired: false,
    resetPending: false,
    lastLoginAt: '2026-07-24T08:32:00Z',
    rowVersion: 4,
  },
  {
    accountId: '9100000000000000002',
    username: 'synthetic.policy',
    displayName: '合成规则管理员',
    status: 'LOCKED',
    firstPasswordChangeRequired: true,
    resetPending: true,
    lockedUntil: '2026-07-24T10:15:00Z',
    rowVersion: 2,
  },
];

const demoRoles: RoleView[] = [
  {
    roleId: '9200000000000000001',
    roleCode: 'LOCAL_SYSTEM_ADMIN',
    roleName: '本地系统管理员',
    capabilities: [
      'ACCOUNT:READ', 'ACCOUNT:CREATE', 'ACCOUNT:EDIT', 'ACCOUNT:LOCK',
      'ACCOUNT:UNLOCK', 'ACCOUNT:RESET_PASSWORD', 'ROLE:READ', 'ROLE:ASSIGN',
      'AUDIT:READ', 'POLICY:READ', 'POLICY:CREATE', 'POLICY:EDIT',
      'POLICY:VALIDATE', 'POLICY:SIMULATE', 'POLICY:PUBLISH',
      'POLICY:DEACTIVATE', 'POLICY:ROLLBACK',
    ],
  },
  {
    roleId: '9200000000000000002',
    roleCode: 'POLICY_OPERATOR',
    roleName: '规则配置员',
    capabilities: ['POLICY:READ', 'POLICY:CREATE', 'POLICY:EDIT', 'POLICY:VALIDATE', 'POLICY:SIMULATE'],
  },
];

export function listAccounts(filters: AccountFilters = {}): Promise<AccountPage> {
  if (isDemoMode()) {
    const q = filters.q?.trim().toLowerCase();
    const items = demoAccounts.filter((account) => (
      (!q || account.username.includes(q) || account.displayName.includes(q))
      && (!filters.status || account.status === filters.status)
    ));
    return Promise.resolve({ items, total: items.length, page: 0, size: filters.size ?? 20 });
  }
  const params = new URLSearchParams();
  if (filters.q) params.set('query', filters.q);
  if (filters.status) params.set('status', filters.status);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 20));
  return requestJson<AccountPage>(`/api/v1/access/accounts?${params}`);
}

export function getAccount(accountId: string): Promise<AccountDetail> {
  if (isDemoMode()) {
    const account = demoAccounts.find((item) => item.accountId === accountId) ?? demoAccounts[0];
    if (!account) return Promise.reject(new Error('DEMO_ACCOUNT_NOT_FOUND'));
    return Promise.resolve({
      ...account,
      roles: [{
        assignmentId: '9250000000000000001',
        roleId: demoRoles[0]?.roleId ?? '',
        roleCode: demoRoles[0]?.roleCode ?? '',
        roleName: demoRoles[0]?.roleName ?? '',
        scopeType: 'COMPANY',
        scopeResourceId: '9700000000000000001',
        validFrom: '2026-07-01T00:00:00Z',
        validTo: null,
      }],
      sessions: [{
        sessionId: '9300000000000000001',
        status: account.status === 'ACTIVE' ? 'ACTIVE' : 'REVOKED',
        createdAt: '2026-07-24T08:00:00Z',
        lastSeenAt: '2026-07-24T08:32:00Z',
        idleExpiresAt: '2026-07-24T09:00:00Z',
        absoluteExpiresAt: '2026-07-24T16:00:00Z',
      }],
    });
  }
  return requestJson<AccountDetail>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}`);
}

export function createAccount(input: {
  username: string;
  displayName: string;
  temporaryPassword?: string;
  employeeId?: string | null;
  roleAssignments: RoleAssignmentRequest[];
}): Promise<AccountDetail> {
  if (isDemoMode()) return getAccount('9100000000000000001');
  return requestJson<AccountDetail>('/api/v1/access/accounts', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function listEmployeeAccountCandidates(input: {
  companyId: string;
  query?: string;
  page?: number;
  size?: number;
}): Promise<EmployeeAccountCandidatePage> {
  if (isDemoMode()) {
    return Promise.resolve({
      items: [],
      total: 0,
      available: 0,
      alreadyProvisioned: 0,
      usernameConflicts: 0,
      page: input.page ?? 0,
      size: input.size ?? 20,
    });
  }
  const params = new URLSearchParams({
    companyId: input.companyId,
    page: String(input.page ?? 0),
    size: String(input.size ?? 20),
  });
  if (input.query?.trim()) params.set('query', input.query.trim());
  return requestJson<EmployeeAccountCandidatePage>(
    `/api/v1/access/account-provisioning/candidates?${params}`,
  );
}

export function createEmployeeAccounts(
  employeeIds: string[],
  idempotencyKey: string,
  recoveryKey: string,
): Promise<BulkAccountCreationResult> {
  if (isDemoMode()) return Promise.resolve({ credentials: [], created: 0, replayed: false });
  return requestJson<BulkAccountCreationResult>(
    '/api/v1/access/account-provisioning/accounts',
    {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey,
        'Provisioning-Recovery-Key': recoveryKey,
      },
      body: JSON.stringify({ employeeIds }),
    },
  );
}

export function updateAccountStatus(
  accountId: string,
  status: Exclude<AccountStatus, 'LOCKED'>,
  expectedVersion: number,
  reason: string,
): Promise<AccountDetail> {
  if (isDemoMode()) return getAccount(accountId);
  return requestJson<AccountDetail>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status, expectedVersion, reason }),
  });
}

export function lockAccount(accountId: string, reason: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}/lock`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function unlockAccount(accountId: string, reason: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}/unlock`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function issuePasswordReset(accountId: string, reason: string): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}/password-reset-grants`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function resetTemporaryPassword(
  accountId: string,
  reason: string,
  temporaryPassword: string,
): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(
    `/api/v1/access/accounts/${encodeURIComponent(accountId)}/temporary-password-reset`,
    {
      method: 'POST',
      body: JSON.stringify({
        reason,
        temporaryPassword,
      }),
    },
  );
}

export function listRoles(): Promise<RoleView[]> {
  if (isDemoMode()) return Promise.resolve(demoRoles);
  return requestJson<RoleView[]>('/api/v1/access/roles');
}

export function assignRoles(
  accountId: string,
  assignments: RoleAssignmentRequest[],
  reason: string,
  expectedVersion: number,
): Promise<void> {
  if (isDemoMode()) return Promise.resolve();
  return requestJson<void>(`/api/v1/access/accounts/${encodeURIComponent(accountId)}/role-assignments`, {
    method: 'PUT',
    body: JSON.stringify({ assignments, reason, expectedVersion }),
  });
}

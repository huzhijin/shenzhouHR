import { IconKey, IconLockOpen, IconPlus, IconPlayerPause, IconRefresh, IconTrash } from '@tabler/icons-react';
import { Alert, Button, DatePicker, Form, Input, Select, Space } from 'antd';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { ConfirmationDialog, OperationFeedback } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { revokeSession } from '../auth/authApi';
import {
  CompanySelect,
  OrganizationSelect,
} from '../referenceData';
import {
  AccountStatusPanel,
  PermissionMatrix,
  RoleScopeList,
  SessionStatusPanel,
} from './AccessComponents';
import {
  assignRoles,
  getAccount,
  isStrongTemporaryPassword,
  listRoles,
  resetTemporaryPassword,
  unlockAccount,
  updateAccountStatus,
  type RoleView,
} from './accessApi';
import {
  allowedScopeTypes,
  editableRoleAssignments,
  roleAssignmentRequests,
  type EditableRoleAssignment,
  type RoleScopeType,
} from './roleScopePolicy';

type Operation = 'disable' | 'enable' | 'unlock' | 'reset' | 'revoke-session';
let newAssignmentSequence = 0;

export function AccountDetailPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const { accountId = '' } = useParams();
  const accountLoader = useMemo(() => () => getAccount(accountId), [accountId]);
  const { resource, reload } = useAsyncResource(accountLoader, () => false, [accountId]);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [roleAssignments, setRoleAssignments] = useState<EditableRoleAssignment[]>([]);
  const [operation, setOperation] = useState<Operation>();
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [reason, setReason] = useState('');
  const [temporaryPassword, setTemporaryPassword] = useState('');
  const [temporaryPasswordError, setTemporaryPasswordError] = useState<string>();
  const [sessionTarget, setSessionTarget] = useState<string>();
  const selectedRoles = useMemo(
    () => Array.from(new Set(roleAssignments.map((assignment) => assignment.roleId))),
    [roleAssignments],
  );

  const loadRoles = async () => {
    const result = await listRoles();
    setRoles(result);
    if (resource.status === 'ready') {
      setRoleAssignments(editableRoleAssignments(resource.data.roles));
    }
  };

  const confirmOperation = async () => {
    if (!operation || resource.status !== 'ready') return;
    if (
      operation === 'reset'
      && !isStrongTemporaryPassword(temporaryPassword)
    ) {
      setTemporaryPasswordError(
        !temporaryPassword
          ? t('access.temporaryPasswordRequired')
          : t('access.initialPasswordRule'),
      );
      return;
    }
    setProcessing(true);
    try {
      if (operation === 'unlock') await unlockAccount(accountId, reason);
      if (operation === 'reset') {
        const passwordForRequest = temporaryPassword;
        setTemporaryPassword('');
        await resetTemporaryPassword(accountId, reason, passwordForRequest);
      }
      if (operation === 'revoke-session' && sessionTarget) await revokeSession(sessionTarget, reason);
      if (operation === 'disable' || operation === 'enable') {
        await updateAccountStatus(accountId, operation === 'disable' ? 'DISABLED' : 'ACTIVE', resource.data.rowVersion, reason);
      }
      setFeedback(t('access.operationCompleted'));
      setOperation(undefined);
      setReason('');
      setTemporaryPassword('');
      setTemporaryPasswordError(undefined);
      setSessionTarget(undefined);
      reload();
    } finally {
      setProcessing(false);
    }
  };

  const saveRoles = async () => {
    if (resource.status !== 'ready') return;
    if (!roleConfigurationComplete(roleAssignments, roles)) return;
    setProcessing(true);
    try {
      await assignRoles(
        accountId,
        roleAssignmentRequests(roleAssignments),
        t('access.roleUpdateReason'),
        resource.data.rowVersion,
      );
      setFeedback(t('access.rolesUpdated'));
      reload();
    } finally {
      setProcessing(false);
    }
  };

  if (resource.status === 'loading' || resource.status === 'partial-loading') return <StatePanel state={resource.status} />;
  if ('error' in resource) return <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} />;
  if (resource.status !== 'ready') return <StatePanel state="404" />;
  const account = resource.data;
  const openUnlock = () => setOperation('unlock');
  const openStatusChange = () => setOperation(account.status === 'DISABLED' ? 'enable' : 'disable');
  const openReset = () => {
    setTemporaryPassword('');
    setTemporaryPasswordError(undefined);
    setOperation('reset');
  };
  const handleLoadRoles = () => {
    void loadRoles();
  };
  const handleSaveRoles = () => {
    void saveRoles();
  };
  const updateSelectedRoles = (roleIds: string[]) => {
    setRoleAssignments((current) => {
      const next = current.filter((assignment) => roleIds.includes(assignment.roleId));
      for (const roleId of roleIds) {
        if (next.some((assignment) => assignment.roleId === roleId)) continue;
        const role = roles.find((candidate) => candidate.roleId === roleId);
        const scopeType = role ? allowedScopeTypes(role)[0] : undefined;
        if (scopeType) next.push(newAssignment(roleId, scopeType));
      }
      return next;
    });
  };
  const updateAssignment = (
    key: string,
    patch: Partial<EditableRoleAssignment>,
  ) => setRoleAssignments((current) => current.map((assignment) => (
    assignment.key === key ? { ...assignment, ...patch } : assignment
  )));
  const addAssignment = (role: RoleView) => {
    const scopeType = allowedScopeTypes(role)[0];
    if (!scopeType) return;
    setRoleAssignments((current) => [
      ...current,
      newAssignment(role.roleId, scopeType),
    ]);
  };
  const removeAssignment = (key: string) => {
    setRoleAssignments((current) => current.filter((assignment) => assignment.key !== key));
  };
  const assignmentsComplete = roleConfigurationComplete(roleAssignments, roles);

  return (
    <>
      <PageHeader
        title={account.displayName}
        description={t('access.localAccount', { username: account.username })}
        breadcrumbs={[{ label: t('access.section') }, { label: t('access.accounts'), path: '/access/accounts' }, { label: account.username }]}
        actions={(
          <Space wrap>
            {capabilities.includes('ACCOUNT:UNLOCK') && account.status === 'LOCKED' ? <Button icon={<IconLockOpen stroke={2} />} onClick={openUnlock}>{t('access.unlock')}</Button> : null}
            {capabilities.includes('ACCOUNT:EDIT') ? <Button icon={<IconPlayerPause stroke={2} />} onClick={openStatusChange}>{account.status === 'DISABLED' ? t('status.active') : t('status.disabled')}</Button> : null}
            {capabilities.includes('ACCOUNT:RESET_PASSWORD') ? <Button icon={<IconKey stroke={2} />} onClick={openReset}>{t('access.resetPassword')}</Button> : null}
          </Space>
        )}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <div className="detail-grid">
        <section className="content-surface"><AccountStatusPanel account={account} /></section>
        <section className="content-surface"><SessionStatusPanel sessions={account.sessions} onRevoke={capabilities.includes('ACCOUNT:EDIT') ? (sessionId) => {
          setSessionTarget(sessionId);
          setOperation('revoke-session');
        } : undefined} /></section>
      </div>
      <section className="content-surface section-spaced">
        <div className="section-heading">
          <div><h2>{t('access.rolesAndScopes')}</h2><p>{t('access.rolesAndScopesDescription')}</p></div>
          <Button icon={<IconRefresh stroke={2} />} onClick={handleLoadRoles}>{t('access.loadRoles')}</Button>
        </div>
        <RoleScopeList assignments={account.roles} />
        {roles.length > 0 ? (
          <>
            <PermissionMatrix roles={roles} selectedRoleIds={selectedRoles} onChange={updateSelectedRoles} />
            <Form layout="vertical" className="authorization-validity">
              {selectedRoles.map((roleId) => {
                const role = roles.find((candidate) => candidate.roleId === roleId);
                const allowedScopes = role ? allowedScopeTypes(role) : [];
                const rows = roleAssignments.filter((assignment) => assignment.roleId === roleId);
                if (!role) return null;
                return (
                  <div key={roleId} className="role-assignment-group">
                    <div className="section-heading">
                      <h3>{role.roleName}</h3>
                      <Button icon={<IconPlus stroke={2} />} onClick={() => addAssignment(role)}>
                        {t('access.addScopeAssignment')}
                      </Button>
                    </div>
                    {rows.map((assignment, index) => (
                      <div key={assignment.key} className="role-assignment-row">
                        <Form.Item label={`${t('access.scopeType')} ${index + 1}`}>
                          <Select
                            value={assignment.scopeType}
                            disabled={allowedScopes.length <= 1}
                            options={allowedScopes.map((scopeType) => ({
                              value: scopeType,
                              label: scopeType === 'COMPANY'
                                ? t('access.company')
                                : scopeType === 'ORGANIZATION'
                                  ? t('access.organization')
                                  : t('access.self'),
                            }))}
                            onChange={(scopeType: RoleScopeType) => updateAssignment(
                              assignment.key,
                              {
                                scopeType,
                                scopeResourceId: scopeType === 'SELF' ? null : '',
                              },
                            )}
                          />
                        </Form.Item>
                        <Form.Item
                          label={`${t('access.scopeTarget')} ${index + 1}`}
                          required={assignment.scopeType !== 'SELF'}
                        >
                          {assignment.scopeType === 'COMPANY' ? (
                            <CompanySelect
                              value={assignment.scopeResourceId ?? undefined}
                              aria-label={`${t('access.company')} ${index + 1}`}
                              onChange={(scopeResourceId) => updateAssignment(
                                assignment.key,
                                { scopeResourceId: scopeResourceId ?? '' },
                              )}
                            />
                          ) : null}
                          {assignment.scopeType === 'ORGANIZATION' ? (
                            <OrganizationSelect
                              value={assignment.scopeResourceId ?? undefined}
                              aria-label={`${t('access.organization')} ${index + 1}`}
                              onChange={(scopeResourceId) => updateAssignment(
                                assignment.key,
                                { scopeResourceId: scopeResourceId ?? '' },
                              )}
                            />
                          ) : null}
                          {assignment.scopeType === 'SELF' ? (
                            <Select
                              aria-label={`${t('access.self')} ${index + 1}`}
                              disabled
                              value="SELF"
                              options={[{
                                value: 'SELF',
                                label: t('access.currentAccountSelf'),
                              }]}
                            />
                          ) : null}
                        </Form.Item>
                        <Form.Item label={`${t('access.authorizationStart')} ${index + 1}`} required>
                          <DatePicker
                            showTime
                            value={dayjs(assignment.validFrom)}
                            onChange={(value) => updateAssignment(
                              assignment.key,
                              { validFrom: value?.toISOString() ?? '' },
                            )}
                          />
                        </Form.Item>
                        <Form.Item label={`${t('access.authorizationExpiry')} ${index + 1}`}>
                          <DatePicker
                            showTime
                            allowClear
                            value={assignment.validTo ? dayjs(assignment.validTo) : null}
                            onChange={(value) => updateAssignment(
                              assignment.key,
                              { validTo: value?.toISOString() ?? null },
                            )}
                          />
                        </Form.Item>
                        <Button
                          danger
                          icon={<IconTrash stroke={2} />}
                          onClick={() => removeAssignment(assignment.key)}
                        >
                          {t('access.removeScopeAssignment')}
                        </Button>
                      </div>
                    ))}
                  </div>
                );
              })}
              <Button type="primary" loading={processing} disabled={!capabilities.includes('ROLE:ASSIGN') || !assignmentsComplete} onClick={handleSaveRoles}>{t('access.saveAuthorization')}</Button>
            </Form>
          </>
        ) : <p>{t('access.loadRolesHelp')}</p>}
      </section>
      <ConfirmationDialog
        open={Boolean(operation)}
        title={operationTitle(operation, t)}
        description={(
          <div className="account-operation-fields">
            <Input.TextArea
              value={reason}
              aria-label={t('access.changeReason')}
              placeholder={t('access.changeReasonPlaceholder')}
              onChange={(event) => setReason(event.target.value)}
            />
            {operation === 'reset' ? (
              <>
                <Alert
                  showIcon
                  type="warning"
                  title={t('access.resetPasswordNotice')}
                />
                <label htmlFor="account-temporary-password">
                  {t('access.strongTemporaryPassword')}
                </label>
                <Input.Password
                  id="account-temporary-password"
                  value={temporaryPassword}
                  autoComplete="new-password"
                  required
                  aria-required="true"
                  aria-describedby="account-temporary-password-policy"
                  aria-invalid={temporaryPasswordError ? 'true' : undefined}
                  onChange={(event) => {
                    setTemporaryPassword(event.target.value);
                    setTemporaryPasswordError(undefined);
                  }}
                />
                <p id="account-temporary-password-policy" className="form-help">
                  {t('access.initialPasswordRule')}
                </p>
                {temporaryPasswordError ? (
                  <p role="alert">{temporaryPasswordError}</p>
                ) : null}
              </>
            ) : null}
          </div>
        )}
        confirmText={t('access.confirmExecute')}
        danger={operation === 'disable' || operation === 'reset'}
        processing={processing}
        onConfirm={() => void confirmOperation()}
        onCancel={() => {
          setOperation(undefined);
          setReason('');
          setTemporaryPassword('');
          setTemporaryPasswordError(undefined);
        }}
      />
    </>
  );
}

export default AccountDetailPage;

function operationTitle(operation: Operation | undefined, t: (key: string) => string): string {
  if (operation === 'disable') return t('access.confirmDisable');
  if (operation === 'enable') return t('access.confirmEnable');
  if (operation === 'unlock') return t('access.confirmUnlock');
  if (operation === 'revoke-session') return t('access.confirmRevokeSession');
  return t('access.confirmReset');
}

function newAssignment(
  roleId: string,
  scopeType: RoleScopeType,
): EditableRoleAssignment {
  newAssignmentSequence += 1;
  return {
    key: `new-${roleId}-${newAssignmentSequence}`,
    roleId,
    scopeType,
    scopeResourceId: null,
    validFrom: new Date().toISOString(),
    validTo: null,
  };
}

function roleConfigurationComplete(
  assignments: EditableRoleAssignment[],
  roles: RoleView[],
): boolean {
  if (assignments.length === 0) return false;
  const semanticKeys = new Set<string>();
  return assignments.every((assignment) => {
    const role = roles.find((candidate) => candidate.roleId === assignment.roleId);
    const validFrom = Date.parse(assignment.validFrom);
    const validTo = assignment.validTo == null ? null : Date.parse(assignment.validTo);
    const semanticKey = [
      assignment.roleId,
      assignment.scopeType,
      assignment.scopeResourceId ?? '',
      assignment.validFrom,
      assignment.validTo ?? '',
    ].join('\u0000');
    const valid = Boolean(
      role
        && allowedScopeTypes(role).includes(assignment.scopeType)
        && (assignment.scopeType === 'SELF' || assignment.scopeResourceId?.trim())
        && Number.isFinite(validFrom)
        && (validTo == null || Number.isFinite(validTo) && validTo > validFrom),
    );
    if (!valid || semanticKeys.has(semanticKey)) return false;
    semanticKeys.add(semanticKey);
    return true;
  });
}

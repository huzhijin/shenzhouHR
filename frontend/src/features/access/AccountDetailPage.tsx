import { IconKey, IconLockOpen, IconPlayerPause, IconRefresh } from '@tabler/icons-react';
import { Button, DatePicker, Form, Input, Space } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { ConfirmationDialog, OperationFeedback } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { revokeSession } from '../auth/authApi';
import { AccountStatusPanel, PermissionMatrix, SessionStatusPanel } from './AccessComponents';
import {
  assignRoles,
  getAccount,
  issuePasswordReset,
  listRoles,
  unlockAccount,
  updateAccountStatus,
  type RoleView,
} from './accessApi';

type Operation = 'disable' | 'enable' | 'unlock' | 'reset' | 'revoke-session';

export function AccountDetailPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const { accountId = '' } = useParams();
  const accountLoader = useMemo(() => () => getAccount(accountId), [accountId]);
  const { resource, reload } = useAsyncResource(accountLoader, () => false, [accountId]);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [selectedRoles, setSelectedRoles] = useState<string[]>([]);
  const [operation, setOperation] = useState<Operation>();
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [reason, setReason] = useState('');
  const [effectiveTo, setEffectiveTo] = useState<string | null>(null);
  const [sessionTarget, setSessionTarget] = useState<string>();

  const loadRoles = async () => {
    const result = await listRoles();
    setRoles(result);
    if (resource.status === 'ready') setSelectedRoles(Array.from(resource.data.roles, (role) => role.roleId));
  };

  const confirmOperation = async () => {
    if (!operation || resource.status !== 'ready') return;
    setProcessing(true);
    try {
      if (operation === 'unlock') await unlockAccount(accountId, reason);
      if (operation === 'reset') await issuePasswordReset(accountId, reason);
      if (operation === 'revoke-session' && sessionTarget) await revokeSession(sessionTarget, reason);
      if (operation === 'disable' || operation === 'enable') {
        await updateAccountStatus(accountId, operation === 'disable' ? 'DISABLED' : 'ACTIVE', resource.data.rowVersion, reason);
      }
      setFeedback(t('access.operationCompleted'));
      setOperation(undefined);
      setReason('');
      setSessionTarget(undefined);
      reload();
    } finally {
      setProcessing(false);
    }
  };

  const saveRoles = async () => {
    setProcessing(true);
    try {
      await assignRoles(
        accountId,
        Array.from(selectedRoles, (roleId) => ({
          roleId,
          scopeType: 'LEGAL_ENTITY',
          scopeResourceId: '9700000000000000001',
          validFrom: new Date().toISOString(),
          validTo: effectiveTo,
        })),
        t('access.roleUpdateReason'),
        account.rowVersion,
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
  const openReset = () => setOperation('reset');
  const handleLoadRoles = () => {
    void loadRoles();
  };
  const handleSaveRoles = () => {
    void saveRoles();
  };

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
        {roles.length > 0 ? (
          <>
            <PermissionMatrix roles={roles} selectedRoleIds={selectedRoles} onChange={setSelectedRoles} />
            <Form layout="inline" className="authorization-validity">
              <Form.Item label={t('access.authorizationExpiry')}>
                <DatePicker onChange={(_value, dateString) => setEffectiveTo(Array.isArray(dateString) ? dateString[0] ?? null : dateString || null)} />
              </Form.Item>
              <Button type="primary" loading={processing} disabled={!capabilities.includes('ROLE:ASSIGN')} onClick={handleSaveRoles}>{t('access.saveAuthorization')}</Button>
            </Form>
          </>
        ) : <p>{t('access.loadRolesHelp')}</p>}
      </section>
      <ConfirmationDialog
        open={Boolean(operation)}
        title={operationTitle(operation, t)}
        description={<Input.TextArea value={reason} aria-label={t('access.changeReason')} placeholder={t('access.changeReasonPlaceholder')} onChange={(event) => setReason(event.target.value)} />}
        confirmText={t('access.confirmExecute')}
        danger={operation === 'disable' || operation === 'reset'}
        processing={processing}
        onConfirm={() => void confirmOperation()}
        onCancel={() => setOperation(undefined)}
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

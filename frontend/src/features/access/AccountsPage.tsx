import { IconLock, IconPlus, IconSearch } from '@tabler/icons-react';
import { Button, Form, Input, Modal, Select } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { DataTable } from '../../shared/components/DataTable';
import { ConfirmationDialog, OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, QueryFilterBar } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { createAccount, listAccounts, listRoles, lockAccount, type AccountStatus, type AccountSummary, type RoleView } from './accessApi';

export function AccountsPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<AccountStatus>();
  const [createOpen, setCreateOpen] = useState(false);
  const [lockTarget, setLockTarget] = useState<AccountSummary>();
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [form] = Form.useForm();
  const loader = useMemo(() => () => listAccounts({ q: query, status }), [query, status]);
  const { resource, reload } = useAsyncResource(loader, (page) => page.items.length === 0, [query, status]);

  const openCreate = async () => {
    setRoles(await listRoles());
    setCreateOpen(true);
  };

  const create = async (values: {
    username: string;
    displayName: string;
    temporaryPassword: string;
    roleId: string;
    scopeType: 'LEGAL_ENTITY' | 'ORGANIZATION' | 'SELF';
    scopeResourceId?: string;
  }) => {
    setProcessing(true);
    try {
      await createAccount({
        username: values.username,
        displayName: values.displayName,
        temporaryPassword: values.temporaryPassword,
        roleAssignments: [{
          roleId: values.roleId,
          scopeType: values.scopeType,
          scopeResourceId: values.scopeType === 'SELF' ? null : values.scopeResourceId ?? null,
          validFrom: new Date().toISOString(),
          validTo: null,
        }],
      });
      setCreateOpen(false);
      form.resetFields();
      setFeedback(t('access.accountCreated'));
      reload();
    } finally {
      setProcessing(false);
    }
  };

  const confirmLock = async () => {
    if (!lockTarget) return;
    setProcessing(true);
    try {
      await lockAccount(lockTarget.accountId, t('access.lockReason'));
      setFeedback(t('access.accountLocked'));
      setLockTarget(undefined);
      reload();
    } finally {
      setProcessing(false);
    }
  };

  return (
    <>
      <PageHeader
        title={t('access.accounts')}
        description={t('access.description')}
        breadcrumbs={[{ label: t('access.section') }, { label: t('access.accounts') }]}
        actions={capabilities.includes('ACCOUNT:CREATE') ? <Button type="primary" icon={<IconPlus stroke={2} />} onClick={() => void openCreate()}>{t('access.createAccount')}</Button> : undefined}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <section className="content-surface">
        <QueryFilterBar query={query} onQueryChange={setQuery} placeholder={t('access.searchAccounts')}>
          <Select
            allowClear
            aria-label={t('access.accountStatus')}
            placeholder={t('access.allStatuses')}
            value={status}
            onChange={setStatus}
            options={[
              { value: 'ACTIVE', label: t('status.active') },
              { value: 'DISABLED', label: t('status.disabled') },
              { value: 'LOCKED', label: t('status.locked') },
            ]}
          />
          <Button icon={<IconSearch stroke={2} />} onClick={reload}>{t('common.search')}</Button>
        </QueryFilterBar>
        {resource.status === 'loading' ? <StatePanel state="loading" /> : null}
        {resource.status === 'partial-loading' ? <StatePanel state="partial-loading" /> : null}
        {resource.status === 'empty' ? <StatePanel state="empty" description={t('access.noAccounts')} /> : null}
        {'error' in resource ? <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} /> : null}
        {resource.status === 'ready' ? (
          <DataTable
            rows={resource.data.items}
            rowKey={(row) => row.accountId}
            columns={[
              { key: 'username', title: t('access.username'), render: (row) => <Link to={`/access/accounts/${row.accountId}`}>{row.username}</Link> },
              { key: 'displayName', title: t('access.displayName'), render: (row) => row.displayName },
              { key: 'status', title: t('access.status'), render: (row) => <StatusBadge status={row.status} /> },
              { key: 'firstChange', title: t('access.firstChange'), render: (row) => row.firstPasswordChangeRequired ? t('access.pending') : t('access.completed') },
              { key: 'lastLogin', title: t('access.lastLogin'), render: (row) => row.lastLoginAt ? formatTime(row.lastLoginAt) : t('access.neverLoggedIn') },
              {
                key: 'actions',
                title: t('access.actions'),
                render: (row) => capabilities.includes('ACCOUNT:LOCK') && row.status !== 'LOCKED'
                  ? <Button danger size="small" icon={<IconLock stroke={2} />} onClick={() => setLockTarget(row)}>{t('access.lock')}</Button>
                  : <Link to={`/access/accounts/${row.accountId}`}>{t('common.view')}</Link>,
              },
            ]}
          />
        ) : null}
      </section>
      <Modal open={createOpen} title={t('access.createLocalAccount')} okText={t('policy.create')} cancelText={t('common.cancel')} confirmLoading={processing} onOk={() => void form.submit()} onCancel={() => setCreateOpen(false)}>
        <Form form={form} layout="vertical" onFinish={(values) => void create(values)}>
          <Form.Item label={t('access.username')} name="username" rules={[{ required: true, pattern: /^[a-z0-9._-]{3,64}$/, message: t('access.usernameRule') }]}><Input autoComplete="off" /></Form.Item>
          <Form.Item label={t('access.displayName')} name="displayName" rules={[{ required: true, message: t('access.displayNameRequired') }]}><Input /></Form.Item>
          <Form.Item label={t('access.initialPassword')} name="temporaryPassword" rules={[{ required: true, min: 12, message: t('access.initialPasswordRule') }]}><Input.Password autoComplete="new-password" /></Form.Item>
          <Form.Item label={t('access.initialRole')} name="roleId" rules={[{ required: true, message: t('access.roleRequired') }]}>
            <Select options={Array.from(roles, (role) => ({ value: role.roleId, label: `${role.roleName}（${role.roleCode}）` }))} />
          </Form.Item>
          <Form.Item label={t('access.scopeType')} name="scopeType" initialValue="LEGAL_ENTITY" rules={[{ required: true }]}>
            <Select options={[
              { value: 'LEGAL_ENTITY', label: t('access.legalEntity') },
              { value: 'ORGANIZATION', label: t('access.organization') },
              { value: 'SELF', label: t('access.self') },
            ]} />
          </Form.Item>
          <Form.Item label={t('access.scopeResource')} name="scopeResourceId" dependencies={['scopeType']} rules={[
            ({ getFieldValue }) => ({ validator: (_rule, value) => getFieldValue('scopeType') === 'SELF' || value ? Promise.resolve() : Promise.reject(new Error(t('access.scopeResourceRequired'))) }),
          ]}><Input /></Form.Item>
        </Form>
      </Modal>
      <ConfirmationDialog
        open={Boolean(lockTarget)}
        title={t('access.confirmLock')}
        description={<>{t('access.lockDescription')} <strong>{lockTarget?.username}</strong></>}
        confirmText={t('access.lockAccount')}
        danger
        processing={processing}
        onConfirm={() => void confirmLock()}
        onCancel={() => setLockTarget(undefined)}
      />
    </>
  );
}

export default AccountsPage;

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

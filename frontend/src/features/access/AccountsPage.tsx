import { IconLock, IconPlus, IconSearch, IconTrash, IconUsersPlus } from '@tabler/icons-react';
import { Alert, Button, Checkbox, Form, Input, Modal, Select, Space } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { DataTable } from '../../shared/components/DataTable';
import { ConfirmationDialog, OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, QueryFilterBar, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  EmployeeSelect,
} from '../referenceData';
import {
  GrantableCompanySelect,
  GrantableOrganizationSelect,
} from './GrantableScopeSelects';
import {
  createAccount,
  isStrongTemporaryPassword,
  listGrantableCompanies,
  listGrantableOrganizations,
  listAccounts,
  listRoles,
  lockAccount,
  type AccountStatus,
  type AccountSummary,
  type GrantableCompany,
  type GrantableOrganization,
  type RoleView,
} from './accessApi';
import { EmployeeAccountProvisioningDialog } from './EmployeeAccountProvisioningDialog';
import { allowedScopeTypes, type RoleScopeType } from './roleScopePolicy';

interface CreateRoleAssignment {
  roleId?: string;
  scopeType?: RoleScopeType;
  scopeResourceId?: string;
  scopeCompanyId?: string;
  includeDescendants?: boolean;
}

type SubmittedRoleAssignment = CreateRoleAssignment & {
  roleId: string;
  scopeType: RoleScopeType;
};

export function AccountsPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<AccountStatus>();
  const [pageNumber, setPageNumber] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [createOpen, setCreateOpen] = useState(false);
  const [provisioningOpen, setProvisioningOpen] = useState(false);
  const [lockTarget, setLockTarget] = useState<AccountSummary>();
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [companyScopeCompanies, setCompanyScopeCompanies] = useState<GrantableCompany[]>([]);
  const [organizationScopeCompanies, setOrganizationScopeCompanies] = useState<GrantableCompany[]>([]);
  const [organizationsByCompany, setOrganizationsByCompany] = useState<
    Record<string, GrantableOrganization[]>
  >({});
  const [form] = Form.useForm();
  const createRoleAssignments = Form.useWatch<CreateRoleAssignment[]>(
    'roleAssignments',
    form,
  ) ?? [];
  const canCreateAccount = capabilities.includes('ACCOUNT:CREATE')
    && capabilities.includes('ROLE:ASSIGN')
    && capabilities.includes('ROLE:READ');
  const canBulkProvision = canBulkProvisionEmployeeAccounts(capabilities);
  const loader = useMemo(
    () => () => listAccounts({ q: query, status, page: pageNumber, size: pageSize }),
    [pageNumber, pageSize, query, status],
  );
  const { resource, reload } = useAsyncResource(
    loader,
    (page) => page.items.length === 0 && page.total === 0,
    [pageNumber, pageSize, query, status],
  );

  useEffect(() => {
    if (
      resource.status !== 'ready'
      || resource.data.items.length > 0
      || resource.data.total === 0
      || pageNumber === 0
    ) return;
    setPageNumber(Math.max(0, Math.ceil(resource.data.total / pageSize) - 1));
  }, [pageNumber, pageSize, resource]);

  const openCreate = async () => {
    const [loadedRoles, companyCompanies, organizationCompanies] = await Promise.all([
      listRoles(),
      listGrantableCompanies('COMPANY', 'ACCOUNT_CREATION'),
      listGrantableCompanies('ORGANIZATION', 'ACCOUNT_CREATION'),
    ]);
    setRoles(loadedRoles);
    setCompanyScopeCompanies(companyCompanies);
    setOrganizationScopeCompanies(organizationCompanies);
    setOrganizationsByCompany({});
    form.setFieldsValue({ roleAssignments: [{}] });
    setCreateOpen(true);
  };

  const loadCreateOrganizations = async (companyId: string) => {
    if (!companyId || organizationsByCompany[companyId]) return;
    const organizations = await listGrantableOrganizations(
      companyId,
      'ACCOUNT_CREATION',
    );
    setOrganizationsByCompany((current) => ({
      ...current,
      [companyId]: organizations,
    }));
  };

  const create = async (values: {
    username: string;
    displayName: string;
    temporaryPassword?: string;
    employeeId?: string;
    roleAssignments: SubmittedRoleAssignment[];
  }) => {
    setProcessing(true);
    try {
      const validFrom = new Date().toISOString();
      const roleAssignments = values.roleAssignments.map((assignment) => ({
        roleId: assignment.roleId,
        scopeType: assignment.scopeType,
        scopeResourceId: assignment.scopeType === 'SELF'
          ? null
          : assignment.scopeResourceId ?? null,
        includeDescendants: assignment.scopeType === 'ORGANIZATION'
          ? assignment.includeDescendants === true
          : assignment.scopeType === 'COMPANY',
        validFrom,
        validTo: null,
      }));
      const hasSelfScope = values.roleAssignments.some(
        (assignment) => assignment.scopeType === 'SELF',
      );
      await createAccount({
        username: values.username,
        displayName: values.displayName,
        temporaryPassword: values.temporaryPassword,
        employeeId: hasSelfScope ? values.employeeId ?? null : null,
        roleAssignments,
      });
      setCreateOpen(false);
      form.resetFields();
      setFeedback(t('access.accountCreated'));
      reload();
    } finally {
      setProcessing(false);
    }
  };

  const closeCreate = () => {
    setCreateOpen(false);
    form.resetFields();
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
        actions={canCreateAccount ? (
          <Space wrap>
            <Button icon={<IconPlus stroke={2} />} onClick={() => void openCreate()}>
              {t('access.createAccount')}
            </Button>
            {canBulkProvision ? (
              <Button
                type="primary"
                icon={<IconUsersPlus stroke={2} />}
                onClick={() => setProvisioningOpen(true)}
              >
                从员工批量开通
              </Button>
            ) : null}
          </Space>
        ) : undefined}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <section className="content-surface">
        <QueryFilterBar
          query={query}
          onQueryChange={(value) => {
            setQuery(value);
            setPageNumber(0);
          }}
          placeholder={t('access.searchAccounts')}
        >
          <Select
            allowClear
            aria-label={t('access.accountStatus')}
            placeholder={t('access.allStatuses')}
            value={status}
            onChange={(value) => {
              setStatus(value);
              setPageNumber(0);
            }}
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
          <>
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
            <ResourcePagination
              ariaLabel="账号列表分页"
              page={resource.data.page}
              pageSize={resource.data.size}
              total={resource.data.total}
              onChange={(nextPage, nextPageSize) => {
                setPageNumber(nextPage);
                setPageSize(nextPageSize);
              }}
            />
          </>
        ) : null}
      </section>
      <Modal
        className="account-create-modal"
        width={960}
        open={createOpen}
        title={t('access.createLocalAccount')}
        okText={t('policy.create')}
        cancelText={t('common.cancel')}
        confirmLoading={processing}
        onOk={() => void form.submit()}
        onCancel={closeCreate}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void create(values)}>
          <Form.Item label={t('access.username')} name="username" rules={[{ required: true, pattern: /^[A-Za-z0-9._-]{3,128}$/, message: t('access.usernameRule') }]}><Input autoComplete="off" /></Form.Item>
          <Form.Item label={t('access.displayName')} name="displayName" rules={[{ required: true, message: t('access.displayNameRequired') }]}><Input /></Form.Item>
          <Form.Item
            label={t('access.initialPassword')}
            name="temporaryPassword"
            extra={t('access.initialPasswordRule')}
            rules={[
              { required: true, message: t('access.temporaryPasswordRequired') },
              {
                validator: (_rule, value) => !value || isStrongTemporaryPassword(value)
                  ? Promise.resolve()
                  : Promise.reject(new Error(t('access.initialPasswordRule'))),
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          {createRoleAssignments.some((assignment) => (
            roles.find((role) => role.roleId === assignment?.roleId)?.roleCode === 'EXECUTIVE'
          )) ? (
            <Alert
              showIcon
              type="info"
              title="首次选择高管角色时，默认加入当前可授权的全部启用公司；以后新增公司不会自动扩权，请在账号详情中手动添加。"
            />
          ) : null}
          <Form.List
            name="roleAssignments"
            rules={[{
              validator: async (_rule, assignments) => {
                if (!assignments?.length) throw new Error('请至少添加一条角色授权');
              },
            }]}
          >
            {(fields, { add, remove }, { errors }) => (
              <div className="role-assignment-group">
                {fields.map((field, index) => {
                  const assignment = createRoleAssignments[field.name] ?? {};
                  const role = roles.find((candidate) => candidate.roleId === assignment.roleId);
                  const allowedScopes = role ? allowedScopeTypes(role) : [];
                  const organizations = assignment.scopeCompanyId
                    ? organizationsByCompany[assignment.scopeCompanyId] ?? []
                    : [];
                  const selectedOrganization = organizations.find(
                    (organization) => organization.organizationId === assignment.scopeResourceId,
                  );
                  return (
                    <div
                      key={field.key}
                      className="role-assignment-row role-assignment-row--create"
                    >
                      <Form.Item
                        className="role-assignment-field"
                        label={`${t('access.initialRole')} ${index + 1}`}
                        name={[field.name, 'roleId']}
                        rules={[{ required: true, message: t('access.roleRequired') }]}
                      >
                        <Select
                          showSearch
                          optionFilterProp="label"
                          placeholder="输入角色名称搜索"
                          options={roles.map((candidate) => ({
                            value: candidate.roleId,
                            label: candidate.roleName,
                          }))}
                          onChange={(roleId: string) => {
                            const nextRole = roles.find((candidate) => candidate.roleId === roleId);
                            const [scopeType] = nextRole ? allowedScopeTypes(nextRole) : [];
                            const current = [...(form.getFieldValue('roleAssignments') ?? [])];
                            const replacements: CreateRoleAssignment[] = nextRole?.roleCode === 'EXECUTIVE'
                              && scopeType === 'COMPANY'
                              && companyScopeCompanies.length > 0
                              ? companyScopeCompanies.map((company) => ({
                                roleId,
                                scopeType,
                                scopeCompanyId: company.companyId,
                                scopeResourceId: company.companyId,
                                includeDescendants: true,
                              }))
                              : [{
                                roleId,
                                scopeType,
                                includeDescendants: scopeType === 'COMPANY',
                              }];
                            current.splice(field.name, 1, ...replacements);
                            form.setFieldValue('roleAssignments', current);
                          }}
                        />
                      </Form.Item>
                      <Form.Item
                        className="role-assignment-field"
                        label={`${t('access.scopeType')} ${index + 1}`}
                        name={[field.name, 'scopeType']}
                        rules={[{ required: true, message: '请选择数据范围类型' }]}
                      >
                        <Select
                          disabled={allowedScopes.length <= 1}
                          options={allowedScopes.map((scopeType) => ({
                            value: scopeType,
                            label: scopeType === 'COMPANY'
                              ? t('access.company')
                              : scopeType === 'ORGANIZATION'
                                ? t('access.organization')
                                : t('access.self'),
                          }))}
                          onChange={(scopeType: RoleScopeType) => {
                            form.setFieldValue(
                              ['roleAssignments', field.name, 'scopeCompanyId'],
                              undefined,
                            );
                            form.setFieldValue(
                              ['roleAssignments', field.name, 'scopeResourceId'],
                              undefined,
                            );
                            form.setFieldValue(
                              ['roleAssignments', field.name, 'includeDescendants'],
                              scopeType === 'COMPANY',
                            );
                          }}
                        />
                      </Form.Item>
                      {assignment.scopeType === 'COMPANY' ? (
                        <Form.Item
                          className="role-assignment-target"
                          label={`${t('access.company')} ${index + 1}`}
                          name={[field.name, 'scopeResourceId']}
                          rules={[{ required: true, message: t('access.companyRequired') }]}
                        >
                          <GrantableCompanySelect companies={companyScopeCompanies} />
                        </Form.Item>
                      ) : null}
                      {assignment.scopeType === 'ORGANIZATION' ? (
                        <div className="organization-scope-fields role-assignment-target">
                          <Form.Item
                            label={`组织所属公司 ${index + 1}`}
                            name={[field.name, 'scopeCompanyId']}
                            rules={[{ required: true, message: '请先选择公司' }]}
                          >
                            <GrantableCompanySelect
                              companies={organizationScopeCompanies}
                              onChange={(companyId) => {
                                form.setFieldValue(
                                  ['roleAssignments', field.name, 'scopeResourceId'],
                                  undefined,
                                );
                                form.setFieldValue(
                                  ['roleAssignments', field.name, 'includeDescendants'],
                                  false,
                                );
                                if (companyId) void loadCreateOrganizations(companyId);
                              }}
                            />
                          </Form.Item>
                          <Form.Item
                            label={`${t('access.organization')} ${index + 1}`}
                            name={[field.name, 'scopeResourceId']}
                            rules={[{ required: true, message: t('access.organizationRequired') }]}
                          >
                            <GrantableOrganizationSelect
                              organizations={organizations}
                              companySelected={Boolean(assignment.scopeCompanyId)}
                              onChange={() => {
                                form.setFieldValue(
                                  ['roleAssignments', field.name, 'includeDescendants'],
                                  false,
                                );
                              }}
                            />
                          </Form.Item>
                          <Form.Item
                            className="role-assignment-checkbox"
                            name={[field.name, 'includeDescendants']}
                            valuePropName="checked"
                          >
                            <Checkbox disabled={!selectedOrganization?.canIncludeDescendants}>
                              包含下级部门
                            </Checkbox>
                          </Form.Item>
                        </div>
                      ) : null}
                      {assignment.scopeType === 'SELF' ? (
                        <p className="role-assignment-help">
                          该角色仅授权给所绑定员工本人。
                        </p>
                      ) : null}
                      <div className="role-assignment-actions">
                        {role ? (
                          <Button
                            icon={<IconPlus stroke={2} />}
                            aria-label={`为第 ${index + 1} 条角色授权添加范围`}
                            onClick={() => add({
                              roleId: role.roleId,
                              scopeType: allowedScopes[0],
                              includeDescendants: allowedScopes[0] === 'COMPANY',
                            }, field.name + 1)}
                          >
                            添加范围
                          </Button>
                        ) : null}
                        <Button
                          danger
                          disabled={fields.length <= 1}
                          icon={<IconTrash stroke={2} />}
                          aria-label={`删除第 ${index + 1} 条角色授权`}
                          onClick={() => remove(field.name)}
                        >
                          删除
                        </Button>
                      </div>
                    </div>
                  );
                })}
                <Button
                  className="role-assignment-add-role"
                  icon={<IconPlus stroke={2} />}
                  onClick={() => add({})}
                >
                  添加角色
                </Button>
                <Form.ErrorList errors={errors} />
              </div>
            )}
          </Form.List>
          {createRoleAssignments.some((assignment) => assignment?.scopeType === 'SELF') ? (
            <Form.Item
              label={t('access.employeeId')}
              name="employeeId"
              rules={[{ required: true, message: t('access.employeeIdRequired') }]}
            >
              <EmployeeSelect />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>
      {canBulkProvision ? (
        <EmployeeAccountProvisioningDialog
          open={provisioningOpen}
          onClose={() => setProvisioningOpen(false)}
          onCreated={() => {
            setFeedback('员工账号开通结果已更新，请按窗口提示保存账号清单。');
            reload();
          }}
        />
      ) : null}
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

export function canBulkProvisionEmployeeAccounts(capabilities: readonly string[]): boolean {
  return capabilities.includes('ACCOUNT:CREATE') && capabilities.includes('ROLE:ASSIGN');
}

export default AccountsPage;

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

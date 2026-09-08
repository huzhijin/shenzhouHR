import {
  IconBuilding,
  IconEdit,
  IconPlus,
  IconRefresh,
  IconSearch,
} from '@tabler/icons-react';
import {
  Button,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tree,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  ApiRequestError,
  createIdempotencyKey,
} from '../../shared/api/apiClient';
import { OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState, formatDate } from '../people/PeopleCommon';
import { CompanySelect } from '../referenceData';
import {
  createLocalOrganization,
  getCurrentOrganizationTree,
  getOrganization,
  updateLocalOrganization,
  type OrganizationCreateRequest,
  type OrganizationDetail,
  type OrganizationNode,
  type OrganizationUpdateRequest,
} from './organizationApi';
import {
  allOrganizationKeys,
  filterOrganizationNodes,
  topLevelOrganizationKeys,
  toOrganizationTreeData,
  type OrganizationTreeDataNode,
} from './organizationTree';

type OrganizationState =
  | { status: 'loading' }
  | { status: 'ready'; nodes: OrganizationNode[] }
  | { status: 'error'; error: ApiRequestError };

type OrganizationFormValue = {
  companyId?: string;
  parentOrganizationId?: string | null;
  code: string;
  name: string;
  organizationType: OrganizationNode['organizationType'];
  status?: OrganizationNode['status'];
  effectiveFrom: dayjs.Dayjs;
  effectiveTo?: dayjs.Dayjs | null;
  reason: string;
};

export function OrganizationPage({ capabilities = [] }: { capabilities?: string[] }) {
  const { t } = useTranslation();
  const [state, setState] = useState<OrganizationState>({ status: 'loading' });
  const [selectedId, setSelectedId] = useState<string>();
  const [organizationQuery, setOrganizationQuery] = useState('');
  const [detail, setDetail] = useState<OrganizationDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<ApiRequestError>();
  const [formMode, setFormMode] = useState<'create' | 'edit'>();
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const mutationKey = useRef<string | undefined>(undefined);
  const [form] = Form.useForm<OrganizationFormValue>();

  const load = useCallback(() => {
    setState({ status: 'loading' });
    void getCurrentOrganizationTree()
      .then((nodes) => {
        setState({ status: 'ready', nodes });
        setSelectedId((current) => current ?? nodes[0]?.organizationId);
      })
      .catch((error: unknown) => setState({
        status: 'error',
        error: asApiError(error, 'ORGANIZATION_UNAVAILABLE'),
      }));
  }, []);

  const loadDetail = useCallback((organizationId: string) => {
    setDetailLoading(true);
    setDetailError(undefined);
    void getOrganization(organizationId)
      .then((nextDetail) => {
        setDetail(nextDetail);
      })
      .catch((error: unknown) => {
        setDetail(undefined);
        setDetailError(asApiError(error, 'ORGANIZATION_DETAIL_UNAVAILABLE'));
      })
      .finally(() => setDetailLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (selectedId) loadDetail(selectedId);
  }, [loadDetail, selectedId]);

  const filteredOrganizationNodes = useMemo(
    () => state.status === 'ready'
      ? filterOrganizationNodes(state.nodes, organizationQuery)
      : [],
    [organizationQuery, state],
  );
  const treeData = useMemo(
    () => toOrganizationTreeData(filteredOrganizationNodes),
    [filteredOrganizationNodes],
  );
  const parentOptions = useMemo(
    () => state.status === 'ready' ? flattenOrganizations(state.nodes) : [],
    [state],
  );

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({
      parentOrganizationId: selectedId,
      organizationType: 'DEPARTMENT',
      effectiveFrom: dayjs(),
    });
    mutationKey.current = createIdempotencyKey('organization-create');
    setFormMode('create');
  };

  const openEdit = () => {
    if (!detail) return;
    form.setFieldsValue({
      parentOrganizationId: detail.parentOrganizationId,
      code: detail.code,
      name: detail.name,
      organizationType: detail.organizationType,
      status: detail.status,
      effectiveFrom: dayjs(detail.effectiveFrom),
      effectiveTo: detail.effectiveTo ? dayjs(detail.effectiveTo) : null,
    });
    mutationKey.current = createIdempotencyKey(`organization-edit-${detail.organizationId}`);
    setFormMode('edit');
  };

  const save = async () => {
    const values = await form.validateFields();
    setProcessing(true);
    setDetailError(undefined);
    try {
      const key = mutationKey.current ?? createIdempotencyKey('organization-save');
      let saved: OrganizationDetail;
      if (formMode === 'create') {
        saved = await createLocalOrganization(toCreateRequest(values), key);
      } else {
        if (!detail) return;
        saved = await updateLocalOrganization(
          detail.organizationId,
          toUpdateRequest(values),
          detail.rowVersion,
          key,
        );
      }
      setSelectedId(saved.organizationId);
      setDetail(saved);
      setFeedback(t(formMode === 'create' ? 'organization.created' : 'organization.updated'));
      setFormMode(undefined);
      mutationKey.current = undefined;
      load();
      loadDetail(saved.organizationId);
    } catch (error: unknown) {
      setDetailError(asApiError(error, 'ORGANIZATION_WRITE_FAILED'));
    } finally {
      setProcessing(false);
    }
  };

  return (
    <section>
      <PageHeader
        title={t('organization.title')}
        description={t('organization.description')}
        breadcrumbs={[{ label: t('people.section') }, { label: t('organization.title') }]}
        actions={(
          <Space wrap>
            <Button icon={<IconRefresh aria-hidden="true" stroke={2} />} onClick={load}>
              {t('common.refresh')}
            </Button>
            {capabilities.includes('ORGANIZATION:CREATE') ? (
              <Button type="primary" icon={<IconPlus aria-hidden="true" stroke={2} />} onClick={openCreate}>
                {t('organization.create')}
              </Button>
            ) : null}
          </Space>
        )}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <div className="organization-workbench">
        <section className="content-surface organization-workbench__tree" aria-labelledby="organization-tree-title">
          <div className="section-heading">
            <div>
              <h2 id="organization-tree-title">{t('organization.tree')}</h2>
              <p>{t('organization.treeDescription')}</p>
            </div>
          </div>
          <Input
            className="organization-directory-search"
            allowClear
            prefix={<IconSearch aria-hidden="true" stroke={2} />}
            aria-label="搜索部门"
            placeholder="输入部门名称或编码"
            value={organizationQuery}
            onChange={(event) => setOrganizationQuery(event.target.value)}
          />
          {state.status === 'loading' ? <StatePanel state="loading" /> : null}
          {state.status === 'error' ? <ApiErrorState error={state.error} onRetry={load} /> : null}
          {state.status === 'ready' && state.nodes.length === 0 ? (
            <StatePanel state="empty" description={t('organization.empty')} />
          ) : null}
          {state.status === 'ready' && state.nodes.length > 0 ? (
            treeData.length > 0 ? (
              <Tree<OrganizationTreeDataNode>
                key={organizationQuery.trim() || 'all-organizations'}
                className="organization-tree"
                treeData={treeData}
                defaultExpandedKeys={organizationQuery.trim()
                  ? allOrganizationKeys(filteredOrganizationNodes)
                  : topLevelOrganizationKeys(state.nodes)}
                blockNode
                selectedKeys={selectedId ? [selectedId] : []}
                onSelect={(keys) => setSelectedId(keys[0] ? String(keys[0]) : undefined)}
                titleRender={(treeNode) => <OrganizationTreeTitle node={treeNode} />}
              />
            ) : (
              <p className="organization-directory-empty" role="status">
                未找到匹配的部门，请调整名称或编码。
              </p>
            )
          ) : null}
        </section>
        <section className="content-surface organization-workbench__detail" aria-labelledby="organization-detail-title">
          {detailLoading ? <StatePanel state="partial-loading" /> : null}
          {detailError ? <ApiErrorState error={detailError} onRetry={selectedId ? () => loadDetail(selectedId) : undefined} /> : null}
          {!detailLoading && !detailError && !detail ? (
            <StatePanel state="empty" description={t('organization.selectNode')} />
          ) : null}
          {!detailLoading && detail ? (
            <>
              <div className="section-heading">
                <div>
                  <h2 id="organization-detail-title">{detail.name}</h2>
                </div>
                {capabilities.includes('ORGANIZATION:EDIT') ? (
                  <Button icon={<IconEdit aria-hidden="true" stroke={2} />} onClick={openEdit}>
                    {t('organization.edit')}
                  </Button>
                ) : null}
              </div>
              <Space wrap className="people-badge-row">
                <StatusBadge status={detail.status} />
              </Space>
              <Descriptions
                className="people-descriptions"
                column={{ xs: 1, sm: 2 }}
                items={[
                  { key: 'type', label: t('organization.type'), children: t(`organization.type.${detail.organizationType}`) },
                  { key: 'children', label: t('organization.childCount'), children: detail.childCount },
                  { key: 'effective', label: t('people.effectiveFrom'), children: formatDate(detail.effectiveFrom) },
                  { key: 'to', label: t('people.effectiveTo'), children: detail.effectiveTo ? formatDate(detail.effectiveTo) : t('people.longTerm') },
                ]}
              />
            </>
          ) : null}
        </section>
      </div>
      <OrganizationFormDialog
        open={Boolean(formMode)}
        mode={formMode ?? 'create'}
        form={form}
        parentOptions={parentOptions}
        processing={processing}
        error={detailError}
        onCancel={() => setFormMode(undefined)}
        onSave={() => void save()}
      />
    </section>
  );
}

export default OrganizationPage;

function OrganizationTreeTitle({ node }: { node: OrganizationTreeDataNode }) {
  return (
    <span className="organization-node">
      <IconBuilding aria-hidden="true" stroke={2} size="var(--size-icon-md)" />
      <strong>{node.unit.name}</strong>
      <StatusBadge status={node.unit.status} />
    </span>
  );
}

function OrganizationFormDialog({
  open,
  mode,
  form,
  parentOptions,
  processing,
  error,
  onCancel,
  onSave,
}: {
  open: boolean;
  mode: 'create' | 'edit';
  form: ReturnType<typeof Form.useForm<OrganizationFormValue>>[0];
  parentOptions: Array<{ label: string; value: string; searchText: string }>;
  processing: boolean;
  error?: ApiRequestError;
  onCancel: () => void;
  onSave: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal
      open={open}
      title={t(mode === 'create' ? 'organization.createTitle' : 'organization.editTitle')}
      okText={t('common.confirm')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      onOk={onSave}
      onCancel={onCancel}
      destroyOnHidden
    >
      {error ? <ApiErrorState error={error} /> : null}
      <Form form={form} layout="vertical">
        {mode === 'create' ? (
          <Form.Item name="companyId" label={t('people.company')} rules={[{ required: true }]}>
            <CompanySelect placeholder="请选择公司" />
          </Form.Item>
        ) : null}
        <Form.Item name="parentOrganizationId" label={t('organization.parent')}>
          <Select
            allowClear
            showSearch
            optionFilterProp="searchText"
            placeholder="输入部门名称或编码搜索"
            options={parentOptions}
          />
        </Form.Item>
        <div className="form-grid">
          <Form.Item name="code" label={t('organization.code')} rules={[{ required: true }, { max: 64 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="name" label={t('organization.name')} rules={[{ required: true }, { max: 128 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="organizationType" label={t('organization.type')} rules={[{ required: true }]}>
            <Select options={Array.from(['COMPANY', 'DEPARTMENT', 'TEAM'], (value) => ({ value, label: t(`organization.type.${value}`) }))} />
          </Form.Item>
          {mode === 'edit' ? (
            <Form.Item name="status" label={t('people.status')} rules={[{ required: true }]}>
              <Select options={Array.from(['ACTIVE', 'INACTIVE'], (value) => ({ value, label: t(`status.${value.toLowerCase()}`) }))} />
            </Form.Item>
          ) : null}
          <Form.Item name="effectiveFrom" label={t('people.effectiveFrom')} rules={[{ required: true }]}>
            <DatePicker />
          </Form.Item>
          {mode === 'edit' ? (
            <Form.Item name="effectiveTo" label={t('people.effectiveTo')}>
              <DatePicker />
            </Form.Item>
          ) : null}
        </div>
        <Form.Item name="reason" label={t('people.reason')} rules={[{ required: true, min: 4 }, { max: 500 }]}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function flattenOrganizations(
  nodes: OrganizationNode[],
  parentNames: string[] = [],
): Array<{ label: string; value: string; searchText: string }> {
  return nodes.flatMap((node) => [
    {
      label: `${[...parentNames, node.name].join(' / ')}（${node.code}）`,
      value: node.organizationId,
      searchText: `${[...parentNames, node.name].join(' ')} ${node.code}`,
    },
    ...flattenOrganizations(node.children, [...parentNames, node.name]),
  ]);
}

function toCreateRequest(values: OrganizationFormValue): OrganizationCreateRequest {
  return {
    companyId: values.companyId ?? '',
    parentOrganizationId: values.parentOrganizationId || null,
    code: values.code.trim(),
    name: values.name.trim(),
    organizationType: values.organizationType,
    effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
    reason: values.reason.trim(),
  };
}

function toUpdateRequest(values: OrganizationFormValue): OrganizationUpdateRequest {
  return {
    parentOrganizationId: values.parentOrganizationId || null,
    code: values.code.trim(),
    name: values.name.trim(),
    organizationType: values.organizationType,
    status: values.status ?? 'ACTIVE',
    effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
    effectiveTo: values.effectiveTo?.format('YYYY-MM-DD') ?? null,
    reason: values.reason.trim(),
  };
}

function asApiError(error: unknown, code: string): ApiRequestError {
  return error instanceof ApiRequestError
    ? error
    : new ApiRequestError(0, { code, retryable: true });
}

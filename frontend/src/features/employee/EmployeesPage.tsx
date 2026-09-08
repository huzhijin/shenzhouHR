import {
  IconBuilding,
  IconBuildingCommunity,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconUsersGroup,
} from '@tabler/icons-react';
import {
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Pagination,
  Select,
  Space,
  Tree,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import {
  ApiRequestError,
  createIdempotencyKey,
} from '../../shared/api/apiClient';
import { DataTable } from '../../shared/components/DataTable';
import { OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, QueryFilterBar } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState } from '../people/PeopleCommon';
import { CompanySelect } from '../referenceData';
import {
  getCurrentOrganizationTree,
  type OrganizationNode,
} from '../organization/organizationApi';
import {
  allOrganizationKeys,
  filterOrganizationNodes,
} from '../organization/organizationTree';
import {
  createLocalEmployee,
  getEmployees,
  type EmployeeCreateRequest,
  type EmployeeFilters,
  type EmployeePage,
  type EmployeeStatus,
  type EmployeeSummary,
} from './employeeApi';

type EmployeeState =
  | { status: 'loading' | 'partial-loading' }
  | { status: 'ready'; page: EmployeePage }
  | { status: 'error'; error: ApiRequestError };

type EmployeeDirectoryState =
  | { status: 'loading'; nodes: OrganizationNode[]; error?: undefined }
  | { status: 'ready'; nodes: OrganizationNode[]; error?: ApiRequestError }
  | { status: 'error'; nodes: OrganizationNode[]; error: ApiRequestError };

interface EmployeeDirectoryTreeNode {
  key: string;
  title: string;
  organization?: OrganizationNode;
  children: EmployeeDirectoryTreeNode[];
}

const allEmployeesKey = 'all-employees';

type CreateEmployeeValues = {
  companyId: string;
  employeeNumber: string;
  displayName: string;
  effectiveFrom: dayjs.Dayjs;
  reason: string;
};

export function EmployeesPage({ capabilities = [] }: { capabilities?: string[] }) {
  const { t } = useTranslation();
  const [pageNumber, setPageNumber] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<EmployeeStatus>();
  const [selectedOrganizationId, setSelectedOrganizationId] = useState<string>();
  const [organizationQuery, setOrganizationQuery] = useState('');
  const [state, setState] = useState<EmployeeState>({ status: 'loading' });
  const [directoryState, setDirectoryState] = useState<EmployeeDirectoryState>({
    status: 'loading',
    nodes: [],
  });
  const [createOpen, setCreateOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [writeError, setWriteError] = useState<ApiRequestError>();
  const [feedback, setFeedback] = useState<string>();
  const [form] = Form.useForm<CreateEmployeeValues>();
  const mutationKey = useRef<string | undefined>(undefined);
  const employeeRequestSequence = useRef(0);
  const directoryRequestSequence = useRef(0);

  const filters = useMemo<EmployeeFilters>(() => ({
    query: query.trim() || undefined,
    organizationId: selectedOrganizationId,
    includeDescendants: Boolean(selectedOrganizationId),
    status,
    sort: 'employeeNumber',
  }), [query, selectedOrganizationId, status]);

  const load = useCallback(() => {
    const requestSequence = ++employeeRequestSequence.current;
    setState((previous) => previous.status === 'ready'
      ? { status: 'partial-loading' }
      : { status: 'loading' });
    void getEmployees(pageNumber, pageSize, filters)
      .then((page) => {
        if (requestSequence === employeeRequestSequence.current) {
          setState({ status: 'ready', page });
        }
      })
      .catch((error: unknown) => {
        if (requestSequence === employeeRequestSequence.current) {
          setState({
            status: 'error',
            error: asApiError(error, 'EMPLOYEE_UNAVAILABLE'),
          });
        }
      });
  }, [filters, pageNumber, pageSize]);

  useEffect(() => {
    load();
    return () => {
      employeeRequestSequence.current += 1;
    };
  }, [load]);

  const loadDirectory = useCallback(() => {
    const requestSequence = ++directoryRequestSequence.current;
    setDirectoryState((previous) => previous.status === 'ready'
      ? { status: 'ready', nodes: previous.nodes }
      : { status: 'loading', nodes: [] });
    void getCurrentOrganizationTree(false)
      .then((nodes) => {
        if (requestSequence !== directoryRequestSequence.current) return;
        setDirectoryState({ status: 'ready', nodes });
        setSelectedOrganizationId((current) => (
          current && !findOrganization(nodes, current) ? undefined : current
        ));
      })
      .catch((error: unknown) => {
        if (requestSequence !== directoryRequestSequence.current) return;
        const requestError = asApiError(error, 'ORGANIZATION_UNAVAILABLE');
        setDirectoryState((previous) => previous.status === 'ready'
          ? { status: 'ready', nodes: previous.nodes, error: requestError }
          : { status: 'error', nodes: [], error: requestError });
      });
  }, []);

  useEffect(() => {
    loadDirectory();
    return () => {
      directoryRequestSequence.current += 1;
    };
  }, [loadDirectory]);

  const refresh = () => {
    loadDirectory();
    load();
  };

  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ effectiveFrom: dayjs() });
    mutationKey.current = createIdempotencyKey('employee-create');
    setWriteError(undefined);
    setCreateOpen(true);
  };

  const create = async () => {
    const values = await form.validateFields();
    setProcessing(true);
    setWriteError(undefined);
    try {
      await createLocalEmployee(toCreateRequest(values), mutationKey.current ?? createIdempotencyKey('employee-create'));
      mutationKey.current = undefined;
      setFeedback(t('employee.created'));
      setCreateOpen(false);
      setPageNumber(0);
      load();
    } catch (error: unknown) {
      setWriteError(asApiError(error, 'EMPLOYEE_CREATE_FAILED'));
    } finally {
      setProcessing(false);
    }
  };

  const page = state.status === 'ready' ? state.page : undefined;
  const directoryNodes = directoryState.nodes;
  const selectedOrganization = selectedOrganizationId
    ? findOrganization(directoryNodes, selectedOrganizationId)
    : undefined;
  const filteredDirectoryNodes = useMemo(
    () => filterOrganizationNodes(directoryNodes, organizationQuery),
    [directoryNodes, organizationQuery],
  );
  const treeData = useMemo<EmployeeDirectoryTreeNode[]>(() => [{
    key: allEmployeesKey,
    title: t('employee.directoryAll'),
    children: toEmployeeDirectoryTreeData(filteredDirectoryNodes),
  }], [filteredDirectoryNodes, t]);

  return (
    <section>
      <PageHeader
        title={t('employee.title')}
        description={t('employee.description')}
        breadcrumbs={[{ label: t('people.section') }, { label: t('employee.title') }]}
        actions={(
          <Space wrap>
            <Button icon={<IconRefresh aria-hidden="true" stroke={2} />} onClick={refresh}>
              {t('common.refresh')}
            </Button>
            {capabilities.includes('EMPLOYEE:CREATE') ? (
              <Button type="primary" icon={<IconPlus aria-hidden="true" stroke={2} />} onClick={openCreate}>
                {t('employee.create')}
              </Button>
            ) : null}
          </Space>
        )}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <div className="employee-directory-workbench">
        <aside
          className="content-surface employee-directory-workbench__tree"
          aria-labelledby="employee-directory-title"
        >
          <div className="section-heading employee-directory-heading">
            <div>
              <h2 id="employee-directory-title">{t('employee.directoryTitle')}</h2>
              <p>{t('employee.directoryDescription')}</p>
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
          {directoryState.status === 'loading' ? <StatePanel state="loading" /> : null}
          {directoryState.error ? (
            <ApiErrorState error={directoryState.error} onRetry={loadDirectory} />
          ) : null}
          {directoryState.status === 'ready' ? (
            <Tree<EmployeeDirectoryTreeNode>
              key={organizationQuery.trim() || 'all-employee-organizations'}
              aria-label={t('employee.directoryTitle')}
              className="organization-tree employee-directory-tree"
              treeData={treeData}
              defaultExpandedKeys={[
                allEmployeesKey,
                ...allOrganizationKeys(filteredDirectoryNodes),
              ]}
              blockNode
              selectedKeys={[selectedOrganizationId ?? allEmployeesKey]}
              onSelect={(keys) => {
                const key = String(keys[0] ?? allEmployeesKey);
                setSelectedOrganizationId(key === allEmployeesKey ? undefined : key);
                setPageNumber(0);
              }}
              titleRender={(treeNode) => (
                <EmployeeDirectoryTreeTitle node={treeNode} />
              )}
            />
          ) : null}
          {directoryState.status === 'ready'
            && organizationQuery.trim()
            && filteredDirectoryNodes.length === 0 ? (
              <p className="organization-directory-empty" role="status">
                未找到匹配的部门，请调整名称或编码。
              </p>
            ) : null}
        </aside>
        <section className="content-surface employee-directory-workbench__list">
          <div className="section-heading employee-list-heading">
            <div>
              <h2>{selectedOrganization?.name ?? t('employee.directoryAll')}</h2>
              <p>{selectedOrganization
                ? t('employee.directoryScopeDescription')
                : t('employee.directoryAllDescription')}</p>
            </div>
            {page ? <strong>{t('employee.total', { total: page.total })}</strong> : null}
          </div>
          <QueryFilterBar
            query={query}
            onQueryChange={(value) => {
              setQuery(value);
              setPageNumber(0);
            }}
            placeholder={t('employee.search')}
          >
            <Select
              allowClear
              value={status}
              aria-label={t('employee.filterStatus')}
              placeholder={t('employee.filterStatus')}
              options={Array.from(['ACTIVE', 'INACTIVE', 'TERMINATED'] as EmployeeStatus[], (value) => ({
                value,
                label: t(`employee.status.${value}`),
              }))}
              onChange={(value) => {
                setStatus(value);
                setPageNumber(0);
              }}
            />
          </QueryFilterBar>
          {state.status === 'loading' ? <StatePanel state="loading" /> : null}
          {state.status === 'partial-loading' ? <StatePanel state="partial-loading" /> : null}
          {state.status === 'error' ? <ApiErrorState error={state.error} onRetry={load} /> : null}
          {page && page.total === 0 ? (
            <StatePanel
              state="empty"
              description={selectedOrganization
                ? t('employee.emptyInOrganization')
                : t('employee.empty')}
            />
          ) : null}
          {page && page.total > 0 ? (
            <>
              <DataTable<EmployeeSummary>
                rows={page.items}
                rowKey={(row) => row.employeeId}
                columns={[
                  {
                    key: 'employee',
                    title: t('employee.column.name'),
                    render: (row) => (
                      <Link to={`/people/employees/${row.employeeId}`}>
                        <strong>{row.displayName}</strong>
                      </Link>
                    ),
                  },
                  { key: 'number', title: t('employee.number'), render: (row) => <code>{row.employeeNumber}</code> },
                  { key: 'organization', title: t('employee.column.organization'), render: (row) => row.organizationName ?? t('employee.unassigned') },
                  { key: 'status', title: t('employee.column.employmentStatus'), render: (row) => <StatusBadge status={row.employmentStatus} /> },
                ]}
              />
              <Pagination
                className="people-pagination"
                current={page.page + 1}
                pageSize={page.size}
                total={page.total}
                showSizeChanger
                pageSizeOptions={[20, 50, 100]}
                showTotal={(total) => t('employee.total', { total })}
                onChange={(nextPage, nextSize) => {
                  setPageNumber(nextPage - 1);
                  setPageSize(nextSize);
                }}
              />
            </>
          ) : null}
        </section>
      </div>
      <Modal
        open={createOpen}
        title={t('employee.createTitle')}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
        confirmLoading={processing}
        onOk={() => void create()}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        {writeError ? <ApiErrorState error={writeError} /> : null}
        <Form form={form} layout="vertical">
          <Form.Item name="companyId" label={t('people.company')} rules={[{ required: true }]}>
            <CompanySelect placeholder="请选择公司" />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="employeeNumber" label={t('employee.number')} rules={[{ required: true }, { max: 64 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="displayName" label={t('employee.name')} rules={[{ required: true }, { max: 100 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="effectiveFrom" label={t('people.effectiveFrom')} rules={[{ required: true }]}>
              <DatePicker />
            </Form.Item>
          </div>
          <Form.Item name="reason" label={t('people.reason')} rules={[{ required: true, min: 4 }, { max: 500 }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}

export default EmployeesPage;

function toCreateRequest(values: CreateEmployeeValues): EmployeeCreateRequest {
  return {
    companyId: values.companyId.trim(),
    employeeNumber: values.employeeNumber.trim(),
    displayName: values.displayName.trim(),
    effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
    reason: values.reason.trim(),
  };
}

function asApiError(error: unknown, code: string): ApiRequestError {
  return error instanceof ApiRequestError
    ? error
    : new ApiRequestError(0, { code, retryable: true });
}

function toEmployeeDirectoryTreeData(
  nodes: OrganizationNode[],
): EmployeeDirectoryTreeNode[] {
  return nodes.map((node) => ({
    key: node.organizationId,
    title: node.name,
    organization: node,
    children: toEmployeeDirectoryTreeData(node.children),
  }));
}

function findOrganization(
  nodes: OrganizationNode[],
  organizationId: string,
): OrganizationNode | undefined {
  for (const node of nodes) {
    if (node.organizationId === organizationId) return node;
    const nested = findOrganization(node.children, organizationId);
    if (nested) return nested;
  }
  return undefined;
}

function EmployeeDirectoryTreeTitle({
  node,
}: {
  node: EmployeeDirectoryTreeNode;
}) {
  const Icon = !node.organization
    ? IconUsersGroup
    : node.organization.organizationType === 'COMPANY'
      ? IconBuilding
      : IconBuildingCommunity;
  return (
    <span className="employee-directory-node">
      <Icon aria-hidden="true" stroke={2} size="var(--size-icon-md)" />
      <span>{node.title}</span>
    </span>
  );
}

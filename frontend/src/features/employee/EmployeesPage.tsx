import {
  IconPlus,
  IconRefresh,
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
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  ApiErrorState,
  PeopleContextStrip,
  SourceAuthority,
} from '../people/PeopleCommon';
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

type CreateEmployeeValues = {
  legalEntityId: string;
  employeeNumber: string;
  displayName: string;
  externalEmployeeId?: string;
  effectiveFrom: dayjs.Dayjs;
  reason: string;
};

export function EmployeesPage({ capabilities = [] }: { capabilities?: string[] }) {
  const { t } = useTranslation();
  const [pageNumber, setPageNumber] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<EmployeeStatus>();
  const [state, setState] = useState<EmployeeState>({ status: 'loading' });
  const [createOpen, setCreateOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [writeError, setWriteError] = useState<ApiRequestError>();
  const [feedback, setFeedback] = useState<string>();
  const [form] = Form.useForm<CreateEmployeeValues>();
  const mutationKey = useRef<string | undefined>(undefined);

  const filters = useMemo<EmployeeFilters>(() => ({
    query: query.trim() || undefined,
    status,
    sort: 'employeeNumber',
  }), [query, status]);

  const load = useCallback(() => {
    setState((previous) => previous.status === 'ready'
      ? { status: 'partial-loading' }
      : { status: 'loading' });
    void getEmployees(pageNumber, pageSize, filters)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error: unknown) => setState({
        status: 'error',
        error: asApiError(error, 'EMPLOYEE_UNAVAILABLE'),
      }));
  }, [filters, pageNumber, pageSize]);

  useEffect(() => {
    load();
  }, [load]);

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

  return (
    <section>
      <PageHeader
        title={t('employee.title')}
        description={t('employee.description')}
        breadcrumbs={[{ label: t('people.section') }, { label: t('employee.title') }]}
        actions={(
          <Space wrap>
            <span className="status-label">
              {isDemoMode() ? t('organization.demoScope') : t('employee.serverScope')}
            </span>
            <Button icon={<IconRefresh aria-hidden="true" stroke={2} />} onClick={load}>
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
      <PeopleContextStrip
        items={[
          { label: t('people.sourceAuthority'), value: t('people.source.local') },
          { label: t('people.matchKey'), value: t('people.preciseMatchOnly') },
          { label: t('people.periodSemantics'), value: '[start_date, end_exclusive)', mono: true },
        ]}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <section className="content-surface">
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
        {page && page.total === 0 ? <StatePanel state="empty" description={t('employee.empty')} /> : null}
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
                { key: 'source', title: t('people.sourceAuthority'), render: (row) => <SourceAuthority authority={row.sourceAuthority} /> },
                { key: 'version', title: t('people.rowVersion'), render: (row) => <code>V{row.rowVersion}</code> },
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
          <Form.Item name="legalEntityId" label={t('people.legalEntity')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="employeeNumber" label={t('employee.number')} rules={[{ required: true }, { max: 64 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="displayName" label={t('employee.name')} rules={[{ required: true }, { max: 100 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="externalEmployeeId" label={t('employee.externalId')}>
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
    legalEntityId: values.legalEntityId.trim(),
    employeeNumber: values.employeeNumber.trim(),
    displayName: values.displayName.trim(),
    externalEmployeeId: values.externalEmployeeId?.trim() || null,
    effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
    reason: values.reason.trim(),
  };
}

function asApiError(error: unknown, code: string): ApiRequestError {
  return error instanceof ApiRequestError
    ? error
    : new ApiRequestError(0, { code, retryable: true });
}

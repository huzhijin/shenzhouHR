import {
  IconBriefcase,
  IconCalculator,
  IconEdit,
  IconPlus,
  IconRefresh,
} from '@tabler/icons-react';
import {
  Alert,
  DatePicker,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Timeline,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import {
  ApiRequestError,
  createIdempotencyKey,
} from '../../shared/api/apiClient';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import {
  ApiErrorState,
  PeopleContextStrip,
  SourceAuthority,
  VersionAuditPanel,
  formatDate,
  formatDateTime,
} from '../people/PeopleCommon';
import {
  createEmploymentPeriod,
  createPriorServiceAdjustment,
  getEmployee,
  listEmployeeVersions,
  listEmploymentPeriods,
  listPriorServiceRecords,
  recalculatePriorService,
  updateEmploymentPeriod,
  updateLocalEmployee,
  type EmployeeDetail,
  type EmployeeStatus,
  type EmployeeUpdateRequest,
  type EmployeeVersionSummary,
  type EmploymentPeriodCreateRequest,
  type EmploymentPeriodView,
  type PriorServiceAdjustmentRequest,
  type PriorServiceRecordView,
  type PriorServiceRecordPage,
} from './employeeApi';

type DetailState =
  | { status: 'loading' | 'partial-loading' }
  | {
      status: 'ready';
      detail: EmployeeDetail;
      versions: EmployeeVersionSummary[];
      periods?: EmploymentPeriodView[];
      priorService?: PriorServiceRecordPage;
    }
  | { status: 'error'; error: ApiRequestError };

type DialogMode = 'edit' | 'period-create' | 'period-edit' | 'prior-adjust' | 'prior-recalculate';

type EmployeeFormValues = {
  employeeNumber: string;
  displayName: string;
  status: EmployeeStatus;
  effectiveFrom: dayjs.Dayjs;
  effectiveTo?: dayjs.Dayjs | null;
  reason: string;
};

type PeriodFormValues = {
  organizationId: string;
  positionId?: string;
  startDate: dayjs.Dayjs;
  terminationDate?: dayjs.Dayjs | null;
  reason: string;
};

type PriorServiceFormValues = {
  amountDays: number;
  businessDate: dayjs.Dayjs;
  reason: string;
};

export function EmployeeDetailPage({ capabilities = [] }: { capabilities?: string[] }) {
  const { t } = useTranslation();
  const { employeeId = '' } = useParams();
  const [state, setState] = useState<DetailState>({ status: 'loading' });
  const [dialog, setDialog] = useState<DialogMode>();
  const [periodTarget, setPeriodTarget] = useState<EmploymentPeriodView>();
  const [processing, setProcessing] = useState(false);
  const [writeError, setWriteError] = useState<ApiRequestError>();
  const [feedback, setFeedback] = useState<string>();
  const [employeeForm] = Form.useForm<EmployeeFormValues>();
  const [periodForm] = Form.useForm<PeriodFormValues>();
  const [priorForm] = Form.useForm<PriorServiceFormValues>();
  const [reasonForm] = Form.useForm<{ reason: string }>();
  const mutationKey = useRef<string | undefined>(undefined);
  const canReadEmployment = capabilities.includes('EMPLOYMENT:READ');
  const canReadPriorService = capabilities.includes('PRIOR_SERVICE:READ');

  const load = useCallback(() => {
    setState((previous) => previous.status === 'ready'
      ? { status: 'partial-loading' }
      : { status: 'loading' });
    void Promise.all([
      getEmployee(employeeId),
      listEmployeeVersions(employeeId),
      canReadEmployment ? listEmploymentPeriods(employeeId) : Promise.resolve(undefined),
      canReadPriorService ? listPriorServiceRecords(employeeId) : Promise.resolve(undefined),
    ])
      .then(([detail, versionPage, periodPage, priorService]) => setState({
        status: 'ready',
        detail,
        versions: versionPage.items,
        periods: periodPage?.items,
        priorService,
      }))
      .catch((error: unknown) => setState({
        status: 'error',
        error: asApiError(error, 'EMPLOYEE_DETAIL_UNAVAILABLE'),
      }));
  }, [canReadEmployment, canReadPriorService, employeeId]);

  useEffect(() => {
    load();
  }, [load]);

  if (state.status === 'error') return <ApiErrorState error={state.error} onRetry={load} />;
  if (state.status !== 'ready') return <StatePanel state={state.status} />;

  const { detail, versions, periods, priorService } = state;

  const openEdit = () => {
    employeeForm.setFieldsValue({
      employeeNumber: detail.employeeNumber,
      displayName: detail.displayName,
      status: detail.status,
      effectiveFrom: dayjs(detail.effectiveFrom),
      effectiveTo: detail.effectiveTo ? dayjs(detail.effectiveTo) : null,
    });
    openDialog('edit');
  };

  const openPeriodCreate = () => {
    periodForm.resetFields();
    periodForm.setFieldsValue({ startDate: dayjs() });
    openDialog('period-create');
  };

  const openPeriodEdit = (period: EmploymentPeriodView) => {
    setPeriodTarget(period);
    periodForm.setFieldsValue({
      organizationId: period.organizationId,
      positionId: period.positionId ?? undefined,
      startDate: dayjs(period.startDate),
      terminationDate: period.terminationDate ? dayjs(period.terminationDate) : null,
    });
    openDialog('period-edit');
  };

  const openDialog = (next: DialogMode) => {
    setWriteError(undefined);
    mutationKey.current = createIdempotencyKey(`${next}-${employeeId}`);
    setDialog(next);
  };

  const saveEmployee = async () => {
    const values = await employeeForm.validateFields();
    await execute(async () => {
      await updateLocalEmployee(
        employeeId,
        toEmployeeRequest(values),
        detail.rowVersion,
        mutationKey.current ?? createIdempotencyKey('employee-edit'),
      );
      setFeedback(t('employee.updated'));
    });
  };

  const savePeriod = async () => {
    const values = await periodForm.validateFields();
    await execute(async () => {
      const request = toPeriodRequest(values);
      if (dialog === 'period-edit' && periodTarget) {
        await updateEmploymentPeriod(
          employeeId,
          periodTarget.employmentPeriodId,
          { ...request, terminationDate: request.terminationDate ?? null },
          periodTarget.rowVersion,
          mutationKey.current ?? createIdempotencyKey('employment-period-edit'),
        );
        setFeedback(t('employee.periodUpdated'));
      } else {
        await createEmploymentPeriod(
          employeeId,
          request,
          detail.rowVersion,
          mutationKey.current ?? createIdempotencyKey('employment-period-create'),
        );
        setFeedback(t('employee.rehireCreated'));
      }
    });
  };

  const savePriorAdjustment = async () => {
    const values = await priorForm.validateFields();
    await execute(async () => {
      await createPriorServiceAdjustment(
        employeeId,
        toPriorRequest(values),
        detail.rowVersion,
        mutationKey.current ?? createIdempotencyKey('prior-service-adjustment'),
      );
      setFeedback(t('employee.priorAdjusted'));
    });
  };

  const recalculate = async () => {
    const values = await reasonForm.validateFields();
    await execute(async () => {
      await recalculatePriorService(
        employeeId,
        detail.rowVersion,
        values.reason.trim(),
        mutationKey.current ?? createIdempotencyKey('prior-service-recalculate'),
      );
      setFeedback(t('employee.priorRecalculated'));
    });
  };

  const execute = async (action: () => Promise<void>) => {
    setProcessing(true);
    setWriteError(undefined);
    try {
      await action();
      mutationKey.current = undefined;
      setDialog(undefined);
      setPeriodTarget(undefined);
      load();
    } catch (error: unknown) {
      setWriteError(asApiError(error, 'EMPLOYEE_WRITE_FAILED'));
    } finally {
      setProcessing(false);
    }
  };
  const openPriorAdjustment = () => {
    priorForm.resetFields();
    priorForm.setFieldsValue({ businessDate: dayjs() });
    openDialog('prior-adjust');
  };
  const openPriorRecalculation = () => {
    reasonForm.resetFields();
    openDialog('prior-recalculate');
  };
  const choosePeriodToEdit = (period: EmploymentPeriodView) => () => {
    openPeriodEdit(period);
  };
  const closeEmployeeDialog = () => {
    setDialog(undefined);
    setPeriodTarget(undefined);
  };
  const submitEmployee = () => {
    void saveEmployee();
  };
  const submitPeriod = () => {
    void savePeriod();
  };
  const submitPriorAdjustment = () => {
    void savePriorAdjustment();
  };
  const submitPriorRecalculation = () => {
    void recalculate();
  };

  return (
    <section aria-labelledby="employee-detail-title">
      <PageHeader
        title={detail.displayName}
        description={t('employee.detailDescription', { employeeNumber: detail.employeeNumber })}
        breadcrumbs={[
          { label: t('people.section') },
          { label: t('employee.title'), path: '/people/employees' },
          { label: detail.displayName },
        ]}
        actions={(
          <Space wrap>
            <AccessibleButton
              label={t('common.refresh')}
              icon={<IconRefresh aria-hidden="true" stroke={2} />}
              onClick={load}
            >
              {t('common.refresh')}
            </AccessibleButton>
            {capabilities.includes('EMPLOYEE:EDIT') ? (
              <AccessibleButton
                label={t('employee.edit')}
                icon={<IconEdit aria-hidden="true" stroke={2} />}
                onClick={openEdit}
              >
                {t('employee.edit')}
              </AccessibleButton>
            ) : null}
            {capabilities.includes('EMPLOYMENT:CREATE') ? (
              <AccessibleButton
                label={t('employee.rehire')}
                type="primary"
                icon={<IconPlus aria-hidden="true" stroke={2} />}
                onClick={openPeriodCreate}
              >
                {t('employee.rehire')}
              </AccessibleButton>
            ) : null}
          </Space>
        )}
      />
      <PeopleContextStrip
        items={[
          { label: t('employee.number'), value: detail.employeeNumber, mono: true },
          { label: t('people.rowVersion'), value: `V${detail.rowVersion}`, mono: true },
          { label: t('people.periodSemantics'), value: '[start_date, end_exclusive)', mono: true },
        ]}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      {writeError ? (
        <div className="section-spaced">
          <ApiErrorState error={writeError} onRetry={load} />
          {writeError.code === 'EMPLOYMENT_PERIOD_OVERLAP' ? (
            <Alert type="error" showIcon title={t('employee.overlapRejected')} description="EMPLOYMENT_PERIOD_OVERLAP" />
          ) : null}
        </div>
      ) : null}
      <div className="detail-grid">
        <section className="content-surface">
          <div className="section-heading">
            <div>
              <h2 id="employee-detail-title">{t('employee.profile')}</h2>
              <p>{t('employee.profileDescription')}</p>
            </div>
          </div>
          <Space wrap className="people-badge-row">
            <StatusBadge status={detail.status} />
            <SourceAuthority authority={detail.sourceAuthority} />
          </Space>
          <Descriptions
            className="people-descriptions"
            column={1}
            items={[
              { key: 'name', label: t('employee.name'), children: detail.displayName },
              { key: 'number', label: t('employee.number'), children: <code>{detail.employeeNumber}</code> },
              { key: 'external', label: t('employee.externalId'), children: detail.externalEmployeeId ? <code>{detail.externalEmployeeId}</code> : t('common.none') },
              { key: 'effective', label: t('people.effectivePeriod'), children: `${formatDate(detail.effectiveFrom)} — ${detail.effectiveTo ? formatDate(detail.effectiveTo) : t('people.longTerm')}` },
              { key: 'source', label: t('organization.sourceBatch'), children: detail.sourceBatchId ? <code>{detail.sourceBatchId}</code> : t('organization.localMaintenance') },
            ]}
          />
        </section>
        <section className="content-surface">
          <div className="section-heading">
            <div>
              <h2>{t('employee.priorService')}</h2>
              <p>{t('employee.priorServiceDescription')}</p>
            </div>
            <Space wrap>
              {capabilities.includes('PRIOR_SERVICE:ADJUST') ? (
                <>
                  <AccessibleButton
                    label={t('employee.adjustPrior')}
                    icon={<IconPlus aria-hidden="true" stroke={2} />}
                    onClick={openPriorAdjustment}
                  >
                    {t('employee.adjustPrior')}
                  </AccessibleButton>
                  <AccessibleButton
                    label={t('employee.recalculate')}
                    icon={<IconCalculator aria-hidden="true" stroke={2} />}
                    onClick={openPriorRecalculation}
                  >
                    {t('employee.recalculate')}
                  </AccessibleButton>
                </>
              ) : null}
            </Space>
          </div>
          {priorService ? (
            <>
              <div className="prior-service-total">
                <strong>{priorService.totalDays}</strong>
                <span>{t('employee.days')}</span>
              </div>
              <p className="replay-digest">
                {t('employee.replayDigest')} <code>{priorService.replayDigest}</code>
              </p>
            </>
          ) : <StatePanel state="403" />}
        </section>
      </div>
      <section className="content-surface section-spaced" aria-labelledby="employment-history-title">
        <div className="section-heading">
          <div>
            <h2 id="employment-history-title">{t('employee.employmentHistory')}</h2>
            <p>{t('employee.employmentHistoryDescription')}</p>
          </div>
        </div>
        {!periods ? <StatePanel state="403" /> : periods.length === 0 ? (
          <StatePanel state="empty" description={t('employee.noPeriods')} />
        ) : (
          <Timeline
            className="employment-timeline"
            items={Array.from(periods, (period) => ({
              color: period.endExclusive ? 'var(--color-text-muted)' : 'var(--color-brand-primary)',
              icon: <IconBriefcase aria-hidden="true" stroke={2} size="var(--size-icon-md)" />,
              content: (
                <article className="employment-period">
                  <div className="section-heading">
                    <div>
                      <h3>{t('employee.employmentPeriod', { start: formatDate(period.startDate) })}</h3>
                      <p>
                        <code>{period.organizationId}</code>
                        {' · '}
                        {period.positionId ? <code>{period.positionId}</code> : t('employee.noPosition')}
                      </p>
                    </div>
                    {capabilities.includes('EMPLOYMENT:EDIT') ? (
                      <AccessibleButton
                        label={t('employee.editPeriod')}
                        size="small"
                        icon={<IconEdit aria-hidden="true" stroke={2} />}
                        onClick={choosePeriodToEdit(period)}
                      >
                        {t('employee.editPeriod')}
                      </AccessibleButton>
                    ) : null}
                  </div>
                  <Descriptions
                    size="small"
                    column={{ xs: 1, sm: 3 }}
                    items={[
                      { key: 'start', label: t('employee.startDate'), children: formatDate(period.startDate) },
                      { key: 'termination', label: t('employee.terminationDate'), children: period.terminationDate ? formatDate(period.terminationDate) : t('employee.currentEmployment') },
                      { key: 'end', label: t('employee.endExclusive'), children: period.endExclusive ? formatDate(period.endExclusive) : t('people.longTerm') },
                    ]}
                  />
                </article>
              ),
            }))}
          />
        )}
      </section>
      <section className="content-surface section-spaced" aria-labelledby="prior-service-history-title">
        <div className="section-heading">
          <div>
            <h2 id="prior-service-history-title">{t('employee.priorServiceHistory')}</h2>
            <p>{t('employee.priorServiceLedgerDescription')}</p>
          </div>
        </div>
        {!priorService ? <StatePanel state="403" /> : priorService.items.length === 0 ? (
          <StatePanel state="empty" description={t('employee.noPriorService')} />
        ) : (
          <DataTable<PriorServiceRecordView>
            rows={priorService.items}
            rowKey={(row) => row.priorServiceRecordId}
            columns={[
              { key: 'type', title: t('employee.recordType'), render: (row) => <StatusBadge status={row.recordType} /> },
              { key: 'amount', title: t('employee.amountDays'), render: (row) => <strong>{row.amountDays > 0 ? `+${row.amountDays}` : row.amountDays}</strong> },
              { key: 'total', title: t('employee.resultingTotal'), render: (row) => row.resultingTotalDays },
              { key: 'date', title: t('employee.businessDate'), render: (row) => formatDate(row.businessDate) },
              { key: 'reason', title: t('people.reason'), render: (row) => row.reason },
              { key: 'actor', title: t('people.actor'), render: (row) => <code>{row.actorId}</code> },
              { key: 'time', title: t('people.changedAt'), render: (row) => formatDateTime(row.occurredAt) },
            ]}
          />
        )}
      </section>
      <section className="content-surface section-spaced">
        <VersionAuditPanel
          versions={versions}
          resourceType="EMPLOYEE"
          resourceId={detail.auditResourceId}
          canReadAudit={capabilities.includes('AUDIT:READ')}
        />
      </section>
      <EmployeeDialogs
        dialog={dialog}
        employeeForm={employeeForm}
        periodForm={periodForm}
        priorForm={priorForm}
        reasonForm={reasonForm}
        processing={processing}
        error={writeError}
        onCancel={closeEmployeeDialog}
        onSaveEmployee={submitEmployee}
        onSavePeriod={submitPeriod}
        onSavePrior={submitPriorAdjustment}
        onRecalculate={submitPriorRecalculation}
      />
    </section>
  );
}

export default EmployeeDetailPage;

function EmployeeDialogs({
  dialog,
  employeeForm,
  periodForm,
  priorForm,
  reasonForm,
  processing,
  error,
  onCancel,
  onSaveEmployee,
  onSavePeriod,
  onSavePrior,
  onRecalculate,
}: {
  dialog?: DialogMode;
  employeeForm: ReturnType<typeof Form.useForm<EmployeeFormValues>>[0];
  periodForm: ReturnType<typeof Form.useForm<PeriodFormValues>>[0];
  priorForm: ReturnType<typeof Form.useForm<PriorServiceFormValues>>[0];
  reasonForm: ReturnType<typeof Form.useForm<{ reason: string }>>[0];
  processing: boolean;
  error?: ApiRequestError;
  onCancel: () => void;
  onSaveEmployee: () => void;
  onSavePeriod: () => void;
  onSavePrior: () => void;
  onRecalculate: () => void;
}) {
  const { t } = useTranslation();
  const save = dialog === 'edit'
    ? onSaveEmployee
    : dialog === 'period-create' || dialog === 'period-edit'
      ? onSavePeriod
      : dialog === 'prior-adjust'
        ? onSavePrior
        : onRecalculate;
  const chooseStatus = (status: EmployeeStatus) => () => {
    employeeForm.setFieldValue('status', status);
  };
  return (
    <Modal
      open={Boolean(dialog)}
      title={t(dialogTitle(dialog))}
      okText={t('common.confirm')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      onOk={save}
      onCancel={onCancel}
      destroyOnHidden
    >
      {error ? <ApiErrorState error={error} /> : null}
      {dialog === 'edit' ? (
        <Form form={employeeForm} layout="vertical">
          <div className="form-grid">
            <Form.Item name="employeeNumber" label={t('employee.number')} rules={[{ required: true }, { max: 64 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="displayName" label={t('employee.name')} rules={[{ required: true }, { max: 100 }]}>
              <Input />
            </Form.Item>
            <Form.Item name="status" label={t('people.status')} rules={[{ required: true }]}>
              <Space.Compact block>
                {Array.from(['ACTIVE', 'INACTIVE', 'TERMINATED'] as EmployeeStatus[], (status) => (
                  <AccessibleButton
                    label={t(`employee.status.${status}`)}
                    key={status}
                    type={employeeForm.getFieldValue('status') === status ? 'primary' : 'default'}
                    onClick={chooseStatus(status)}
                  >
                    {t(`employee.status.${status}`)}
                  </AccessibleButton>
                ))}
              </Space.Compact>
            </Form.Item>
            <Form.Item name="effectiveFrom" label={t('people.effectiveFrom')} rules={[{ required: true }]}>
              <DatePicker />
            </Form.Item>
            <Form.Item name="effectiveTo" label={t('people.effectiveTo')}>
              <DatePicker />
            </Form.Item>
          </div>
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'period-create' || dialog === 'period-edit' ? (
        <Form form={periodForm} layout="vertical">
          <Form.Item name="organizationId" label={t('employee.organizationId')} rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="positionId" label={t('employee.positionId')}>
            <Input />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="startDate" label={t('employee.startDate')} rules={[{ required: true }]}>
              <DatePicker />
            </Form.Item>
            <Form.Item name="terminationDate" label={t('employee.terminationDate')}>
              <DatePicker />
            </Form.Item>
          </div>
          <Alert
            showIcon
            type="info"
            title={t('employee.halfOpenRule')}
            description={t('employee.terminationConversion')}
          />
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'prior-adjust' ? (
        <Form form={priorForm} layout="vertical">
          <div className="form-grid">
            <Form.Item name="amountDays" label={t('employee.amountDays')} rules={[{ required: true }, { type: 'number', min: -36500, max: 36500 }]}>
              <InputNumber precision={0} min={-36500} max={36500} />
            </Form.Item>
            <Form.Item name="businessDate" label={t('employee.businessDate')} rules={[{ required: true }]}>
              <DatePicker />
            </Form.Item>
          </div>
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'prior-recalculate' ? (
        <Form form={reasonForm} layout="vertical">
          <Alert showIcon type="info" title={t('employee.recalculateRule')} />
          <ReasonField />
        </Form>
      ) : null}
    </Modal>
  );
}

function ReasonField() {
  const { t } = useTranslation();
  return (
    <Form.Item name="reason" label={t('people.reason')} rules={[{ required: true, min: 4 }, { max: 500 }]}>
      <Input.TextArea rows={3} />
    </Form.Item>
  );
}

function dialogTitle(dialog?: DialogMode) {
  if (dialog === 'edit') return 'employee.editTitle';
  if (dialog === 'period-create') return 'employee.rehireTitle';
  if (dialog === 'period-edit') return 'employee.editPeriodTitle';
  if (dialog === 'prior-adjust') return 'employee.adjustPriorTitle';
  return 'employee.recalculateTitle';
}

function toEmployeeRequest(values: EmployeeFormValues): EmployeeUpdateRequest {
  return {
    employeeNumber: values.employeeNumber.trim(),
    displayName: values.displayName.trim(),
    status: values.status,
    effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
    effectiveTo: values.effectiveTo?.format('YYYY-MM-DD') ?? null,
    reason: values.reason.trim(),
  };
}

function toPeriodRequest(values: PeriodFormValues): EmploymentPeriodCreateRequest {
  return {
    organizationId: values.organizationId.trim(),
    positionId: values.positionId?.trim() || null,
    startDate: values.startDate.format('YYYY-MM-DD'),
    terminationDate: values.terminationDate?.format('YYYY-MM-DD') ?? null,
    reason: values.reason.trim(),
  };
}

function toPriorRequest(values: PriorServiceFormValues): PriorServiceAdjustmentRequest {
  return {
    amountDays: values.amountDays,
    businessDate: values.businessDate.format('YYYY-MM-DD'),
    reason: values.reason.trim(),
  };
}

function asApiError(error: unknown, code: string): ApiRequestError {
  return error instanceof ApiRequestError
    ? error
    : new ApiRequestError(0, { code, retryable: true });
}

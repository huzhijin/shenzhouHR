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
  Select,
  Space,
  Switch,
  Table,
  Tag,
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
  formatDate,
  formatDateTime,
} from '../people/PeopleCommon';
import {
  getCurrentOrganizationTree,
  type OrganizationNode,
} from '../organization/organizationApi';
import {
  adjustLeaveBalance,
  createEmploymentPeriod,
  createPriorServiceAdjustment,
  getAnnualLeaveAccount,
  getEmployee,
  getPunchExemption,
  getTimeOffAccount,
  listEmploymentPeriods,
  listPriorServiceRecords,
  recalculatePriorService,
  setLeaveOpeningBalance,
  updateEmploymentPeriod,
  updateLocalEmployee,
  type AnnualLeaveAccount,
  type AnnualLeaveLedgerEntry,
  type LeaveAccountKind,
  type EmployeeDetail,
  type EmployeeStatus,
  type EmployeeUpdateRequest,
  type EmploymentPeriodCreateRequest,
  type EmploymentPeriodView,
  type PriorServiceAdjustmentRequest,
  type PriorServiceRecordView,
  type PriorServiceRecordPage,
  type PunchExemptionStatus,
  setPunchExemption,
} from './employeeApi';

// 工龄换算：正负对称的年转天数（按 365 天/年，四舍五入到整数天）
function convertYearsToDays(years: number): number {
  // 按绝对值四舍五入再恢复符号，确保正负对称（365天/年）
  const sign = years < 0 ? -1 : 1;
  return sign * Math.round(Math.abs(years) * 365);
}

type DetailState =
  | { status: 'loading' | 'partial-loading' }
  | {
      status: 'ready';
      detail: EmployeeDetail;
      organizations: OrganizationNode[];
      periods?: EmploymentPeriodView[];
      priorService?: PriorServiceRecordPage;
      annualLeave?: AnnualLeaveAccount;
      timeOff?: AnnualLeaveAccount;
      punchExemption?: PunchExemptionStatus;
    }
  | { status: 'error'; error: ApiRequestError };

type DialogMode = 'edit' | 'period-create' | 'period-edit' | 'prior-adjust' | 'prior-recalculate' | 'leave-opening' | 'leave-adjust';

type EmployeeFormValues = {
  employeeNumber: string;
  displayName: string;
  status: EmployeeStatus;
  effectiveFrom: dayjs.Dayjs;
  effectiveTo?: dayjs.Dayjs | null;
  reason: string;
};

type OriginalEmployeeState = {
  status: EmployeeStatus;
  effectiveFrom: string;
};

type PeriodFormValues = {
  organizationId: string;
  positionId?: string;
  startDate: dayjs.Dayjs;
  terminationDate?: dayjs.Dayjs | null;
  reason: string;
};

type PriorServiceFormValues = {
  amountYears: number; // 界面输入调整年数（保留2位小数），内部转换为天数（365天/年）
  businessDate: dayjs.Dayjs;
  reason: string;
};

export type EmployeeOrganizationOption = {
  label: string;
  value: string;
  searchText: string;
  disabled?: boolean;
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
  const originalEmployeeState = useRef<OriginalEmployeeState | undefined>(undefined);
  const canReadEmployment = capabilities.includes('EMPLOYMENT:READ');
  const canReadPriorService = capabilities.includes('PRIOR_SERVICE:READ');
  const canReadLeave = capabilities.includes('ANNUAL_LEAVE:READ');
  const canAdjustLeave = capabilities.includes('ANNUAL_LEAVE:ADJUST');
  const currentYear = new Date().getFullYear();
  const [leaveForm] = Form.useForm<{ balanceHours: number; adjustmentHours: number; reason: string }>();
  const [leaveKind, setLeaveKind] = useState<LeaveAccountKind>('ANNUAL_LEAVE');

  const load = useCallback(() => {
    setState((previous) => previous.status === 'ready'
      ? { status: 'partial-loading' }
      : { status: 'loading' });
    void Promise.all([
      getEmployee(employeeId),
      canReadEmployment ? listEmploymentPeriods(employeeId) : Promise.resolve(undefined),
      canReadPriorService ? listPriorServiceRecords(employeeId) : Promise.resolve(undefined),
      getCurrentOrganizationTree(true),
      canReadLeave ? getAnnualLeaveAccount(employeeId, currentYear) : Promise.resolve(undefined),
      canReadLeave ? getTimeOffAccount(employeeId, currentYear) : Promise.resolve(undefined),
      getPunchExemption(employeeId).catch(() => undefined),
    ])
      .then(([detail, periodPage, priorService, organizations, annualLeave, timeOff, punchExemption]) => setState({
        status: 'ready',
        detail,
        organizations,
        periods: periodPage?.items,
        priorService,
        annualLeave,
        timeOff,
        punchExemption,
      }))
      .catch((error: unknown) => setState({
        status: 'error',
        error: asApiError(error, 'EMPLOYEE_DETAIL_UNAVAILABLE'),
      }));
  }, [canReadEmployment, canReadPriorService, canReadLeave, employeeId]);

  useEffect(() => {
    load();
  }, [load]);

  if (state.status === 'error') return <ApiErrorState error={state.error} onRetry={load} />;
  if (state.status !== 'ready') return <StatePanel state={state.status} />;

  const { detail, organizations, periods, priorService, annualLeave, timeOff, punchExemption } = state;
  const leaveKindLabel = leaveKind === 'TIME_OFF' ? '调休' : '年假';
  const saveStandingExempt = (standingExempt: boolean) => {
    setProcessing(true);
    setWriteError(undefined);
    void setPunchExemption(employeeId, standingExempt)
      .then((next) => {
        setState((previous) => previous.status === 'ready'
          ? { ...previous, punchExemption: next }
          : previous);
        setFeedback(t('employee.punchExemptSaved'));
      })
      .catch((error: unknown) => setWriteError(asApiError(error, 'EMPLOYEE_PUNCH_EXEMPT_UNAVAILABLE')))
      .finally(() => setProcessing(false));
  };
  const organizationOptions = employeeOrganizationOptions(
    organizations,
    periods?.map((period) => period.organizationId) ?? [],
    t('employee.organizationUnavailable'),
  );

  const openEdit = () => {
    employeeForm.setFieldsValue({
      employeeNumber: detail.employeeNumber,
      displayName: detail.displayName,
      status: detail.status,
      effectiveFrom: dayjs(detail.effectiveFrom),
      effectiveTo: detail.effectiveTo ? dayjs(detail.effectiveTo) : null,
    });
    originalEmployeeState.current = {
      status: detail.status,
      effectiveFrom: detail.effectiveFrom,
    };
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
    try {
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
    } catch {
      // validateFields失败时Form会自动显示错误，直接return让Modal保持打开
      return;
    }
  };

  const savePeriod = async () => {
    try {
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
    } catch {
      return;
    }
  };

  const savePriorAdjustment = async () => {
    try {
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
    } catch {
      return;
    }
  };

  const recalculate = async () => {
    try {
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
    } catch {
      return;
    }
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
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      {writeError ? (
        <div className="section-spaced">
          <ApiErrorState error={writeError} onRetry={load} />
          {writeError.code === 'EMPLOYMENT_PERIOD_OVERLAP' ? (
            <Alert
              type="error"
              showIcon
              title={t('employee.overlapRejected')}
              description={t('employee.overlapRejectedDescription')}
            />
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
          </Space>
          <Descriptions
            className="people-descriptions"
            column={1}
            items={[
              { key: 'name', label: t('employee.name'), children: detail.displayName },
              { key: 'number', label: t('employee.number'), children: <code>{detail.employeeNumber}</code> },
              { key: 'effective', label: t('people.effectivePeriod'), children: `${formatDate(detail.effectiveFrom)} — ${detail.effectiveTo ? formatDate(detail.effectiveTo) : t('people.longTerm')}` },
              {
                key: 'punchExempt',
                label: t('employee.punchExempt'),
                children: (
                  <Space direction="vertical" size={4}>
                    <Switch
                      checked={punchExemption?.standingExempt ?? false}
                      disabled={!capabilities.includes('EMPLOYEE:EDIT') || processing}
                      onChange={saveStandingExempt}
                    />
                    <span>{t('employee.punchExemptDescription')}</span>
                    {punchExemption?.executiveExempt ? (
                      <Tag color="blue">{t('employee.executiveExempt')}</Tag>
                    ) : null}
                  </Space>
                ),
              },
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
          {detail.priorService ? (
            <div className="prior-service-total">
              <strong>{detail.priorService.totalYears.toFixed(2)}</strong>
              <span>年</span>
              <span>{detail.priorService.totalDays} {t('employee.days')}</span>
            </div>
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
              icon: <IconBriefcase aria-hidden="true" stroke={2} size={20} />,
              content: (
                <article className="employment-period">
                  <div className="section-heading">
                    <div>
                      <h3>{t('employee.employmentPeriod', { start: formatDate(period.startDate) })}</h3>
                      <p>{employeeOrganizationName(organizations, period.organizationId) ?? t('employee.organizationUnavailable')}</p>
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
                    column={{ xs: 1, sm: 2 }}
                    items={[
                      { key: 'start', label: t('employee.startDate'), children: formatDate(period.startDate) },
                      { key: 'termination', label: t('employee.terminationDate'), children: period.terminationDate ? formatDate(period.terminationDate) : t('employee.currentEmployment') },
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
              {
                key: 'amount',
                title: '调整量',
                render: (row) => {
                  const days = row.amountDays;
                  const sign = days > 0 ? '+' : '';

                  // 优先显示amountYears（新365记录），否则从天数计算年数
                  if (row.amountYears != null) {
                    return <strong>{sign}{days} 天 ({sign}{row.amountYears.toFixed(2)} 年)</strong>;
                  } else if (row.conversionBasis === 'DAYS_PER_YEAR_360') {
                    // Excel 360记录：显示为360天/年
                    const years = (days / 360).toFixed(2);
                    return <strong>{sign}{days} 天 ({sign}{years} 年 360天/年)</strong>;
                  } else {
                    // 旧真实天数记录：从天数估算年数
                    const years = (days / 365).toFixed(2);
                    return <strong>{sign}{days} 天 (≈{sign}{years} 年)</strong>;
                  }
                }
              },
              {
                key: 'total',
                title: '累计工龄',
                render: (row) => {
                  // 使用后端返回的累计精确年数（从台账replay计算）
                  if (row.resultingTotalYears != null) {
                    return `${row.resultingTotalDays} 天 (${row.resultingTotalYears.toFixed(2)} 年)`;
                  }
                  // 降级：如果后端未提供，按最后一条记录的basis估算
                  const years = row.conversionBasis === 'DAYS_PER_YEAR_360'
                    ? (row.resultingTotalDays / 360).toFixed(2)
                    : (row.resultingTotalDays / 365).toFixed(2);
                  return `${row.resultingTotalDays} 天 (≈${years} 年)`;
                }
              },
              { key: 'date', title: t('employee.businessDate'), render: (row) => formatDate(row.businessDate) },
              { key: 'reason', title: t('people.reason'), render: (row) => row.reason },
              { key: 'time', title: t('people.changedAt'), render: (row) => formatDateTime(row.occurredAt) },
            ]}
          />
        )}
      </section>

      {canReadLeave && (
        <section className="content-surface section-spaced" aria-labelledby="leave-quota-title">
          <div className="section-heading">
            <div>
              <h2 id="leave-quota-title">假期额度（{currentYear} 年）</h2>
              <p>年假和调休余额放在一起查看；HR 可分别设置期初或手动调整。</p>
            </div>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 24 }}>
            <LeaveQuotaCard
              title="年假"
              account={annualLeave}
              canAdjust={canAdjustLeave}
              onOpening={() => {
                setLeaveKind('ANNUAL_LEAVE');
                leaveForm.resetFields();
                leaveForm.setFieldsValue({
                  balanceHours: annualLeave?.balanceHours ?? 0,
                  reason: '期初年假余额录入',
                });
                openDialog('leave-opening');
              }}
              onAdjust={() => {
                setLeaveKind('ANNUAL_LEAVE');
                leaveForm.resetFields();
                leaveForm.setFieldsValue({ reason: '手动调整年假' });
                openDialog('leave-adjust');
              }}
            />
            <LeaveQuotaCard
              title="调休"
              account={timeOff}
              canAdjust={canAdjustLeave}
              onOpening={() => {
                setLeaveKind('TIME_OFF');
                leaveForm.resetFields();
                leaveForm.setFieldsValue({
                  balanceHours: timeOff?.balanceHours ?? 0,
                  reason: '期初调休额度录入',
                });
                openDialog('leave-opening');
              }}
              onAdjust={() => {
                setLeaveKind('TIME_OFF');
                leaveForm.resetFields();
                leaveForm.setFieldsValue({ reason: '手动调整调休' });
                openDialog('leave-adjust');
              }}
            />
          </div>
        </section>
      )}

      <EmployeeDialogs
        dialog={dialog}
        employeeForm={employeeForm}
        periodForm={periodForm}
        priorForm={priorForm}
        reasonForm={reasonForm}
        leaveForm={leaveForm}
        leaveKindLabel={leaveKindLabel}
        processing={processing}
        error={writeError}
        organizationOptions={organizationOptions}
        originalEmployeeState={originalEmployeeState.current}
        onCancel={closeEmployeeDialog}
        onSaveEmployee={submitEmployee}
        onSavePeriod={submitPeriod}
        onSavePrior={submitPriorAdjustment}
        onRecalculate={submitPriorRecalculation}
        onSaveLeaveOpening={async () => {
          const values = await leaveForm.validateFields();
          await execute(async () => {
            await setLeaveOpeningBalance(
              employeeId,
              values.balanceHours,
              currentYear,
              values.reason.trim(),
              createIdempotencyKey(`leave-opening-${leaveKind}-${employeeId}`),
              leaveKind,
            );
            setFeedback(`期初${leaveKindLabel}余额已设置`);
          });
        }}
        onSaveLeaveAdjust={async () => {
          const values = await leaveForm.validateFields();
          await execute(async () => {
            await adjustLeaveBalance(
              employeeId,
              values.adjustmentHours,
              currentYear,
              values.reason.trim(),
              createIdempotencyKey(`leave-adjust-${leaveKind}-${employeeId}`),
              leaveKind,
            );
            setFeedback(`${leaveKindLabel}余额已调整`);
          });
        }}
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
  leaveForm,
  leaveKindLabel,
  processing,
  error,
  organizationOptions,
  originalEmployeeState,
  onCancel,
  onSaveEmployee,
  onSavePeriod,
  onSavePrior,
  onRecalculate,
  onSaveLeaveOpening,
  onSaveLeaveAdjust,
}: {
  dialog?: DialogMode;
  employeeForm: ReturnType<typeof Form.useForm<EmployeeFormValues>>[0];
  periodForm: ReturnType<typeof Form.useForm<PeriodFormValues>>[0];
  priorForm: ReturnType<typeof Form.useForm<PriorServiceFormValues>>[0];
  reasonForm: ReturnType<typeof Form.useForm<{ reason: string }>>[0];
  leaveForm: ReturnType<typeof Form.useForm<{ balanceHours: number; adjustmentHours: number; reason: string }>>[0];
  leaveKindLabel: string;
  processing: boolean;
  error?: ApiRequestError;
  organizationOptions: EmployeeOrganizationOption[];
  originalEmployeeState?: OriginalEmployeeState;
  onCancel: () => void;
  onSaveEmployee: () => void;
  onSavePeriod: () => void;
  onSavePrior: () => void;
  onRecalculate: () => void;
  onSaveLeaveOpening: () => void;
  onSaveLeaveAdjust: () => void;
}) {
  const { t } = useTranslation();
  const currentStatus = Form.useWatch('status', employeeForm);
  const currentAmountYears = Form.useWatch('amountYears', priorForm);
  const save = dialog === 'edit'
    ? onSaveEmployee
    : dialog === 'period-create' || dialog === 'period-edit'
      ? onSavePeriod
      : dialog === 'prior-adjust'
        ? onSavePrior
        : dialog === 'leave-opening'
          ? onSaveLeaveOpening
          : dialog === 'leave-adjust'
            ? onSaveLeaveAdjust
            : onRecalculate;
  const chooseStatus = (status: EmployeeStatus) => () => {
    employeeForm.setFieldValue('status', status);
    // 状态变化时，清空生效日期要求用户明确选择，除非切回原状态
    if (originalEmployeeState && status !== originalEmployeeState.status) {
      // 状态变化：清空日期，必须用户明确选择
      employeeForm.setFieldValue('effectiveFrom', undefined);
    } else if (originalEmployeeState && status === originalEmployeeState.status) {
      // 切回原状态：恢复原日期
      employeeForm.setFieldValue('effectiveFrom', dayjs(originalEmployeeState.effectiveFrom));
    }
  };
  return (
    <Modal
      open={Boolean(dialog)}
      title={dialog === 'leave-opening' || dialog === 'leave-adjust'
        ? dialogTitle(dialog, leaveKindLabel)
        : t(dialogTitle(dialog))}
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
                    type={currentStatus === status ? 'primary' : 'default'}
                    onClick={chooseStatus(status)}
                  >
                    {t(`employee.status.${status}`)}
                  </AccessibleButton>
                ))}
              </Space.Compact>
            </Form.Item>
            {originalEmployeeState && currentStatus !== originalEmployeeState.status ? (
              <Alert
                type="warning"
                showIcon
                message="状态变更需明确生效日期"
                description="状态变化需要新的生效日期（严格晚于原生效日期）。请明确选择生效日期，不要使用默认值。离职状态从该日起生效，保留此前在职历史。"
                style={{ marginBottom: 16 }}
              />
            ) : null}
            <Form.Item
              name="effectiveFrom"
              label={t('people.effectiveFrom')}
              extra={originalEmployeeState && currentStatus === originalEmployeeState.status
                ? t('people.identityCorrectionHint')
                : '状态变更时必须晚于原生效日期'}
              rules={[
                { required: true },
                ...(originalEmployeeState && currentStatus !== originalEmployeeState.status ? [{
                  validator: async (_: unknown, value: dayjs.Dayjs) => {
                    if (!value || !value.isAfter(dayjs(originalEmployeeState.effectiveFrom), 'day')) {
                      throw new Error('新状态生效日期必须严格晚于原生效日期');
                    }
                  }
                }] : []),
              ]}
            >
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="effectiveTo" label={t('people.effectiveTo')}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'period-create' || dialog === 'period-edit' ? (
        <Form form={periodForm} layout="vertical">
          <Form.Item name="organizationId" label={t('employee.organizationId')} rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="searchText"
              options={organizationOptions}
              placeholder={t('employee.organizationPlaceholder')}
            />
          </Form.Item>
          {/* 岗位主数据尚未接入；编辑任职时保留原值，避免把已存岗位意外清空。 */}
          <Form.Item name="positionId" hidden>
            <Input type="hidden" />
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
          <Alert
            showIcon
            type="info"
            description="输入工龄调整年数（正数增加、负数减少），系统将按 365 天/年转换为整数天数保存。最多保留 2 位小数（0.01 年 ≈ 4 天），输入后即可预览转换结果。"
          />
          <div className="form-grid">
            <Form.Item
              name="amountYears"
              label="调整年数"
              rules={[
                { required: true, message: '请输入调整年数' },
                { type: 'number', min: -100.00, max: 100.00, message: '调整年数范围：-100.00 至 100.00 年' },
                {
                  validator: async (_: unknown, value: number) => {
                    if (value !== undefined && value !== null) {
                      const days = convertYearsToDays(value);
                      if (days === 0) {
                        throw new Error('调整量无效：不能为 0 年或折合 0 天');
                      }
                    }
                  }
                }
              ]}
            >
              <InputNumber
                precision={2}
                min={-100.00}
                max={100.00}
                step={0.01}
                addonAfter="年"
                placeholder="正增负减"
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item name="businessDate" label={t('employee.businessDate')} rules={[{ required: true }]}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </div>
          {currentAmountYears !== undefined && currentAmountYears !== null ? (
            <Alert
              type="info"
              showIcon
              message="转换预览"
              description={`${currentAmountYears.toFixed(2)} 年 = ${convertYearsToDays(currentAmountYears)} 天（365 天/年四舍五入取整显示，例如 0.01 年 = 3.65 天，显示为 4 天）`}
              style={{ marginBottom: 16 }}
            />
          ) : null}
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'prior-recalculate' ? (
        <Form form={reasonForm} layout="vertical">
          <Alert showIcon type="info" title={t('employee.recalculateRule')} />
          <ReasonField />
        </Form>
      ) : null}
      {dialog === 'leave-opening' ? (
        <Form form={leaveForm} layout="vertical">
          <Alert
            showIcon
            type="info"
            title={`设置${leaveKindLabel}期初余额`}
            description={`此操作将把该员工本年${leaveKindLabel}余额重置为指定小时数（8小时=1天）。超用可录入负数，页面按天展示。如已有期初记录，系统将自动补差。`}
          />
          <Form.Item
            name="balanceHours"
            label="期初余额（小时）"
            rules={[{ required: true }, { type: 'number', min: -9999, max: 9999 }]}
          >
            <InputNumber precision={2} min={-9999} max={9999} step={8} addonAfter="h" />
          </Form.Item>
          <Form.Item name="reason" label="原因" rules={[{ required: true, min: 2 }, { max: 500 }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      ) : null}
      {dialog === 'leave-adjust' ? (
        <Form form={leaveForm} layout="vertical">
          <Alert
            showIcon
            type="info"
            title={`手动调整${leaveKindLabel}余额`}
            description="正数增加余额，负数扣减余额。有 OA 预占时不得低于预占小时；无预占时允许负数。"
          />
          <Form.Item
            name="adjustmentHours"
            label="调整小时数"
            rules={[{ required: true }, { type: 'number', min: -9999, max: 9999 }]}
          >
            <InputNumber precision={2} min={-9999} max={9999} step={8} addonAfter="h" />
          </Form.Item>
          <Form.Item name="reason" label="原因" rules={[{ required: true, min: 2 }, { max: 500 }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      ) : null}
    </Modal>
  );
}

function ReasonField() {
  const { t } = useTranslation();
  return (
    <Form.Item name="reason" label={t('people.reason')} rules={[{ required: true, min: 2 }, { max: 500 }]}>
      <Input.TextArea rows={3} />
    </Form.Item>
  );
}

function dialogTitle(dialog?: DialogMode, leaveKindLabel = '年假') {
  if (dialog === 'edit') return 'employee.editTitle';
  if (dialog === 'period-create') return 'employee.rehireTitle';
  if (dialog === 'period-edit') return 'employee.editPeriodTitle';
  if (dialog === 'prior-adjust') return 'employee.adjustPriorTitle';
  if (dialog === 'leave-opening') return `${leaveKindLabel}期初余额`;
  if (dialog === 'leave-adjust') return `手动调整${leaveKindLabel}余额`;
  return 'employee.recalculateTitle';
}

function LeaveQuotaCard({
  title,
  account,
  canAdjust,
  onOpening,
  onAdjust,
}: {
  title: string;
  account?: AnnualLeaveAccount;
  canAdjust: boolean;
  onOpening: () => void;
  onAdjust: () => void;
}) {
  return (
    <article>
      <div className="section-heading" style={{ marginBottom: 12 }}>
        <h3 style={{ margin: 0 }}>{title}</h3>
        {canAdjust ? (
          <Space wrap>
            <AccessibleButton
              label={`设置${title}期初余额`}
              icon={<IconPlus aria-hidden="true" stroke={2} />}
              onClick={onOpening}
            >
              设置期初
            </AccessibleButton>
            <AccessibleButton
              label={`手动调整${title}余额`}
              icon={<IconCalculator aria-hidden="true" stroke={2} />}
              onClick={onAdjust}
            >
              手动调整
            </AccessibleButton>
          </Space>
        ) : null}
      </div>
      {!account ? (
        <StatePanel state="empty" description={`暂无${title}账户记录`} />
      ) : (
        <>
          <div style={{ marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: 'var(--color-text-muted)' }}>当前余额</div>
            <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--color-brand-primary)' }}>
              {account.balanceHours.toFixed(1)} 小时
            </div>
            <div style={{ fontSize: 13, color: 'var(--color-text-muted)' }}>
              ≈ {account.equivalentDays.toFixed(1)} 天（8 小时制）
            </div>
          </div>
          {account.entries.length > 0 ? (
            <Table<AnnualLeaveLedgerEntry>
              size="small"
              dataSource={account.entries}
              rowKey="entryId"
              pagination={false}
              columns={[
                {
                  title: '类型',
                  dataIndex: 'entryTypeLabel',
                  width: 100,
                  render: (label: string, row) => (
                    <Tag color={row.amountHours >= 0 ? 'green' : 'red'}>{label}</Tag>
                  ),
                },
                {
                  title: '小时数',
                  dataIndex: 'amountHours',
                  width: 90,
                  render: (h: number) => (
                    <strong style={{ color: h >= 0 ? '#389e0d' : '#cf1322' }}>
                      {h >= 0 ? `+${h.toFixed(2)}` : h.toFixed(2)}
                    </strong>
                  ),
                },
                { title: '业务日期', dataIndex: 'businessDate', width: 110 },
                {
                  title: '到期日',
                  dataIndex: 'expiresOn',
                  width: 110,
                  render: (d: string | null) => d ?? '不过期',
                },
              ]}
            />
          ) : null}
        </>
      )}
    </article>
  );
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

export function toPeriodRequest(values: PeriodFormValues): EmploymentPeriodCreateRequest {
  return {
    organizationId: values.organizationId.trim(),
    positionId: values.positionId?.trim() || null,
    startDate: values.startDate.format('YYYY-MM-DD'),
    terminationDate: values.terminationDate?.format('YYYY-MM-DD') ?? null,
    reason: values.reason.trim(),
  };
}

function toPriorRequest(values: PriorServiceFormValues): PriorServiceAdjustmentRequest {
  // 年数是权威输入，服务端从年数计算天数
  const amountDays = convertYearsToDays(values.amountYears);
  return {
    amountYears: values.amountYears,
    amountDays,
    businessDate: values.businessDate.format('YYYY-MM-DD'),
    reason: values.reason.trim(),
  };
}

export function employeeOrganizationOptions(
  nodes: OrganizationNode[],
  selectedOrganizationIds: string[] = [],
  unavailableLabel = '部门信息暂不可用',
): EmployeeOrganizationOption[] {
  const selected = new Set(selectedOrganizationIds);
  const options = flattenEmployeeOrganizations(nodes)
    .filter(({ node }) => node.organizationType !== 'COMPANY' || selected.has(node.organizationId))
    .map(({ node, path }) => ({
      label: `${path.join(' / ')}（${node.code}）`,
      value: node.organizationId,
      searchText: `${path.join(' ')} ${node.code}`,
      disabled: node.status !== 'ACTIVE' || node.organizationType === 'COMPANY',
    }));
  const knownIds = new Set(options.map((option) => option.value));
  for (const organizationId of selected) {
    if (!knownIds.has(organizationId)) {
      options.push({
        label: unavailableLabel,
        value: organizationId,
        searchText: unavailableLabel,
        disabled: true,
      });
    }
  }
  return options;
}

export function employeeOrganizationName(
  nodes: OrganizationNode[],
  organizationId: string,
): string | undefined {
  return flattenEmployeeOrganizations(nodes)
    .find(({ node }) => node.organizationId === organizationId)
    ?.node.name;
}

function flattenEmployeeOrganizations(
  nodes: OrganizationNode[],
  parentNames: string[] = [],
): Array<{ node: OrganizationNode; path: string[] }> {
  return nodes.flatMap((node) => [
    { node, path: [...parentNames, node.name] },
    ...flattenEmployeeOrganizations(node.children, [...parentNames, node.name]),
  ]);
}

function asApiError(error: unknown, code: string): ApiRequestError {
  return error instanceof ApiRequestError
    ? error
    : new ApiRequestError(0, { code, retryable: true });
}

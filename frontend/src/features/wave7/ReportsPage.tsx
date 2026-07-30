import {
  Button,
  Input,
  type InputRef,
} from 'antd';
import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

import {
  ApiRequestError,
  saveDownloadedFile,
} from '../../shared/api/apiClient';
import {
  ConfirmationDialog,
  OperationFeedback,
  StatusBadge,
} from '../../shared/components/FeedbackComponents';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  AttendanceReportExportView,
  AttendanceReportCompanyDirectory,
  AttendanceReportType,
  LiveReportProjection,
  ReportColumnKey,
  ReportExportProjection,
  ReportExportRequest,
  ReportProjection,
  ReportRowProjection,
} from './wave7Contracts';
import {
  hasLiveReportMetadata,
  normalizeReportExportPurpose,
} from './wave7Contracts';
import {
  isReportExceptionState,
  type Wave7ProjectionGateway,
} from './wave7Gateway';
import {
  formatDateTime,
  FrozenHistoryNotice,
  LockedActionReason,
  ProjectionMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';

export function ReportsRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
  initialReportType = 'ATTENDANCE_DETAIL',
  initialPeriod = currentShanghaiPeriod(),
}: {
  capabilities?: readonly string[];
  gateway?: Wave7ProjectionGateway;
  initialReportType?: AttendanceReportType;
  initialPeriod?: string;
}) {
  const [reportType, setReportType] =
    useState<AttendanceReportType>(initialReportType);
  const [period, setPeriod] = useState(initialPeriod);
  const [companyId, setCompanyId] = useState('');
  const loadCompanies = useCallback(
    () => gateway.loadReportCompanies(period),
    [gateway, period],
  );

  return (
    <>
      <section className="content-surface wave7-report-filters" aria-labelledby="wave7-report-filters-heading">
        <h2 id="wave7-report-filters-heading">报表条件</h2>
        <div className="wave7-report-filter-grid">
          <label>
            <span>报表类型</span>
            <select
              aria-label="报表类型"
              value={reportType}
              onChange={(event) =>
                setReportType(event.target.value as AttendanceReportType)}
            >
              {reportTypeOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span>月份</span>
            <input
              aria-label="月份"
              type="month"
              value={period}
              onChange={(event) => {
                if (event.target.value) {
                  setCompanyId('');
                  setPeriod(event.target.value);
                }
              }}
            />
          </label>
        </div>
        <p>切换月份后会先清空上一条件的数据，再读取当前账号可见且已有正式投影的公司。</p>
      </section>
      <Wave7AsyncBoundary
        key={`companies:${period}`}
        loader={loadCompanies}
        isEmpty={(value) => value.companies.length === 0}
      >
        {(directory) => (
          <AuthorizedCompanyReport
            directory={directory}
            selectedCompanyId={companyId}
            onSelectCompany={setCompanyId}
            reportType={reportType}
            period={period}
            capabilities={capabilities}
            gateway={gateway}
          />
        )}
      </Wave7AsyncBoundary>
    </>
  );
}

function AuthorizedCompanyReport({
  directory,
  selectedCompanyId,
  onSelectCompany,
  reportType,
  period,
  capabilities,
  gateway,
}: {
  directory: AttendanceReportCompanyDirectory;
  selectedCompanyId: string;
  onSelectCompany: (companyId: string) => void;
  reportType: AttendanceReportType;
  period: string;
  capabilities: readonly string[];
  gateway: Wave7ProjectionGateway;
}) {
  const selectedIsAuthorized = directory.companies.some(
    (option) => option.companyId === selectedCompanyId,
  );
  const effectiveCompanyId = selectedIsAuthorized
    ? selectedCompanyId
    : directory.companies.length === 1
      ? directory.companies[0]!.companyId
      : '';
  const loadReport = useCallback(
    () => gateway.loadReport({
      reportType,
      period,
      companyId: effectiveCompanyId,
      page: 0,
      size: 50,
    }),
    [effectiveCompanyId, gateway, period, reportType],
  );
  const queryKey =
    `${reportType}:${period}:${effectiveCompanyId}`;

  return (
    <>
      <section
        className="content-surface wave7-report-filters"
        aria-labelledby="wave7-report-company-heading"
      >
        <h2 id="wave7-report-company-heading">公司范围</h2>
        <div className="wave7-report-filter-grid">
          <label>
            <span>公司</span>
            <select
              aria-label="公司"
              value={effectiveCompanyId}
              disabled={directory.companies.length === 1}
              onChange={(event) =>
                onSelectCompany(event.target.value)}
            >
              {directory.companies.length > 1 ? (
                <option value="">请选择公司</option>
              ) : null}
              {directory.companies.map((option) => (
                <option
                  key={option.companyId}
                  value={option.companyId}
                >
                  {option.companyName}
                </option>
              ))}
            </select>
          </label>
        </div>
        <p>
          {directory.companies.length > 1
            ? '当前账号可查看多个公司，请显式选择后查询；公司条件只会缩小服务端授权范围。'
            : '已按当前月份唯一可见且已有正式投影的公司查询。'}
        </p>
      </section>
      {effectiveCompanyId === '' ? (
        <OperationFeedback
          kind="info"
          message="请选择公司后查询正式报表。"
        />
      ) : (
        <Wave7AsyncBoundary
          key={queryKey}
          loader={loadReport}
          isEmpty={(value) => value.rows.length === 0}
        >
          {(projection) => (
            hasLiveReportMetadata(projection)
            && hasFormalReportExportGateway(gateway)
              ? (
                  <FormalReportWorkspace
                    projection={projection}
                    capabilities={capabilities}
                    gateway={gateway}
                  />
                )
              : (
                  <ReportView
                    projection={projection}
                    canCreateExport={capabilities.includes(
                      'ATTENDANCE_REPORT:EXPORT_CREATE',
                    )}
                  />
                )
          )}
        </Wave7AsyncBoundary>
      )}
    </>
  );
}

export const reportTypeOptions: ReadonlyArray<{
  value: AttendanceReportType;
  label: string;
}> = [
  { value: 'ATTENDANCE_DETAIL', label: '考勤明细' },
  { value: 'LEAVE', label: '请假统计' },
  { value: 'OVERTIME', label: '加班统计' },
  { value: 'WORK_HOURS', label: '月工时统计' },
  { value: 'EXCEPTIONS', label: '考勤异常总览' },
  { value: 'LATE', label: '迟到统计' },
  { value: 'MISSED_PUNCH', label: '未打卡统计' },
  { value: 'ATTENDANCE_RATE', label: '出勤率统计' },
  { value: 'ANNUAL_LEAVE', label: '年假统计' },
];

export const reportExportPollIntervalMs = 3_000;

type FormalReportExportGateway = Wave7ProjectionGateway & Required<Pick<
  Wave7ProjectionGateway,
  'createReportExport' | 'loadReportExport' | 'downloadReportExport'
>>;

function hasFormalReportExportGateway(
  gateway: Wave7ProjectionGateway,
): gateway is FormalReportExportGateway {
  return typeof gateway.createReportExport === 'function'
    && typeof gateway.loadReportExport === 'function'
    && typeof gateway.downloadReportExport === 'function';
}

function FormalReportWorkspace({
  projection,
  capabilities,
  gateway,
}: {
  projection: LiveReportProjection;
  capabilities: readonly string[];
  gateway: FormalReportExportGateway;
}) {
  const [job, setJob] =
    useState<AttendanceReportExportView | null>(null);
  const [statusError, setStatusError] = useState<string>();
  const [refreshing, setRefreshing] = useState(false);
  const mounted = useRef(true);
  const statusRequestSequence = useRef(0);
  const canCreateExport =
    capabilities.includes('ATTENDANCE_REPORT:EXPORT_CREATE');
  const canDownloadExport =
    capabilities.includes('ATTENDANCE_REPORT:EXPORT_DOWNLOAD')
    && projection.metadata.allowedActions.includes(
      'REPORT_EXPORT_DOWNLOAD',
    );

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const createExport = useCallback(async (
    request: ReportExportRequest,
    currentPassword?: string,
  ) => {
    if (currentPassword === undefined) {
      throw new ApiRequestError(400, {
        code: 'CURRENT_PASSWORD_REQUIRED',
        retryable: false,
      });
    }
    statusRequestSequence.current += 1;
    setJob(null);
    setStatusError(undefined);
    const created = await gateway.createReportExport({
      reportType: projection.reportType,
      period: projection.filters.period,
      companyId: projection.filters.companyId,
      status: projection.reportType === 'EXCEPTIONS'
        && typeof projection.filters.status === 'string'
        && isReportExceptionState(projection.filters.status)
        ? projection.filters.status
        : null,
      purpose: request.purpose,
      currentPassword,
    });
    if (mounted.current) setJob(created);
  }, [gateway, projection]);

  const refreshExport = useCallback(async (exportId: string) => {
    const requestSequence = ++statusRequestSequence.current;
    setRefreshing(true);
    try {
      const refreshed = await gateway.loadReportExport(exportId);
      if (
        !mounted.current
        || requestSequence !== statusRequestSequence.current
      ) {
        return;
      }
      setJob((current) =>
        current?.exportId === exportId ? refreshed : current);
      setStatusError(undefined);
    } catch (caught: unknown) {
      if (
        !mounted.current
        || requestSequence !== statusRequestSequence.current
      ) {
        return;
      }
      if (isAuthorizationFailure(caught)) {
        setJob((current) =>
          current?.exportId === exportId ? null : current);
      }
      setStatusError(exportActionMessage(caught, 'refresh'));
    } finally {
      if (
        mounted.current
        && requestSequence === statusRequestSequence.current
      ) {
        setRefreshing(false);
      }
    }
  }, [gateway]);

  useEffect(() => {
    if (
      job === null
      || (job.status !== 'QUEUED' && job.status !== 'BUILDING')
      || statusError !== undefined
    ) {
      return undefined;
    }
    const exportId = job.exportId;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      const requestSequence = ++statusRequestSequence.current;
      void gateway.loadReportExport(exportId)
        .then((refreshed) => {
          if (
            cancelled
            || !mounted.current
            || requestSequence !== statusRequestSequence.current
          ) {
            return;
          }
          setJob((current) =>
            current?.exportId === exportId ? refreshed : current);
          setStatusError(undefined);
        })
        .catch((caught: unknown) => {
          if (
            cancelled
            || !mounted.current
            || requestSequence !== statusRequestSequence.current
          ) {
            return;
          }
          if (isAuthorizationFailure(caught)) {
            setJob((current) =>
              current?.exportId === exportId ? null : current);
          }
          setStatusError(exportActionMessage(caught, 'refresh'));
        });
    }, reportExportPollIntervalMs);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [gateway, job, statusError]);

  const downloadExport = useCallback(async (
    exportId: string,
    currentPassword: string,
  ) => {
    const file = await gateway.downloadReportExport(
      exportId,
      currentPassword,
    );
    if (mounted.current) saveDownloadedFile(file);
  }, [gateway]);

  return (
    <>
      <ReportView
        projection={projection}
        canCreateExport={canCreateExport}
        requireCurrentPassword
        onCreateExport={createExport}
      />
      {statusError ? (
        <OperationFeedback kind="error" message={statusError} />
      ) : null}
      {job ? (
        <FormalReportExportStatus
          job={job}
          canDownload={canDownloadExport}
          refreshing={refreshing}
          onRefresh={() => {
            void refreshExport(job.exportId);
          }}
          onDownload={downloadExport}
        />
      ) : null}
    </>
  );
}

export function ReportView({
  projection,
  canCreateExport,
  onCreateExport,
  requireCurrentPassword = false,
}: {
  projection: ReportProjection;
  canCreateExport: boolean;
  onCreateExport?: (
    request: ReportExportRequest,
    currentPassword?: string,
  ) => void | Promise<void>;
  requireCurrentPassword?: boolean;
}) {
  const [exportOpen, setExportOpen] = useState(false);
  const [purpose, setPurpose] = useState('');
  const [purposeError, setPurposeError] = useState<string>();
  const [currentPassword, setCurrentPassword] = useState('');
  const [passwordError, setPasswordError] = useState<string>();
  const [submissionError, setSubmissionError] = useState<string>();
  const [exportSubmitting, setExportSubmitting] = useState(false);
  const exportAttempt = useRef(0);
  const passwordInput = useRef<InputRef>(null);
  const projectionAllowsExport = projection.metadata.allowedActions.includes('REPORT_EXPORT_CREATE');
  const exportEnabled = canCreateExport && projectionAllowsExport && onCreateExport !== undefined;
  const liveMetadata = hasLiveReportMetadata(projection) ? projection : null;
  const reportColumns: Array<DataColumn<ReportRowProjection>> = projection.columns.map((column) => ({
    key: column.key,
    title: column.label,
    render: (row) => row.values[column.key] ?? '—',
  }));

  const confirmExport = async () => {
    if (exportSubmitting) return;
    let request: ReportExportRequest;
    try {
      request = createReportExportRequest(
        projection,
        projection.exportFieldAllowlist,
        purpose,
      );
    } catch (caught: unknown) {
      void caught;
      setPurposeError(purpose.trim() === ''
        ? '请填写导出用途。'
        : '导出用途必须为 2 至 200 个安全字符。');
      return;
    }
    if (
      requireCurrentPassword
      && (currentPassword.trim() === '' || currentPassword.length > 256)
    ) {
      setPasswordError('请输入有效的当前密码。');
      return;
    }
    const passwordForRequest = currentPassword;
    const attempt = ++exportAttempt.current;
    setCurrentPassword('');
    setPasswordError(undefined);
    setSubmissionError(undefined);
    setExportSubmitting(true);
    try {
      if (requireCurrentPassword) {
        await onCreateExport?.(request, passwordForRequest);
      } else {
        await onCreateExport?.(request);
      }
      if (attempt !== exportAttempt.current) return;
      setExportOpen(false);
      setPurpose('');
      setPurposeError(undefined);
    } catch (caught: unknown) {
      if (attempt !== exportAttempt.current) return;
      setSubmissionError(exportActionMessage(caught, 'create'));
    } finally {
      if (attempt === exportAttempt.current) {
        setCurrentPassword('');
        setExportSubmitting(false);
      }
    }
  };

  const closeExportDialog = () => {
    exportAttempt.current++;
    if (passwordInput.current?.input) {
      passwordInput.current.input.value = '';
    }
    setExportOpen(false);
    setPurpose('');
    setPurposeError(undefined);
    setCurrentPassword('');
    setPasswordError(undefined);
    setSubmissionError(undefined);
    setExportSubmitting(false);
  };

  return (
    <>
      <PageHeader
        title={projection.reportTitle}
        description="汇总、明细和导出固定使用当前范围、筛选与版本。"
        actions={(
          <Button type="primary" disabled={!exportEnabled} onClick={() => setExportOpen(true)}>
            创建受控导出
          </Button>
        )}
      />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      {!canCreateExport || !projectionAllowsExport
        ? <LockedActionReason>当前仅可查看报表，不能创建导出。</LockedActionReason>
        : null}
      <section className="wave7-report-binding" aria-label="报表查询绑定">
        <dl>
          {liveMetadata ? (
            <div>
              <dt>报表类型</dt>
              <dd>{reportTypeLabel(liveMetadata.reportType)}</dd>
            </div>
          ) : null}
          <div><dt>筛选期间</dt><dd>{projection.filters.period}</dd></div>
          {projection.filters.companyId ? (
            <div>
              <dt>公司绑定</dt>
              <dd><code>{projection.filters.companyId}</code></dd>
            </div>
          ) : null}
          <div><dt>状态</dt><dd>{projection.filters.status ?? '全部'}</dd></div>
          <div>
            <dt>授权 scope</dt>
            <dd>
              {projection.metadata.scope.label}
              {' · '}
              {projection.metadata.scope.type}
            </dd>
          </div>
          {liveMetadata ? (
            <div>
              <dt>公式版本</dt>
              <dd><code>{liveMetadata.formulaVersion}</code></dd>
            </div>
          ) : null}
          <div><dt>查询指纹</dt><dd><code>{projection.queryFingerprint}</code></dd></div>
          <div><dt>授权行数</dt><dd>{projection.rowCount}</dd></div>
          {liveMetadata ? (
            <div>
              <dt>分页</dt>
              <dd>
                第 {liveMetadata.page + 1} 页
                {' / '}
                {liveMetadata.totalPages} 页
                {' · '}
                每页 {liveMetadata.size} 行
              </dd>
            </div>
          ) : null}
        </dl>
      </section>
      <section className="content-surface" aria-labelledby="wave7-report-table-heading">
        <h2 id="wave7-report-table-heading">同版本汇总明细</h2>
        <DataTable
          ariaLabel={projection.reportTitle}
          rows={projection.rows}
          rowKey={(row) => row.rowReference}
          columns={reportColumns}
        />
      </section>
      <ConfirmationDialog
        open={exportOpen}
        title="确认创建受控导出"
        confirmText="创建导出"
        processing={exportSubmitting}
        onConfirm={() => {
          void confirmExport();
        }}
        onCancel={closeExportDialog}
        description={(
          <div className="wave7-export-confirmation">
            <p>导出将固定使用当前范围、筛选、字段白名单和投影版本，并记录审计。</p>
            <dl>
              <div><dt>范围</dt><dd>{projection.metadata.scope.label}</dd></div>
              <div><dt>版本</dt><dd><code>{projection.metadata.projectionVersion}</code></dd></div>
              <div><dt>字段数</dt><dd>{projection.exportFieldAllowlist.length}</dd></div>
            </dl>
            <label htmlFor="wave7-export-purpose">导出用途</label>
            <Input.TextArea
              id="wave7-export-purpose"
              value={purpose}
              maxLength={200}
              aria-describedby="wave7-export-purpose-help"
              aria-invalid={purposeError ? 'true' : undefined}
              onChange={(event) => {
                setPurpose(event.target.value);
                if (purposeError) setPurposeError(undefined);
                if (submissionError) setSubmissionError(undefined);
              }}
            />
            <p id="wave7-export-purpose-help">
              请填写 2 至 200 个字符的复核或业务用途。
            </p>
            {purposeError ? <p role="alert">{purposeError}</p> : null}
            {requireCurrentPassword ? (
              <>
                <label htmlFor="wave7-export-current-password">
                  当前密码
                </label>
                <Input.Password
                  ref={passwordInput}
                  id="wave7-export-current-password"
                  value={currentPassword}
                  maxLength={256}
                  autoComplete="current-password"
                  aria-describedby="wave7-export-password-help"
                  aria-invalid={passwordError ? 'true' : undefined}
                  onChange={(event) => {
                    setCurrentPassword(event.target.value);
                    if (passwordError) setPasswordError(undefined);
                    if (submissionError) setSubmissionError(undefined);
                  }}
                />
                <p id="wave7-export-password-help">
                  创建导出前必须重新验证当前密码；密码不会写入网址或浏览器存储。
                </p>
                {passwordError ? (
                  <p role="alert">{passwordError}</p>
                ) : null}
              </>
            ) : null}
            {submissionError ? (
              <p role="alert">{submissionError}</p>
            ) : null}
          </div>
        )}
      />
    </>
  );
}

export function FormalReportExportStatus({
  job,
  canDownload,
  refreshing,
  onRefresh,
  onDownload,
}: {
  job: AttendanceReportExportView;
  canDownload: boolean;
  refreshing: boolean;
  onRefresh: () => void;
  onDownload: (
    exportId: string,
    currentPassword: string,
  ) => Promise<void>;
}) {
  const [downloadOpen, setDownloadOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [passwordError, setPasswordError] = useState<string>();
  const [downloadError, setDownloadError] = useState<string>();
  const [downloading, setDownloading] = useState(false);
  const downloadAttempt = useRef(0);
  const passwordInput = useRef<InputRef>(null);
  const downloadEnabled = job.status === 'READY' && canDownload;

  const closeDownloadDialog = () => {
    downloadAttempt.current++;
    if (passwordInput.current?.input) {
      passwordInput.current.input.value = '';
    }
    setDownloadOpen(false);
    setCurrentPassword('');
    setPasswordError(undefined);
    setDownloadError(undefined);
    setDownloading(false);
  };

  const confirmDownload = async () => {
    if (downloading) return;
    if (currentPassword.trim() === '' || currentPassword.length > 256) {
      setPasswordError('请输入有效的当前密码。');
      return;
    }
    const passwordForRequest = currentPassword;
    const attempt = ++downloadAttempt.current;
    setCurrentPassword('');
    setPasswordError(undefined);
    setDownloadError(undefined);
    setDownloading(true);
    try {
      await onDownload(job.exportId, passwordForRequest);
      if (attempt !== downloadAttempt.current) return;
      closeDownloadDialog();
    } catch (caught: unknown) {
      if (attempt !== downloadAttempt.current) return;
      setDownloadError(exportActionMessage(caught, 'download'));
    } finally {
      if (attempt === downloadAttempt.current) {
        setCurrentPassword('');
        setDownloading(false);
      }
    }
  };

  return (
    <section
      className="content-surface wave7-export-status"
      aria-labelledby="formal-report-export-status-heading"
      aria-live="polite"
    >
      <header>
        <h2 id="formal-report-export-status-heading">导出任务</h2>
        <StatusBadge
          status={job.status === 'BUILDING' ? 'RUNNING' : job.status}
        />
      </header>
      <dl className="wave7-detail-grid">
        <div>
          <dt>交付方式</dt>
          <dd>{job.deliveryMode === 'SYNC' ? '同步' : '异步'}</dd>
        </div>
        <div><dt>报表类型</dt><dd>{reportTypeLabel(job.reportType)}</dd></div>
        <div><dt>期间</dt><dd>{job.period}</dd></div>
        <div><dt>用途</dt><dd>{job.purpose}</dd></div>
        <div><dt>授权行数</dt><dd>{job.rowCount}</dd></div>
        <div>
          <dt>到期时间</dt>
          <dd>{formatDateTime(job.expiresAt)}</dd>
        </div>
        {job.completedAt ? (
          <div>
            <dt>完成时间</dt>
            <dd>{formatDateTime(job.completedAt)}</dd>
          </div>
        ) : null}
        <div><dt>任务引用</dt><dd><code>{job.exportId}</code></dd></div>
      </dl>
      {job.status === 'QUEUED' || job.status === 'BUILDING' ? (
        <p role="status">
          异步导出正在安全生成；系统会自动轮询，也可以手动刷新。
        </p>
      ) : null}
      {job.status === 'FAILED' ? (
        <OperationFeedback
          kind="error"
          message="导出生成失败，请重新创建。"
        />
      ) : null}
      {job.status === 'EXPIRED' ? (
        <OperationFeedback
          kind="warning"
          message="导出已过期，请重新创建。"
        />
      ) : null}
      <div className="wave7-export-actions">
        <Button
          disabled={refreshing || job.status === 'EXPIRED'}
          loading={refreshing}
          onClick={onRefresh}
        >
          刷新导出状态
        </Button>
        <Button
          type="primary"
          disabled={!downloadEnabled}
          onClick={() => {
            setDownloadOpen(true);
            setCurrentPassword('');
            setPasswordError(undefined);
            setDownloadError(undefined);
          }}
        >
          下载文件
        </Button>
      </div>
      {job.status === 'READY' && !canDownload ? (
        <LockedActionReason>
          当前账号不能下载该导出。
        </LockedActionReason>
      ) : null}
      <ConfirmationDialog
        open={downloadOpen}
        title="重新验证并下载"
        confirmText="验证并下载"
        processing={downloading}
        onConfirm={() => {
          void confirmDownload();
        }}
        onCancel={closeDownloadDialog}
        description={(
          <div className="wave7-export-confirmation">
            <p>
              下载前服务端会重新校验当前密码、授权范围、投影版本和文件摘要。
            </p>
            <label htmlFor="wave7-download-current-password">
              当前密码
            </label>
            <Input.Password
              ref={passwordInput}
              id="wave7-download-current-password"
              value={currentPassword}
              maxLength={256}
              autoComplete="current-password"
              aria-describedby="wave7-download-password-help"
              aria-invalid={passwordError ? 'true' : undefined}
              onChange={(event) => {
                setCurrentPassword(event.target.value);
                if (passwordError) setPasswordError(undefined);
                if (downloadError) setDownloadError(undefined);
              }}
            />
            <p id="wave7-download-password-help">
              密码仅用于本次身份复核，请求发出后立即清空。
            </p>
            {passwordError ? (
              <p role="alert">{passwordError}</p>
            ) : null}
            {downloadError ? (
              <p role="alert">{downloadError}</p>
            ) : null}
          </div>
        )}
      />
    </section>
  );
}

export function ReportExportStatus({
  job,
  canDownload,
  onDownload,
}: {
  job: ReportExportProjection;
  canDownload: boolean;
  onDownload?: (exportReference: string) => void;
}) {
  const downloadEnabled = job.state === 'READY'
    && job.canDownload
    && canDownload
    && onDownload !== undefined;
  return (
    <section className="content-surface wave7-export-status" aria-labelledby="wave7-export-status-heading" aria-live="polite">
      <header>
        <h2 id="wave7-export-status-heading">导出任务</h2>
        <StatusBadge status={job.state} />
      </header>
      <dl className="wave7-detail-grid">
        <div><dt>交付方式</dt><dd>{job.delivery === 'SYNCHRONOUS' ? '同步' : '异步'}</dd></div>
        <div><dt>用途</dt><dd>{job.purpose}</dd></div>
        <div><dt>申请人</dt><dd>{job.requesterLabel}</dd></div>
        <div><dt>创建时间</dt><dd>{formatDateTime(job.createdAt)}</dd></div>
        <div><dt>审计引用</dt><dd><code>{job.auditReference}</code></dd></div>
        <div><dt>任务引用</dt><dd><code>{job.exportReference}</code></dd></div>
      </dl>
      <Button
        disabled={!downloadEnabled}
        onClick={() => onDownload?.(job.exportReference)}
      >
        下载文件
      </Button>
    </section>
  );
}

export function createReportExportRequest(
  projection: ReportProjection,
  selectedFields: ReportColumnKey[],
  purpose: string,
): ReportExportRequest {
  const normalizedPurpose = normalizeReportExportPurpose(purpose);
  const allowlist = new Set(projection.exportFieldAllowlist);
  if (!selectedFields.every((field) => allowlist.has(field))) {
    throw new TypeError('导出字段超出当前报表白名单');
  }
  return {
    queryFingerprint: projection.queryFingerprint,
    projectionVersion: projection.metadata.projectionVersion,
    scopeReference: projection.metadata.scope.reference,
    filters: projection.filters,
    selectedFields: [...selectedFields],
    purpose: normalizedPurpose,
  };
}

export function exportDeliveryForRowCount(
  rowCount: number,
): ReportExportProjection['delivery'] {
  if (!Number.isInteger(rowCount) || rowCount < 0) {
    throw new TypeError('导出行数必须为非负整数');
  }
  return rowCount <= 50_000 ? 'SYNCHRONOUS' : 'ASYNCHRONOUS';
}

function reportTypeLabel(reportType: AttendanceReportType): string {
  return reportTypeOptions.find((option) => option.value === reportType)?.label
    ?? reportType;
}

function isAuthorizationFailure(caught: unknown): boolean {
  return caught instanceof ApiRequestError
    && (caught.status === 401
      || caught.status === 403
      || caught.status === 404);
}

function exportActionMessage(
  caught: unknown,
  action: 'create' | 'refresh' | 'download',
): string {
  if (caught instanceof ApiRequestError) {
    if (
      caught.status === 401
      && caught.code === 'REAUTHENTICATION_FAILED'
    ) {
      return action === 'download'
        ? '当前密码验证失败，文件未下载。'
        : '当前密码验证失败，导出未创建。';
    }
    if (caught.status === 401) {
      return '会话已失效，请重新登录。';
    }
    if (caught.status === 403 || caught.status === 404) {
      return '当前账号无权访问该导出，或导出不存在。';
    }
    if (caught.status === 409 || caught.status === 410) {
      return '导出当前不可用，请刷新状态或重新创建。';
    }
    if (caught.status === 400 || caught.status === 422) {
      return '导出条件无效，请检查用途和当前密码。';
    }
    if (caught.status === 0) {
      return '网络暂时不可用，请稍后重试。';
    }
  }
  return action === 'refresh'
    ? '导出状态暂时无法刷新，请稍后手动重试。'
    : '报表导出操作失败，请稍后重试。';
}

function currentShanghaiPeriod(): string {
  const parts = new Intl.DateTimeFormat('en', {
    year: 'numeric',
    month: '2-digit',
    timeZone: 'Asia/Shanghai',
  }).formatToParts(new Date());
  const year = parts.find((part) => part.type === 'year')?.value;
  const month = parts.find((part) => part.type === 'month')?.value;
  if (year === undefined || month === undefined) {
    throw new TypeError('无法确定当前报表月份');
  }
  return `${year}-${month}`;
}

export default ReportsRoute;

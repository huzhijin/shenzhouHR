import {
  Button,
  Input,
  type InputRef,
} from 'antd';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { useSearchParams } from 'react-router-dom';

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
  AttendanceMonthMatrixBadgeCode,
  AttendanceMonthMatrixProjection,
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
  type ReportExceptionState,
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
  const [searchParameters, setSearchParameters] = useSearchParams();
  const [reportType, setReportType] =
    useState<AttendanceReportType>(() => initialReportTypeFromSearch(
      searchParameters,
      initialReportType,
    ));
  const [period, setPeriod] = useState(() => initialPeriodFromSearch(
    searchParameters,
    initialPeriod,
  ));
  const [companyId, setCompanyId] = useState(() =>
    initialCompanyIdFromSearch(searchParameters));
  const [reportPage, setReportPage] = useState(0);
  const [exceptionStatus, setExceptionStatus] =
    useState<ReportExceptionState | undefined>(
      () => initialExceptionStatusFromSearch(searchParameters),
    );
  const expectedProjectionVersion =
    initialExpectedProjectionVersionFromSearch(searchParameters);
  const clearExpectedProjectionVersion = useCallback(() => {
    if (!searchParameters.has('expectedProjectionVersion')) return;
    const next = new URLSearchParams(searchParameters);
    next.delete('expectedProjectionVersion');
    setSearchParameters(next, { replace: true });
  }, [searchParameters, setSearchParameters]);
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
              onChange={(event) => {
                setReportPage(0);
                setReportType(
                  event.target.value as AttendanceReportType,
                );
              }}
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
                  setReportPage(0);
                  setCompanyId('');
                  clearExpectedProjectionVersion();
                  setPeriod(event.target.value);
                }
              }}
            />
          </label>
          {reportType === 'EXCEPTIONS' ? (
            <label>
              <span>异常状态</span>
              <select
                aria-label="异常状态"
                value={exceptionStatus ?? ''}
                onChange={(event) => {
                  const value = event.target.value;
                  setReportPage(0);
                  setExceptionStatus(
                    isReportExceptionState(value) ? value : undefined,
                  );
                }}
              >
                {reportExceptionStatusOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
        </div>
        <p>切换月份后会重新读取当前账号可查看的公司和考勤数据。</p>
      </section>
      <Wave7AsyncBoundary
        key={`companies:${period}`}
        loader={loadCompanies}
        isEmpty={(value) => value.companies.length === 0}
        emptyTitle="所选月份暂无可查看报表"
        emptyDescription="所选月份无已发布正式投影。连接数据库或已有原始数据不会自动生成报表，需完成考勤计算与正式投影发布。"
      >
        {(directory) => (
          <AuthorizedCompanyReport
            directory={directory}
            selectedCompanyId={companyId}
            onSelectCompany={setCompanyId}
            expectedProjectionVersion={expectedProjectionVersion}
            onClearExpectedProjectionVersion={
              clearExpectedProjectionVersion
            }
            reportType={reportType}
            period={period}
            exceptionStatus={exceptionStatus}
            reportPage={reportPage}
            onReportPageChange={setReportPage}
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
  expectedProjectionVersion,
  onClearExpectedProjectionVersion,
  reportType,
  period,
  exceptionStatus,
  reportPage,
  onReportPageChange,
  capabilities,
  gateway,
}: {
  directory: AttendanceReportCompanyDirectory;
  selectedCompanyId: string;
  onSelectCompany: (companyId: string) => void;
  expectedProjectionVersion?: string;
  onClearExpectedProjectionVersion: () => void;
  reportType: AttendanceReportType;
  period: string;
  exceptionStatus?: ReportExceptionState;
  reportPage: number;
  onReportPageChange: (page: number) => void;
  capabilities: readonly string[];
  gateway: Wave7ProjectionGateway;
}) {
  const [matrixPage, setMatrixPage] = useState(0);
  const [versionEpoch, setVersionEpoch] = useState(0);
  const projectionVersion = useRef(expectedProjectionVersion);
  const expectedVersionFromUrl = useRef(expectedProjectionVersion);
  const automaticRebaseAttempted = useRef(false);
  const reportRequestGeneration = useRef(0);
  const selectedIsAuthorized = directory.companies.some(
    (option) => option.companyId === selectedCompanyId,
  );
  const effectiveCompanyId = selectedIsAuthorized
    ? selectedCompanyId
    : directory.companies.length === 1
      ? directory.companies[0]!.companyId
      : '';

  useEffect(() => {
    setMatrixPage(0);
  }, [effectiveCompanyId, period, reportType]);

  useEffect(() => {
    if (expectedVersionFromUrl.current === expectedProjectionVersion) {
      return;
    }
    expectedVersionFromUrl.current = expectedProjectionVersion;
    projectionVersion.current = expectedProjectionVersion;
    automaticRebaseAttempted.current = false;
    setMatrixPage(0);
    onReportPageChange(0);
    setVersionEpoch((value) => value + 1);
  }, [expectedProjectionVersion, onReportPageChange]);

  const queryKey =
    `${reportType}:${period}:${effectiveCompanyId}:${
      reportType === 'EXCEPTIONS' ? exceptionStatus ?? '' : ''
    }:${reportPage}:${matrixPage}:${versionEpoch}`;

  useLayoutEffect(() => {
    reportRequestGeneration.current += 1;
    return () => {
      reportRequestGeneration.current += 1;
    };
  }, [queryKey]);

  const loadReport = useCallback(
    async () => {
      const requestGeneration = reportRequestGeneration.current + 1;
      reportRequestGeneration.current = requestGeneration;
      const isCurrentRequest = () =>
        reportRequestGeneration.current === requestGeneration;
      const matrixLoader = gateway.loadAttendanceMonthMatrix;
      const shouldLoadMatrix = reportType === 'ATTENDANCE_DETAIL'
        && matrixLoader !== undefined;
      const requestedProjectionVersion = projectionVersion.current;
      let requestUsedProjectionPrecondition =
        requestedProjectionVersion !== undefined;
      try {
        const reportQuery = {
          reportType,
          period,
          companyId: effectiveCompanyId,
          ...(reportType === 'EXCEPTIONS'
            && exceptionStatus !== undefined
            ? { status: exceptionStatus }
            : {}),
          ...(requestedProjectionVersion !== undefined
            ? {
                expectedProjectionVersion:
                  requestedProjectionVersion,
              }
            : {}),
          page: reportPage,
          size: 50,
        };
        let projection: ReportProjection;
        let monthMatrix: AttendanceMonthMatrixProjection | null;
        if (requestedProjectionVersion !== undefined) {
          [projection, monthMatrix] = await Promise.all([
            gateway.loadReport(reportQuery),
            shouldLoadMatrix
              ? matrixLoader({
                  period,
                  companyId: effectiveCompanyId,
                  expectedProjectionVersion:
                    requestedProjectionVersion,
                  page: matrixPage,
                  size: 20,
                })
              : Promise.resolve(null),
          ]);
        } else {
          projection = await gateway.loadReport(reportQuery);
          const establishedProjectionVersion =
            projection.metadata.projectionVersion;
          requestUsedProjectionPrecondition = shouldLoadMatrix;
          monthMatrix = shouldLoadMatrix
            ? await matrixLoader({
                period,
                companyId: effectiveCompanyId,
                expectedProjectionVersion: establishedProjectionVersion,
                page: matrixPage,
                size: 20,
              })
            : null;
        }
        if (
          monthMatrix !== null
          && !attendanceReportMatrixSnapshotsMatch(
            projection,
            monthMatrix,
          )
        ) {
          throw new ApiRequestError(409, {
            code: 'ATTENDANCE_REPORT_MATRIX_SNAPSHOT_MISMATCH',
            message: attendanceReportMatrixSnapshotMismatchMessage,
            retryable: true,
          });
        }
        if (isCurrentRequest()) {
          projectionVersion.current =
            projection.metadata.projectionVersion;
          automaticRebaseAttempted.current = false;
        }
        return { projection, monthMatrix };
      } catch (caught: unknown) {
        if (
          isCurrentRequest()
          && requestUsedProjectionPrecondition
          && isProjectionVersionChanged(caught)
          && !automaticRebaseAttempted.current
        ) {
          automaticRebaseAttempted.current = true;
          projectionVersion.current = undefined;
          setMatrixPage(0);
          onReportPageChange(0);
          onClearExpectedProjectionVersion();
          setVersionEpoch((value) => value + 1);
        }
        throw caught;
      }
    },
    [
      effectiveCompanyId,
      exceptionStatus,
      gateway,
      matrixPage,
      onClearExpectedProjectionVersion,
      onReportPageChange,
      period,
      reportPage,
      reportType,
    ],
  );

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
              onChange={(event) => {
                reportRequestGeneration.current += 1;
                projectionVersion.current = undefined;
                automaticRebaseAttempted.current = false;
                setMatrixPage(0);
                onReportPageChange(0);
                onClearExpectedProjectionVersion();
                setVersionEpoch((value) => value + 1);
                onSelectCompany(event.target.value);
              }}
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
            ? '当前账号可查看多个公司，请选择公司后查询；结果不会超出账号的数据权限。'
            : '已按当前月份唯一可见的公司查询。'}
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
          isEmpty={(value) => value.monthMatrix !== null
            ? value.monthMatrix.employeeCount === 0
            : value.projection.rowCount === 0}
        >
          {({ projection, monthMatrix }) => (
            hasLiveReportMetadata(projection)
            && hasFormalReportExportGateway(gateway)
              ? (
                  <FormalReportWorkspace
                    projection={projection}
                    monthMatrix={monthMatrix}
                    onMatrixPageChange={setMatrixPage}
                    onReportPageChange={onReportPageChange}
                    capabilities={capabilities}
                    gateway={gateway}
                  />
                )
              : (
                  <ReportView
                    projection={projection}
                    monthMatrix={monthMatrix}
                    onMatrixPageChange={setMatrixPage}
                    onReportPageChange={onReportPageChange}
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

const reportExceptionStatusOptions: ReadonlyArray<{
  value: '' | ReportExceptionState;
  label: string;
}> = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'PENDING_EVIDENCE', label: '待补充材料' },
  { value: 'PENDING_REVIEW', label: '待复核' },
  { value: 'RESOLVED', label: '已处理' },
];

function initialReportTypeFromSearch(
  parameters: URLSearchParams,
  fallback: AttendanceReportType,
): AttendanceReportType {
  const value = singleSearchParameter(parameters, 'reportType');
  return reportTypeOptions.some((option) => option.value === value)
    ? value as AttendanceReportType
    : fallback;
}

function initialPeriodFromSearch(
  parameters: URLSearchParams,
  fallback: string,
): string {
  const value = singleSearchParameter(parameters, 'period');
  return value !== undefined
    && /^\d{4}-(0[1-9]|1[0-2])$/.test(value)
    ? value
    : fallback;
}

function initialCompanyIdFromSearch(
  parameters: URLSearchParams,
): string {
  const value = singleSearchParameter(parameters, 'companyId');
  return value !== undefined
    && value === value.trim()
    && value.length >= 1
    && value.length <= 36
    && !hasControlCharacter(value)
    ? value
    : '';
}

function initialExceptionStatusFromSearch(
  parameters: URLSearchParams,
): ReportExceptionState | undefined {
  const value = singleSearchParameter(parameters, 'status');
  return value !== undefined && isReportExceptionState(value)
    ? value
    : undefined;
}

function initialExpectedProjectionVersionFromSearch(
  parameters: URLSearchParams,
): string | undefined {
  const value = singleSearchParameter(
    parameters,
    'expectedProjectionVersion',
  );
  return value !== undefined
    && value === value.trim()
    && value.length >= 1
    && value.length <= 128
    && !hasControlCharacter(value)
    ? value
    : undefined;
}

function singleSearchParameter(
  parameters: URLSearchParams,
  name: string,
): string | undefined {
  const values = parameters.getAll(name);
  return values.length === 1 ? values[0] : undefined;
}

function hasControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined
      && (
        codePoint <= 0x1f
        || (codePoint >= 0x7f && codePoint <= 0x9f)
      );
  });
}

export const reportExportPollIntervalMs = 3_000;

export const attendanceReportMatrixSnapshotMismatchMessage =
  '考勤矩阵与平铺明细不是同一份正式数据快照，系统已停止展示和导出。请刷新后重试。';

function isProjectionVersionChanged(error: unknown): boolean {
  return error instanceof ApiRequestError
    && error.status === 409
    && error.code === 'ATTENDANCE_REPORT_PROJECTION_CHANGED';
}

export function attendanceReportMatrixSnapshotsMatch(
  projection: ReportProjection,
  matrix: AttendanceMonthMatrixProjection,
): boolean {
  if (
    !hasLiveReportMetadata(projection)
    || projection.reportType !== 'ATTENDANCE_DETAIL'
  ) {
    return false;
  }
  const reportMetadata = projection.metadata;
  const matrixMetadata = matrix.metadata;
  const reportScope = reportMetadata.scope;
  const matrixScope = matrixMetadata.scope;
  const reportFilters = projection.filters;
  const matrixFilters = matrix.filters;
  return reportMetadata.projectionVersion
      === matrixMetadata.projectionVersion
    && reportMetadata.dataAsOf === matrixMetadata.dataAsOf
    && equalOrderedStrings(
      reportMetadata.sourceVersions,
      matrixMetadata.sourceVersions,
    )
    && reportMetadata.periodState === matrixMetadata.periodState
    && reportMetadata.periodLabel === matrixMetadata.periodLabel
    && reportMetadata.timeZone === matrixMetadata.timeZone
    && equalStringSets(
      reportMetadata.allowedActions,
      matrixMetadata.allowedActions,
    )
    && reportScope.type === matrixScope.type
    && reportScope.reference === matrixScope.reference
    && reportScope.label === matrixScope.label
    && reportFilters.period === matrixFilters.period
    && reportFilters.companyId === matrixFilters.companyId
    && (reportFilters.organizationId ?? null)
      === (matrixFilters.organizationId ?? null)
    && (reportFilters.employeeId ?? null)
      === (matrixFilters.employeeId ?? null)
    && (reportFilters.status ?? null) === null
    && reportFilters.scopeReference === matrixFilters.scopeReference
    && reportMetadata.periodLabel === reportFilters.period
    && matrixMetadata.periodLabel === matrixFilters.period
    && reportScope.reference === reportFilters.scopeReference
    && matrixScope.reference === matrixFilters.scopeReference;
}

function equalOrderedStrings(
  left: readonly string[],
  right: readonly string[],
): boolean {
  return left.length === right.length
    && left.every((value, index) => value === right[index]);
}

function equalStringSets(
  left: readonly string[],
  right: readonly string[],
): boolean {
  return left.length === right.length
    && left.every((value) => right.includes(value));
}

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
  monthMatrix,
  onMatrixPageChange,
  onReportPageChange,
  capabilities,
  gateway,
}: {
  projection: LiveReportProjection;
  monthMatrix: AttendanceMonthMatrixProjection | null;
  onMatrixPageChange: (page: number) => void;
  onReportPageChange: (page: number) => void;
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
      projectionVersion: request.projectionVersion,
      queryFingerprint: request.queryFingerprint,
      scopeReference: request.scopeReference,
      filters: {
        ...request.filters,
        companyId: projection.filters.companyId,
      },
      selectedFields: [...request.selectedFields],
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
        monthMatrix={monthMatrix}
        onMatrixPageChange={onMatrixPageChange}
        onReportPageChange={onReportPageChange}
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
  monthMatrix = null,
  onMatrixPageChange,
  onReportPageChange,
  canCreateExport,
  onCreateExport,
  requireCurrentPassword = false,
}: {
  projection: ReportProjection;
  monthMatrix?: AttendanceMonthMatrixProjection | null;
  onMatrixPageChange?: (page: number) => void;
  onReportPageChange?: (page: number) => void;
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
  if (
    monthMatrix !== null
    && !attendanceReportMatrixSnapshotsMatch(
      projection,
      monthMatrix,
    )
  ) {
    return (
      <OperationFeedback
        kind="error"
        message={attendanceReportMatrixSnapshotMismatchMessage}
      />
    );
  }
  const projectionAllowsExport = projection.metadata.allowedActions.includes('REPORT_EXPORT_CREATE');
  const exportEnabled = canCreateExport && projectionAllowsExport && onCreateExport !== undefined;
  const liveMetadata = hasLiveReportMetadata(projection) ? projection : null;
  const businessColumns = projection.columns.filter((column) =>
    isBusinessReportColumn(column.key));
  const businessExportFields = projection.exportFieldAllowlist.filter(
    isBusinessReportColumn,
  );
  const reportColumns: Array<DataColumn<ReportRowProjection>> =
    businessColumns.map((column) => ({
      key: column.key,
      // The API label is metadata for compatibility only. Keeping the visible
      // header keyed here prevents backend field names or future internal copy
      // from leaking into the business report.
      title: reportColumnLabel(column.key),
      render: (row) => reportCellDisplayValue(
        column.key,
        row.values[column.key],
      ),
    }));

  const confirmExport = async () => {
    if (exportSubmitting) return;
    let request: ReportExportRequest;
    try {
      request = createReportExportRequest(
        projection,
        businessExportFields,
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
        description={monthMatrix
          ? '当前页面以月度矩阵展示；受控导出为同一正式快照的平铺考勤明细，不保留颜色、标签样式或矩阵布局。'
          : '按当前范围和筛选条件展示汇总明细，并支持受控导出。'}
        actions={(
          <Button type="primary" disabled={!exportEnabled} onClick={() => setExportOpen(true)}>
            创建受控导出
          </Button>
        )}
      />
      {monthMatrix ? (
        <section aria-label="月度考勤矩阵数据概览">
          <ProjectionMetadata metadata={monthMatrix.metadata} />
        </section>
      ) : (
        <ProjectionMetadata metadata={projection.metadata} />
      )}
      <FrozenHistoryNotice
        metadata={monthMatrix?.metadata ?? projection.metadata}
      />
      {liveMetadata?.reportType === 'ATTENDANCE_RATE'
        && liveMetadata.formulaVersion
          === 'ATTENDANCE_RATE_CONFIRMED_OVER_SCHEDULED_V1_PROVISIONAL'
        ? (
            <OperationFeedback
              kind="info"
              message="当前出勤率为暂行口径：排班内确认工作分钟 ÷ 原始应出勤分钟 × 100%。最终分子、分母及请假处理仍须业务签字确认。"
            />
          )
        : null}
      {monthMatrix ? (
        <OperationFeedback
          kind="info"
          message="导出文件是平铺正式明细，不保留当前矩阵的颜色、状态标签样式和横向日期布局。"
        />
      ) : null}
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
          <div>
            <dt>筛选期间</dt>
            <dd>{monthMatrix?.filters.period ?? projection.filters.period}</dd>
          </div>
          {monthMatrix === null ? (
            <div>
              <dt>状态</dt>
              <dd>
                {typeof projection.filters.status === 'string'
                  ? reportCellDisplayValue(
                      'exception-state',
                      projection.filters.status,
                    )
                  : '全部'}
              </dd>
            </div>
          ) : null}
          <div>
            <dt>数据范围</dt>
            <dd>{monthMatrix?.metadata.scope.label
              ?? projection.metadata.scope.label}</dd>
          </div>
          {monthMatrix || liveMetadata ? (
            <div>
              <dt>计算公式版本</dt>
              <dd>
                <code>
                  {monthMatrix?.formulaVersion
                    ?? liveMetadata?.formulaVersion}
                </code>
              </dd>
            </div>
          ) : null}
          <div>
            <dt>{monthMatrix ? '员工总数' : '记录数'}</dt>
            <dd>{monthMatrix?.employeeCount ?? projection.rowCount}</dd>
          </div>
          {monthMatrix ? (
            <div>
              <dt>矩阵分页</dt>
              <dd>
                第 {monthMatrix.page + 1} 页
                {' / '}
                {monthMatrix.totalPages} 页
                {' · '}
                每页 {monthMatrix.size} 人
              </dd>
            </div>
          ) : liveMetadata ? (
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
      {monthMatrix ? (
        <AttendanceMonthMatrixView
          matrix={monthMatrix}
          onPageChange={onMatrixPageChange}
        />
      ) : (
        <section className="content-surface" aria-labelledby="wave7-report-table-heading">
          <h2 id="wave7-report-table-heading">汇总明细</h2>
          <DataTable
            ariaLabel={projection.reportTitle}
            rows={projection.rows}
            rowKey={(row) => row.rowReference}
            columns={reportColumns}
          />
          {liveMetadata ? (
            <FormalReportPagination
              projection={liveMetadata}
              onPageChange={onReportPageChange}
            />
          ) : null}
        </section>
      )}
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
            <p>
              {monthMatrix
                ? '导出将使用当前范围和同一正式快照，生成平铺考勤明细；不保留矩阵颜色、标签样式和布局，并记录操作日志。'
                : '导出将使用当前范围和筛选条件，并记录操作日志。'}
            </p>
            <dl>
              <div><dt>范围</dt><dd>{projection.metadata.scope.label}</dd></div>
              <div><dt>导出字段</dt><dd>{businessExportFields.length} 项</dd></div>
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

function FormalReportPagination({
  projection,
  onPageChange,
}: {
  projection: LiveReportProjection;
  onPageChange?: (page: number) => void;
}) {
  const pageOutOfRange = projection.totalPages > 0
    && projection.page >= projection.totalPages;
  useEffect(() => {
    if (pageOutOfRange && onPageChange !== undefined) {
      onPageChange(projection.totalPages - 1);
    }
  }, [
    onPageChange,
    pageOutOfRange,
    projection.totalPages,
  ]);

  if (pageOutOfRange) {
    return (
      <OperationFeedback
        kind="info"
        message="当前页码已超出有效范围，正在返回最后一页。"
      />
    );
  }
  return (
    <div className="wave7-report__pagination" aria-label="报表分页">
      <Button
        disabled={projection.page === 0 || onPageChange === undefined}
        onClick={() => onPageChange?.(projection.page - 1)}
      >
        上一页
      </Button>
      <span>
        第 {projection.page + 1} / {Math.max(projection.totalPages, 1)} 页
        {' · '}
        每页 {projection.size} 行
      </span>
      <Button
        disabled={
          projection.page + 1 >= projection.totalPages
          || onPageChange === undefined
        }
        onClick={() => onPageChange?.(projection.page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}

const monthMatrixBadgeLabels: Readonly<Record<
  AttendanceMonthMatrixBadgeCode,
  string
>> = {
  LATE: '迟到',
  EARLY_DEPARTURE: '早退',
  MISSING_PUNCH: '漏刷',
  ABSENCE: '旷工',
  RECOGNIZED_OVERTIME: '认可加班',
  TIME_OFF: '调休',
  OUTING: '外出',
  TRIP: '出差',
  PERSONAL_LEAVE: '事假',
  SICK_LEAVE: '病假',
  ANNUAL_LEAVE: '年假',
  PUNCH_CORRECTION: '补签',
  REST_DAY: '休息日',
  OTHER_LEAVE: '其他请假/调休',
  LEAVE_REVOCATION: '销假',
  OVERTIME_APPLICATION: '加班单',
  EXEMPT_PUNCH: '免打卡',
  OTHER_ATTENDANCE_DOCUMENT: '其他考勤单',
  OTHER_EXCEPTION: '其他异常',
};

const monthMatrixLegendCodes: readonly AttendanceMonthMatrixBadgeCode[] = [
  'LATE',
  'EARLY_DEPARTURE',
  'MISSING_PUNCH',
  'ABSENCE',
  'RECOGNIZED_OVERTIME',
  'TIME_OFF',
  'OUTING',
  'TRIP',
  'PERSONAL_LEAVE',
  'SICK_LEAVE',
  'ANNUAL_LEAVE',
  'PUNCH_CORRECTION',
  'REST_DAY',
];

export function AttendanceMonthMatrixView({
  matrix,
  onPageChange,
}: {
  matrix: AttendanceMonthMatrixProjection;
  onPageChange?: (page: number) => void;
}) {
  const pageOutOfRange = matrix.totalPages > 0
    && matrix.page >= matrix.totalPages;
  useEffect(() => {
    if (pageOutOfRange && onPageChange !== undefined) {
      onPageChange(matrix.totalPages - 1);
    }
  }, [matrix.totalPages, onPageChange, pageOutOfRange]);

  if (pageOutOfRange) {
    return (
      <section
        className="content-surface wave7-month-matrix"
        aria-labelledby="wave7-month-matrix-heading"
      >
        <h2 id="wave7-month-matrix-heading">月度考勤明细矩阵</h2>
        <OperationFeedback
          kind="info"
          message="当前页码已超出有效范围，正在返回最后一页。"
        />
      </section>
    );
  }
  return (
    <section
      className="content-surface wave7-month-matrix"
      aria-labelledby="wave7-month-matrix-heading"
    >
      <div className="wave7-month-matrix__heading">
        <div>
          <h2 id="wave7-month-matrix-heading">月度考勤明细矩阵</h2>
          <p>
            颜色仅用于快速识别；考勤结论由打卡时间、班次、规则、有效单据和正式异常事实计算。
            同一天存在多个状态时会全部并列显示。
          </p>
        </div>
        <span>共 {matrix.employeeCount} 人</span>
      </div>
      <div className="wave7-month-matrix__legend" aria-label="考勤状态图例">
        {monthMatrixLegendCodes.map((code) => (
          <MonthMatrixBadge key={code} code={code} />
        ))}
      </div>
      <div className="wave7-month-matrix__scroll">
        <table aria-label="正式月度考勤明细矩阵">
          <thead>
            <tr>
              <th className="wave7-month-matrix__identity">工号</th>
              <th className="wave7-month-matrix__identity">姓名</th>
              <th className="wave7-month-matrix__organization">部门</th>
              {matrix.dates.map((date) => (
                <th key={date}>
                  <span>{date.slice(8)}</span>
                  <small>{matrixWeekday(date)}</small>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {matrix.rows.map((row) => (
              <tr key={row.employeeId}>
                <td className="wave7-month-matrix__identity">{row.employeeNumber}</td>
                <td className="wave7-month-matrix__identity">{row.employeeName}</td>
                <td className="wave7-month-matrix__organization">{row.organizationName}</td>
                {row.days.map((day) => (
                  <td
                    key={day.date}
                    title={[
                      day.organizationName,
                      day.shiftLabel,
                    ].filter(Boolean).join(' · ') || undefined}
                  >
                    <div className="wave7-month-matrix__punches">
                      <time>{formatMatrixPunch(
                        day.firstPunchAt,
                        matrix.metadata.timeZone,
                      )}</time>
                      <time>{formatMatrixPunch(
                        day.lastPunchAt,
                        matrix.metadata.timeZone,
                      )}</time>
                    </div>
                    {day.shiftLabel ? (
                      <small className="wave7-month-matrix__shift">
                        {day.shiftLabel}
                      </small>
                    ) : null}
                    <div className="wave7-month-matrix__badges">
                      {day.badges.map((code) => (
                        <MonthMatrixBadge key={code} code={code} />
                      ))}
                    </div>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="wave7-month-matrix__pagination" aria-label="矩阵分页">
        <Button
          disabled={matrix.page === 0 || onPageChange === undefined}
          onClick={() => onPageChange?.(matrix.page - 1)}
        >
          上一页
        </Button>
        <span>
          第 {matrix.page + 1} / {Math.max(matrix.totalPages, 1)} 页
          {' · '}
          每页 {matrix.size} 人
        </span>
        <Button
          disabled={
            matrix.page + 1 >= matrix.totalPages
            || onPageChange === undefined
          }
          onClick={() => onPageChange?.(matrix.page + 1)}
        >
          下一页
        </Button>
      </div>
    </section>
  );
}

function MonthMatrixBadge({
  code,
}: {
  code: AttendanceMonthMatrixBadgeCode;
}) {
  return (
    <span
      className="wave7-month-matrix__badge"
      data-badge-code={code}
    >
      {monthMatrixBadgeLabels[code]}
    </span>
  );
}

function formatMatrixPunch(
  value: string | null,
  timeZone: string,
): string {
  if (value === null) return '—';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone,
    }).format(new Date(value));
  } catch (error: unknown) {
    void error;
    return '—';
  }
}

function matrixWeekday(value: string): string {
  const weekday = new Date(`${value}T00:00:00Z`).getUTCDay();
  return ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][weekday]
    ?? '—';
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
              下载前会重新校验当前密码和数据访问权限。
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
  if (
    selectedFields.length === 0
    || new Set(selectedFields).size !== selectedFields.length
    || !selectedFields.every((field) => (
      allowlist.has(field) && isBusinessReportColumn(field)
    ))
  ) {
    throw new TypeError('导出字段超出当前报表白名单');
  }
  return {
    queryFingerprint: projection.queryFingerprint,
    projectionVersion: projection.metadata.projectionVersion,
    scopeReference: projection.metadata.scope.reference,
    filters: {
      ...projection.filters,
      scopeReference: projection.metadata.scope.reference,
    },
    selectedFields: [...selectedFields],
    purpose: normalizedPurpose,
  };
}

const internalReportColumns = new Set<ReportColumnKey>([
  // These fields remain available to backend reconciliation and audit logic.
  // The UI and interactive export deliberately omit their opaque values.
  'document-reference',
  'rate-formula-version',
]);

export function isBusinessReportColumn(key: ReportColumnKey): boolean {
  return !internalReportColumns.has(key);
}

const reportColumnLabels = {
  'business-date': '考勤日期',
  'employee-number': '工号',
  'employee-name': '姓名',
  organization: '部门',
  shift: '班次',
  'scheduled-hours': '应出勤工时',
  'confirmed-hours': '确认工时',
  'recognized-overtime-hours': '认可加班',
  'leave-hours': '请假/调休',
  'absence-hours': '旷工',
  'actual-work-hours': '实际工时',
  'late-minutes': '迟到分钟',
  'penalized-late-minutes': '计罚迟到分钟',
  'early-minutes': '早退分钟',
  'missing-punch-count': '缺卡次数',
  'first-punch': '首次有效打卡',
  'last-punch': '末次有效打卡',
  'document-type': '单据类型',
  'document-reference': '单据记录',
  'document-start': '开始时间',
  'document-end': '结束时间',
  'approval-state': '审批状态',
  'recognized-hours': '认定小时',
  'weekday-overtime-hours': '工作日加班',
  'saturday-overtime-hours': '周六加班',
  'sunday-overtime-hours': '周日加班',
  'holiday-overtime-hours': '法定节假日加班',
  'exception-type': '异常类型',
  'exception-severity': '异常级别',
  'exception-state': '处理状态',
  'exception-minutes': '异常分钟',
  'evidence-summary': '异常说明',
  'late-event-count': '迟到次数',
  'attendance-rate': '出勤率（%）',
  'rate-formula-version': '计算规则',
  'account-type': '账户类型',
  'opening-hours': '期初',
  'granted-hours': '系统发放',
  'overtime-credit-hours': '加班转入',
  'manual-increase-hours': '人工增加',
  'used-hours': '请假/调休使用',
  'expired-hours': '到期失效',
  'returned-hours': '销假/撤销返还',
  'manual-deduction-hours': '人工扣减',
  'balance-hours': '当前余额',
  'equivalent-days': '折合天数（8 小时/天）',
  scope: '范围',
  'late-count': '迟到次数',
  'early-count': '早退次数',
  'missing-count': '缺卡次数',
} satisfies Readonly<Record<ReportColumnKey, string>>;

export function reportColumnLabel(key: ReportColumnKey): string {
  return reportColumnLabels[key] ?? '其他字段';
}

export function reportCellDisplayValue(
  key: ReportColumnKey,
  value: string | number | undefined,
): string | number {
  if (value === undefined || value === '') return '—';
  if (typeof value === 'number') return value;
  const normalized = value.trim().toUpperCase();
  if (key === 'exception-type') {
    return ({
      LATE: '迟到',
      EARLY_DEPARTURE: '早退',
      MISSING_PUNCH_PENDING: '缺卡待补正',
      MISSING_PUNCH_OVERDUE: '缺卡逾期',
      ABSENCE: '旷工',
      EVIDENCE_CONFLICT: '考勤依据冲突',
      LEAVE_PUNCH_CONFLICT: '请假与打卡冲突',
      OUTING_OR_TRIP_INCOMPLETE: '外出或出差信息不完整',
      OA_APPROVAL_STATUS_UNKNOWN: '审批状态待确认',
      OA_PERSON_REFERENCE_INVALID: '单据人员信息待确认',
      EMPLOYEE_UNMATCHED: '员工信息未匹配',
      DUPLICATE_SOURCE_RECORD: '来源记录重复',
      SOURCE_SCHEMA_CHANGED: '来源数据格式变化',
      SOURCE_SYNC_STALE: '来源数据未及时更新',
      NO_ATTENDANCE_GROUP: '未配置考勤组',
      NO_SHIFT_OR_CALENDAR: '未配置班次或日历',
      AMBIGUOUS_PUNCH_MATCH: '打卡匹配待确认',
      CROSS_MIDNIGHT_REVIEW_REQUIRED: '跨日考勤待复核',
      OVERTIME_DOCUMENT_MISSING_OR_LATE: '加班单据缺失或提交较晚',
      EARLY_RETURN_CANDIDATE: '可能提前返岗',
      POST_CLOSE_SOURCE_CHANGE: '结算后来源数据发生变化',
      INPUT_INTEGRITY_ERROR: '考勤数据不完整',
    } as Readonly<Record<string, string>>)[normalized]
      ?? unknownReportEnumLabel(value, normalized, '其他异常');
  }
  if (key === 'exception-severity') {
    return ({
      INFO: '提示',
      WARNING: '警告',
      ERROR: '错误',
    } as Readonly<Record<string, string>>)[normalized]
      ?? unknownReportEnumLabel(value, normalized, '其他级别');
  }
  if (key === 'exception-state') {
    return ({
      OPEN: '待处理',
      PENDING: '待确认',
      PENDING_EVIDENCE: '待补充依据',
      PENDING_REVIEW: '待复核',
      RESOLVED: '已解决',
      CLOSED: '已完成',
    } as Readonly<Record<string, string>>)[normalized]
      ?? unknownReportEnumLabel(value, normalized, '其他状态');
  }
  if (key === 'approval-state') {
    return ({
      OPEN: '待处理',
      PENDING: '待审批',
      APPROVED: '已通过',
      REJECTED: '已驳回',
      DRAFT: '草稿',
      MODIFIED: '已修改',
      SUPPLEMENTED: '已补充',
      REVOKED: '已撤销',
      UNKNOWN: '待确认',
    } as Readonly<Record<string, string>>)[normalized]
      ?? unknownReportEnumLabel(value, normalized, '其他状态');
  }
  if (key === 'account-type') {
    return ({
      ANNUAL_LEAVE: '年假',
      COMP_TIME: '调休',
      RECOGNIZED_OVERTIME: '认可加班',
    } as Readonly<Record<string, string>>)[normalized]
      ?? unknownReportEnumLabel(value, normalized, '其他账户');
  }
  if (key === 'document-type') {
    const label = ({
      LEAVE: '请假',
      LEAVE_REVOCATION: '销假',
      OVERTIME: '加班',
      TRIP: '出差',
      OUTING: '外出',
      PUNCH_CORRECTION: '补卡',
      TIME_OFF: '调休',
      EXEMPT_PUNCH: '免打卡',
      ANNUAL_LEAVE: '年假',
      PERSONAL_LEAVE: '事假',
      SICK_LEAVE: '病假',
      MATERNITY_LEAVE: '产假',
    } as Readonly<Record<string, string>>)[normalized];
    if (label) return label;
    return /^[A-Z][A-Z0-9_:-]*$/.test(normalized) ? '其他单据' : value;
  }
  return value;
}

function unknownReportEnumLabel(
  value: string,
  normalized: string,
  fallback: string,
): string {
  return /^[A-Z][A-Z0-9_:-]*$/.test(normalized) ? fallback : value;
}

export function exportDeliveryForRowCount(
  rowCount: number,
): ReportExportProjection['delivery'] {
  if (!Number.isInteger(rowCount) || rowCount < 0) {
    throw new TypeError('导出行数必须为非负整数');
  }
  return rowCount <= 50_000 ? 'SYNCHRONOUS' : 'ASYNCHRONOUS';
}

export function reportTypeLabel(reportType: AttendanceReportType): string {
  return reportTypeOptions.find((option) => option.value === reportType)?.label
    ?? '其他报表';
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

import {
  ApiRequestError,
  requestFile,
  requestJson,
  type DownloadedFile,
} from '../../shared/api/apiClient';
import type {
  AttendanceReportExportView,
  AttendanceReportCompanyDirectory,
  AttendanceMonthMatrixProjection,
  AttendanceReportType,
  AttendanceRecordsProjection,
  DashboardLoadResult,
  FeedbackProjection,
  LeaveProjection,
  ReportExportRequest,
  ReportProjection,
  SelfAttendanceDashboardProjection,
  TodayProjection,
} from './wave7Contracts';
import {
  assertAttendanceReportExportView,
  assertAttendanceReportCompanyDirectory,
  assertAttendanceMonthMatrixProjection,
  assertLiveReportProjection,
  attendanceReportTypes,
  normalizeReportExportPurpose,
  parseAttendanceDashboardResponse,
  parseSelfAttendanceDashboardResponse,
  reportColumnKeys,
} from './wave7Contracts';

export interface ReportQuery {
  reportType: AttendanceReportType;
  period: string;
  companyId?: string;
  organizationId?: string;
  employeeId?: string;
  status?: ReportExceptionState;
  expectedProjectionVersion?: string;
  page?: number;
  size?: number;
}

export interface ReportExportCreateRequest extends ReportExportRequest {
  reportType: AttendanceReportType;
  filters: ReportExportRequest['filters'] & { companyId: string };
}

export interface AttendanceMonthMatrixQuery {
  period: string;
  companyId?: string;
  organizationId?: string;
  employeeId?: string;
  expectedProjectionVersion?: string;
  page?: number;
  size?: number;
}

export type ReportExceptionState =
  | 'OPEN'
  | 'PENDING_EVIDENCE'
  | 'PENDING_REVIEW'
  | 'RESOLVED';

export interface Wave7ProjectionGateway {
  loadSelfDashboard(): Promise<SelfAttendanceDashboardProjection>;
  loadToday(): Promise<TodayProjection>;
  loadRecords(): Promise<AttendanceRecordsProjection>;
  loadLeave(): Promise<LeaveProjection>;
  loadFeedback(): Promise<FeedbackProjection>;
  loadDashboard(companyId?: string): Promise<DashboardLoadResult>;
  loadReportCompanies(
    period: string,
  ): Promise<AttendanceReportCompanyDirectory>;
  loadReport(query?: ReportQuery): Promise<ReportProjection>;
  loadAttendanceMonthMatrix?(
    query: AttendanceMonthMatrixQuery,
  ): Promise<AttendanceMonthMatrixProjection>;
  createReportExport?(
    request: ReportExportCreateRequest,
  ): Promise<AttendanceReportExportView>;
  loadReportExport?(
    exportId: string,
  ): Promise<AttendanceReportExportView>;
  downloadReportExport?(
    exportId: string,
  ): Promise<DownloadedFile>;
}

const reportExportBasePath = '/api/v1/attendance-reports/exports';
const xlsxMediaType =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

const upstreamPending = (): Promise<never> => Promise.reject(new ApiRequestError(503, {
  code: 'WAVE7_UPSTREAM_PENDING',
  message: '该功能的数据尚未准备好，请稍后再试。',
  retryable: false,
}));

export const wave7ProjectionGateway: Wave7ProjectionGateway = {
  loadSelfDashboard: async () => {
    const response = await requestJson<unknown>(
      '/api/v1/me/attendance-dashboard',
    );
    try {
      return parseSelfAttendanceDashboardResponse(response);
    } catch (error: unknown) {
      void error;
      throw invalidSelfAttendanceDashboardResponse();
    }
  },
  loadToday: upstreamPending,
  loadRecords: upstreamPending,
  loadLeave: upstreamPending,
  loadFeedback: upstreamPending,
  loadDashboard: async (companyId) => {
    const normalizedCompanyId = normalizeDashboardCompanyId(companyId);
    const parameters = new URLSearchParams();
    if (normalizedCompanyId !== undefined) {
      parameters.set('companyId', normalizedCompanyId);
    }
    const query = parameters.size > 0
      ? `?${parameters.toString()}`
      : '';
    const response = await requestJson<unknown>(
      `/api/v1/attendance-dashboards${query}`,
    );
    try {
      const result = parseAttendanceDashboardResponse(response);
      if (
        normalizedCompanyId !== undefined
        && (
          result.kind !== 'DASHBOARD'
          || result.selectedCompanyId !== normalizedCompanyId
        )
      ) {
        throw invalidDashboardResponse();
      }
      return result;
    } catch (error: unknown) {
      if (
        error instanceof ApiRequestError
        && error.code === 'INVALID_DASHBOARD_RESPONSE'
      ) {
        throw error;
      }
      void error;
      throw invalidDashboardResponse();
    }
  },
  loadReportCompanies: async (period) => {
    if (!isYearMonth(period)) {
      throw invalidReportQuery();
    }
    const response = await requestJson<unknown>(
      `/api/v1/attendance-reports/companies?period=${
        encodeURIComponent(period)
      }`,
    );
    try {
      assertAttendanceReportCompanyDirectory(response);
    } catch (error: unknown) {
      void error;
      throw invalidReportResponse();
    }
    if (response.period !== period) {
      throw invalidReportResponse();
    }
    return response;
  },
  loadReport: async (query) => {
    const normalized = normalizeReportQuery(query);
    const parameters = new URLSearchParams();
    parameters.set('reportType', normalized.reportType);
    parameters.set('period', normalized.period);
    if (normalized.companyId !== undefined) {
      parameters.set('companyId', normalized.companyId);
    }
    if (normalized.organizationId !== undefined) {
      parameters.set('organizationId', normalized.organizationId);
    }
    if (normalized.employeeId !== undefined) {
      parameters.set('employeeId', normalized.employeeId);
    }
    if (normalized.status !== undefined) {
      parameters.set('status', normalized.status);
    }
    if (normalized.expectedProjectionVersion !== undefined) {
      parameters.set(
        'expectedProjectionVersion',
        normalized.expectedProjectionVersion,
      );
    }
    parameters.set('page', String(normalized.page));
    parameters.set('size', String(normalized.size));

    const response = await requestJson<unknown>(
      `/api/v1/attendance-reports?${parameters.toString()}`,
    );
    try {
      assertLiveReportProjection(response);
    } catch (error: unknown) {
      void error;
      throw invalidReportResponse();
    }
    if (
      response.reportType !== normalized.reportType
      || response.filters.period !== normalized.period
      || (
        normalized.companyId !== undefined
        && response.filters.companyId !== normalized.companyId
      )
      || (response.filters.organizationId ?? undefined)
        !== normalized.organizationId
      || (response.filters.employeeId ?? undefined) !== normalized.employeeId
      || (response.filters.status ?? null)
        !== (normalized.status ?? null)
      || (
        normalized.expectedProjectionVersion !== undefined
        && response.metadata.projectionVersion
          !== normalized.expectedProjectionVersion
      )
      || response.page !== normalized.page
      || response.size !== normalized.size
    ) {
      throw invalidReportResponse();
    }
    return response;
  },
  loadAttendanceMonthMatrix: async (query) => {
    const normalized = normalizeAttendanceMonthMatrixQuery(query);
    const parameters = new URLSearchParams();
    parameters.set('period', normalized.period);
    if (normalized.companyId !== undefined) {
      parameters.set('companyId', normalized.companyId);
    }
    if (normalized.organizationId !== undefined) {
      parameters.set('organizationId', normalized.organizationId);
    }
    if (normalized.employeeId !== undefined) {
      parameters.set('employeeId', normalized.employeeId);
    }
    if (normalized.expectedProjectionVersion !== undefined) {
      parameters.set(
        'expectedProjectionVersion',
        normalized.expectedProjectionVersion,
      );
    }
    parameters.set('page', String(normalized.page));
    parameters.set('size', String(normalized.size));
    const response = await requestJson<unknown>(
      `/api/v1/attendance-reports/month-matrix?${parameters.toString()}`,
    );
    try {
      assertAttendanceMonthMatrixProjection(response);
    } catch (error: unknown) {
      void error;
      throw invalidReportResponse();
    }
    if (
      response.filters.period !== normalized.period
      || (
        normalized.companyId !== undefined
        && response.filters.companyId !== normalized.companyId
      )
      || (response.filters.organizationId ?? undefined)
        !== normalized.organizationId
      || (response.filters.employeeId ?? undefined) !== normalized.employeeId
      || (
        normalized.expectedProjectionVersion !== undefined
        && response.metadata.projectionVersion
          !== normalized.expectedProjectionVersion
      )
      || response.page !== normalized.page
      || response.size !== normalized.size
    ) {
      throw invalidReportResponse();
    }
    return response;
  },
  createReportExport: async (request) => {
    const normalized = normalizeReportExportCreateRequest(request);
    let response: unknown;
    try {
      response = await requestJson<unknown>(reportExportBasePath, {
        method: 'POST',
        body: JSON.stringify(normalized),
      });
    } catch (error: unknown) {
      throw safeReportExportError(error);
    }
    try {
      assertAttendanceReportExportView(response);
    } catch (error: unknown) {
      void error;
      throw invalidReportExportResponse();
    }
    if (
      response.reportType !== normalized.reportType
      || response.period !== normalized.filters.period
      || response.companyId !== normalized.filters.companyId
      || response.purpose !== normalized.purpose
    ) {
      throw invalidReportExportResponse();
    }
    return response;
  },
  loadReportExport: async (exportId) => {
    const normalizedExportId = normalizeExportId(exportId);
    let response: unknown;
    try {
      response = await requestJson<unknown>(
        `${reportExportBasePath}/${encodeURIComponent(normalizedExportId)}`,
      );
    } catch (error: unknown) {
      throw safeReportExportError(error);
    }
    try {
      assertAttendanceReportExportView(response);
    } catch (error: unknown) {
      void error;
      throw invalidReportExportResponse();
    }
    if (response.exportId !== normalizedExportId) {
      throw invalidReportExportResponse();
    }
    return response;
  },
  downloadReportExport: async (exportId) => {
    const normalizedExportId = normalizeExportId(exportId);
    let file: DownloadedFile;
    try {
      file = await requestFile(
        `${reportExportBasePath}/${encodeURIComponent(normalizedExportId)}/download`,
        {
          method: 'POST',
          body: JSON.stringify({}),
        },
      );
    } catch (error: unknown) {
      throw safeReportExportError(error);
    }
    if (
      file.blob.size < 1
      || file.blob.type.toLowerCase() !== xlsxMediaType
      || !isSafeXlsxFileName(file.fileName)
      || !await hasXlsxZipSignature(file.blob)
    ) {
      throw new ApiRequestError(502, {
        code: 'INVALID_REPORT_EXPORT_FILE',
        message: '导出文件暂时无法使用，请重新导出。',
        retryable: true,
      });
    }
    return file;
  },
};

interface NormalizedReportQuery {
  reportType: AttendanceReportType;
  period: string;
  companyId?: string;
  organizationId?: string;
  employeeId?: string;
  status?: ReportExceptionState;
  expectedProjectionVersion?: string;
  page: number;
  size: number;
}

interface NormalizedAttendanceMonthMatrixQuery {
  period: string;
  companyId?: string;
  organizationId?: string;
  employeeId?: string;
  expectedProjectionVersion?: string;
  page: number;
  size: number;
}

function normalizeAttendanceMonthMatrixQuery(
  query: AttendanceMonthMatrixQuery,
): NormalizedAttendanceMonthMatrixQuery {
  if (
    query === undefined
    || !hasOnlyKeys(
      query,
      [
        'period',
        'companyId',
        'organizationId',
        'employeeId',
        'expectedProjectionVersion',
        'page',
        'size',
      ],
    )
    || !isYearMonth(query.period)
  ) {
    throw invalidReportQuery();
  }
  const page = query.page ?? 0;
  const size = query.size ?? 50;
  if (
    !Number.isInteger(page)
    || page < 0
    || !Number.isInteger(size)
    || size < 1
    || size > 200
  ) {
    throw invalidReportQuery();
  }
  return {
    period: query.period,
    companyId: normalizeOptionalQueryFilter(query.companyId, 36),
    organizationId: normalizeOptionalQueryFilter(
      query.organizationId,
      36,
    ),
    employeeId: normalizeOptionalQueryFilter(query.employeeId, 36),
    expectedProjectionVersion: normalizeOptionalQueryFilter(
      query.expectedProjectionVersion,
      128,
    ),
    page,
    size,
  };
}

function normalizeReportQuery(query?: ReportQuery): NormalizedReportQuery {
  if (
    query === undefined
    || !hasOnlyKeys(
      query,
      [
        'reportType',
        'period',
        'companyId',
        'organizationId',
        'employeeId',
        'status',
        'expectedProjectionVersion',
        'page',
        'size',
      ],
    )
    || !attendanceReportTypes.includes(query.reportType)
    || !isYearMonth(query.period)
  ) {
    throw invalidReportQuery();
  }
  const page = query.page ?? 0;
  const size = query.size ?? 50;
  if (
    !Number.isInteger(page)
    || page < 0
    || !Number.isInteger(size)
    || size < 1
    || size > 200
  ) {
    throw invalidReportQuery();
  }
  let normalizedStatus: ReportExceptionState | undefined;
  if (query.status !== undefined) {
    const candidate = query.status.trim();
    if (
      query.reportType !== 'EXCEPTIONS'
      || !isReportExceptionState(candidate)
    ) {
      throw invalidReportQuery();
    }
    normalizedStatus = candidate;
  }
  const companyId = normalizeOptionalQueryFilter(
    query.companyId,
    36,
  );
  return {
    reportType: query.reportType,
    period: query.period,
    companyId,
    organizationId: normalizeOptionalQueryFilter(query.organizationId, 36),
    employeeId: normalizeOptionalQueryFilter(query.employeeId, 36),
    status: normalizedStatus || undefined,
    expectedProjectionVersion: normalizeOptionalQueryFilter(
      query.expectedProjectionVersion,
      128,
    ),
    page,
    size,
  };
}

function invalidReportQuery(): ApiRequestError {
  return new ApiRequestError(400, {
    code: 'INVALID_REPORT_QUERY',
    message: '报表条件无效。',
    retryable: false,
  });
}

function invalidReportResponse(): ApiRequestError {
  return new ApiRequestError(502, {
    code: 'INVALID_RESPONSE_BODY',
    message: '报表数据暂时无法显示，请刷新后重试。',
    retryable: true,
  });
}

function normalizeDashboardCompanyId(
  value: string | undefined,
): string | undefined {
  if (value === undefined) return undefined;
  if (
    typeof value !== 'string'
    || value.length > 36
    || value.trim() === ''
    || value !== value.trim()
    || hasC0OrC1ControlCharacter(value)
  ) {
    throw new ApiRequestError(400, {
      code: 'INVALID_DASHBOARD_QUERY',
      message: '控制台公司条件无效。',
      retryable: false,
    });
  }
  return value;
}

function invalidDashboardResponse(): ApiRequestError {
  return new ApiRequestError(502, {
    code: 'INVALID_DASHBOARD_RESPONSE',
    message: '考勤工作台数据暂时无法显示，请刷新后重试。',
    retryable: true,
  });
}

function invalidSelfAttendanceDashboardResponse(): ApiRequestError {
  return new ApiRequestError(502, {
    code: 'INVALID_SELF_ATTENDANCE_DASHBOARD_RESPONSE',
    message: '个人考勤数据暂时无法显示，请刷新后重试。',
    retryable: true,
  });
}

function normalizeReportExportCreateRequest(
  request: ReportExportCreateRequest,
): ReportExportCreateRequest {
  if (
    request === undefined
    || !hasOnlyKeys(
      request,
      [
        'reportType',
        'projectionVersion',
        'queryFingerprint',
        'scopeReference',
        'filters',
        'selectedFields',
        'purpose',
      ],
    )
    || !attendanceReportTypes.includes(request.reportType)
    || !isSafeRequiredText(request.projectionVersion, 128)
    || typeof request.queryFingerprint !== 'string'
    || !/^[a-f0-9]{64}$/.test(request.queryFingerprint)
    || !isSafeRequiredText(request.scopeReference, 128)
    || !hasOnlyKeys(
      request.filters,
      [
        'period',
        'scopeReference',
        'companyId',
        'organizationId',
        'employeeId',
        'status',
      ],
    )
    || !isYearMonth(request.filters.period)
    || !isSafeRequiredText(request.filters.scopeReference, 128)
    || request.filters.scopeReference !== request.scopeReference
    || !Array.isArray(request.selectedFields)
    || request.selectedFields.length === 0
    || request.selectedFields.length > reportColumnKeys.length
    || new Set(request.selectedFields).size !== request.selectedFields.length
    || !request.selectedFields.every((field) => (
      typeof field === 'string'
      && reportColumnKeys.includes(
        field as typeof reportColumnKeys[number],
      )
    ))
  ) {
    throw invalidReportExportRequest();
  }
  const normalizedStatus = normalizeOptionalFilter(
    request.filters.status,
    32,
  );
  const companyId = normalizeOptionalFilter(
    request.filters.companyId,
    36,
  );
  if (
    typeof companyId !== 'string'
    || companyId !== companyId.trim()
  ) {
    throw invalidReportExportRequest();
  }
  const organizationId = normalizeOptionalFilter(
    request.filters.organizationId,
    36,
  );
  const employeeId = normalizeOptionalFilter(
    request.filters.employeeId,
    36,
  );
  if (
    (typeof organizationId === 'string'
      && organizationId !== organizationId.trim())
    || (typeof employeeId === 'string'
      && employeeId !== employeeId.trim())
  ) {
    throw invalidReportExportRequest();
  }
  if (
    normalizedStatus !== null
    && normalizedStatus !== undefined
    && (
      request.reportType !== 'EXCEPTIONS'
      || !isReportExceptionState(normalizedStatus)
    )
  ) {
    throw invalidReportExportRequest();
  }
  const status = normalizedStatus as
    | ReportExceptionState
    | null
    | undefined;
  let purpose: string;
  try {
    purpose = normalizeReportExportPurpose(request.purpose);
  } catch (error: unknown) {
    void error;
    throw invalidReportExportRequest();
  }
  return {
    reportType: request.reportType,
    projectionVersion: request.projectionVersion,
    queryFingerprint: request.queryFingerprint,
    scopeReference: request.scopeReference,
    filters: {
      period: request.filters.period,
      scopeReference: request.filters.scopeReference,
      companyId,
      organizationId,
      employeeId,
      status,
    },
    selectedFields: [...request.selectedFields],
    purpose,
  };
}

function isSafeRequiredText(
  value: unknown,
  maximumLength: number,
): value is string {
  return typeof value === 'string'
    && value.length <= maximumLength
    && value.trim() !== ''
    && value === value.trim()
    && !hasC0OrC1ControlCharacter(value);
}

function normalizeOptionalQueryFilter(
  value: string | undefined,
  maximumLength: number,
): string | undefined {
  if (value === undefined) return undefined;
  if (
    typeof value !== 'string'
    || value.length > maximumLength
    || value.trim() === ''
    || value !== value.trim()
    || hasC0OrC1ControlCharacter(value)
  ) {
    throw invalidReportQuery();
  }
  return value;
}

function hasOnlyKeys(
  value: unknown,
  allowedKeys: readonly string[],
): value is Record<string, unknown> {
  if (
    typeof value !== 'object'
    || value === null
    || Array.isArray(value)
  ) {
    return false;
  }
  const allowed = new Set(allowedKeys);
  return Object.keys(value).every((key) => allowed.has(key));
}

function isYearMonth(value: unknown): value is string {
  return typeof value === 'string'
    && /^\d{4}-(0[1-9]|1[0-2])$/.test(value);
}

function normalizeOptionalFilter(
  value: string | null | undefined,
  maximumLength: number,
): string | null | undefined {
  if (value === null || value === undefined) return value;
  if (
    typeof value !== 'string'
    || value.length > maximumLength
    || value.trim() === ''
    || hasC0OrC1ControlCharacter(value)
  ) {
    throw invalidReportExportRequest();
  }
  return value;
}

export function isReportExceptionState(
  value: string,
): value is ReportExceptionState {
  return value === 'OPEN'
    || value === 'PENDING_EVIDENCE'
    || value === 'PENDING_REVIEW'
    || value === 'RESOLVED';
}

function normalizeExportId(value: string): string {
  if (
    typeof value !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      value,
    )
  ) {
    throw new ApiRequestError(400, {
      code: 'INVALID_REPORT_EXPORT_REFERENCE',
      message: '报表导出引用无效。',
      retryable: false,
    });
  }
  return value;
}

function isSafeXlsxFileName(value: string): boolean {
  return value.length > 5
    && value.length <= 255
    && !/[/\\]/u.test(value)
    && !Array.from(value).some((character) => {
      const codePoint = character.codePointAt(0);
      return codePoint !== undefined
        && (codePoint <= 0x1f || codePoint === 0x7f);
    })
    && value.toLowerCase().endsWith('.xlsx');
}

async function hasXlsxZipSignature(blob: Blob): Promise<boolean> {
  if (blob.size < 4) return false;
  try {
    const signature = new Uint8Array(
      await blob.slice(0, 4).arrayBuffer(),
    );
    return signature[0] === 0x50
      && signature[1] === 0x4b
      && signature[2] === 0x03
      && signature[3] === 0x04;
  } catch (error: unknown) {
    void error;
    return false;
  }
}

function hasC0OrC1ControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined
      && (
        codePoint <= 0x1f
        || (codePoint >= 0x7f && codePoint <= 0x9f)
      );
  });
}

function invalidReportExportRequest(): ApiRequestError {
  return new ApiRequestError(400, {
    code: 'INVALID_REPORT_EXPORT_REQUEST',
    message: '报表导出条件无效。',
    retryable: false,
  });
}

function invalidReportExportResponse(): ApiRequestError {
  return new ApiRequestError(502, {
    code: 'INVALID_REPORT_EXPORT_RESPONSE',
    message: '导出状态暂时无法读取，请刷新后重试。',
    retryable: true,
  });
}

function safeReportExportError(error: unknown): ApiRequestError {
  if (!(error instanceof ApiRequestError)) {
    return new ApiRequestError(0, {
      code: 'REPORT_EXPORT_NETWORK_FAILED',
      message: '报表导出服务暂时不可用。',
      retryable: true,
    });
  }
  let message = '报表导出服务暂时不可用。';
  if (error.status === 401) {
    message = '会话已失效，请重新登录。';
  } else if (error.status === 403 || error.status === 404) {
    message = '当前账号无权访问该导出，或导出不存在。';
  } else if (error.status === 409 || error.status === 410) {
    message = '导出当前不可用，请刷新状态或重新创建。';
  } else if (error.status === 400 || error.status === 422) {
    message = '报表导出条件无效。';
  }
  return new ApiRequestError(error.status, {
    code: error.code,
    correlationId: error.correlationId,
    retryable: error.retryable,
    message,
  });
}

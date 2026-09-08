import { translate } from '../i18n/messages';

export interface ApiErrorBody {
  code: string;
  correlationId: string;
  retryable: boolean;
  message?: string;
  fieldErrors?: Array<{ field: string; code: string; message: string }>;
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly retryable: boolean;
  readonly fieldErrors: ApiErrorBody['fieldErrors'];

  constructor(status: number, body?: Partial<ApiErrorBody>) {
    super(body?.message ?? safeMessage(status));
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = body?.code ?? 'REQUEST_FAILED';
    this.correlationId = body?.correlationId;
    this.retryable = body?.retryable ?? (status === 0 || status >= 500);
    this.fieldErrors = body?.fieldErrors;
  }
}

let csrfToken: string | undefined;

export interface ApiResponseMetadata {
  idempotencyReplayed?: boolean;
}

const responseMetadata = new WeakMap<object, ApiResponseMetadata>();

export function apiResponseMetadata(value: unknown): ApiResponseMetadata {
  if ((typeof value !== 'object' && typeof value !== 'function') || value === null) {
    return {};
  }
  return responseMetadata.get(value) ?? {};
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await request(path, init, 'application/json');
  if (response.status === 204) {
    return undefined as T;
  }
  try {
    const value = await response.json() as T;
    const replayedHeader = response.headers.get('Idempotency-Replayed');
    if (
      (typeof value === 'object' || typeof value === 'function')
      && value !== null
      && (replayedHeader === 'true' || replayedHeader === 'false')
    ) {
      responseMetadata.set(value, {
        idempotencyReplayed: replayedHeader === 'true',
      });
    }
    return value;
  } catch (error: unknown) {
    void error;
    throw new ApiRequestError(response.status, {
      code: 'INVALID_RESPONSE_BODY',
      retryable: true,
    });
  }
}

export interface DownloadedFile {
  blob: Blob;
  fileName: string;
}

export async function requestFile(path: string, init: RequestInit = {}): Promise<DownloadedFile> {
  const response = await request(
    path,
    init,
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  );
  return {
    blob: await response.blob(),
    fileName: responseFileName(response.headers.get('Content-Disposition')),
  };
}

/** Keep the blob URL until the browser has started the download. */
export const BROWSER_DOWNLOAD_REVOKE_MS = 1500;

export function triggerBrowserDownload(blob: Blob, fileName: string): void {
  if (
    typeof document === 'undefined'
    || typeof URL === 'undefined'
    || typeof URL.createObjectURL !== 'function'
  ) {
    return;
  }
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName;
  link.rel = 'noopener';
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  window.setTimeout(() => {
    link.remove();
    URL.revokeObjectURL(objectUrl);
  }, BROWSER_DOWNLOAD_REVOKE_MS);
}

export function saveDownloadedFile(file: DownloadedFile): void {
  triggerBrowserDownload(file.blob, file.fileName);
}

export function createIdempotencyKey(scope: string): string {
  const cryptoRef = globalThis.crypto;
  const unique = cryptoRef && typeof cryptoRef.randomUUID === 'function'
    ? cryptoRef.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${scope}:${unique}`;
}

export function versionHeaders(rowVersion: number, idempotencyKey: string): HeadersInit {
  return {
    'Idempotency-Key': idempotencyKey,
    'If-Match': `"${rowVersion}"`,
  };
}

export function ifMatchHeaders(rowVersion: number): HeadersInit {
  return {
    'If-Match': `"${rowVersion}"`,
  };
}

export function changeReasonHeaders(
  reason: string,
  headers: HeadersInit = {},
): HeadersInit {
  const result = new Headers(headers);
  result.set('X-Change-Reason', `UTF-8''${encodeURIComponent(reason)}`);
  return result;
}

async function request(path: string, init: RequestInit, accept: string): Promise<Response> {
  const targetUrl = resolveApiTarget(path);
  const headers = new Headers(init.headers);
  headers.set('Accept', accept);
  const method = (init.method ?? 'GET').toUpperCase();
  if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) {
    headers.set('X-CSRF-TOKEN', csrfToken);
  }
  const developmentPrincipalId = import.meta.env.VITE_DEVELOPMENT_PRINCIPAL_ID;
  if (import.meta.env.DEV && developmentPrincipalId) {
    headers.set('X-Development-Principal', developmentPrincipalId);
  }

  let response: Response;
  try {
    response = await fetch(targetUrl, {
      ...init,
      credentials: 'same-origin',
      headers,
    });
  } catch (error: unknown) {
    if (error instanceof ApiRequestError) {
      throw error;
    }
    if (isAbortLike(error)) {
      throw new ApiRequestError(408, {
        code: 'REQUEST_TIMEOUT',
        retryable: true,
        message: translate('error.requestTimeout'),
      });
    }
    throw new ApiRequestError(0, {
      code: 'NETWORK_REQUEST_FAILED',
      retryable: true,
    });
  }

  if (!response.ok) {
    const errorBody = await readErrorBody(response);
    throw new ApiRequestError(response.status, errorBody);
  }
  const receivedCsrfToken = response.headers.get('X-CSRF-TOKEN');
  if (receivedCsrfToken) {
    csrfToken = receivedCsrfToken;
  }
  return response;
}

function resolveApiTarget(path: string): string {
  if (!path.startsWith('/api/v1/') || path.includes('\\')) {
    throw new ApiRequestError(0, {
      code: 'INVALID_API_TARGET',
      retryable: false,
    });
  }
  const targetUrl = new URL(path, window.location.origin);
  const allowedOrigins = new Set([window.location.origin]);
  const allowedHosts = new Set([window.location.host]);
  if (
    !allowedOrigins.has(targetUrl.origin)
    || !allowedHosts.has(targetUrl.host)
    || !targetUrl.pathname.startsWith('/api/v1/')
    || targetUrl.username
    || targetUrl.password
  ) {
    throw new ApiRequestError(0, {
      code: 'INVALID_API_TARGET',
      retryable: false,
    });
  }
  return `${targetUrl.pathname}${targetUrl.search}`;
}

async function readErrorBody(response: Response): Promise<Partial<ApiErrorBody> | undefined> {
  try {
    const value: unknown = await response.json();
    if (isErrorBody(value)) {
      return sanitizeServerErrorBody(value);
    }
  } catch (caught: unknown) {
    void caught;
    return undefined;
  }
  return undefined;
}

function safeMessage(status: number): string {
  if (status === 401) return translate('organization.sessionInvalid');
  if (status === 403 || status === 404) return translate('organization.notAvailable');
  if (status === 400) return translate('error.invalidRequest');
  if (status === 408) return translate('error.requestTimeout');
  if (status === 409 || status === 412) return translate('error.dataChanged');
  if (status === 422) return translate('error.validationFailed');
  if (status === 429) return translate('error.tooManyRequests');
  return translate('error.serviceUnavailable');
}

function isAbortLike(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const name = 'name' in error && typeof error.name === 'string'
    ? error.name
    : '';
  return name === 'AbortError' || name === 'TimeoutError';
}

function isErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return typeof candidate.code === 'string'
    && (candidate.correlationId === undefined || typeof candidate.correlationId === 'string')
    && (candidate.retryable === undefined || typeof candidate.retryable === 'boolean');
}

function sanitizeServerErrorBody(body: ApiErrorBody): Partial<ApiErrorBody> {
  const correlationId = safeCorrelationId(body.correlationId);
  const fieldErrors = safeFieldErrors(body.fieldErrors);

  // Server messages can contain implementation details. Keep only metadata
  // needed for client-side branching and support lookup; the constructor will
  // select a status-based, user-facing message. Known attendance-report codes
  // keep their Chinese operator text so a 409 is not shown as a generic 500.
  const code = safeErrorCode(body.code);
  const message = passThroughOperatorMessage(code, body.message);
  return {
    code,
    ...(message ? { message } : {}),
    ...(correlationId ? { correlationId } : {}),
    ...(typeof body.retryable === 'boolean'
      ? { retryable: body.retryable }
      : {}),
    ...(fieldErrors ? { fieldErrors } : {}),
  };
}

function passThroughOperatorMessage(
  code: string,
  value: string | undefined,
): string | undefined {
  if (
    !code.startsWith('ATTENDANCE_REPORT_')
    && !code.startsWith('PAPER_OVERTIME_')
    && !code.startsWith('EMPLOYEE_')
  ) {
    return undefined;
  }
  if (typeof value !== 'string') return undefined;
  const message = value.trim();
  if (
    message.length === 0
    || message.length > 160
    || hasControlCharacter(message)
    || !/^[\u4e00-\u9fa5]/.test(message)
  ) {
    return undefined;
  }
  return message;
}

function safeErrorCode(value: string): string {
  return /^[A-Z][A-Z0-9_]{0,127}$/u.test(value)
    ? value
    : 'REQUEST_FAILED';
}

function safeCorrelationId(value: string | undefined): string | undefined {
  if (
    value === undefined
    || !/^[A-Za-z0-9._:-]{1,128}$/u.test(value)
  ) {
    return undefined;
  }
  return value;
}

function safeFieldErrors(
  value: ApiErrorBody['fieldErrors'],
): ApiErrorBody['fieldErrors'] {
  if (!Array.isArray(value)) return undefined;
  const errors: NonNullable<ApiErrorBody['fieldErrors']> = [];
  let genericIssueAdded = false;

  for (const issue of value.slice(0, 20)) {
    if (typeof issue !== 'object' || issue === null) continue;
    const candidate = issue as Record<string, unknown>;
    const message = safeValidationMessage(candidate.message);
    const isGeneric = message === genericValidationMessage;
    if (isGeneric && genericIssueAdded) continue;

    errors.push({
      field: safeFieldName(candidate.field),
      code: safeFieldErrorCode(candidate.code),
      message,
    });
    genericIssueAdded ||= isGeneric;
  }

  return errors.length > 0 ? errors : undefined;
}

const genericValidationMessage = '请检查相关填写内容。';

function safeValidationMessage(value: unknown): string {
  if (typeof value !== 'string') return genericValidationMessage;
  const message = value.trim();
  if (
    message.length === 0
    || message.length > 160
    || hasUnsafeValidationDetail(message)
  ) {
    return genericValidationMessage;
  }
  return message;
}

function hasUnsafeValidationDetail(message: string): boolean {
  if (hasControlCharacter(message)) return true;
  const technicalPatterns = [
    /\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b/iu,
    /\b\d{16,}\b/u,
    /\b[a-z][a-z0-9]*_[a-z0-9_]+\b/iu,
    /\b(?:rowVersion|queryFingerprint|projectionVersion|scopeResourceId|versionId)\b/iu,
    /\b(?:id|uuid|sql|exception|constraint|credential|secret|token)\b/iu,
    /(?:服务端|后端|数据库|投影|接口响应|响应体|技术字段|堆栈|外键|约束名)/u,
    /(?:https?:\/\/|[\\/](?:api|src|main|java|node_modules)[\\/])/iu,
    /[{}[\]<>]/u,
    /(?:[A-Za-z]{3,}\s+){2,}[A-Za-z]{3,}/u,
  ];
  return technicalPatterns.some((pattern) => pattern.test(message));
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

function safeFieldName(value: unknown): string {
  return typeof value === 'string'
    && /^[A-Za-z][A-Za-z0-9.]{0,127}$/u.test(value)
    ? value
    : 'input';
}

function safeFieldErrorCode(value: unknown): string {
  return typeof value === 'string'
    && /^[A-Z][A-Z0-9_]{0,127}$/u.test(value)
    ? value
    : 'INVALID_INPUT';
}

function responseFileName(contentDisposition: string | null): string {
  if (!contentDisposition) {
    return 'download.xlsx';
  }
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return 'download.xlsx';
    }
  }
  return /filename="?([^";]+)"?/i.exec(contentDisposition)?.[1] ?? 'download.xlsx';
}

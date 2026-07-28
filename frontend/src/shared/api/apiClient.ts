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

export function saveDownloadedFile(file: DownloadedFile): void {
  const url = URL.createObjectURL(file.blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = file.fileName;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function createIdempotencyKey(scope: string): string {
  const unique = globalThis.crypto.randomUUID();
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
      return value;
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
  return translate('error.serviceUnavailable');
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

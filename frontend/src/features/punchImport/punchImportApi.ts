import {
  ApiRequestError,
  changeReasonHeaders,
  createIdempotencyKey,
  requestFile,
  requestJson,
  saveDownloadedFile,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  demoPunchImports,
  demoPunchIssues,
  demoPunchRows,
} from './punchImportDemo';
import type {
  PunchImportBatchView,
  PunchImportIssuePage,
  PunchImportPage,
  PunchImportRowPage,
} from './punchImportTypes';

const demoMode = import.meta.env.MODE === 'demo' && isDemoMode();

export function listPunchImports(page = 0, size = 20): Promise<PunchImportPage> {
  if (demoMode) return Promise.resolve(demoPage(demoPunchImports, page, size));
  return requestJson(`/api/v1/attendance-punch-imports?page=${page}&size=${size}`);
}

export function getPunchImport(batchId: string): Promise<PunchImportBatchView> {
  if (demoMode) {
    const batch = demoPunchImports.find((item) => item.batchId === batchId);
    if (batch) return Promise.resolve(batch);
    return Promise.reject(new ApiRequestError(404, {
      code: 'PUNCH_IMPORT_NOT_FOUND',
      retryable: false,
    }));
  }
  return requestJson(`/api/v1/attendance-punch-imports/${encodeURIComponent(batchId)}`);
}

export function listPunchImportIssues(
  batchId: string,
  page = 0,
  size = 20,
): Promise<PunchImportIssuePage> {
  if (demoMode) return Promise.resolve(demoPage(demoPunchIssues, page, size));
  return requestJson(
    `/api/v1/attendance-punch-imports/${encodeURIComponent(batchId)}/errors?page=${page}&size=${size}`,
  );
}

export function listPunchImportRows(
  batchId: string,
  page = 0,
  size = 20,
): Promise<PunchImportRowPage> {
  if (demoMode) return Promise.resolve(demoPage(demoPunchRows, page, size));
  return requestJson(
    `/api/v1/attendance-punch-imports/${encodeURIComponent(batchId)}/rows?page=${page}&size=${size}`,
  );
}

export async function downloadPunchTemplate(): Promise<void> {
  if (demoMode) return;
  saveDownloadedFile(await requestFile('/api/v1/attendance-punch-imports/template'));
}

export function uploadPunchImport(
  file: File,
  legalEntityId: string,
  sourceId: string,
  reason: string,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    return Promise.resolve({
      ...demoPunchImports[0]!,
      batchId: 'synthetic-punch-batch-uploaded',
      originalFilename: file.name,
      state: 'DRAFT',
      totalRows: 0,
      validRows: 0,
      invalidRows: 0,
      exactDuplicateRows: 0,
      nearDuplicateRows: 0,
      affectedEmployees: 0,
      affectedDateFrom: null,
      affectedDateTo: null,
      precheckTokenPresent: false,
      precheckToken: null,
      rowVersion: 1,
    });
  }
  const form = new FormData();
  form.append('file', file);
  form.append(
    'metadata',
    new Blob([JSON.stringify({ legalEntityId, sourceId })], {
      type: 'application/json',
    }),
  );
  return requestJson('/api/v1/attendance-punch-imports', {
    method: 'POST',
    headers: changeReasonHeaders(reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-punch-upload'),
    }),
    body: form,
  });
}

export function precheckPunchImport(
  batch: PunchImportBatchView,
  reason: string,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    return Promise.resolve({
      ...batch,
      state: 'AWAITING_CONFIRMATION',
      precheckTokenPresent: true,
      precheckToken: 'synthetic-precheck-token-not-for-production',
      rowVersion: batch.rowVersion + 1,
    });
  }
  return mutate(batch, 'precheck', reason, 'VALIDATING');
}

export function publishPunchImport(
  batch: PunchImportBatchView,
  mode: 'STRICT' | 'VALID_ROWS_ONLY',
  reason: string,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    return Promise.resolve({
      ...batch,
      state: mode === 'STRICT' ? 'PUBLISHED' : 'PARTIALLY_PUBLISHED',
      rowVersion: batch.rowVersion + 1,
    });
  }
  if (!batch.precheckToken) {
    return Promise.reject(new ApiRequestError(409, {
      code: 'PRECHECK_TOKEN_REQUIRED',
      retryable: false,
    }));
  }
  return requestJson(
    `/api/v1/attendance-punch-imports/${encodeURIComponent(batch.batchId)}/publish`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          batch.rowVersion,
          createIdempotencyKey('attendance-punch-publish'),
        ),
      ),
      body: JSON.stringify({
        precheckToken: batch.precheckToken,
        mode,
        confirmation: 'CONFIRM_PUBLISH',
      }),
    },
  );
}

export function voidOrReversePunchImport(
  batch: PunchImportBatchView,
  reason: string,
): Promise<PunchImportBatchView> {
  return mutate(batch, 'void-or-reverse', reason, 'VOIDED', {
    confirmation: 'CONFIRM_VOID_OR_REVERSE',
  });
}

async function mutate(
  batch: PunchImportBatchView,
  action: string,
  reason: string,
  demoState: PunchImportBatchView['state'],
  body?: object,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    return {
      ...batch,
      state: demoState,
      rowVersion: batch.rowVersion + 1,
    };
  }
  return requestJson(
    `/api/v1/attendance-punch-imports/${encodeURIComponent(batch.batchId)}/${action}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          batch.rowVersion,
          createIdempotencyKey(`attendance-punch-${action}`),
        ),
      ),
      body: body ? JSON.stringify(body) : undefined,
    },
  );
}

function demoPage<T>(items: T[], page: number, size: number) {
  const start = page * size;
  return {
    items: items.slice(start, start + size),
    page,
    size,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : Math.ceil(items.length / size),
  };
}

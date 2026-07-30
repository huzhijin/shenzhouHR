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
let demoPunchImportStore = copyDemoPunchImports();
let demoUploadSequence = 0;

export function resetPunchImportDemoState(): void {
  demoPunchImportStore = copyDemoPunchImports();
  demoUploadSequence = 0;
}

export function listPunchImports(page = 0, size = 20): Promise<PunchImportPage> {
  if (demoMode) {
    return Promise.resolve(
      demoPage(demoPunchImportStore.map(copyPunchImport), page, size),
    );
  }
  return requestJson(`/api/v1/attendance-punch-imports?page=${page}&size=${size}`);
}

export function getPunchImport(batchId: string): Promise<PunchImportBatchView> {
  if (demoMode) {
    const batch = demoPunchImportStore.find((item) => item.batchId === batchId);
    if (batch) return Promise.resolve(copyPunchImport(batch));
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
  if (demoMode) {
    const fileName = '神州HR_考勤打卡导入模板_V1.0.xlsx';
    const response = await fetch(new URL(fileName, document.baseURI));
    if (!response.ok) {
      throw new ApiRequestError(response.status, {
        code: 'DEMO_PUNCH_TEMPLATE_UNAVAILABLE',
        retryable: true,
      });
    }
    saveDownloadedFile({
      blob: await response.blob(),
      fileName,
    });
    return;
  }
  saveDownloadedFile(await requestFile('/api/v1/attendance-punch-imports/template'));
}

export function uploadPunchImport(
  file: File,
  companyId: string,
  sourceId: string,
  reason: string,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    demoUploadSequence += 1;
    const uploaded: PunchImportBatchView = {
      ...demoPunchImports[0]!,
      batchId: `ATT-XLS-UPLOAD-${String(demoUploadSequence).padStart(3, '0')}`,
      companyId,
      sourceId,
      originalFilename: file.name,
      fileSha256: 'c'.repeat(64),
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
      createdAt: '2026-07-28T05:30:00Z',
      rowVersion: 1,
    };
    demoPunchImportStore = [uploaded, ...demoPunchImportStore];
    return Promise.resolve(copyPunchImport(uploaded));
  }
  const form = new FormData();
  form.append('file', file);
  form.append(
    'metadata',
    new Blob([JSON.stringify({ companyId, sourceId })], {
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
    return Promise.resolve(updateDemoPunchImport(batch.batchId, (current) => ({
      ...current,
      state: 'AWAITING_CONFIRMATION',
      totalRows: demoPunchImports[0]!.totalRows,
      validRows: demoPunchImports[0]!.validRows,
      invalidRows: demoPunchImports[0]!.invalidRows,
      exactDuplicateRows: demoPunchImports[0]!.exactDuplicateRows,
      nearDuplicateRows: demoPunchImports[0]!.nearDuplicateRows,
      affectedEmployees: demoPunchImports[0]!.affectedEmployees,
      affectedDateFrom: demoPunchImports[0]!.affectedDateFrom,
      affectedDateTo: demoPunchImports[0]!.affectedDateTo,
      precheckTokenPresent: true,
      precheckToken: 'demo-precheck-token',
      rowVersion: current.rowVersion + 1,
    })));
  }
  return mutate(batch, 'precheck', reason, 'VALIDATING');
}

export function publishPunchImport(
  batch: PunchImportBatchView,
  mode: 'STRICT' | 'VALID_ROWS_ONLY',
  reason: string,
): Promise<PunchImportBatchView> {
  if (demoMode) {
    if (mode === 'STRICT' && batch.invalidRows > 0) {
      return Promise.reject(new ApiRequestError(409, {
        code: 'STRICT_PUBLISH_BLOCKED_BY_INVALID_ROWS',
        message: `当前批次仍有 ${batch.invalidRows} 条阻断行，请先修正或仅发布有效行。`,
        retryable: false,
      }));
    }
    return Promise.resolve(updateDemoPunchImport(batch.batchId, (current) => ({
      ...current,
      state: mode === 'STRICT' ? 'PUBLISHED' : 'PARTIALLY_PUBLISHED',
      precheckTokenPresent: false,
      precheckToken: null,
      rowVersion: current.rowVersion + 1,
    })));
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
    return updateDemoPunchImport(batch.batchId, (current) => ({
      ...current,
      state: demoState,
      precheckTokenPresent: false,
      precheckToken: null,
      rowVersion: current.rowVersion + 1,
    }));
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

function updateDemoPunchImport(
  batchId: string,
  update: (current: PunchImportBatchView) => PunchImportBatchView,
): PunchImportBatchView {
  const index = demoPunchImportStore.findIndex((item) => item.batchId === batchId);
  if (index < 0) {
    throw new ApiRequestError(404, {
      code: 'PUNCH_IMPORT_NOT_FOUND',
      retryable: false,
    });
  }
  const next = update(demoPunchImportStore[index]!);
  demoPunchImportStore = demoPunchImportStore.map((item, itemIndex) => (
    itemIndex === index ? next : item
  ));
  return copyPunchImport(next);
}

function copyDemoPunchImports(): PunchImportBatchView[] {
  return demoPunchImports.map(copyPunchImport);
}

function copyPunchImport(batch: PunchImportBatchView): PunchImportBatchView {
  return { ...batch };
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

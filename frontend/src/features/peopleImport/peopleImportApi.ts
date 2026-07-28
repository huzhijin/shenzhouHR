import {
  ApiRequestError,
  requestFile,
  requestJson,
  type DownloadedFile,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { translate } from '../../shared/i18n/messages';
import { peopleImportFilePolicy } from '../../shared/files/peopleImportFilePolicy';
import {
  createDemoBatch,
  getDemoBatch,
  getDemoBatches,
  getDemoDiff,
  getDemoIssues,
  getDemoTemplates,
  publishDemoBatch,
  replaceDemoMapping,
  rollbackDemoBatch,
  startDemoPrecheck,
  uploadDemoFile,
  voidDemoBatch,
} from './demoPeopleImport';
import type {
  PeopleImportBatchDetail,
  PeopleImportBatchPage,
  PeopleImportCreateRequest,
  PeopleImportDiffCategory,
  PeopleImportDiffPage,
  PeopleImportIssuePage,
  PeopleImportMappingRequest,
  PeopleImportFileView,
  PeopleImportPublicationView,
  PeopleImportRollbackView,
  PeopleImportTemplateType,
  PeopleImportTemplateVersionPage,
} from './peopleImportTypes';
import { peopleImportFileValidator } from './peopleImportFileValidation';

const basePath = '/api/v1/people-imports';

export function listPeopleImportTemplates(
  type?: PeopleImportTemplateType,
): Promise<PeopleImportTemplateVersionPage> {
  if (isDemoMode()) return Promise.resolve(getDemoTemplates(type));
  const parameters = new URLSearchParams({ page: '0', size: '20' });
  if (type) parameters.set('type', type);
  return requestJson<PeopleImportTemplateVersionPage>(`${basePath}/templates?${parameters}`);
}

export function downloadPeopleImportTemplate(
  type: PeopleImportTemplateType,
  version: string,
): Promise<DownloadedFile> {
  if (isDemoMode()) {
    return Promise.resolve(syntheticDownload(`synthetic-${type.toLowerCase()}-${version}.xlsx`));
  }
  return requestFile(
    `${basePath}/templates/${encodeURIComponent(type)}/versions/${encodeURIComponent(version)}`,
  );
}

export function listPeopleImportBatches(): Promise<PeopleImportBatchPage> {
  if (isDemoMode()) return Promise.resolve(getDemoBatches());
  return requestJson<PeopleImportBatchPage>(`${basePath}?page=0&size=20&sort=updatedAt`);
}

export function createPeopleImportBatch(
  request: PeopleImportCreateRequest,
  idempotencyKey: string,
): Promise<PeopleImportBatchDetail> {
  if (isDemoMode()) return Promise.resolve(createDemoBatch(request));
  return requestJson<PeopleImportBatchDetail>(basePath, {
    method: 'POST',
    headers: versionHeaders(0, idempotencyKey),
    body: JSON.stringify(request),
  });
}

export function getPeopleImportBatch(batchId: string): Promise<PeopleImportBatchDetail> {
  if (isDemoMode()) return Promise.resolve(getDemoBatch(batchId));
  return requestJson<PeopleImportBatchDetail>(`${basePath}/${encodeURIComponent(batchId)}`);
}

export function uploadPeopleImportFile(
  batchId: string,
  file: File,
  rowVersion: number,
  idempotencyKey: string,
): Promise<PeopleImportFileView> {
  peopleImportFileValidator.validate(file);
  const uploadFile = withCanonicalXlsxMediaType(file);
  if (isDemoMode()) {
    return Promise.resolve(uploadDemoFile(batchId, uploadFile));
  }
  const formData = new FormData();
  formData.set('file', uploadFile);
  return requestJson<PeopleImportFileView>(`${basePath}/${encodeURIComponent(batchId)}/file`, {
    method: 'PUT',
    headers: versionHeaders(rowVersion, idempotencyKey),
    body: formData,
  });
}

function withCanonicalXlsxMediaType(file: File): File {
  if (file.type.length > 0) return file;
  return new File([file], file.name, {
    type: peopleImportFilePolicy.allowedTypes[0],
    lastModified: file.lastModified,
  });
}

export function replacePeopleImportMapping(
  batchId: string,
  request: PeopleImportMappingRequest,
  rowVersion: number,
  idempotencyKey: string,
): Promise<PeopleImportBatchDetail> {
  if (isDemoMode()) return Promise.resolve(replaceDemoMapping(batchId, request));
  return requestJson<PeopleImportBatchDetail>(`${basePath}/${encodeURIComponent(batchId)}/mapping`, {
    method: 'PUT',
    headers: versionHeaders(rowVersion, idempotencyKey),
    body: JSON.stringify(request),
  });
}

export function precheckPeopleImport(
  batchId: string,
  rowVersion: number,
  idempotencyKey: string,
): Promise<PeopleImportBatchDetail> {
  if (isDemoMode()) return Promise.resolve(startDemoPrecheck(batchId));
  return requestJson<PeopleImportBatchDetail>(`${basePath}/${encodeURIComponent(batchId)}/precheck`, {
    method: 'POST',
    headers: versionHeaders(rowVersion, idempotencyKey),
  });
}

export function listPeopleImportDiff(
  batchId: string,
  category?: PeopleImportDiffCategory,
  page = 0,
  size = 20,
): Promise<PeopleImportDiffPage> {
  if (isDemoMode()) return Promise.resolve(getDemoDiff(batchId, category, page, size));
  const parameters = new URLSearchParams({ page: String(page), size: String(size) });
  if (category) parameters.set('category', category);
  return requestJson<PeopleImportDiffPage>(
    `${basePath}/${encodeURIComponent(batchId)}/diff?${parameters}`,
  );
}

export function listPeopleImportErrors(
  batchId: string,
  page = 0,
  size = 20,
): Promise<PeopleImportIssuePage> {
  if (isDemoMode()) return Promise.resolve(getDemoIssues(batchId, page, size));
  return requestJson<PeopleImportIssuePage>(
    `${basePath}/${encodeURIComponent(batchId)}/errors?page=${page}&size=${size}`,
  );
}

export function downloadPeopleImportErrorReport(batchId: string): Promise<DownloadedFile> {
  if (isDemoMode()) return Promise.resolve(syntheticDownload('synthetic-people-import-errors.xlsx'));
  return requestFile(`${basePath}/${encodeURIComponent(batchId)}/error-report`);
}

export function publishPeopleImport(
  batch: PeopleImportBatchDetail,
  reason: string,
  idempotencyKey: string,
): Promise<PeopleImportPublicationView> {
  if (batch.precheckVersion === null) {
    return Promise.reject(new ApiRequestError(409, {
      code: 'PEOPLE_IMPORT_PRECHECK_REQUIRED',
      message: translate('peopleImport.precheckVersionRequired'),
      retryable: false,
    }));
  }
  if (isDemoMode()) return Promise.resolve(publishDemoBatch(batch.batchId));
  return requestJson<PeopleImportPublicationView>(
    `${basePath}/${encodeURIComponent(batch.batchId)}/publish`,
    {
      method: 'POST',
      headers: versionHeaders(batch.rowVersion, idempotencyKey),
      body: JSON.stringify({
        reason,
        confirmedFileSha256: batch.file?.sha256 ?? batch.fileSha256,
        confirmedPrecheckVersion: batch.precheckVersion,
      }),
    },
  );
}

export function voidPeopleImport(
  batchId: string,
  rowVersion: number,
  reason: string,
  idempotencyKey: string,
): Promise<PeopleImportBatchDetail> {
  if (isDemoMode()) return Promise.resolve(voidDemoBatch(batchId));
  return requestJson<PeopleImportBatchDetail>(`${basePath}/${encodeURIComponent(batchId)}/void`, {
    method: 'POST',
    headers: versionHeaders(rowVersion, idempotencyKey),
    body: JSON.stringify({ reason }),
  });
}

export function rollbackPeopleImport(
  batch: PeopleImportBatchDetail,
  reason: string,
  idempotencyKey: string,
): Promise<PeopleImportRollbackView> {
  if (isDemoMode()) return Promise.resolve(rollbackDemoBatch(batch.batchId));
  return requestJson<PeopleImportRollbackView>(
    `${basePath}/${encodeURIComponent(batch.batchId)}/rollback`,
    {
      method: 'POST',
      headers: versionHeaders(batch.rowVersion, idempotencyKey),
      body: JSON.stringify({
        reason,
        confirmedPublicationId: batch.publication?.publicationId,
      }),
    },
  );
}

function syntheticDownload(fileName: string): DownloadedFile {
  return {
    blob: new Blob(['synthetic workbook'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
    fileName,
  };
}

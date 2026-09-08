import {
  changeReasonHeaders,
  createIdempotencyKey,
  requestJson,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import type {
  AttendanceSourceJobView,
  AttendanceSourceView,
  IntegrationStatusView,
  OaDocumentView,
  StablePage,
} from './attendanceSourceTypes';
import {
  demoAttendanceSources,
  demoIntegrationStatus,
  demoOaDocuments,
  demoSourceJobs,
} from './attendanceSourceDemo';

const demoMode = import.meta.env.MODE === 'demo' && isDemoMode();
let demoSourceJobStore = copyDemoSourceJobs();

export function resetAttendanceSourceDemoState(): void {
  demoSourceJobStore = copyDemoSourceJobs();
}

export function listAttendanceSources(
  page = 0,
  size = 20,
): Promise<StablePage<AttendanceSourceView>> {
  if (demoMode) return Promise.resolve(demoPage(demoAttendanceSources, page, size));
  return requestJson(`/api/v1/attendance-sources?page=${page}&size=${size}`);
}

export function getSourceIntegrationStatus(): Promise<IntegrationStatusView> {
  if (demoMode) return Promise.resolve(demoIntegrationStatus);
  return requestJson('/api/v1/attendance-sources/integration-status');
}

export function listOaDocuments(
  sourceId: string,
  page = 0,
  size = 20,
  documentType?: string,
): Promise<StablePage<OaDocumentView>> {
  if (demoMode) {
    const filtered = documentType
      ? demoOaDocuments.filter((item) => item.documentType === documentType)
      : demoOaDocuments;
    return Promise.resolve(demoPage(filtered, page, size));
  }
  const typeQuery = documentType
    ? `&documentType=${encodeURIComponent(documentType)}`
    : '';
  return requestJson(
    `/api/v1/attendance-sources/${encodeURIComponent(sourceId)}/documents?page=${page}&size=${size}${typeQuery}`,
  );
}

export function listAttendanceSourceJobs(
  page = 0,
  size = 20,
): Promise<StablePage<AttendanceSourceJobView>> {
  if (demoMode) {
    return Promise.resolve(
      demoPage(demoSourceJobStore.map(copySourceJob), page, size),
    );
  }
  return requestJson(`/api/v1/attendance-source-jobs?page=${page}&size=${size}`);
}

export function startAttendanceSourceJob(
  sourceId: string,
): Promise<AttendanceSourceJobView> {
  if (demoMode) {
    return Promise.resolve({
      jobId: `JOB-MANUAL-${Date.now()}`,
      sourceId,
      sourceDisplayName: '手动重试',
      sourceType: 'DELI_CLOUD',
      state: 'QUEUED',
      committedPages: 0,
      rawFactCount: 0,
      quarantinedCount: 0,
      safeErrorSummary: null,
      startedAt: null,
      completedAt: null,
      rowVersion: 1,
    });
  }
  return requestJson('/api/v1/attendance-source-jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceId }),
  });
}

export function retryAttendanceSourceJob(
  job: AttendanceSourceJobView,
  reason: string,
): Promise<AttendanceSourceJobView> {
  if (demoMode) {
    const index = demoSourceJobStore.findIndex((item) => item.jobId === job.jobId);
    if (index < 0) return Promise.resolve(copySourceJob(job));
    const current = demoSourceJobStore[index]!;
    const retried: AttendanceSourceJobView = {
      ...current,
      state: 'QUEUED',
      rowVersion: current.rowVersion + 1,
      safeErrorSummary: null,
      startedAt: null,
      completedAt: null,
    };
    demoSourceJobStore = demoSourceJobStore.map((item, itemIndex) => (
      itemIndex === index ? retried : item
    ));
    return Promise.resolve(copySourceJob(retried));
  }
  return requestJson(
    `/api/v1/attendance-source-jobs/${encodeURIComponent(job.jobId)}/retry`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          job.rowVersion,
          createIdempotencyKey('attendance-source-job-retry'),
        ),
      ),
    },
  );
}

function copyDemoSourceJobs(): AttendanceSourceJobView[] {
  return demoSourceJobs.map(copySourceJob);
}

function copySourceJob(job: AttendanceSourceJobView): AttendanceSourceJobView {
  return { ...job };
}

function demoPage<T>(items: T[], page: number, size: number): StablePage<T> {
  const start = page * size;
  return {
    items: items.slice(start, start + size),
    page,
    size,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : Math.ceil(items.length / size),
  };
}

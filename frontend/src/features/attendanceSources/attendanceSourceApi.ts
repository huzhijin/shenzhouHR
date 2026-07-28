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
): Promise<StablePage<OaDocumentView>> {
  if (demoMode) return Promise.resolve(demoPage(demoOaDocuments, page, size));
  return requestJson(
    `/api/v1/attendance-sources/${encodeURIComponent(sourceId)}/documents?page=${page}&size=${size}`,
  );
}

export function listAttendanceSourceJobs(
  page = 0,
  size = 20,
): Promise<StablePage<AttendanceSourceJobView>> {
  if (demoMode) return Promise.resolve(demoPage(demoSourceJobs, page, size));
  return requestJson(`/api/v1/attendance-source-jobs?page=${page}&size=${size}`);
}

export function retryAttendanceSourceJob(
  job: AttendanceSourceJobView,
  reason: string,
): Promise<AttendanceSourceJobView> {
  if (demoMode) {
    return Promise.resolve({
      ...job,
      state: 'QUEUED',
      rowVersion: job.rowVersion + 1,
      safeErrorSummary: null,
    });
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

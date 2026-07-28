import type {
  AttendanceSourceJobView,
  AttendanceSourceView,
  IntegrationStatusView,
  OaDocumentView,
} from './attendanceSourceTypes';

export const demoAttendanceSources: AttendanceSourceView[] = [
  {
    sourceId: 'synthetic-source-deli',
    legalEntityId: 'synthetic-legal-entity',
    sourceType: 'DELI_CLOUD',
    code: 'SYNTHETIC_DELI',
    displayName: '得力契约桩（合成）',
    state: 'ACTIVE',
    timeZone: 'Asia/Shanghai',
    configurationRevision: 3,
    committedWatermark: 'synthetic-page-18',
    lastSuccessfulSyncAt: '2026-07-28T01:10:00Z',
    rowVersion: 3,
  },
  {
    sourceId: 'synthetic-source-oa',
    legalEntityId: 'synthetic-legal-entity',
    sourceType: 'OA_ATTENDANCE',
    code: 'SYNTHETIC_OA',
    displayName: 'OA 契约桩（合成）',
    state: 'ACTIVE',
    timeZone: 'Asia/Shanghai',
    configurationRevision: 2,
    committedWatermark: 'synthetic-doc-version-7',
    lastSuccessfulSyncAt: '2026-07-28T01:20:00Z',
    rowVersion: 2,
  },
];

export const demoSourceJobs: AttendanceSourceJobView[] = [
  {
    jobId: 'synthetic-job-001',
    sourceId: 'synthetic-source-deli',
    sourceDisplayName: '得力契约桩（合成）',
    sourceType: 'DELI_CLOUD',
    state: 'SUCCEEDED',
    committedPages: 4,
    rawFactCount: 128,
    quarantinedCount: 0,
    safeErrorSummary: null,
    startedAt: '2026-07-28T01:00:00Z',
    completedAt: '2026-07-28T01:10:00Z',
    rowVersion: 1,
  },
  {
    jobId: 'synthetic-job-002',
    sourceId: 'synthetic-source-oa',
    sourceDisplayName: 'OA 契约桩（合成）',
    sourceType: 'OA_ATTENDANCE',
    state: 'PARTIALLY_QUARANTINED',
    committedPages: 2,
    rawFactCount: 12,
    quarantinedCount: 1,
    safeErrorSummary: '1 条 UNKNOWN 状态已隔离；未暴露原始载荷',
    startedAt: '2026-07-28T01:15:00Z',
    completedAt: '2026-07-28T01:20:00Z',
    rowVersion: 2,
  },
];

export const demoOaDocuments: OaDocumentView[] = [
  {
    documentId: 'synthetic-document-001',
    sourceDocumentId: '922337203685477580812346',
    sourceVersion: '7',
    documentType: 'LEAVE',
    sourceStatus: 'APPROVED',
    effectiveCandidate: true,
    employeeNumber: 'SYNTHETIC-E001',
    intervalStart: '2026-07-28T01:00:00Z',
    intervalEndExclusive: '2026-07-28T09:00:00Z',
  },
  {
    documentId: 'synthetic-document-002',
    sourceDocumentId: '922337203685477580812347',
    sourceVersion: '2',
    documentType: 'UNKNOWN',
    sourceStatus: 'UNKNOWN',
    effectiveCandidate: false,
    employeeNumber: null,
    intervalStart: null,
    intervalEndExclusive: null,
  },
];

export const demoIntegrationStatus: IntegrationStatusView = {
  deliContractStub: 'PASS',
  oaContractStub: 'PASS',
  filePortContract: 'PASS',
  deliLive: 'NOT_VERIFIED',
  oaLive: 'NOT_VERIFIED',
  productionFileStorage: 'NOT_VERIFIED',
};

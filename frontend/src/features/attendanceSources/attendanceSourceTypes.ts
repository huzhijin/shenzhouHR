export type AttendanceSourceType =
  | 'DELI_CLOUD'
  | 'OA_ATTENDANCE'
  | 'DEVICE_EXCEL'
  | 'STANDARD_XLSX';
export type AttendanceSourceState = 'ACTIVE' | 'INACTIVE';
export type SourceJobState =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIALLY_QUARANTINED'
  | 'FAILED'
  | 'CANCELLED';

export interface StablePage<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AttendanceSourceView {
  sourceId: string;
  legalEntityId: string;
  sourceType: AttendanceSourceType;
  code: string;
  displayName: string;
  state: AttendanceSourceState;
  timeZone: string;
  configurationRevision: number;
  committedWatermark: string | null;
  lastSuccessfulSyncAt: string | null;
  rowVersion: number;
}

export interface AttendanceSourceJobView {
  jobId: string;
  sourceId: string;
  sourceDisplayName: string;
  sourceType: AttendanceSourceType;
  state: SourceJobState;
  committedPages: number;
  rawFactCount: number;
  quarantinedCount: number;
  safeErrorSummary: string | null;
  startedAt: string | null;
  completedAt: string | null;
  rowVersion: number;
}

export interface OaDocumentView {
  documentId: string;
  sourceDocumentId: string;
  sourceVersion: string;
  documentType: string;
  sourceStatus: 'APPROVED' | 'DRAFT' | 'REJECTED' | 'UNKNOWN' | 'MODIFIED' | 'SUPPLEMENTED' | 'REVOKED';
  effectiveCandidate: boolean;
  employeeNumber: string | null;
  intervalStart: string | null;
  intervalEndExclusive: string | null;
}

export interface IntegrationStatusView {
  deliContractStub: 'PASS';
  oaContractStub: 'PASS';
  filePortContract: 'PASS';
  deliLive: 'NOT_VERIFIED';
  oaLive: 'NOT_VERIFIED';
  productionFileStorage: 'NOT_VERIFIED';
}

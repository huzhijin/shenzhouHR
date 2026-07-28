import type { StablePage } from '../attendanceSources/attendanceSourceTypes';

export type PunchImportState =
  | 'DRAFT'
  | 'VALIDATING'
  | 'VALIDATION_FAILED'
  | 'AWAITING_CONFIRMATION'
  | 'BLOCKED_BY_FROZEN_PERIOD'
  | 'PUBLISHING'
  | 'PUBLISHED'
  | 'PARTIALLY_PUBLISHED'
  | 'PUBLISH_FAILED'
  | 'VOIDED';

export interface PunchImportBatchView {
  batchId: string;
  legalEntityId: string;
  sourceId: string;
  originalFilename: string;
  fileSha256: string;
  state: PunchImportState;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  exactDuplicateRows: number;
  nearDuplicateRows: number;
  affectedEmployees: number;
  affectedDateFrom: string | null;
  affectedDateTo: string | null;
  precheckTokenPresent: boolean;
  precheckToken: string | null;
  createdAt: string;
  rowVersion: number;
}

export interface PunchImportIssueView {
  issueId: string;
  rowNumber: number;
  field: string | null;
  code: string;
  severity: 'BLOCKING' | 'WARNING';
  safeMessage: string;
}

export interface PunchImportRowView {
  rowId: string;
  rowNumber: number;
  employeeNumber: string | null;
  punchTime: string | null;
  sourceTimeZone: string | null;
  matchState: 'MATCHED' | 'UNMATCHED' | 'AMBIGUOUS' | 'OUT_OF_SCOPE';
  duplicateState: 'NONE' | 'EXACT' | 'NEAR_PENDING';
  publishable: boolean;
}

export type PunchImportPage = StablePage<PunchImportBatchView>;
export type PunchImportIssuePage = StablePage<PunchImportIssueView>;
export type PunchImportRowPage = StablePage<PunchImportRowView>;

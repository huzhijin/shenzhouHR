import { peopleImportFilePolicy } from '../../shared/files/peopleImportFilePolicy';

export type PeopleImportTemplateType =
  | 'ORGANIZATION'
  | 'EMPLOYEE'
  | 'EMPLOYMENT'
  | 'PRIOR_SERVICE';

export type PeopleImportBatchStatus =
  | 'DRAFT'
  | 'VALIDATING'
  | 'VALIDATION_FAILED'
  | 'AWAITING_CONFIRMATION'
  | 'PUBLISHING'
  | 'PUBLISHED'
  | 'PUBLISH_FAILED'
  | 'VOIDED';

export type PeopleImportDiffCategory =
  | 'ADDED'
  | 'UPDATED'
  | 'UNCHANGED'
  | 'CONFLICT'
  | 'ERROR';

export interface PeopleImportTemplateField {
  key: string;
  label: string;
  required: boolean;
  valueType: 'TEXT' | 'DATE' | 'INTEGER' | 'DECIMAL' | 'ENUM' | 'PRECISE_ID';
  matchKey: boolean;
  description?: string;
  enumValues?: string[];
}

export interface PeopleImportTemplateVersion {
  templateType: PeopleImportTemplateType;
  templateVersion: string;
  fileName: string;
  sha256: string;
  publishedAt: string;
  fields: PeopleImportTemplateField[];
}

export interface PeopleImportTemplateVersionPage {
  items: PeopleImportTemplateVersion[];
  total: number;
  page: number;
  size: number;
}

export interface PeopleImportPrecheckSummary {
  added: number;
  updated: number;
  unchanged: number;
  conflict: number;
  error: number;
  blockingIssueCount: number;
}

export interface PeopleImportFileView {
  fileId: string;
  originalFileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface PeopleImportFileValidationPolicy {
  allowedExtensions: readonly string[];
  allowedTypes: readonly string[];
  maxSizeBytes: number;
}

export const peopleImportFileValidationPolicy = Object.freeze({
  ...peopleImportFilePolicy,
}) satisfies PeopleImportFileValidationPolicy;

export interface PeopleImportMappingEntry {
  sourceColumn: string;
  targetField: string;
}

export interface PeopleImportPublicationView {
  publicationId: string;
  batchId: string;
  snapshotDigest: string;
  localVersionIds: string[];
  deduplicated: boolean;
  duplicateOfPublicationId?: string | null;
  publishedBy: string;
  publishedAt: string;
}

export interface PeopleImportBatchSummary {
  batchId: string;
  legalEntityId: string;
  templateType: PeopleImportTemplateType;
  templateVersion: string;
  status: PeopleImportBatchStatus;
  fileSha256?: string | null;
  precheckVersion: number | null;
  precheckSummary?: PeopleImportPrecheckSummary | null;
  rowVersion: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string | null;
}

export interface PeopleImportBatchDetail extends PeopleImportBatchSummary {
  file?: PeopleImportFileView | null;
  mapping: PeopleImportMappingEntry[];
  publication?: PeopleImportPublicationView | null;
  auditResourceId: string;
}

export interface PeopleImportBatchPage {
  items: PeopleImportBatchSummary[];
  total: number;
  page: number;
  size: number;
}

export interface PeopleImportDiffRow {
  diffId: string;
  rowNumber: number;
  entityType: PeopleImportTemplateType;
  category: PeopleImportDiffCategory;
  matchedResourceId?: string | null;
  sourceValues: Record<string, unknown>;
  currentValues?: Record<string, unknown>;
  proposedValues?: Record<string, unknown>;
}

export interface PeopleImportDiffPage {
  summary: PeopleImportPrecheckSummary;
  items: PeopleImportDiffRow[];
  total: number;
  page: number;
  size: number;
}

export interface PeopleImportIssueView {
  issueId: string;
  rowNumber: number;
  field?: string | null;
  code: string;
  message: string;
  severity: 'WARNING' | 'BLOCKING';
}

export interface PeopleImportIssuePage {
  items: PeopleImportIssueView[];
  total: number;
  page: number;
  size: number;
}

export interface PeopleImportRollbackView {
  rollbackId: string;
  batchId: string;
  sourcePublicationId: string;
  restoredSnapshotDigest: string;
  createdVersionIds: string[];
  rolledBackBy: string;
  rolledBackAt: string;
}

export interface PeopleImportCreateRequest {
  legalEntityId: string;
  templateType: PeopleImportTemplateType;
  templateVersion: string;
  reason: string;
}

export interface PeopleImportMappingRequest {
  entries: PeopleImportMappingEntry[];
  reason: string;
}

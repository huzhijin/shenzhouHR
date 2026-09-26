import {
  createIdempotencyKey,
  requestFile,
  requestJson,
  saveDownloadedFile,
  type DownloadedFile,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { validatePeopleImportFile } from './peopleImportFileValidation';

export interface RosterImportSummary {
  added: number;
  updated: number;
  unchanged: number;
  conflict: number;
  error: number;
  blocking: number;
}

export interface RosterImportDiff {
  rowNumber: number;
  category: 'ADDED' | 'UPDATED' | 'UNCHANGED' | 'CONFLICT' | 'ERROR' | string;
  matchedResourceId?: string | null;
  sourceValues: Record<string, unknown>;
  currentValues?: Record<string, unknown> | null;
  proposedValues?: Record<string, unknown> | null;
}

export interface RosterImportIssue {
  rowNumber: number;
  field?: string | null;
  code: string;
  message: string;
  severity: 'WARNING' | 'BLOCKING' | string;
}

export interface RosterImportBatch {
  batchId: string;
  status: string;
  reason: string;
  originalFileName: string;
  fileSha256: string;
  summary: RosterImportSummary;
  diffs: RosterImportDiff[];
  issues: RosterImportIssue[];
  rowVersion: number;
  createdAt: string;
  publishedAt?: string | null;
}

const basePath = '/api/v1/roster-imports';

export function downloadRosterTemplate(): Promise<DownloadedFile> {
  if (isDemoMode()) {
    return Promise.resolve({
      blob: new Blob(['demo-roster'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }),
      fileName: 'shenzhouhr-roster-import.xlsx',
    });
  }
  return requestFile(`${basePath}/template`);
}

export async function saveRosterTemplate(): Promise<void> {
  const file = await downloadRosterTemplate();
  saveDownloadedFile(file);
}

export function uploadRosterFile(file: File, reason: string): Promise<RosterImportBatch> {
  validatePeopleImportFile(file);
  if (isDemoMode()) {
    return Promise.resolve(demoBatch(file.name));
  }
  const form = new FormData();
  form.set('file', file);
  form.set('reason', reason);
  return requestJson<RosterImportBatch>(`${basePath}?reason=${encodeURIComponent(reason)}`, {
    method: 'POST',
    body: form,
  });
}

export function publishRosterImport(batchId: string, reason: string): Promise<RosterImportBatch> {
  if (isDemoMode()) {
    return Promise.resolve({ ...demoBatch('demo.xlsx'), status: 'PUBLISHED' });
  }
  return requestJson<RosterImportBatch>(
    `${basePath}/${encodeURIComponent(batchId)}/publish?reason=${encodeURIComponent(reason)}`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': createIdempotencyKey('roster-publish') },
    },
  );
}

function demoBatch(fileName: string): RosterImportBatch {
  return {
    batchId: 'demo-roster-batch',
    status: 'PRECHECKED',
    reason: '演示预检',
    originalFileName: fileName,
    fileSha256: '0'.repeat(64),
    summary: { added: 1, updated: 0, unchanged: 0, conflict: 0, error: 0, blocking: 0 },
    diffs: [{
      rowNumber: 2,
      category: 'ADDED',
      sourceValues: { employeeNumber: 'SZJN0042', displayName: '高露浩' },
    }],
    issues: [],
    rowVersion: 0,
    createdAt: new Date().toISOString(),
    publishedAt: null,
  };
}

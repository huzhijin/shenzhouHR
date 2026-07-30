import { afterEach, describe, expect, it, vi } from 'vitest';

import { peopleImportFileValidationPolicy } from './peopleImportTypes';
import { publishPeopleImport, uploadPeopleImportFile } from './peopleImportApi';
import type {
  PeopleImportBatchDetail,
  PeopleImportFileView,
  PeopleImportPublicationView,
} from './peopleImportTypes';

describe('people import publish contract', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends precheckVersion when it differs from rowVersion', async () => {
    const publication: PeopleImportPublicationView = {
      publicationId: 'publication-1',
      batchId: 'batch-1',
      snapshotDigest: 'a'.repeat(64),
      localVersionIds: ['organization-version-1'],
      deduplicated: false,
      publishedBy: 'actor-1',
      publishedAt: '2026-07-25T05:00:00Z',
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(publication), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await publishPeopleImport(batch({ rowVersion: 42, precheckVersion: 17 }), '复核后发布', 'publish-key');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toMatchObject({
      confirmedPrecheckVersion: 17,
    });
    expect(new Headers(init.headers).get('If-Match')).toBe('"42"');
  });

  it('rejects publish locally when precheckVersion is null', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      publishPeopleImport(batch({ rowVersion: 42, precheckVersion: null }), '尝试发布', 'publish-key'),
    ).rejects.toMatchObject({
      status: 409,
      code: 'PEOPLE_IMPORT_PRECHECK_REQUIRED',
      retryable: false,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('canonicalizes an empty browser MIME type before multipart upload', async () => {
    const uploaded: PeopleImportFileView = {
      fileId: 'file-1',
      originalFileName: 'employees.xlsx',
      mediaType: peopleImportFileValidationPolicy.allowedTypes[0],
      sizeBytes: 8,
      sha256: 'c'.repeat(64),
      uploadedBy: 'actor-1',
      uploadedAt: '2026-07-25T05:00:00Z',
    };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(uploaded), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await uploadPeopleImportFile(
      'batch-1',
      new File(['workbook'], 'employees.xlsx', { type: '' }),
      7,
      'upload-key',
    );

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const multipartFile = (init.body as FormData).get('file');
    expect(multipartFile).toBeInstanceOf(File);
    expect(multipartFile).toMatchObject({
      name: 'employees.xlsx',
      size: 8,
      type: peopleImportFileValidationPolicy.allowedTypes[0],
    });
  });
});

function batch(overrides: Pick<PeopleImportBatchDetail, 'rowVersion' | 'precheckVersion'>): PeopleImportBatchDetail {
  return {
    batchId: 'batch-1',
    companyId: 'company-1',
    templateType: 'ORGANIZATION',
    templateVersion: '1.0',
    status: 'AWAITING_CONFIRMATION',
    fileSha256: 'b'.repeat(64),
    precheckSummary: {
      added: 1,
      updated: 0,
      unchanged: 0,
      conflict: 0,
      error: 0,
      blockingIssueCount: 0,
    },
    createdBy: 'actor-1',
    createdAt: '2026-07-25T04:00:00Z',
    updatedAt: '2026-07-25T04:30:00Z',
    mapping: [{ sourceColumn: '组织编码', targetField: 'organizationCode' }],
    auditResourceId: 'batch-1',
    ...overrides,
  };
}

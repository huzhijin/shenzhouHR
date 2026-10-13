import type {
  PeopleImportBatchDetail,
  PeopleImportBatchPage,
  PeopleImportCreateRequest,
  PeopleImportDiffCategory,
  PeopleImportDiffPage,
  PeopleImportFileView,
  PeopleImportIssuePage,
  PeopleImportMappingRequest,
  PeopleImportPublicationView,
  PeopleImportRollbackView,
  PeopleImportTemplateField,
  PeopleImportTemplateType,
  PeopleImportTemplateVersion,
  PeopleImportTemplateVersionPage,
} from './peopleImportTypes';

const syntheticActor = 'synthetic-people-admin';
const syntheticCompany = 'synthetic-company-jiangsu';
const now = '2026-07-25T03:20:00Z';
const digest = 'a'.repeat(64);

const fieldsByType: Record<PeopleImportTemplateType, PeopleImportTemplateField[]> = {
  ORGANIZATION: [
    field('organizationCode', '合成组织编码', true, true, 'TEXT'),
    field('organizationName', '合成组织名称', true, false, 'TEXT'),
    field('parentOrganizationCode', '合成上级组织编码', false, false, 'TEXT'),
    field('effectiveFrom', '生效日', true, false, 'DATE'),
  ],
  EMPLOYEE: [
    field('employeeNumber', '合成员工编号', true, true, 'TEXT'),
    field('displayName', '合成姓名', true, false, 'TEXT'),
    field('externalEmployeeId', '外部员工编号', false, true, 'PRECISE_ID'),
    field('effectiveFrom', '生效日', true, false, 'DATE'),
  ],
  EMPLOYMENT: [
    field('employeeNumber', '合成员工编号', true, true, 'TEXT'),
    field('organizationCode', '合成组织编码', true, true, 'TEXT'),
    field('startDate', '任职开始日', true, false, 'DATE'),
    field('terminationDate', '业务离职日', false, false, 'DATE'),
  ],
  PRIOR_SERVICE: [
    field('employeeNumber', '合成员工编号', true, true, 'TEXT'),
    field('amountDays', '入职前累计工龄天数', true, false, 'INTEGER'),
    field('businessDate', '业务日期', true, false, 'DATE'),
    field('reason', '合成维护原因', true, false, 'TEXT'),
  ],
};

const templates = (Object.keys(fieldsByType) as PeopleImportTemplateType[]).map(
  (templateType): PeopleImportTemplateVersion => ({
    templateType,
    templateVersion: '1.0',
    fileName: `synthetic-${templateType.toLowerCase()}-opening-v1.0.xlsx`,
    sha256: digest,
    publishedAt: now,
    fields: fieldsByType[templateType],
  }),
);

class DemoPeopleImportStore {
  private sequence = 1;
  private currentBatch: PeopleImportBatchDetail | undefined;

  nextBatchId(): string {
    const batchId = `synthetic-people-import-${this.sequence}`;
    this.sequence += 1;
    return batchId;
  }

  listBatches(): PeopleImportBatchDetail[] {
    return this.currentBatch ? [this.currentBatch] : [];
  }

  requireBatch(batchId: string): PeopleImportBatchDetail {
    if (!this.currentBatch || this.currentBatch.batchId !== batchId) {
      throw new Error('SYNTHETIC_BATCH_NOT_FOUND');
    }
    return this.currentBatch;
  }

  replaceBatch(batch: PeopleImportBatchDetail): PeopleImportBatchDetail {
    this.currentBatch = batch;
    return batch;
  }
}

const demoPeopleImportStore = new DemoPeopleImportStore();

export function getDemoTemplates(type?: PeopleImportTemplateType): PeopleImportTemplateVersionPage {
  const items = type ? templates.filter((template) => template.templateType === type) : templates;
  return { items, total: items.length, page: 0, size: 20 };
}

export function getDemoBatches(): PeopleImportBatchPage {
  const items = demoPeopleImportStore.listBatches();
  return { items, total: items.length, page: 0, size: 20 };
}

export function createDemoBatch(input: PeopleImportCreateRequest): PeopleImportBatchDetail {
  const batchId = demoPeopleImportStore.nextBatchId();
  return demoPeopleImportStore.replaceBatch({
    batchId,
    companyId: input.companyId || syntheticCompany,
    templateType: input.templateType,
    templateVersion: input.templateVersion,
    status: 'DRAFT',
    precheckVersion: null,
    rowVersion: 0,
    createdBy: syntheticActor,
    createdAt: now,
    updatedAt: now,
    mapping: [],
    auditResourceId: batchId,
  });
}

export function getDemoBatch(batchId: string): PeopleImportBatchDetail {
  return demoPeopleImportStore.requireBatch(batchId);
}

export function uploadDemoFile(batchId: string, file: File): PeopleImportFileView {
  const batch = getDemoBatch(batchId);
  const view: PeopleImportFileView = {
    fileId: `${batchId}-file`,
    originalFileName: file.name,
    mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: file.size,
    sha256: digest,
    uploadedBy: syntheticActor,
    uploadedAt: now,
  };
  demoPeopleImportStore.replaceBatch({
    ...batch,
    file: view,
    fileSha256: view.sha256,
    precheckVersion: null,
    precheckSummary: null,
    status: 'DRAFT',
    rowVersion: batch.rowVersion + 1,
    updatedAt: now,
  });
  return view;
}

export function replaceDemoMapping(
  batchId: string,
  request: PeopleImportMappingRequest,
): PeopleImportBatchDetail {
  const batch = getDemoBatch(batchId);
  return demoPeopleImportStore.replaceBatch({
    ...batch,
    mapping: request.entries,
    status: 'DRAFT',
    precheckVersion: null,
    precheckSummary: null,
    rowVersion: batch.rowVersion + 1,
    updatedAt: now,
  });
}

export function startDemoPrecheck(batchId: string): PeopleImportBatchDetail {
  const batch = getDemoBatch(batchId);
  const precheckVersion = batch.rowVersion + 1;
  return demoPeopleImportStore.replaceBatch({
    ...batch,
    status: 'AWAITING_CONFIRMATION',
    precheckVersion,
    precheckSummary: {
      added: 18,
      updated: 3,
      unchanged: 7,
      conflict: 1,
      error: 1,
      blockingIssueCount: 0,
    },
    rowVersion: precheckVersion,
    updatedAt: now,
  });
}

export function getDemoDiff(
  batchId: string,
  category?: PeopleImportDiffCategory,
  page = 0,
  size = 20,
): PeopleImportDiffPage {
  const batch = getDemoBatch(batchId);
  const categories: PeopleImportDiffCategory[] = ['ADDED', 'UPDATED', 'UNCHANGED', 'CONFLICT', 'ERROR'];
  const all = categories.map((item, index) => ({
    diffId: `${batchId}-diff-${index + 1}`,
    rowNumber: index + 2,
    entityType: batch.templateType,
    category: item,
    matchedResourceId: item === 'ADDED' ? null : `synthetic-resource-${index + 1}`,
    sourceValues: { employeeNumber: `SYN-${String(index + 1).padStart(4, '0')}` },
    currentValues: item === 'UPDATED' ? { displayName: '合成员工旧名' } : {},
    proposedValues: { displayName: `合成员工${index + 1}` },
  }));
  const filtered = category ? all.filter((item) => item.category === category) : all;
  return {
    summary: batch.precheckSummary ?? {
      added: 0,
      updated: 0,
      unchanged: 0,
      conflict: 0,
      error: 0,
      blockingIssueCount: 0,
    },
    items: filtered.slice(page * size, page * size + size),
    total: filtered.length,
    page,
    size,
  };
}

export function getDemoIssues(batchId: string, page = 0, size = 20): PeopleImportIssuePage {
  getDemoBatch(batchId);
  const items = [{
    issueId: `${batchId}-issue-1`,
    rowNumber: 6,
    field: 'displayName',
    code: 'INVALID_VALUE',
    message: '合成警告：显示名称含待人工核对字符。',
    severity: 'WARNING' as const,
  }];
  return { items: items.slice(page * size, page * size + size), total: items.length, page, size };
}

export function publishDemoBatch(batchId: string): PeopleImportPublicationView {
  const batch = getDemoBatch(batchId);
  const publication = batch.publication ?? {
    publicationId: `${batchId}-publication`,
    batchId,
    snapshotDigest: digest,
    localVersionIds: [`${batchId}-local-version`],
    deduplicated: false,
    duplicateOfPublicationId: null,
    publishedBy: syntheticActor,
    publishedAt: now,
  };
  const deduplicated = batch.status === 'PUBLISHED';
  demoPeopleImportStore.replaceBatch({
    ...batch,
    status: 'PUBLISHED',
    publication: { ...publication, deduplicated },
    publishedAt: publication.publishedAt,
    rowVersion: batch.rowVersion + 1,
    updatedAt: now,
  });
  return { ...publication, deduplicated };
}

export function voidDemoBatch(batchId: string): PeopleImportBatchDetail {
  const batch = getDemoBatch(batchId);
  return demoPeopleImportStore.replaceBatch({
    ...batch,
    status: 'VOIDED',
    rowVersion: batch.rowVersion + 1,
    updatedAt: now,
  });
}

export function rollbackDemoBatch(batchId: string): PeopleImportRollbackView {
  const batch = getDemoBatch(batchId);
  if (!batch.publication) {
    throw new Error('SYNTHETIC_PUBLICATION_NOT_FOUND');
  }
  return {
    rollbackId: `${batchId}-rollback`,
    batchId,
    sourcePublicationId: batch.publication.publicationId,
    restoredSnapshotDigest: digest,
    createdVersionIds: [`${batchId}-restored-version`],
    rolledBackBy: syntheticActor,
    rolledBackAt: now,
  };
}

function field(
  key: string,
  label: string,
  required: boolean,
  matchKey: boolean,
  valueType: PeopleImportTemplateField['valueType'],
): PeopleImportTemplateField {
  return { key, label, required, matchKey, valueType };
}

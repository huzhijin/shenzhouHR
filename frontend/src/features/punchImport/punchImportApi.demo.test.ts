import { beforeEach, describe, expect, it } from 'vitest';

import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  getPunchImport,
  listPunchImports,
  precheckPunchImport,
  publishPunchImport,
  resetPunchImportDemoState,
  uploadPunchImport,
  voidOrReversePunchImport,
} from './punchImportApi';

describe.runIf(isDemoMode())('punch import demo state', () => {
  beforeEach(() => {
    resetPunchImportDemoState();
  });

  it('keeps an uploaded draft available to list and detail reads', async () => {
    const before = await listPunchImports();
    const uploaded = await uploadPunchImport(
      new File(['synthetic'], 'customer-demo.xlsx'),
      'customer-legal-entity',
      'customer-xlsx-source',
      '客户演示上传',
    );

    expect(uploaded).toMatchObject({
      originalFilename: 'customer-demo.xlsx',
      legalEntityId: 'customer-legal-entity',
      sourceId: 'customer-xlsx-source',
      state: 'DRAFT',
      rowVersion: 1,
    });
    await expect(getPunchImport(uploaded.batchId)).resolves.toEqual(uploaded);

    const after = await listPunchImports();
    expect(after.totalElements).toBe(before.totalElements + 1);
    expect(after.items[0]).toEqual(uploaded);
  });

  it('blocks strict publish while precheck still contains invalid rows', async () => {
    const uploaded = await uploadPunchImport(
      new File(['synthetic'], 'strict-demo.xlsx'),
      'customer-legal-entity',
      'customer-xlsx-source',
      '客户演示上传',
    );
    const prechecked = await precheckPunchImport(uploaded, '客户演示预检');
    expect(prechecked).toMatchObject({
      state: 'AWAITING_CONFIRMATION',
      precheckTokenPresent: true,
      rowVersion: 2,
    });
    expect(prechecked.totalRows).toBeGreaterThan(0);
    await expect(getPunchImport(uploaded.batchId)).resolves.toEqual(prechecked);

    await expect(
      publishPunchImport(prechecked, 'STRICT', '客户演示发布'),
    ).rejects.toMatchObject({
      status: 409,
      code: 'STRICT_PUBLISH_BLOCKED_BY_INVALID_ROWS',
    });
    await expect(getPunchImport(uploaded.batchId)).resolves.toEqual(prechecked);
  });

  it('persists a valid-rows-only publication', async () => {
    const batch = await getPunchImport('ATT-XLS-DEMO-001');
    const published = await publishPunchImport(
      batch,
      'VALID_ROWS_ONLY',
      '客户演示部分发布',
    );

    expect(published.state).toBe('PARTIALLY_PUBLISHED');
    await expect(getPunchImport(batch.batchId)).resolves.toEqual(published);

    const voided = await voidOrReversePunchImport(published, '客户演示冲正');
    expect(voided).toMatchObject({ state: 'VOIDED', rowVersion: 6 });
    await expect(getPunchImport(batch.batchId)).resolves.toEqual(voided);
  });
});

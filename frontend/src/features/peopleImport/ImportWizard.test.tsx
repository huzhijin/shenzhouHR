import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import type { PeopleImportBatchDetail } from './peopleImportTypes';
import {
  ImportWizard,
  canPublishBatch,
  canVoidBatch,
  stepForBatch,
} from './ImportWizard';

describe('people import workflow presentation', () => {
  it('keeps server processing and retry states on the publish step', () => {
    expect(stepForBatch(batch('PUBLISHING'))).toBe('publish');
    expect(stepForBatch(batch('PUBLISH_FAILED'))).toBe('publish');
  });

  it('shows only transitions accepted by the backend state machine', () => {
    expect(canVoidBatch(batch('DRAFT'))).toBe(true);
    expect(canVoidBatch(batch('VALIDATION_FAILED'))).toBe(true);
    expect(canVoidBatch(batch('AWAITING_CONFIRMATION'))).toBe(true);
    expect(canVoidBatch(batch('VALIDATING'))).toBe(false);
    expect(canVoidBatch(batch('PUBLISHING'))).toBe(false);
    expect(canVoidBatch(batch('PUBLISH_FAILED'))).toBe(false);

    expect(canPublishBatch(batch('AWAITING_CONFIRMATION'))).toBe(true);
    expect(canPublishBatch(batch('PUBLISH_FAILED'))).toBe(true);
    expect(canPublishBatch(batch('DRAFT'))).toBe(false);
  });

  it('announces a failed precheck only as an error, not as completed', () => {
    render(
      <ImportWizard
        templates={[]}
        batch={batch('VALIDATION_FAILED')}
        capabilities={[]}
        onBatchChange={vi.fn()}
      />,
    );

    const precheckStep = screen.getByRole('button', { name: /04.*预检.*失败/ });
    expect(precheckStep).toHaveAttribute('data-state', 'error');
    expect(precheckStep).not.toHaveAccessibleName(/已完成/);
    expect(precheckStep.querySelectorAll('svg')).toHaveLength(1);
  });
});

function batch(status: PeopleImportBatchDetail['status']): PeopleImportBatchDetail {
  return {
    batchId: '10000000-0000-0000-0000-000000000001',
    auditResourceId: '10000000-0000-0000-0000-000000000001',
    companyId: '10000000-0000-0000-0000-000000000002',
    templateType: 'EMPLOYEE',
    templateVersion: '1.0',
    status,
    fileSha256: 'a'.repeat(64),
    precheckVersion: 1,
    rowVersion: 1,
    mapping: [],
    precheckSummary: {
      added: 0,
      updated: 0,
      unchanged: 0,
      conflict: 0,
      error: 0,
      blockingIssueCount: 0,
    },
    createdBy: '10000000-0000-0000-0000-000000000003',
    createdAt: '2026-07-25T00:00:00Z',
    updatedAt: '2026-07-25T00:00:00Z',
  };
}

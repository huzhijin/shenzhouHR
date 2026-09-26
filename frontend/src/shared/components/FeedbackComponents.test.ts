import { describe, expect, it } from 'vitest';

import { statusLabel } from './FeedbackComponents';

describe('statusLabel', () => {
  it('translates employee, import, and audit statuses', () => {
    expect(statusLabel('TERMINATED')).toBe('已离职');
    expect(statusLabel('VALIDATING')).toBe('正在预检');
    expect(statusLabel('FAILURE')).toBe('失败');
    expect(statusLabel('DENIED')).toBe('已拒绝');
  });

  it('translates employee ledger record types', () => {
    expect(statusLabel('OPENING_IMPORT')).toBe('期初导入');
    expect(statusLabel('ADJUSTMENT')).toBe('调整');
    expect(statusLabel('REVERSAL')).toBe('冲正');
  });

  it('does not expose an unknown server enum to the interface', () => {
    expect(statusLabel('NEW_INTERNAL_WORKFLOW_STATE')).toBe('其他状态');
  });
});

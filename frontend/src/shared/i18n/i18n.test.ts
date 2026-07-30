import { describe, expect, it } from 'vitest';

import i18n from './i18n';
import { translate } from './messages';

describe('i18n interpolation', () => {
  it('renders the correlation id instead of exposing the template token', () => {
    expect(i18n.t('error.correlationId', { correlationId: 'wave1-correlation-001' }))
      .toBe('关联 ID：wave1-correlation-001');
  });

  it('renders multiple variables used by policy conflict feedback', () => {
    expect(i18n.t('policy.conflictVersion', {
      versionId: 'version-3',
      scopeType: 'COMPANY',
      scopeId: 'company-1',
    })).not.toContain('{');
  });

  it('keeps direct translations compatible with i18next templates', () => {
    expect(translate('error.correlationId', { correlationId: 'wave1-correlation-002' }))
      .toBe('关联 ID：wave1-correlation-002');
  });

  it('renders every lower-case import summary metric used by the precheck cards', () => {
    expect(['added', 'updated', 'unchanged', 'conflict', 'error'].map(
      (metric) => i18n.t(`peopleImport.metric.${metric}`),
    )).toEqual(['新增', '修改', '不变', '冲突', '错误']);
  });
});

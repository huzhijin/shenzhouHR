import { describe, expect, it } from 'vitest';

import {
  auditActionLabel,
  auditActorLabel,
  auditFieldLabel,
  auditResourceLabel,
  auditTextLabel,
  isHiddenAuditField,
  shouldMaskAuditChange,
} from './auditDisplayLabels';

describe('audit display labels', () => {
  it('translates action and resource machine codes', () => {
    expect(auditActionLabel('ACCOUNT_UNLOCKED')).toBe('解锁账号');
    expect(auditActionLabel('PEOPLE_IMPORT_PRECHECKED')).toBe('预检期初导入批次');
    expect(auditResourceLabel('LOCAL_ACCOUNT')).toBe('本地账号');
    expect(auditResourceLabel('EMPLOYEE')).toBe('员工档案');
  });

  it('translates actor types and change fields while preserving human names', () => {
    expect(auditActorLabel('SYSTEM')).toBe('系统服务');
    expect(auditActorLabel('王管理员')).toBe('王管理员');
    expect(auditActorLabel('10000000-0000-0000-0000-000000000002'))
      .toBe('系统操作主体');
    expect(auditFieldLabel('status')).toBe('状态');
  });

  it('masks internal identifiers while preserving business-facing values', () => {
    const internalId = '10000000-0000-0000-0000-000000000002';

    expect(auditActionLabel(internalId)).toBe('其他审计操作');
    expect(auditResourceLabel(internalId)).toBe('其他业务对象');
    expect(auditFieldLabel('policyVersionId')).toBe('其他变更字段');
    expect(isHiddenAuditField('eventId')).toBe(true);
    expect(isHiddenAuditField('rowVersion')).toBe(true);
    expect(isHiddenAuditField('displayName')).toBe(false);
    expect(shouldMaskAuditChange('organizationId', internalId, internalId))
      .toBe(true);
    expect(shouldMaskAuditChange('scopeBindings', '[]', '[{"scope":"SELF"}]'))
      .toBe(true);
    expect(shouldMaskAuditChange('status', 'LOCKED', 'ACTIVE')).toBe(false);
    expect(auditTextLabel(`关联 ${internalId}`, '已记录')).toBe('已记录');
    expect(auditTextLabel('管理员手工解锁', '已记录')).toBe('管理员手工解锁');
  });
});

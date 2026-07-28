import { describe, expect, it } from 'vitest';

import {
  auditActionLabel,
  auditActorLabel,
  auditFieldLabel,
  auditResourceLabel,
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
    expect(auditFieldLabel('status')).toBe('状态');
  });
});

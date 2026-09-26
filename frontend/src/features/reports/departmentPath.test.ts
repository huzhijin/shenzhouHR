import { describe, expect, it } from 'vitest';

import { excelWrappedDepartment, joinDepartmentSegments, visibleDepartmentPath } from './departmentPath';

describe('departmentPath', () => {
  it('joins full hierarchy and wraps only between segments', () => {
    const path = joinDepartmentSegments(['服务中心', '工程二部', 'RF-B组']);
    expect(visibleDepartmentPath(path)).toBe('服务中心-工程二部-RF-B组');
    expect(excelWrappedDepartment(path)).toBe('服务中心-\n工程二部-\nRF-B组');
    expect(path).not.toContain('RF-\u200B');
  });

  it('keeps a first-level name unhyphenated', () => {
    expect(joinDepartmentSegments(['销售中心'])).toBe('销售中心');
  });
});

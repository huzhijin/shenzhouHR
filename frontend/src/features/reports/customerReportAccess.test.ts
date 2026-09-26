import { describe, expect, it } from 'vitest';

import {
  assertCustomerReportExportable,
  authorizedDepartmentOptions,
  authorizedEmployeeOptions,
  customerReportDemoScopes,
  isCustomerReportQueryWithinScope,
  isWithinCustomerReportScope,
} from './customerReportAccess';

describe('customer report data scope', () => {
  const companyScope = customerReportDemoScopes[0]!;
  const manufacturingScope = customerReportDemoScopes.find(
    (scope) => scope.actorLabel === '部门负责人',
  )!;
  const selfScope = customerReportDemoScopes.find((scope) => scope.type === 'SELF')!;

  it('exposes only departments and employees granted by the active scope', () => {
    expect(authorizedDepartmentOptions(manufacturingScope).map((option) => option.value))
      .toEqual(['全部部门', '制造中心']);
    expect(authorizedEmployeeOptions(manufacturingScope, '全部部门').map((option) => option.value))
      .toEqual(['全部员工', '陈思远', '周晴', '赵凯']);
    expect(authorizedEmployeeOptions(selfScope, '全部部门').map((option) => option.value))
      .toEqual(['全部员工', '陈思远']);
    expect(authorizedDepartmentOptions(companyScope)).toHaveLength(4);
  });

  it('fails closed for forged department and employee filters', () => {
    expect(isCustomerReportQueryWithinScope('研发中心', '全部员工', manufacturingScope))
      .toBe(false);
    expect(isCustomerReportQueryWithinScope('制造中心', '吴昊', manufacturingScope))
      .toBe(false);
    expect(isCustomerReportQueryWithinScope('制造中心', '陈思远', manufacturingScope))
      .toBe(true);
  });

  it('requires both organization and employee membership', () => {
    expect(isWithinCustomerReportScope(
      { department: '制造中心', employee: '陈思远' },
      manufacturingScope,
    )).toBe(true);
    expect(isWithinCustomerReportScope(
      { department: '研发中心', employee: '陈思远' },
      manufacturingScope,
    )).toBe(false);
    expect(isWithinCustomerReportScope(
      { department: '制造中心', employee: '张伟' },
      manufacturingScope,
    )).toBe(false);
    expect(isWithinCustomerReportScope(
      { department: '制造中心-\u200B晶圆制造部', employee: '陈思远' },
      manufacturingScope,
    )).toBe(true);
  });

  it('lets live authorized-company snapshots export without a client name allow-list', () => {
    const liveScope = {
      reference: '41000000-0000-0000-0000-000000000003',
      type: 'COMPANY' as const,
      label: '江苏神州半导体科技股份有限公司',
      actorLabel: '授权范围',
      allowedDepartments: [],
      allowedEmployees: [],
    };
    expect(() => assertCustomerReportExportable(
      [{ department: '服务中心- 工程二部- RF-B组', employee: '卞骏' }],
      liveScope,
      false,
    )).not.toThrow();
  });

  it('still rejects demo rows outside the granted department and employee names', () => {
    expect(() => assertCustomerReportExportable(
      [{ department: '研发中心', employee: '张伟' }],
      manufacturingScope,
      true,
    )).toThrow('报表包含超出当前数据权限范围的记录');
  });
});

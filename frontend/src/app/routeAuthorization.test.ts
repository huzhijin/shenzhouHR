import { describe, expect, it } from 'vitest';

import {
  authorizedMenu,
  firstAuthorizedPath,
  selectedMenuKey,
} from './routeAuthorization';

describe('firstAuthorizedPath', () => {
  it('returns the first server menu item backed by a current capability', () => {
    expect(firstAuthorizedPath({
      capabilities: ['MASTER_DATA:READ'],
      menu: [
        { key: 'employees', label: '人员主数据', path: '/employees' },
        { key: 'organization', label: '组织架构', path: '/organization' },
      ],
    })).toBe('/employees');
  });

  it('does not trust a menu item when the required capability is missing', () => {
    const session = {
      capabilities: [],
      menu: [{ key: 'employees', label: '人员主数据', path: '/employees' }],
    };

    expect(firstAuthorizedPath(session)).toBeUndefined();
    expect(authorizedMenu(session)).toEqual([]);
  });

  it('rejects unknown server-provided routes', () => {
    expect(firstAuthorizedPath({
      capabilities: ['MASTER_DATA:READ'],
      menu: [{ key: 'unknown', label: '未知功能', path: '/unknown' }],
    })).toBeUndefined();
  });

  it('allows every WAVE-1 navigation route only when its server capability is present', () => {
    const session = {
      capabilities: [
        'POLICY:READ',
        'ACCOUNT:READ',
        'ROLE:READ',
        'AUDIT:READ',
      ],
      menu: [
        { key: 'rules', label: '规则中心', path: '/rules' },
        { key: 'rule-templates', label: '策略模板', path: '/rules/templates' },
        { key: 'accounts', label: '账号', path: '/access/accounts' },
        { key: 'roles', label: '角色', path: '/access/roles' },
        { key: 'audit', label: '审计', path: '/access/audit' },
      ],
    };

    expect(authorizedMenu(session)).toEqual(session.menu);
  });

  it('does not expose a WAVE-1 menu entry when the matching capability is absent', () => {
    const session = {
      capabilities: ['POLICY:READ'],
      menu: [
        { key: 'rules', label: '规则中心', path: '/rules' },
        { key: 'accounts', label: '账号', path: '/access/accounts' },
        { key: 'roles', label: '角色', path: '/access/roles' },
        { key: 'audit', label: '审计', path: '/access/audit' },
      ],
    };

    expect(authorizedMenu(session)).toEqual([
      { key: 'rules', label: '规则中心', path: '/rules' },
    ]);
  });

  it('rejects every PAYROLL route even when the server sends a PAYROLL capability', () => {
    expect(authorizedMenu({
      capabilities: ['PAYROLL:READ', 'PAYROLL:EDIT'],
      menu: [
        { key: 'payroll', label: '受排除模块', path: '/payroll' },
        { key: 'payslips', label: '受排除明细', path: '/me/payslips' },
      ],
    })).toEqual([]);
  });

  it('requires both read capabilities before exposing the people import route', () => {
    const menu = [{ key: 'people-import', label: '期初导入', path: '/people/import' }];

    expect(authorizedMenu({
      capabilities: ['PEOPLE_IMPORT:READ'],
      menu,
    })).toEqual([]);
    expect(authorizedMenu({
      capabilities: ['PEOPLE_IMPORT:TEMPLATE_DOWNLOAD'],
      menu,
    })).toEqual([]);
    expect(authorizedMenu({
      capabilities: ['PEOPLE_IMPORT:READ', 'PEOPLE_IMPORT:TEMPLATE_DOWNLOAD'],
      menu,
    })).toEqual(menu);
  });

  it('requires tree and detail capabilities before exposing organization maintenance', () => {
    const menu = [{ key: 'organization', label: '组织维护', path: '/people/organization' }];

    expect(authorizedMenu({
      capabilities: ['ORGANIZATION:READ'],
      menu,
    })).toEqual([]);
    expect(authorizedMenu({
      capabilities: ['MASTER_DATA:READ', 'ORGANIZATION:READ'],
      menu,
    })).toEqual(menu);
  });

  it('selects the longest matching menu path at a path-segment boundary', () => {
    const menu = [
      { key: 'rules', label: '规则中心', path: '/rules' },
      { key: 'templates', label: '策略模板', path: '/rules/templates' },
    ];

    expect(selectedMenuKey(menu, '/rules/templates/template-1')).toBe('templates');
    expect(selectedMenuKey(menu, '/rules')).toBe('rules');
    expect(selectedMenuKey(menu, '/rules-extra')).toBeUndefined();
  });

  it('exposes WAVE-3 routes only with attendance setup read capability', () => {
    const menu = [
      { key: 'attendance-groups', label: '考勤组', path: '/rules/attendance-groups' },
      { key: 'attendance-shifts', label: '班次版本', path: '/rules/shifts' },
      { key: 'attendance-calendars', label: '工作日历', path: '/rules/calendars' },
      { key: 'attendance-policies', label: '考勤策略', path: '/rules/attendance-policy' },
    ];

    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_SETUP:READ'],
      menu,
    })).toEqual(menu);
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_SETUP:MANAGE_POLICY'],
      menu,
    })).toEqual([]);
    expect(selectedMenuKey(menu, '/rules/attendance-policy/version-1'))
      .toBe('attendance-policies');
  });

  it('exposes WAVE-4 source and import routes only with their read capabilities', () => {
    const sourceMenu = [
      { key: 'attendance-sources-online', label: '在线来源', path: '/sources/online' },
      { key: 'attendance-sources-oa', label: 'OA 单据', path: '/sources/oa' },
      { key: 'attendance-source-jobs', label: '同步作业', path: '/sources/jobs' },
    ];
    const importMenu = [{
      key: 'attendance-punch-imports',
      label: '异构考勤 Excel',
      path: '/sources/attendance-excel',
    }];

    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_SOURCE:READ'],
      menu: [...sourceMenu, ...importMenu],
    })).toEqual(sourceMenu);
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_PUNCH_IMPORT:READ'],
      menu: [...sourceMenu, ...importMenu],
    })).toEqual(importMenu);
    expect(authorizedMenu({
      capabilities: [
        'ATTENDANCE_SOURCE:CONFIGURE',
        'ATTENDANCE_PUNCH_IMPORT:PUBLISH',
      ],
      menu: [...sourceMenu, ...importMenu],
    })).toEqual([]);
    expect(selectedMenuKey(
      importMenu,
      '/sources/attendance-excel/synthetic-batch',
    )).toBe('attendance-punch-imports');
  });

  it('exposes daily overtime and time-off daily query routes with query read', () => {
    const menu = [
      { key: 'attendance-query-leave-summary', label: '请假汇总', path: '/attendance/queries/leave-summary' },
      { key: 'attendance-query-overtime-daily', label: '加班日报', path: '/attendance/queries/overtime-daily' },
      { key: 'attendance-query-finance-overtime', label: '每日加班查询', path: '/attendance/queries/finance-overtime' },
      { key: 'attendance-query-overtime-fee-daily', label: '每日加班费查询', path: '/attendance/queries/overtime-fee-daily' },
      { key: 'attendance-query-overtime-voluntary-daily', label: '每日义务加班查询', path: '/attendance/queries/overtime-voluntary-daily' },
      { key: 'attendance-query-overtime-comp-daily', label: '每日调休查询', path: '/attendance/queries/overtime-comp-daily' },
      { key: 'attendance-query-absence-stat', label: '旷工统计表', path: '/attendance/queries/absence-stat' },
      { key: 'attendance-query-leave-stat', label: '请假统计表', path: '/attendance/queries/leave-stat' },
      { key: 'attendance-query-daily-journal', label: '考勤日报', path: '/attendance/queries/daily-journal' },
      { key: 'attendance-query-makeup', label: '补签', path: '/attendance/queries/makeup' },
      { key: 'attendance-query-time-off-daily', label: '调休日报', path: '/attendance/queries/time-off-daily' },
      { key: 'attendance-query-missed-punch-stat', label: '忘打卡统计表', path: '/attendance/queries/missed-punch-stat' },
      { key: 'attendance-query-annual-leave-stat', label: '年假统计表', path: '/attendance/queries/annual-leave-stat' },
      { key: 'attendance-query-time-off-stat', label: '调休统计表', path: '/attendance/queries/time-off-stat' },
      { key: 'paper-overtime', label: '纸质加班单', path: '/attendance/paper-overtime' },
    ];
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_REPORT_QUERY:READ'],
      menu,
    })).toEqual(menu.slice(0, 14));
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_REPORT_QUERY:READ', 'PAPER_OVERTIME:MANAGE'],
      menu,
    }).map((item) => item.path)).not.toContain('/attendance/paper-overtime');
  });

  it('exposes each WAVE-7 route only with its exact read capability', () => {
    const menu = [
      { key: 'workbench', label: '管理看板', path: '/workbench' },
      { key: 'attendance-screen', label: '考勤大屏', path: '/attendance/screen' },
      { key: 'attendance-reports', label: '统计报表', path: '/attendance/reports' },
      { key: 'self-today', label: '今日', path: '/me/today' },
      { key: 'self-records', label: '记录', path: '/me/records' },
      { key: 'self-leave', label: '假期', path: '/me/leave' },
      { key: 'self-feedback', label: '反馈', path: '/me/feedback' },
    ];
    const capabilities = [
      'ATTENDANCE_DASHBOARD:READ',
      'ATTENDANCE_REPORT:READ',
      'ATTENDANCE_SELF:READ',
      'LEAVE_SELF:READ',
      'ATTENDANCE_FEEDBACK:READ',
    ];

    expect(authorizedMenu({ capabilities, menu })).toEqual(menu);
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_REPORT:EXPORT_CREATE', 'ATTENDANCE_FEEDBACK:CREATE'],
      menu,
    })).toEqual([]);
  });

  it('accepts the shared workbench route for either dashboard scope without widening other routes', () => {
    const organizationWorkbench = [{
      key: 'workbench',
      label: '考勤工作台',
      path: '/workbench',
    }];
    const personalWorkbench = [{
      key: 'personal-workbench',
      label: '我的考勤工作台',
      path: '/workbench',
    }];

    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_DASHBOARD:READ'],
      menu: organizationWorkbench,
    })).toEqual(organizationWorkbench);
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_SELF:READ'],
      menu: personalWorkbench,
    })).toEqual(personalWorkbench);
    expect(authorizedMenu({
      capabilities: ['ATTENDANCE_REPORT:READ'],
      menu: personalWorkbench,
    })).toEqual([]);
  });

  it('rejects prototype-only and excluded WAVE-7 routes from a server menu', () => {
    expect(authorizedMenu({
      capabilities: [
        'ATTENDANCE_DASHBOARD:READ',
        'ATTENDANCE_REPORT:READ',
        'ATTENDANCE_SELF:READ',
        'PAYROLL:READ',
      ],
      menu: [
        { key: 'qa', label: '设计交付', path: '/qa/handoff' },
        { key: 'prototype-recalc', label: '旧重算入口', path: '/attendance/recalc' },
        { key: 'demo-person', label: '原型人员', path: '/people/employees/EMP-DEMO-001' },
        { key: 'excluded-module', label: '受排除模块', path: '/payroll' },
      ],
    })).toEqual([]);
  });
});

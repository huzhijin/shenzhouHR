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
});

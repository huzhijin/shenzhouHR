import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';

import {
  DAY_UPDATED_TOAST,
  detailEntries,
  EXCEPTION_TYPE_FILTER_OPTIONS,
  MATRIX_CROSS_MONTH_CONTENT,
  MATRIX_CROSS_MONTH_TITLE,
  matrixDayStatus,
  matrixDayStatusKey,
  rangeStaysInMonth,
  sheetColumns,
  sheetQueryRange,
  visibleExportColumns,
} from './QueryReportsPage';
import { defaultQueryPeriod, isWholeCalendarMonth } from './queryPeriod';

describe('QueryReportsPage presentation', () => {
  it('keeps work-hours drawer on Chinese business columns', () => {
    const entries = detailEntries('work-hours', {
      employeeNumber: 'SZST0048',
      employeeName: '张珍珍',
      department: '采购部',
      scheduledHours: 128,
      paidOvertimeHours: 1.5,
      leaveHours: 0,
      annualLeaveHours: 4.5,
      compensatoryOvertimeHours: 0,
      timeOffHours: 0,
      actualHours: 125,
      note: null,
      leftoverEnglish: 'nope',
    });
    expect(entries.map((entry) => entry.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '应出勤工时',
      '加班时数',
      '义务加班',
      '事假+病假+其他假期',
      '年假',
      '加班换调休',
      '实际调休',
      '个人实际出勤工时',
      '备注',
    ]);
    expect(entries.map((entry) => entry.key)).not.toContain('leftoverEnglish');
    expect(entries.map((entry) => entry.title).join(',')).not.toMatch(/annualLeaveHours|timeOffHours/);
  });

  it('formats overtime hours on a half-hour grid', () => {
    const twoAndAHalf = detailEntries('overtime', {
      employeeNumber: 'SZST0048',
      employeeName: '张珍珍',
      department: '采购部',
      leaveType: 'PAID',
      startAt: '2026-08-03T10:00:00Z',
      endAt: '2026-08-03T13:00:00Z',
      hours: 2.5,
      approvalState: 'APPROVED',
    });
    expect(twoAndAHalf.find((entry) => entry.key === 'hours')?.value).toBe('2.5');
    const zero = detailEntries('overtime', {
      employeeNumber: 'SZST0048',
      employeeName: '张珍珍',
      department: '采购部',
      leaveType: 'PAID',
      startAt: '2026-08-03T10:00:00Z',
      endAt: '2026-08-03T13:00:00Z',
      hours: 0,
      approvalState: 'APPROVED',
    });
    expect(zero.find((entry) => entry.key === 'hours')?.value).toBe('0');
  });

  it('keeps late drawer on Chinese minute columns', () => {
    const entries = detailEntries('late', {
      employeeNumber: 'SZST0008',
      employeeName: '刘锐',
      department: '服务中心',
      lateEvents: 0,
      lateMinutes: 0,
      penalizedLateMinutes: 0,
    });
    expect(entries.map((entry) => entry.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '迟到次数',
      '迟到分钟',
      '计罚迟到分钟',
    ]);
    expect(entries.map((entry) => entry.title).join(',')).not.toMatch(/lateMinutes|penalizedLateMinutes/);
  });

  it('shows department as the official hyphen-joined path', () => {
    const entries = detailEntries('exceptions', {
      employeeNumber: 'SZST0015',
      employeeName: '孙鹏',
      department: '服务中心-\u200B工程二部-\u200BRF-B组',
      businessDate: '2026-08-17',
      exceptionType: 'MISSING_ON_DUTY',
      details: '无上班卡，无下班卡',
      state: 'OPEN',
    });
    expect(entries.find((entry) => entry.key === 'department')?.value)
      .toBe('服务中心-工程二部-RF-B组');
  });

  it('lists makeup punches with 补签 time instead of leave columns', () => {
    expect(sheetColumns('makeup').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '单据类型',
      '补签时间',
      '审批状态',
      '详情',
    ]);
    expect(sheetColumns('leave').map((column) => column.title)).not.toContain('补签时间');
    expect(sheetColumns('overtime').map((column) => column.title)).not.toContain('补签时间');
  });

  it('lists finance overtime daily and journal columns', () => {
    expect(sheetColumns('overtime-daily').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '日期',
      '工作日加班',
      '周末加班',
      '节假日加班',
      '加班费',
      '转调休',
      '义务加班',
    ]);
    expect(sheetColumns('daily-journal').map((column) => column.title)).toEqual([
      '序号',
      '部门',
      '工号',
      '姓名',
      '日期',
      '班次',
      '上班',
      '下班',
      '迟到',
      '早退',
      '旷工',
      '请假',
      '加班',
      '备注',
      '改打卡',
    ]);
    expect(sheetColumns('overtime').map((column) => column.title)).not.toContain('工作日加班');
    expect(sheetColumns('finance-overtime').map((column) => column.title)).toEqual([
      '部门',
      '工号',
      '加班人',
      '平时加班',
      '周末加班',
      '节假日加班',
      '加班费',
      '转调休',
      '义务加班',
    ]);
    expect(sheetColumns('time-off-daily').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '日期',
      '调休小时',
      '假别',
    ]);
    expect(sheetColumns('finance-overtime').map((column) => column.title)).not.toEqual(
      sheetColumns('overtime-daily').map((column) => column.title),
    );
    expect(sheetColumns('overtime-daily', '加班费').map((column) => column.title))
      .toEqual([
        '工号',
        '姓名',
        '部门',
        '日期',
        '工作日加班',
        '周末加班',
        '节假日加班',
        '加班费',
      ]);
    expect(sheetColumns('overtime-daily', '转调休').map((column) => column.title))
      .not.toContain('加班费');
    expect(sheetColumns('overtime-daily', '义务加班').map((column) => column.title))
      .toEqual([
        '工号',
        '姓名',
        '部门',
        '日期',
        '工作日加班',
        '周末加班',
        '节假日加班',
        '义务加班',
      ]);
    expect(sheetColumns('finance-overtime', '加班费').map((column) => column.title))
      .not.toContain('转调休');
    expect(sheetColumns('finance-overtime', '转调休').map((column) => column.title))
      .toContain('转调休');
    expect(sheetColumns('finance-overtime', '义务加班').map((column) => column.title))
      .toContain('义务加班');
    expect(sheetColumns('finance-overtime', '义务加班').map((column) => column.title))
      .not.toContain('加班费');
    expect(sheetColumns('overtime-fee-daily').map((column) => column.title)).toEqual([
      '部门',
      '工号',
      '加班人',
      '平时加班',
      '周末加班',
      '节假日加班',
      '加班费',
    ]);
    expect(sheetColumns('overtime-voluntary-daily').map((column) => column.title))
      .toEqual([
        '部门',
        '工号',
        '加班人',
        '平时加班',
        '周末加班',
        '节假日加班',
        '义务加班',
      ]);
    expect(sheetColumns('overtime-comp-daily').map((column) => column.title))
      .toContain('转调休');
    expect(sheetColumns('overtime-comp-daily').map((column) => column.title))
      .not.toContain('加班费');
    expect(sheetColumns('matrix').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '异常天数',
      '本月明细',
    ]);
    expect(sheetColumns('leave-summary').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '假别',
      '合计小时',
      '单据数',
    ]);
    expect(sheetColumns('annual-leave-stat').map((column) => column.title)).toEqual([
      '序号',
      '一级部门',
      '二级部门',
      '姓名',
      '入职日期',
      '公司工龄',
      '公司外已证明工龄',
      '累计工龄（年）',
      '按累计工龄当年应休天数',
      '新员工计算年休假日历天数',
      '期初小时',
      '可休天数',
      '可休小时数',
      '1月已休',
      '2月已休',
      '3月已休',
      '4月已休',
      '5月已休',
      '6月已休',
      '7月已休',
      '8月已休',
      '9月已休',
      '10月已休',
      '11月已休',
      '12月已休',
    ]);
    expect(sheetColumns('time-off-stat').map((column) => column.title)).not.toContain(
      '按累计工龄当年应休天数',
    );
    expect(sheetColumns('time-off-stat').map((column) => column.title)).toContain('期初小时');
    expect(sheetColumns('missed-punch').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '考勤日期',
      '缺卡类型',
      '说明',
      '详情',
    ]);
    expect(sheetColumns('missed-punch-stat').map((column) => column.title)).toEqual([
      '序号',
      '部门',
      '姓名',
      '次数',
      '备注',
      '详情',
    ]);
    expect(sheetColumns('absence-stat').map((column) => column.title)).toEqual([
      '部门',
      '工号',
      '姓名',
      '合计旷工',
    ]);
    expect(sheetColumns('leave-stat').map((column) => column.title)).toEqual([
      '部门',
      '工号',
      '姓名',
      '合计请假',
    ]);
    expect(sheetColumns('leave').map((column) => column.title)).toContain('假别');
    expect(sheetColumns('leave-stat').map((column) => column.title)).not.toEqual(
      sheetColumns('leave').map((column) => column.title),
    );
  });

  it('paints outing days as 外出 instead of 缺卡', () => {
    const outingDay = {
      missingPunches: 2,
      badges: ['OUTING'],
      morning: { text: '外出', tone: 'OUTING' },
      afternoon: { text: '外出', tone: 'OUTING' },
    };
    expect(matrixDayStatusKey(outingDay)).toBe('out');
    expect(matrixDayStatus(outingDay)).toBe('外出');
    expect(matrixDayStatusKey({
      missingPunches: 2,
      morning: { text: '漏刷', tone: 'MISSING_PUNCH' },
      afternoon: { text: '漏刷', tone: 'MISSING_PUNCH' },
    })).toBe('missed');
  });

  it('keeps the hire-day toast and hides undeclared overtime from exception filters', () => {
    expect(DAY_UPDATED_TOAST).toBe('已更新该日考勤');
    expect(EXCEPTION_TYPE_FILTER_OPTIONS.map((option) => option.label)).toContain('加班异常');
    expect(EXCEPTION_TYPE_FILTER_OPTIONS.map((option) => option.label)).not.toContain('虚假加班');
    expect(EXCEPTION_TYPE_FILTER_OPTIONS.map((option) => option.label)).not.toContain('未报加班');
    expect(EXCEPTION_TYPE_FILTER_OPTIONS.map((option) => option.label)).not.toContain('长时在岗待审');
  });

  it('does not turn the note column into a second details button', () => {
    expect(sheetColumns('work-hours').filter((column) => column.key === 'action'))
      .toHaveLength(1);
    expect(sheetColumns('work-hours').some((column) => column.key === 'note')).toBe(true);
    expect(sheetColumns('late').some((column) => column.key === 'details')).toBe(false);
  });

  it('keeps 考勤明细 date ranges inside one calendar month', () => {
    expect(rangeStaysInMonth(dayjs('2026-08-01'), dayjs('2026-08-31'), '2026-08')).toBe(true);
    expect(rangeStaysInMonth(dayjs('2026-08-01'), dayjs('2026-09-01'), '2026-08')).toBe(false);
    expect(rangeStaysInMonth(dayjs('2026-07-31'), dayjs('2026-08-02'), '2026-08')).toBe(false);
    expect(MATRIX_CROSS_MONTH_TITLE).toBe('暂不支持跨月');
    expect(MATRIX_CROSS_MONTH_CONTENT).toContain('同一个月内');
    expect(MATRIX_CROSS_MONTH_TITLE).not.toContain('月份');
    expect(MATRIX_CROSS_MONTH_CONTENT).not.toContain('请先选月份');
  });

  it('drops action columns from query export headers', () => {
    expect(visibleExportColumns('leave').map((column) => column.title)).toEqual([
      '工号',
      '姓名',
      '部门',
      '假别',
      '开始时间',
      '结束时间',
      '小时',
      '审批状态',
    ]);
    expect(visibleExportColumns('daily-journal').some((column) => column.key === 'adjust')).toBe(false);
    expect(visibleExportColumns('matrix').map((column) => column.key)).not.toContain('action');
  });

  it('defaults early-month queries to last month and always sends a date range', () => {
    expect(defaultQueryPeriod(dayjs('2026-09-01')).format('YYYY-MM')).toBe('2026-08');
    expect(defaultQueryPeriod(dayjs('2026-09-08')).format('YYYY-MM')).toBe('2026-09');
    const august = dayjs('2026-08-01');
    expect(sheetQueryRange('exceptions', null, august).map((day) => day.format('YYYY-MM-DD')))
      .toEqual(['2026-08-01', '2026-08-31']);
    expect(sheetQueryRange('missed-punch-stat', null, august).map((day) => day.format('YYYY-MM-DD')))
      .toEqual(['2026-08-01', '2026-08-31']);
    expect(sheetQueryRange('annual-leave-stat', null, august).map((day) => day.format('YYYY-MM-DD')))
      .toEqual(['2026-01-01', '2026-12-31']);
    expect(sheetQueryRange('time-off-stat', null, dayjs('2026-01-01')).map((day) => day.format('YYYY-MM-DD')))
      .toEqual(['2026-01-01', '2026-12-31']);
    expect(sheetQueryRange(
      'leave',
      [dayjs('2026-07-10'), dayjs('2026-08-20')],
      august,
    ).map((day) => day.format('YYYY-MM-DD'))).toEqual(['2026-07-10', '2026-08-20']);
    expect(isWholeCalendarMonth('2026-08', '2026-08-01', '2026-08-31')).toBe(true);
    expect(isWholeCalendarMonth('2026-08', '2026-08-01', '2026-08-07')).toBe(false);
    expect(isWholeCalendarMonth('2026-08')).toBe(true);
  });
});

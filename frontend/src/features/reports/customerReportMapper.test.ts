import { describe, expect, it } from 'vitest';

import { reportFixture } from '../../test/fixtures/wave7ContractFixtures';
import type { ReportProjection, ReportRowProjection } from '../wave7/wave7Contracts';
import {
  attendanceCellTooltip,
  primaryAttendanceStatus,
  toAnnualLeaveRows,
  toAttendanceDetailRows,
  toAttendanceExceptionRows,
  toAttendanceRateRows,
  toLeaveRows,
  toOvertimeRows,
  toWorkHoursRows,
} from './customerReportMapper';
import type { AttendanceMonthMatrixProjection } from '../wave7/wave7Contracts';

describe('customer report mapper business-rule fields', () => {
  it('maps missing off-duty punch to 下班缺卡 with details', () => {
    const [row] = toAttendanceExceptionRows(projection({
      'business-date': '2026-08-17',
      'employee-number': 'SZST0056',
      'employee-name': '朱健',
      organization: 'IT部',
      'exception-type': 'MISSING_OFF_DUTY',
      'exception-severity': 'WARNING',
      'exception-state': 'OPEN',
      'exception-minutes': '0',
      'exception-details': '上班 08:24，无下班卡',
      'evidence-summary': '仅有上班卡',
    }));
    expect(row?.exceptionType).toBe('下班缺卡');
    expect(row?.details).toBe('上班 08:24，无下班卡');
  });

  it('maps negative annual leave and time-off balances as distinct types', () => {
    const [annual] = toAttendanceExceptionRows(projection({
      'business-date': '2026-08-01',
      'employee-number': 'SZJN0006',
      'employee-name': '江忠朗',
      organization: '上海晟州',
      'exception-type': 'NEGATIVE_ANNUAL_LEAVE_BALANCE',
      'exception-severity': 'ERROR',
      'exception-state': 'OPEN',
      'exception-minutes': '480',
      'exception-details': '年假余额 -1.00 天（-8.00 小时）',
      'evidence-summary': '年假余额 -1.00 天（-8.00 小时）',
    }));
    const [timeOff] = toAttendanceExceptionRows(projection({
      'business-date': '2026-08-01',
      'employee-number': 'SZST0641',
      'employee-name': '调休员工',
      organization: '质量中心',
      'exception-type': 'NEGATIVE_TIME_OFF_BALANCE',
      'exception-severity': 'ERROR',
      'exception-state': 'OPEN',
      'exception-minutes': '60',
      'exception-details': '调休余额 -0.13 天（-1.00 小时）',
      'evidence-summary': '调休余额 -0.13 天（-1.00 小时）',
    }));
    expect(annual?.exceptionType).toBe('年假余额为负');
    expect(timeOff?.exceptionType).toBe('调休余额为负');
  });

  it('renders the preserved sick-leave enum as a filterable business label', () => {
    const [row] = toLeaveRows(projection({
      'employee-name': '病假员工',
      organization: '测试部门',
      'document-type': 'SICK',
      'recognized-hours': '16.00',
      'approval-state': 'APPROVED',
    }));

    expect(row).toMatchObject({ type: '病假', hours: 16 });
  });

  it('maps overtime documents one row at a time including paper origin', () => {
    const rows = toOvertimeRows({
      ...reportFixture,
      columns: [],
      exportFieldAllowlist: [],
      rowCount: 4,
      rows: [
        {
          rowReference: 'ot-1',
          values: {
            'employee-number': '10012',
            'employee-name': '陈士庆',
            organization: '设备工程部',
            'document-type': 'COMPENSATORY',
            'document-start': '2026-08-18T09:40:00Z',
            'document-end': '2026-08-18T14:00:00Z',
            'recognized-hours': '2.50',
            'approval-state': 'APPROVED',
            'source-origin': 'PAPER',
          },
        },
        {
          rowReference: 'ot-2',
          values: {
            'employee-number': '10012',
            'employee-name': '陈士庆',
            organization: '设备工程部',
            'document-type': 'PAID',
            'document-start': '2026-08-20T09:40:00Z',
            'document-end': '2026-08-20T14:00:00Z',
            'recognized-hours': '2.50',
            'approval-state': 'APPROVED',
            'source-origin': 'OA',
          },
        },
        {
          rowReference: 'ot-3',
          values: {
            'employee-number': '10012',
            'employee-name': '陈士庆',
            organization: '设备工程部',
            'document-type': 'PAID',
            'document-start': '2026-08-23T09:40:00Z',
            'document-end': '2026-08-23T14:00:00Z',
            'recognized-hours': '3.00',
            'approval-state': 'APPROVED',
            'source-origin': 'OA',
          },
        },
        {
          rowReference: 'ot-4',
          values: {
            'employee-number': '10012',
            'employee-name': '陈士庆',
            organization: '设备工程部',
            'document-type': 'VOLUNTARY',
            'document-start': '2026-08-25T09:40:00Z',
            'document-end': '2026-08-25T14:00:00Z',
            'recognized-hours': '2.00',
            'approval-state': 'APPROVED',
            'source-origin': 'OA',
          },
        },
      ],
    });

    expect(rows).toHaveLength(4);
    expect(rows.map((row) => row.source)).toEqual(['纸质', 'OA', 'OA', 'OA']);
    expect(rows.map((row) => row.overtimeType)).toEqual([
      '调休',
      '加班费',
      '加班费',
      '义务加班',
    ]);
  });

  it('maps work-hours annual leave and time-off separately from other leave', () => {
    const [row] = toWorkHoursRows(projection({
      'employee-name': '工时员工',
      organization: '测试部门',
      'scheduled-hours': '160.00',
      'paid-overtime-hours': '8.00',
      'leave-hours': '3.50',
      'annual-leave-hours': '8.00',
      'compensatory-overtime-hours': '4.00',
      'time-off-hours': '4.50',
      'actual-work-hours': '156.00',
    }));

    expect(row).toMatchObject({
      plannedHours: 160,
      overtimeHours: 8,
      leaveHours: 3.5,
      annualLeaveHours: 8,
      exchangedHours: 4,
      usedTimeOffHours: 4.5,
      actualHours: 156,
    });
  });

  it('maps day-based attendance and the dedicated sick-leave column', () => {
    const [row] = toAttendanceRateRows(projection({
      'employee-name': '病假员工',
      organization: '测试部门',
      'scheduled-attendance-days': '22',
      'actual-attendance-days': '22',
      'sick-leave-days': '2',
      'attendance-rate': '100.00',
    }));

    expect(row).toMatchObject({
      scheduledDays: 22,
      actualDays: 22,
      sickLeaveDays: 2,
      rate: '100.00%',
      note: '按实际出勤天数 ÷ 应出勤天数',
    });
  });

  it('keeps the zero-scheduled-days rate as N/A without a percent suffix', () => {
    const [row] = toAttendanceRateRows(projection({
      'employee-name': '无排班员工',
      organization: '测试部门',
      'scheduled-attendance-days': '0',
      'actual-attendance-days': '0',
      'sick-leave-days': '0',
      'attendance-rate': 'N/A',
    }));

    expect(row?.rate).toBe('N/A');
  });

  it('does not paint 漏刷 over leave, rest days, or a complete punch pair', () => {
    expect(primaryAttendanceStatus(
      ['MISSING_PUNCH', 'PERSONAL_LEAVE'],
      null,
      null,
    )).toBe('personal-leave');
    expect(primaryAttendanceStatus(['REST_DAY', 'MISSING_PUNCH'], null, null))
      .toBe('rest-day');
    expect(primaryAttendanceStatus(
      ['REST_DAY', 'RECOGNIZED_OVERTIME'],
      '2026-08-01T00:00:00Z',
      '2026-08-01T08:00:00Z',
    )).toBe('overtime');
    expect(primaryAttendanceStatus(
      ['MISSING_PUNCH'],
      '2026-08-03T00:54:00Z',
      '2026-08-03T10:08:00Z',
    )).toBeUndefined();
    expect(primaryAttendanceStatus(
      ['MISSING_PUNCH'],
      '2026-08-04T00:55:00Z',
      '2026-08-04T00:55:00Z',
    )).toBe('missed');
    expect(primaryAttendanceStatus(['MISSING_PUNCH'], null, null)).toBe('missed');
    expect(primaryAttendanceStatus(
      ['EXEMPT_PUNCH', 'MISSING_PUNCH', 'ABSENCE'],
      null,
      null,
    )).toBeUndefined();
    expect(primaryAttendanceStatus(
      ['LATE', 'MISSING_PUNCH'],
      '2026-08-03T01:10:00Z',
      '2026-08-03T10:08:00Z',
    )).toBe('late');
  });

  it('builds a hover card from punches, shift and remaining badges', () => {
    expect(attendanceCellTooltip({
      day: 3,
      weekday: '一',
      shiftLabel: 'STANDARD_SEASONAL',
      primary: '08:54',
      secondary: '18:08',
      badges: ['MISSING_PUNCH', 'RECOGNIZED_OVERTIME'],
    })).toBe([
      '03日/一',
      '班次：STANDARD_SEASONAL',
      '上班：08:54',
      '下班：18:08',
      '状态：加班',
    ].join('\n'));
  });

  it('labels standing exemption without punches as 正常出勤', () => {
    expect(attendanceCellTooltip({
      day: 10,
      weekday: '一',
      shiftLabel: 'STANDARD_SEASONAL',
      primary: '',
      secondary: '',
      badges: ['EXEMPT_PUNCH'],
    })).toContain('状态：正常出勤');
    expect(attendanceCellTooltip({
      day: 10,
      weekday: '一',
      shiftLabel: 'STANDARD_SEASONAL',
      primary: '',
      secondary: '',
      badges: ['EXEMPT_PUNCH'],
    })).not.toContain('无打卡');
  });

  it('does not label a complete punch pair as 漏刷 or 旷工', () => {
    expect(attendanceCellTooltip({
      day: 7,
      weekday: '五',
      shiftLabel: 'STANDARD_SEASONAL',
      primary: '08:00',
      secondary: '18:02',
      badges: ['MISSING_PUNCH', 'ABSENCE'],
    })).toBe([
      '07日/五',
      '班次：STANDARD_SEASONAL',
      '上班：08:00',
      '下班：18:02',
      '状态：正常出勤',
    ].join('\n'));
  });

  it('uses the same department path on work-hours, leave, annual leave and the matrix', () => {
    const path = '服务中心-\u200B工程二部-\u200BRF-B组';
    const identity = {
      'employee-name': '陈思远',
      organization: path,
    };
    expect(toWorkHoursRows(projection({
      ...identity,
      'scheduled-hours': '176',
      'paid-overtime-hours': '0',
      'leave-hours': '0',
      'actual-work-hours': '176',
    }))[0]?.department).toBe(path);
    expect(toLeaveRows(projection({
      ...identity,
      'document-type': 'ANNUAL',
      'recognized-hours': '8',
      'approval-state': 'APPROVED',
    }))[0]?.department).toBe(path);
    expect(toAnnualLeaveRows(projection({
      ...identity,
      'account-type': 'ANNUAL_LEAVE',
      'balance-hours': '80',
      'equivalent-days': '10',
    }))[0]?.department).toBe(path);
    const [matrixRow] = toAttendanceDetailRows(matrixProjection());
    expect(matrixRow?.department).toBe('技术支持中心-现场服务部-武汉产品服务组');
  });

  it('maps a live month-matrix cell onto the customer colour and tooltip', () => {
    const [row] = toAttendanceDetailRows(matrixProjection());
    expect(row?.department).toBe('技术支持中心-现场服务部-武汉产品服务组');
    expect(row?.days[0]).toMatchObject({
      day: 1,
      weekday: '六',
      status: 'rest-day',
    });
    expect(row?.days[0]?.note).toContain('休息日');
    expect(row?.days[1]).toMatchObject({
      primary: '08:54',
      secondary: '18:08',
      status: undefined,
    });
    expect(row?.days[1]?.note).toContain('上班：08:54');
    expect(row?.days[1]?.note).toContain('正常出勤');
    expect(row?.days[1]?.note).not.toContain('漏刷');
  });

  it('maps half-day leave onto two independent bands', () => {
    const [row] = toAttendanceDetailRows(slottedProjection({
      morning: { text: '08:14', tone: null, punchAt: '2026-07-22T00:14:00Z' },
      afternoon: { text: '年假', tone: 'ANNUAL_LEAVE', punchAt: null },
      merged: false,
      hover: '22日/三\n上班：08:14\n下班：年假\n年假 4.5小时',
    }));
    expect(row?.days[0]).toMatchObject({
      primary: '08:14',
      secondary: '年假',
      primaryStatus: undefined,
      secondaryStatus: 'annual-leave',
      merged: false,
    });
    expect(row?.days[0]?.note).toContain('4.5小时');
    expect(row?.days[0]?.secondary).not.toContain('4.5');
  });

  it('maps a full-day leave to one merged label', () => {
    const [row] = toAttendanceDetailRows(slottedProjection({
      morning: { text: '年假', tone: 'ANNUAL_LEAVE', punchAt: null },
      afternoon: { text: '年假', tone: 'ANNUAL_LEAVE', punchAt: null },
      merged: true,
      hover: '27日/一\n状态：年假',
    }));
    expect(row?.days[0]).toMatchObject({
      merged: true,
      mergedLabel: '年假',
      mergedStatus: 'annual-leave',
      primary: '年假',
      secondary: '年假',
    });
    expect(row?.days[0]?.note).not.toContain('3.5小时');
  });

  it('paints paternity leave with its own legend tone', () => {
    const [row] = toAttendanceDetailRows(slottedProjection({
      morning: { text: '陪产假', tone: 'PATERNITY_LEAVE', punchAt: null },
      afternoon: { text: '陪产假', tone: 'PATERNITY_LEAVE', punchAt: null },
      merged: true,
      hover: '27日/一\n状态：陪产假',
    }));
    expect(row?.days[0]?.mergedLabel).toBe('陪产假');
    expect(row?.days[0]?.mergedStatus).toBe('paternity-leave');
  });

  it('keeps overtime times on the grid and names overtime in hover', () => {
    const [row] = toAttendanceDetailRows(slottedProjection({
      morning: { text: '08:26', tone: 'RECOGNIZED_OVERTIME', punchAt: '2026-07-01T00:26:00Z' },
      afternoon: { text: '21:38', tone: 'RECOGNIZED_OVERTIME', punchAt: '2026-07-01T13:38:00Z' },
      merged: false,
      hover: '01日/三\n上班：08:26\n下班：21:38\n加班',
    }));
    expect(row?.days[0]).toMatchObject({
      primary: '08:26',
      secondary: '21:38',
      primaryStatus: 'overtime',
      secondaryStatus: 'overtime',
      merged: false,
    });
    expect(row?.days[0]?.note).toContain('加班');
    expect(row?.days[0]?.secondary).not.toContain('加班');
  });

  it('maps a missing punch slot as 漏刷', () => {
    const [row] = toAttendanceDetailRows(slottedProjection({
      morning: { text: '08:21', tone: null, punchAt: '2026-07-03T00:21:00Z' },
      afternoon: { text: '漏刷', tone: 'MISSING_PUNCH', punchAt: null },
      merged: false,
      hover: '03日/五\n上班：08:21\n下班：漏刷',
    }));
    expect(row?.days[0]?.secondary).toBe('漏刷');
    expect(row?.days[0]?.secondaryStatus).toBe('missed');
  });
});

function slottedProjection(day: {
  morning: { text: string; tone: string | null; punchAt: string | null };
  afternoon: { text: string; tone: string | null; punchAt: string | null };
  merged: boolean;
  hover: string;
}): AttendanceMonthMatrixProjection {
  const base = matrixProjection();
  return {
    ...base,
    formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V2',
    dates: ['2026-07-22'],
    rows: [{
      ...base.rows[0]!,
      days: [{
        date: '2026-07-22',
        organizationName: '制造中心',
        shiftLabel: '扬州总部班次',
        firstPunchAt: day.morning.punchAt,
        lastPunchAt: day.afternoon.punchAt,
        badges: [],
        ...day,
      }],
    }],
  };
}

function matrixProjection(): AttendanceMonthMatrixProjection {
  return {
    kind: 'ATTENDANCE_MONTH_MATRIX',
    metadata: {
      ...reportFixture.metadata,
      projectionVersion: 'LIVE-test',
      periodLabel: '2026-08',
      periodState: 'OPEN',
      scope: {
        type: 'COMPANY',
        reference: 'company-a',
        label: '江苏神州',
      },
      allowedActions: [],
    },
    queryFingerprint: 'a'.repeat(64),
    formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V1',
    filters: {
      period: '2026-08',
      scopeReference: 'company-a',
      companyId: 'company-a',
      organizationId: null,
      employeeId: null,
    },
    dates: ['2026-08-01', '2026-08-03'],
    employeeCount: 1,
    page: 0,
    size: 1,
    totalPages: 1,
    rows: [{
      employeeId: 'employee-a',
      employeeNumber: 'SZST0614',
      employeeName: '徐锦豪',
      organizationId: 'org-a',
      organizationName: '技术支持中心-现场服务部-武汉产品服务组',
      days: [
        {
          date: '2026-08-01',
          organizationName: '技术支持中心-现场服务部-武汉产品服务组',
          shiftLabel: '无班次',
          firstPunchAt: null,
          lastPunchAt: null,
          badges: ['REST_DAY'],
        },
        {
          date: '2026-08-03',
          organizationName: '技术支持中心-现场服务部-武汉产品服务组',
          shiftLabel: 'STANDARD_SEASONAL',
          firstPunchAt: '2026-08-03T00:54:00Z',
          lastPunchAt: '2026-08-03T10:08:00Z',
          badges: ['MISSING_PUNCH'],
        },
      ],
    }],
  };
}

function projection(
  values: ReportRowProjection['values'],
): ReportProjection {
  return {
    ...reportFixture,
    columns: [],
    exportFieldAllowlist: [],
    rowCount: 1,
    rows: [{ rowReference: 'test-row', values }],
  };
}

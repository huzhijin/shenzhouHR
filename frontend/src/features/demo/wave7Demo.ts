import type {
  AttendanceRecordsProjection,
  DashboardProjection,
  FeedbackProjection,
  LeaveProjection,
  ReportProjection,
  TodayProjection,
  Wave7AllowedAction,
  Wave7PeriodState,
  Wave7ProjectionMetadata,
  Wave7Scope,
} from '../wave7/wave7Contracts';

const REPORTING_PERIOD = '2026-06';

export function createDemoTodayProjection(): TodayProjection {
  const businessDate = currentShanghaiDate();
  return {
    kind: 'TODAY',
    metadata: metadata({
      projectionVersion: `DEMO-TODAY-${businessDate}-V1`,
      periodLabel: businessDate.slice(0, 7),
      periodState: 'OPEN',
      scope: selfScope(),
      allowedActions: [],
    }),
    businessDate,
    shiftLabel: '标准班 · 08:30–17:30',
    firstEffectivePunch: '08:24',
    lastEffectivePunch: '18:17',
    attendanceStatus: '正常',
    confirmedMinutes: 480,
    issueLabels: ['当日数据为演示合成数据'],
  };
}

export function createDemoAttendanceRecordsProjection(): AttendanceRecordsProjection {
  return {
    kind: 'RECORDS',
    metadata: metadata({
      projectionVersion: 'DEMO-SELF-ATTENDANCE-2026-06-V1',
      periodLabel: REPORTING_PERIOD,
      periodState: 'CLOSED',
      scope: selfScope(),
      allowedActions: [],
    }),
    summary: {
      scheduledMinutes: 10_080,
      confirmedMinutes: 9_990,
      recognizedOvertimeMinutes: 255,
      leaveMinutes: 240,
    },
    records: [
      attendanceRecord('2026-06-01', 480, '正常'),
      attendanceRecord('2026-06-02', 480, '正常'),
      attendanceRecord('2026-06-03', 450, '迟到', ['迟到 3 分钟']),
      attendanceRecord('2026-06-04', 480, '正常'),
      attendanceRecord('2026-06-05', 480, '事假', ['事假 4 小时']),
      attendanceRecord('2026-06-08', 510, '正常', ['认可加班 0.5 小时']),
      attendanceRecord('2026-06-09', 480, '已补签', ['上班卡已补签']),
      attendanceRecord('2026-06-10', 480, '正常'),
    ],
  };
}

export function createDemoLeaveProjection(): LeaveProjection {
  return {
    kind: 'LEAVE',
    metadata: metadata({
      projectionVersion: 'DEMO-TIME-ACCOUNT-2026-V1',
      periodLabel: REPORTING_PERIOD,
      periodState: 'CLOSED',
      scope: selfScope(),
      allowedActions: [],
    }),
    accounts: [
      {
        accountReference: 'demo-account:annual-leave:2026',
        label: '2026 年年假',
        unit: 'HOURS',
        grantedHours: 80,
        openingHours: 0,
        usedHours: 16,
        remainingHours: 64,
        expiresOn: '2026-12-31',
        equivalentDays: 8,
        ledgerVersion: 'DEMO-ANNUAL-LEDGER-2026-V1',
      },
      {
        accountReference: 'demo-account:comp-time:2026',
        label: '调休账户',
        unit: 'HOURS',
        grantedHours: 18.5,
        openingHours: 0,
        usedHours: 8,
        remainingHours: 10.5,
        expiresOn: '2026-12-31',
        equivalentDays: 1.31,
        ledgerVersion: 'DEMO-COMP-LEDGER-2026-V1',
      },
    ],
  };
}

export function createDemoFeedbackProjection(): FeedbackProjection {
  return {
    kind: 'FEEDBACK',
    metadata: metadata({
      projectionVersion: 'DEMO-FEEDBACK-2026-06-V1',
      periodLabel: REPORTING_PERIOD,
      periodState: 'CLOSED',
      scope: selfScope(),
      allowedActions: ['FEEDBACK_CREATE'],
    }),
    items: [
      {
        feedbackReference: 'DEMO-FEEDBACK-20260609-001',
        attendanceDate: '2026-06-09',
        problemTypeLabel: '忘打卡',
        content: '6 月 9 日上班卡未记录，已提交补签单。',
        state: 'RESOLVED',
        replyText: '补签单已审核通过，考勤结果已重新计算。',
        linkedAdjustmentResult: '6 月 9 日状态由“缺卡”调整为“已补签”',
        progress: [
          { sequence: 1, label: '员工提交', occurredAt: '2026-06-09T10:05:00+08:00' },
          { sequence: 2, label: '主管审核', occurredAt: '2026-06-09T13:20:00+08:00' },
          { sequence: 3, label: '结果生效', occurredAt: '2026-06-09T13:25:00+08:00' },
        ],
      },
    ],
  };
}

export function createDemoDashboardProjection(): DashboardProjection {
  return {
    kind: 'DASHBOARD',
    metadata: metadata({
      projectionVersion: 'DEMO-DASHBOARD-2026-06-V1',
      periodLabel: REPORTING_PERIOD,
      periodState: 'CLOSED',
      scope: companyScope(),
      allowedActions: ['DASHBOARD_DRILL_DOWN'],
    }),
    title: '2026 年 06 月考勤管理工作台',
    metrics: [
      metric('attendance-rate', '出勤率', '98.6%', 'demo-report:attendance-rate'),
      metric('exception-rate', '考勤异常率', '1.8%', 'demo-report:exceptions'),
      metric('confirmed-work', '确认出勤工时', '4,792.5 小时'),
      metric('recognized-overtime', '认可加班', '181.5 小时', 'demo-report:overtime'),
      metric('leave', '请假工时', '167.5 小时', 'demo-report:leave'),
      metric('unsettled-periods', '未月结期间', '0'),
      metric('freshness', '数据状态', '刚刚更新'),
    ],
  };
}

export function createDemoReportProjection(): ReportProjection {
  const rows: ReportProjection['rows'] = [
    reportRow('DEMO-DEPT-01', '研发一部', 1_344, 1_326.5, 2, 1, 1, 8, 42.5, 16),
    reportRow('DEMO-DEPT-02', '研发二部', 1_176, 1_168, 1, 0, 2, 0, 38.5, 24),
    reportRow('DEMO-DEPT-03', '生产运营部', 1_512, 1_493, 3, 2, 1, 8, 80, 87.5),
    reportRow('DEMO-DEPT-04', '职能管理部', 840, 805, 1, 0, 1, 0, 20.5, 40),
  ];
  return {
    kind: 'REPORT',
    metadata: metadata({
      projectionVersion: 'DEMO-REPORT-2026-06-V1',
      periodLabel: REPORTING_PERIOD,
      periodState: 'CLOSED',
      scope: companyScope(),
      allowedActions: [
        'REPORT_DRILL_DOWN',
        'REPORT_EXPORT_CREATE',
        'REPORT_EXPORT_DOWNLOAD',
      ],
    }),
    reportTitle: '2026 年 06 月部门考勤统计汇总',
    queryFingerprint: 'demo-query:company:2026-06:closed-v1',
    filters: {
      period: REPORTING_PERIOD,
      scopeReference: companyScope().reference,
      status: '已月结',
    },
    columns: [
      { key: 'scope', label: '部门' },
      { key: 'scheduled-hours', label: '应出勤工时' },
      { key: 'confirmed-hours', label: '实际出勤工时' },
      { key: 'late-count', label: '迟到次数' },
      { key: 'early-count', label: '早退次数' },
      { key: 'missing-count', label: '忘打卡次数' },
      { key: 'absence-hours', label: '旷工小时' },
      { key: 'recognized-overtime-hours', label: '加班时数' },
      { key: 'leave-hours', label: '请假小时' },
    ],
    exportFieldAllowlist: [
      'scope',
      'scheduled-hours',
      'confirmed-hours',
      'late-count',
      'early-count',
      'missing-count',
      'absence-hours',
      'recognized-overtime-hours',
      'leave-hours',
    ],
    rowCount: rows.length,
    rows,
  };
}

function attendanceRecord(
  businessDate: string,
  confirmedMinutes: number,
  statusLabel: string,
  issueLabels: string[] = [],
): AttendanceRecordsProjection['records'][number] {
  return {
    businessDate,
    shiftLabel: '标准班 · 08:30–17:30',
    confirmedMinutes,
    statusLabel,
    issueLabels,
    explanationReference: `demo-explanation:${businessDate}`,
  };
}

function reportRow(
  reference: string,
  scope: string,
  scheduledHours: number,
  confirmedHours: number,
  lateCount: number,
  earlyCount: number,
  missingCount: number,
  absenceHours: number,
  overtimeHours: number,
  leaveHours: number,
): ReportProjection['rows'][number] {
  return {
    rowReference: reference,
    values: {
      scope,
      'scheduled-hours': scheduledHours,
      'confirmed-hours': confirmedHours,
      'late-count': lateCount,
      'early-count': earlyCount,
      'missing-count': missingCount,
      'absence-hours': absenceHours,
      'recognized-overtime-hours': overtimeHours,
      'leave-hours': leaveHours,
    },
    drillDownReference: `demo-report-detail:${reference}`,
  };
}

function metric(
  key: DashboardProjection['metrics'][number]['key'],
  label: string,
  displayValue: string,
  drillDownReference?: string,
): DashboardProjection['metrics'][number] {
  return {
    key,
    label,
    displayValue,
    suppressed: false,
    drillDownReference,
  };
}

function metadata(options: {
  projectionVersion: string;
  periodLabel: string;
  periodState: Wave7PeriodState;
  scope: Wave7Scope;
  allowedActions: Wave7AllowedAction[];
}): Wave7ProjectionMetadata {
  return {
    projectionVersion: options.projectionVersion,
    sourceVersions: [
      'DEMO-ATTENDANCE-CLOSE-2026-06-V1',
      'DEMO-TIME-LEDGER-2026-V1',
    ],
    dataAsOf: new Date().toISOString(),
    timeZone: 'Asia/Shanghai',
    periodLabel: options.periodLabel,
    periodState: options.periodState,
    scope: options.scope,
    allowedActions: options.allowedActions,
  };
}

function selfScope(): Wave7Scope {
  return {
    type: 'SELF',
    reference: 'demo-scope:self:demo.admin',
    label: '演示管理员本人',
  };
}

function companyScope(): Wave7Scope {
  return {
    type: 'COMPANY',
    reference: 'demo-scope:company:shenzhou',
    label: '江苏神州半导体（演示）',
  };
}

function currentShanghaiDate(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

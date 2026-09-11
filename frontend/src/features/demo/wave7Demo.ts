import type {
  AttendanceRecordsProjection,
  FeedbackProjection,
  LeaveProjection,
  LiveDashboardProjection,
  ReportProjection,
  SelfAttendanceDashboardProjection,
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

export function createDemoSelfAttendanceDashboardProjection(
): SelfAttendanceDashboardProjection {
  const businessDate = currentShanghaiDate();
  const periodLabel = businessDate.slice(0, 7);
  const trendDates = recentPeriodDates(businessDate, 7);
  const previousDate = trendDates[Math.max(0, trendDates.length - 2)]
    ?? businessDate;
  return {
    kind: 'SELF_ATTENDANCE_DASHBOARD',
    metadata: {
      projectionVersion: `DEMO-SELF-DASHBOARD-${businessDate}-V1`,
      sourceVersions: [
        `DEMO-SELF-ATTENDANCE-${periodLabel}-V1`,
        `DEMO-SELF-EXCEPTION-${businessDate}-V1`,
      ],
      dataAsOf: new Date().toISOString(),
      timeZone: 'Asia/Shanghai',
      periodLabel,
      periodState: 'OPEN',
      scope: {
        type: 'SELF',
        reference: 'current-principal',
        label: '本人',
      },
    },
    businessDate,
    summary: {
      scheduledMinutes: trendDates.length * 480,
      confirmedMinutes: Math.max(0, trendDates.length * 480 - 45),
      recognizedOvertimeMinutes: 75,
      leaveMinutes: 240,
      unresolvedExceptionCount: 2,
    },
    dailyTrend: trendDates.map((date, index) => ({
      businessDate: date,
      scheduledMinutes: 480,
      confirmedMinutes: index === trendDates.length - 1 ? 435 : 480,
      recognizedOvertimeMinutes: index === trendDates.length - 3 ? 75 : 0,
      leaveMinutes: index === trendDates.length - 4 ? 240 : 0,
      issueCount: index >= trendDates.length - 2 ? 1 : 0,
    })),
    today: {
      shiftLabel: '标准班 · 08:30–17:30',
      firstPunchAt: `${businessDate}T00:42:00Z`,
      lastPunchAt: null,
      statusLabel: '存在未解决异常',
      confirmedMinutes: 435,
      issueLabels: ['迟到待确认'],
    },
    exceptionTypeDistribution: [
      { type: 'LATE', count: 1 },
      { type: 'MISSING_PUNCH', count: 1 },
    ],
    recentExceptions: [
      {
        businessDate,
        type: 'LATE',
        severity: 'WARNING',
        state: 'PENDING_REVIEW',
        minutes: 12,
        safeEvidenceSummary: '首个有效打卡晚于班次开始时间，等待复核。',
      },
      {
        businessDate: previousDate,
        type: 'MISSING_PUNCH',
        severity: 'INFO',
        state: 'PENDING_EVIDENCE',
        minutes: 0,
        safeEvidenceSummary: '下班打卡记录缺失，可补充本人考勤凭证。',
      },
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

export function createDemoDashboardProjection(): LiveDashboardProjection {
  const businessDate = currentShanghaiDate();
  const period = businessDate.slice(0, 7);
  const trendDates = recentPeriodDates(businessDate, 7);
  const trendCounts = [5, 4, 6, 3, 8, 5, 7].slice(-trendDates.length);
  const trendBlocking = [1, 1, 2, 0, 3, 1, 3].slice(-trendDates.length);
  const trendAffected = [5, 4, 5, 3, 7, 5, 6].slice(-trendDates.length);
  return {
    kind: 'DASHBOARD',
    metadata: metadata({
      projectionVersion: `DEMO-DASHBOARD-${businessDate}-V1`,
      periodLabel: period,
      periodState: 'OPEN',
      scope: companyScope(),
      allowedActions: ['DASHBOARD_DRILL_DOWN'],
    }),
    title: '今日异常考勤',
    businessDate,
    selectedCompanyId: '30000000-0000-0000-0000-000000000001',
    summary: {
      unresolvedCount: 7,
      affectedEmployeeCount: 6,
      blockingCount: 3,
    },
    analytics: {
      dailyTrend: trendDates.map((date, index) => ({
        businessDate: date,
        exceptionCount: trendCounts[index]!,
        blockingCount: trendBlocking[index]!,
        affectedEmployeeCount: trendAffected[index]!,
      })),
      severityDistribution: [
        { severity: 'INFO', count: 1 },
        { severity: 'WARNING', count: 3 },
        { severity: 'ERROR', count: 3 },
      ],
      typeDistribution: [
        { exceptionType: 'MISSING_PUNCH_OVERDUE', count: 3 },
        { exceptionType: 'LATE', count: 2 },
        { exceptionType: 'ABSENCE', count: 1 },
        { exceptionType: 'EVIDENCE_CONFLICT', count: 1 },
      ],
      organizationRanking: [
        {
          organizationName: '制造一部',
          exceptionCount: 4,
          blockingCount: 2,
        },
        {
          organizationName: '研发一部',
          exceptionCount: 2,
          blockingCount: 1,
        },
        {
          organizationName: '人力资源部',
          exceptionCount: 1,
          blockingCount: 0,
        },
      ],
    },
    exceptions: [
      dashboardException(
        'DEMO-EXCEPTION-001',
        'SZ001',
        '张明',
        '制造一部',
        businessDate,
        'MISSING_PUNCH_OVERDUE',
        'ERROR',
        'PENDING_REVIEW',
        480,
        '计划工作段缺少下班有效打卡',
      ),
      dashboardException(
        'DEMO-EXCEPTION-002',
        'SZ018',
        '李悦',
        '研发一部',
        businessDate,
        'EVIDENCE_CONFLICT',
        'ERROR',
        'PENDING_EVIDENCE',
        0,
        'OA 单据时间与原始打卡证据存在冲突',
      ),
      dashboardException(
        'DEMO-EXCEPTION-003',
        'SZ026',
        '周程',
        '制造一部',
        businessDate,
        'ABSENCE',
        'ERROR',
        'PENDING_REVIEW',
        480,
        '计划工作段未匹配到打卡或有效业务单据',
      ),
      dashboardException(
        'DEMO-EXCEPTION-004',
        'SZ031',
        '王敏',
        '制造一部',
        businessDate,
        'MISSING_PUNCH_OVERDUE',
        'WARNING',
        'OPEN',
        0,
        '上班有效打卡缺失，补正时限已超过',
      ),
      dashboardException(
        'DEMO-EXCEPTION-005',
        'SZ042',
        '陈辉',
        '研发一部',
        businessDate,
        'LATE',
        'WARNING',
        'OPEN',
        18,
        '首次有效打卡晚于计划开始时间 18 分钟',
      ),
      dashboardException(
        'DEMO-EXCEPTION-006',
        'SZ057',
        '赵宁',
        '制造一部',
        businessDate,
        'LATE',
        'WARNING',
        'OPEN',
        11,
        '首次有效打卡晚于计划开始时间 11 分钟',
      ),
      dashboardException(
        'DEMO-EXCEPTION-007',
        'SZ074',
        '钱晓',
        '人力资源部',
        businessDate,
        'MISSING_PUNCH_OVERDUE',
        'INFO',
        'PENDING_EVIDENCE',
        0,
        '补签单已提交，等待证据同步',
      ),
    ],
    companies: [{
      companyId: '30000000-0000-0000-0000-000000000001',
      companyName: '江苏神州半导体科技股份有限公司',
    }],
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
    metadata: {
      ...metadata({
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
      scope: companyScope(),
    },
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
  key: LiveDashboardProjection['metrics'][number]['key'],
  label: string,
  displayValue: string,
  drillDownReference?: string,
): LiveDashboardProjection['metrics'][number] {
  return {
    key,
    label,
    displayValue,
    suppressed: false,
    drillDownReference,
  };
}

function dashboardException(
  exceptionReference: string,
  employeeNumber: string,
  employeeName: string,
  organizationName: string,
  businessDate: string,
  exceptionType: string,
  severity: LiveDashboardProjection['exceptions'][number]['severity'],
  state: LiveDashboardProjection['exceptions'][number]['state'],
  exceptionMinutes: number,
  evidenceSummary: string,
): LiveDashboardProjection['exceptions'][number] {
  return {
    exceptionReference,
    employeeNumber,
    employeeName,
    organizationName,
    businessDate,
    exceptionType,
    severity,
    state,
    exceptionMinutes,
    evidenceSummary,
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

function companyScope(): Wave7Scope & { type: 'COMPANY' } {
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

function recentPeriodDates(
  businessDate: string,
  requestedDays: number,
): string[] {
  const period = businessDate.slice(0, 7);
  const end = new Date(`${businessDate}T00:00:00Z`);
  const dates: string[] = [];
  for (let offset = requestedDays - 1; offset >= 0; offset -= 1) {
    const date = new Date(end);
    date.setUTCDate(end.getUTCDate() - offset);
    const value = date.toISOString().slice(0, 10);
    if (value.startsWith(period)) {
      dates.push(value);
    }
  }
  return dates;
}

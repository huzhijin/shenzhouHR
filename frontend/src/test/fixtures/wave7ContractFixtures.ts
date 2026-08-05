import type {
  AttendanceRecordsProjection,
  DashboardProjection,
  FeedbackProjection,
  LeaveProjection,
  ReportExportProjection,
  ReportProjection,
  SelfAttendanceDashboardProjection,
  TodayProjection,
  Wave7ProjectionMetadata,
} from '../../features/wave7/wave7Contracts';
import type { Wave7ProjectionGateway } from '../../features/wave7/wave7Gateway';

const selfMetadata: Wave7ProjectionMetadata = {
  projectionVersion: 'SELF-2026-07-V3',
  sourceVersions: ['ATT-CLOSE-2026-07-V3', 'TIME-LEDGER-2026-07-V2'],
  dataAsOf: '2026-07-28T09:30:00+08:00',
  timeZone: 'Asia/Shanghai',
  periodLabel: '2026-07',
  periodState: 'FROZEN',
  scope: {
    type: 'SELF',
    reference: 'scope:self:synthetic',
    label: '本人',
  },
  allowedActions: [],
};

export const todayFixture: TodayProjection = {
  kind: 'TODAY',
  metadata: {
    ...selfMetadata,
    projectionVersion: 'SELF-TODAY-2026-07-28-V1',
    periodState: 'OPEN',
  },
  businessDate: '2026-07-28',
  shiftLabel: '合成日班 · 08:30–17:30',
  firstEffectivePunch: '08:27',
  lastEffectivePunch: '17:36',
  attendanceStatus: '暂算正常',
  confirmedMinutes: 480,
  issueLabels: [],
};

export const selfDashboardFixture: SelfAttendanceDashboardProjection = {
  kind: 'SELF_ATTENDANCE_DASHBOARD',
  metadata: {
    projectionVersion: 'SELF-DASHBOARD-2026-07-28-V1',
    sourceVersions: ['SELF-ATTENDANCE-2026-07-28-V1'],
    dataAsOf: '2026-07-28T01:30:00Z',
    timeZone: 'Asia/Shanghai',
    periodLabel: '2026-07',
    periodState: 'OPEN',
    scope: {
      type: 'SELF',
      reference: 'current-principal',
      label: '本人',
    },
  },
  businessDate: '2026-07-28',
  summary: {
    scheduledMinutes: 960,
    confirmedMinutes: 930,
    recognizedOvertimeMinutes: 30,
    leaveMinutes: 0,
    unresolvedExceptionCount: 1,
  },
  dailyTrend: [{
    businessDate: '2026-07-28',
    scheduledMinutes: 480,
    confirmedMinutes: 450,
    recognizedOvertimeMinutes: 0,
    leaveMinutes: 0,
    issueCount: 1,
  }],
  today: {
    shiftLabel: '合成日班',
    firstPunchAt: '2026-07-28T00:42:00Z',
    lastPunchAt: null,
    statusLabel: '存在未解决异常',
    confirmedMinutes: 450,
    issueLabels: ['迟到待确认'],
  },
  exceptionTypeDistribution: [{ type: 'LATE', count: 1 }],
  recentExceptions: [{
    businessDate: '2026-07-28',
    type: 'LATE',
    severity: 'WARNING',
    state: 'PENDING_REVIEW',
    minutes: 12,
    safeEvidenceSummary: '首个有效打卡晚于班次开始时间。',
  }],
};

export const recordsFixture: AttendanceRecordsProjection = {
  kind: 'RECORDS',
  metadata: selfMetadata,
  summary: {
    scheduledMinutes: 10_080,
    confirmedMinutes: 9_960,
    recognizedOvertimeMinutes: 330,
    leaveMinutes: 120,
  },
  records: [
    {
      businessDate: '2026-07-21',
      shiftLabel: '合成日班',
      confirmedMinutes: 480,
      statusLabel: '正常',
      issueLabels: [],
      explanationReference: 'explain:synthetic:0721',
    },
    {
      businessDate: '2026-07-22',
      shiftLabel: '合成日班',
      confirmedMinutes: 450,
      statusLabel: '已解决',
      issueLabels: ['缺卡已补正'],
      explanationReference: 'explain:synthetic:0722',
    },
  ],
};

export const leaveFixture: LeaveProjection = {
  kind: 'LEAVE',
  metadata: selfMetadata,
  accounts: [
    {
      accountReference: 'account:synthetic:annual',
      label: '年假',
      unit: 'HOURS',
      grantedHours: 40,
      openingHours: 8,
      usedHours: 16,
      remainingHours: 32,
      expiresOn: '2027-07-20',
      equivalentDays: 4,
      ledgerVersion: 'TIME-LEDGER-2026-07-V2',
    },
    {
      accountReference: 'account:synthetic:comp',
      label: '调休',
      unit: 'HOURS',
      grantedHours: 12.5,
      openingHours: 0,
      usedHours: 4,
      remainingHours: 8.5,
      expiresOn: '2026-12-31',
      ledgerVersion: 'TIME-LEDGER-2026-07-V2',
    },
  ],
};

export const feedbackFixture: FeedbackProjection = {
  kind: 'FEEDBACK',
  metadata: {
    ...selfMetadata,
    allowedActions: ['FEEDBACK_CREATE'],
  },
  items: [
    {
      feedbackReference: 'feedback:synthetic:001',
      attendanceDate: '2026-07-22',
      problemTypeLabel: '缺卡说明',
      content: '<script>仅作为纯文本显示</script>',
      state: 'RESOLVED',
      replyText: '已核对合成补卡记录。',
      linkedAdjustmentResult: '已生成合成调整版本 V2',
      progress: [
        { sequence: 1, label: '已提交', occurredAt: '2026-07-22T09:00:00+08:00' },
        { sequence: 2, label: '处理中', occurredAt: '2026-07-22T10:00:00+08:00' },
        { sequence: 3, label: '已解决', occurredAt: '2026-07-22T11:00:00+08:00' },
      ],
    },
  ],
};

export const dashboardFixture: DashboardProjection = {
  kind: 'DASHBOARD',
  metadata: {
    ...selfMetadata,
    projectionVersion: 'DASH-2026-07-V3',
    scope: {
      type: 'ORGANIZATION',
      reference: 'scope:organization:synthetic',
      label: '合成制造一部',
    },
    allowedActions: ['DASHBOARD_DRILL_DOWN'],
  },
  title: '考勤管理看板',
  metrics: [
    { key: 'attendance-rate', label: '出勤率', displayValue: '98.6%', suppressed: false, drillDownReference: 'report:attendance-rate:v3' },
    { key: 'exception-rate', label: '异常率', displayValue: '1.4%', suppressed: false, drillDownReference: 'report:exceptions:v3' },
    { key: 'recognized-overtime', label: '认可加班', displayValue: '126.50 小时', suppressed: false },
    { key: 'leave', label: '请假', suppressed: true, suppressionLabel: '样本量不足，已隐藏' },
    { key: 'unsettled-periods', label: '未月结期间', displayValue: '0', suppressed: false },
    { key: 'freshness', label: '数据新鲜度', displayValue: '10 分钟内', suppressed: false },
  ],
};

export const reportFixture: ReportProjection = {
  kind: 'REPORT',
  metadata: {
    ...dashboardFixture.metadata,
    projectionVersion: 'REPORT-2026-07-V3',
    scope: {
      ...dashboardFixture.metadata.scope,
      type: 'ORGANIZATION',
    },
    allowedActions: ['REPORT_DRILL_DOWN', 'REPORT_EXPORT_CREATE', 'REPORT_EXPORT_DOWNLOAD'],
  },
  reportTitle: '部门月度考勤汇总',
  queryFingerprint: 'a'.repeat(64),
  filters: {
    period: '2026-07',
    scopeReference: 'scope:organization:synthetic',
    companyId: '30000000-0000-0000-0000-000000000001',
    status: '全部',
  },
  columns: [
    { key: 'scope', label: '范围' },
    { key: 'scheduled-hours', label: '应出勤' },
    { key: 'confirmed-hours', label: '确认工时' },
    { key: 'late-count', label: '迟到' },
    { key: 'recognized-overtime-hours', label: '认可加班' },
    { key: 'leave-hours', label: '请假' },
  ],
  exportFieldAllowlist: [
    'scope',
    'scheduled-hours',
    'confirmed-hours',
    'late-count',
    'recognized-overtime-hours',
    'leave-hours',
  ],
  rowCount: 2,
  rows: [
    {
      rowReference: 'report-row:synthetic:01',
      values: {
        scope: '合成一组',
        'scheduled-hours': '168',
        'confirmed-hours': '166',
        'late-count': '1',
        'recognized-overtime-hours': '12.5',
        'leave-hours': '2',
      },
      drillDownReference: 'report-detail:synthetic:01:v3',
    },
    {
      rowReference: 'report-row:synthetic:02',
      values: {
        scope: '合成二组',
        'scheduled-hours': '168',
        'confirmed-hours': '168',
        'late-count': '0',
        'recognized-overtime-hours': '8',
        'leave-hours': '0',
      },
      drillDownReference: 'report-detail:synthetic:02:v3',
    },
  ],
};

export const queuedExportFixture: ReportExportProjection = {
  exportReference: 'export:synthetic:001',
  state: 'QUEUED',
  delivery: 'ASYNCHRONOUS',
  queryFingerprint: reportFixture.queryFingerprint,
  projectionVersion: reportFixture.metadata.projectionVersion,
  scopeReference: reportFixture.metadata.scope.reference,
  selectedFields: reportFixture.exportFieldAllowlist,
  purpose: '月度考勤复核',
  requesterLabel: '合成考勤管理员',
  createdAt: '2026-07-28T09:35:00+08:00',
  expiresAt: '2026-07-29T09:35:00+08:00',
  auditReference: 'audit:synthetic:export:001',
  canDownload: false,
};

export const wave7FixtureGateway: Wave7ProjectionGateway = {
  loadSelfDashboard: async () => selfDashboardFixture,
  loadToday: async () => todayFixture,
  loadRecords: async () => recordsFixture,
  loadLeave: async () => leaveFixture,
  loadFeedback: async () => feedbackFixture,
  loadDashboard: async () => dashboardFixture,
  loadReportCompanies: async (period) => ({
    period,
    companies: [{
      companyId: '30000000-0000-0000-0000-000000000001',
      companyName: '神州半导体',
    }],
  }),
  loadReport: async () => reportFixture,
};

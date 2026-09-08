import { describe, expect, it, vi } from 'vitest';

import {
  dashboardFixture,
  reportFixture,
  todayFixture,
} from '../../test/fixtures/wave7ContractFixtures';
import {
  assertAttendanceMonthMatrixProjection,
  assertAttendanceReportExportView,
  assertAttendanceReportCompanyDirectory,
  assertLiveReportProjection,
  assertWave7Projection,
  parseAttendanceDashboardResponse,
} from './wave7Contracts';
import { wave7ProjectionGateway } from './wave7Gateway';

const retiredBoundaryIdKey = ['legal', 'EntityId'].join('');
const retiredDirectoryKey = ['legal', 'Entities'].join('');

describe('Wave 7 projection contracts', () => {
  it.each([todayFixture, dashboardFixture, reportFixture])(
    'accepts a complete $kind contract fixture',
    (fixture) => {
      expect(() => assertWave7Projection(fixture)).not.toThrow();
    },
  );

  it('rejects a projection without version and freshness metadata', () => {
    expect(() => assertWave7Projection({
      ...todayFixture,
      metadata: {
        periodState: 'OPEN',
      },
    })).toThrow(/projectionVersion/);
  });

  it('rejects unknown allowed actions', () => {
    expect(() => assertWave7Projection({
      ...todayFixture,
      metadata: {
        ...todayFixture.metadata,
        allowedActions: ['UNKNOWN_ACTION'],
      },
    })).toThrow(/allowedActions/);
  });

  it('accepts the formal nine-type report pagination contract', () => {
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      reportType: 'EXCEPTIONS',
      formulaVersion: 'ATTENDANCE_EXCEPTIONS_V1',
      page: 0,
      size: 50,
      totalPages: 1,
      columns: [
        { key: 'employee-number', label: '工号' },
        { key: 'exception-type', label: '异常类型' },
      ],
      exportFieldAllowlist: ['employee-number', 'exception-type'],
      rows: [{
        rowReference: 'formal-row-1',
        values: {
          'employee-number': 'SYN-001',
          'exception-type': 'LATE',
        },
      }],
      rowCount: 1,
    })).not.toThrow();
  });

  it('accepts a strict multi-badge monthly matrix and rejects date drift', () => {
    const dates = Array.from({ length: 31 }, (_, index) => (
      `2026-07-${String(index + 1).padStart(2, '0')}`
    ));
    const matrix = {
      kind: 'ATTENDANCE_MONTH_MATRIX',
      metadata: {
        ...reportFixture.metadata,
        periodLabel: '2026-07',
        scope: {
          type: 'ORGANIZATION',
          reference: 'scope:authorized',
          label: '授权组织',
        },
      },
      queryFingerprint: 'a'.repeat(64),
      formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V1',
      filters: {
        period: '2026-07',
        scopeReference: 'scope:authorized',
        companyId: 'company-a',
        organizationId: null,
        employeeId: null,
      },
      dates,
      employeeCount: 1,
      rows: [{
        employeeId: 'employee-a',
        employeeNumber: 'SZ001',
        employeeName: '张三',
        organizationId: 'org-a',
        organizationName: '制造一部',
        days: dates.map((date, index) => ({
          date,
          organizationName: index === 0 ? '制造一部' : null,
          shiftLabel: index === 0 ? '扬州总部班次' : null,
          firstPunchAt: index === 0 ? '2026-07-01T00:25:00Z' : null,
          lastPunchAt: index === 0 ? '2026-07-01T10:15:00Z' : null,
          badges: index === 0
            ? ['LATE', 'EARLY_DEPARTURE', 'PUNCH_CORRECTION']
            : [],
        })),
      }],
      page: 0,
      size: 20,
      totalPages: 1,
    };

    expect(() => assertAttendanceMonthMatrixProjection(matrix))
      .not.toThrow();
    expect(() => assertAttendanceMonthMatrixProjection({
      ...matrix,
      dates: [...dates.slice(0, 30), '2026-08-01'],
    })).toThrow(/dates are inconsistent/);
    expect(() => assertAttendanceMonthMatrixProjection({
      ...matrix,
      rows: [{
        ...matrix.rows[0],
        days: [{
          ...matrix.rows[0]!.days[0],
          badges: ['LATE', 'LATE'],
        }, ...matrix.rows[0]!.days.slice(1)],
      }],
    })).toThrow(/badges are invalid/);
  });

  it('rejects a formal report missing pagination or using an unknown type', () => {
    const liveReport = {
      ...reportFixture,
      reportType: 'ATTENDANCE_DETAIL',
      formulaVersion: 'ATTENDANCE_DETAIL_V1',
      page: 0,
      size: 50,
      totalPages: 1,
    };
    const { formulaVersion: omitted, ...missingFormula } = liveReport;
    void omitted;

    expect(() => assertLiveReportProjection(missingFormula))
      .toThrow(/formulaVersion/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      reportType: 'UNSUPPORTED',
    })).toThrow(/reportType/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      metadata: {
        ...liveReport.metadata,
        scope: {
          ...liveReport.metadata.scope,
          type: 'ATTENDANCE_GROUP',
        },
      },
    })).toThrow(/scope.type/);
  });

  it('rejects malformed report fingerprints and inconsistent pagination', () => {
    const liveReport = {
      ...reportFixture,
      reportType: 'ATTENDANCE_DETAIL',
      formulaVersion: 'ATTENDANCE_DETAIL_V1',
      page: 0,
      size: 50,
      totalPages: 1,
    };

    expect(() => assertLiveReportProjection({
      ...liveReport,
      queryFingerprint: 'not-a-sha256',
    })).toThrow(/queryFingerprint/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      totalPages: 2,
    })).toThrow(/pagination/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      size: 1,
    })).toThrow(/pagination/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      size: 201,
      totalPages: 1,
    })).toThrow(/service limit/);
  });

  it('enforces OpenAPI bounds on live report text and filter fields', () => {
    const liveReport = {
      ...reportFixture,
      reportType: 'ATTENDANCE_DETAIL',
      formulaVersion: 'ATTENDANCE_DETAIL_V1',
      page: 0,
      size: 50,
      totalPages: 1,
    };

    expect(() => assertLiveReportProjection({
      ...liveReport,
      reportTitle: '报'.repeat(101),
    })).toThrow(/reportTitle/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      formulaVersion: 'F'.repeat(129),
    })).toThrow(/formulaVersion/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      filters: {
        ...liveReport.filters,
        scopeReference: 's'.repeat(129),
      },
    })).toThrow(/scopeReference/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      filters: {
        ...liveReport.filters,
        companyId: 'c'.repeat(37),
      },
    })).toThrow(/companyId/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      filters: {
        ...liveReport.filters,
        organizationId: 'o'.repeat(37),
      },
    })).toThrow(/organizationId/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      filters: {
        ...liveReport.filters,
        employeeId: 'e'.repeat(37),
      },
    })).toThrow(/employeeId/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      filters: {
        ...liveReport.filters,
        status: 's'.repeat(33),
      },
    })).toThrow(/status/);
  });

  it('rejects unsafe live report allowlists, row identities, and values', () => {
    const liveReport = {
      ...reportFixture,
      reportType: 'ATTENDANCE_DETAIL',
      formulaVersion: 'ATTENDANCE_DETAIL_V1',
      page: 0,
      size: 50,
      totalPages: 1,
    };

    expect(() => assertLiveReportProjection({
      ...liveReport,
      exportFieldAllowlist: [],
    })).toThrow(/non-empty and unique/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      exportFieldAllowlist: ['scope', 'scope'],
    })).toThrow(/non-empty and unique/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      rows: [
        liveReport.rows[0],
        {
          ...liveReport.rows[1],
          rowReference: liveReport.rows[0]!.rowReference,
        },
      ],
    })).toThrow(/duplicate rowReference/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      rows: [{
        ...liveReport.rows[0],
        rowReference: 'r'.repeat(257),
      }],
      rowCount: 1,
    })).toThrow(/rowReference/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      rows: [{
        ...liveReport.rows[0],
        drillDownReference: 'd'.repeat(257),
      }],
      rowCount: 1,
    })).toThrow(/drillDownReference/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      rows: [{
        ...liveReport.rows[0],
        values: {
          ...liveReport.rows[0]!.values,
          'scheduled-hours': 168,
        },
      }],
      rowCount: 1,
    })).toThrow(/row value must be a string/);
  });

  it('accepts strict synchronous and asynchronous export views', () => {
    const ready = {
      exportId: '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f',
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      deliveryMode: 'SYNC',
      status: 'READY',
      purpose: '月度考勤复核',
      rowCount: 42,
      expiresAt: '2026-07-30T01:00:00Z',
      completedAt: '2026-07-29T01:00:00.123456Z',
    };

    expect(() => assertAttendanceReportExportView(ready)).not.toThrow();
    expect(() => assertAttendanceReportExportView({
      ...ready,
      deliveryMode: 'ASYNC',
      status: 'QUEUED',
      completedAt: undefined,
    })).not.toThrow();
  });

  it('accepts an authorized company directory and rejects duplicate ids', () => {
    const directory = {
      period: '2026-07',
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
    };

    expect(() =>
      assertAttendanceReportCompanyDirectory(directory))
      .not.toThrow();
    expect(() => assertAttendanceReportCompanyDirectory({
      ...directory,
      companies: [
        directory.companies[0],
        directory.companies[0],
      ],
    })).toThrow(/duplicate ids/);
  });

  it('rejects retired boundary properties even beside canonical company data', () => {
    const directory = {
      period: '2026-07',
      companies: [{
        companyId: 'company-a',
        companyName: '神州半导体',
      }],
    };
    expect(() => assertAttendanceReportCompanyDirectory({
      ...directory,
      [retiredDirectoryKey]: directory.companies,
    })).toThrow(/unsupported property/);
    expect(() => assertAttendanceReportCompanyDirectory({
      ...directory,
      companies: [{
        ...directory.companies[0],
        [retiredBoundaryIdKey]: 'company-a',
      }],
    })).toThrow(/unsupported property/);

    expect(() => assertLiveReportProjection({
      ...reportFixture,
      filters: {
        ...reportFixture.filters,
        [retiredBoundaryIdKey]: reportFixture.filters.companyId,
      },
    })).toThrow(/unsupported property/);
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      [retiredBoundaryIdKey]: reportFixture.filters.companyId,
    })).toThrow(/unsupported property/);
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      metadata: {
        ...reportFixture.metadata,
        [retiredBoundaryIdKey]: reportFixture.filters.companyId,
      },
    })).toThrow(/unsupported property/);
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      columns: [{
        ...reportFixture.columns[0],
        [retiredBoundaryIdKey]: reportFixture.filters.companyId,
      }],
    })).toThrow(/unsupported property/);
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      rows: [{
        ...reportFixture.rows[0],
        [retiredBoundaryIdKey]: reportFixture.filters.companyId,
      }],
    })).toThrow(/unsupported property/);

    const exportView = {
      exportId: '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f',
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: 'company-a',
      deliveryMode: 'SYNC',
      status: 'READY',
      purpose: '月度考勤复核',
      rowCount: 42,
      expiresAt: '2026-07-30T01:00:00Z',
      completedAt: '2026-07-29T01:00:00Z',
      [retiredBoundaryIdKey]: 'company-a',
    };
    expect(() => assertAttendanceReportExportView(exportView))
      .toThrow(/unsupported property/);
  });

  it('rejects malformed or internally inconsistent export views', () => {
    const ready = {
      exportId: '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f',
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      deliveryMode: 'SYNC',
      status: 'READY',
      purpose: '月度考勤复核',
      rowCount: 42,
      expiresAt: '2026-07-30T01:00:00Z',
      completedAt: '2026-07-29T01:00:00Z',
    };

    expect(() => assertAttendanceReportExportView({
      ...ready,
      deliveryMode: 'SYNC',
      status: 'QUEUED',
      completedAt: undefined,
    })).toThrow(/synchronous/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      purpose: 'x',
    })).toThrow(/2 至 200/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      rowCount: Number.MAX_SAFE_INTEGER + 1,
    })).toThrow(/safe integer/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      completedAt: null,
    })).toThrow(/completedAt/);
  });

  it('accepts ready and multi-company attendance dashboard responses', () => {
    const ready = parseAttendanceDashboardResponse(
      attendanceDashboardResponse(),
    );
    expect(ready.kind).toBe('DASHBOARD');
    if (ready.kind !== 'DASHBOARD') {
      throw new TypeError('dashboard response must be ready');
    }
    expect(ready.metrics).toEqual([]);
    expect(ready.summary).toEqual({
      unresolvedCount: 1,
      affectedEmployeeCount: 1,
      blockingCount: 1,
    });
    expect(ready.analytics.dailyTrend).toHaveLength(7);
    expect(ready.analytics.severityDistribution.map((item) =>
      item.severity)).toEqual(['INFO', 'WARNING', 'ERROR']);

    expect(parseAttendanceDashboardResponse({
      kind: 'DASHBOARD_COMPANY_SELECTION',
      title: '今日异常考勤',
      businessDate: '2026-07-30',
      selectedCompanyId: null,
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
      message: '请选择公司后查看今日异常考勤',
    })).toMatchObject({
      kind: 'DASHBOARD_COMPANY_SELECTION',
      selectedCompanyId: null,
    });
    expect(parseAttendanceDashboardResponse({
      kind: 'DASHBOARD_COMPANY_SELECTION',
      title: '今日异常考勤',
      businessDate: '2026-07-30',
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
      message: '请选择公司后查看今日异常考勤',
    })).toMatchObject({
      kind: 'DASHBOARD_COMPANY_SELECTION',
      selectedCompanyId: null,
    });
  });

  it('rejects unsafe or inconsistent attendance dashboard responses', () => {
    const ready = attendanceDashboardResponse();
    const firstException = ready.exceptions[0]!;
    const tooManyExceptions = Array.from({ length: 501 }, (_, index) => ({
      ...firstException,
      exceptionReference: `exception-${index}`,
    }));

    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      summary: {
        unresolvedCount: 501,
        affectedEmployeeCount: 1,
        blockingCount: 1,
      },
      exceptions: tooManyExceptions,
    })).toThrow(/at most 500/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      summary: {
        unresolvedCount: 1,
        affectedEmployeeCount: 2,
        blockingCount: 1,
      },
    })).toThrow(/internally inconsistent/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      currentPassword: 'must-not-pass-through',
    })).toThrow(/unsupported property/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      businessDate: '2026-02-31',
    })).toThrow(/YYYY-MM-DD/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      exceptions: [{
        ...firstException,
        businessDate: '2026-06-30',
      }],
    })).toThrow(/must match/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      exceptions: [{
        ...firstException,
        state: 'RESOLVED',
      }],
    })).toThrow(/state is invalid/);
  });

  it('rejects missing, malformed, or misordered dashboard analytics', () => {
    const ready = attendanceDashboardResponse();
    const { analytics: omitted, ...missingAnalytics } = ready;
    void omitted;

    expect(() => parseAttendanceDashboardResponse(missingAnalytics))
      .toThrow(/incomplete/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      analytics: {
        ...ready.analytics,
        internalScopeId: 'must-not-pass-through',
      },
    })).toThrow(/unsupported property/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      analytics: {
        ...ready.analytics,
        dailyTrend: ready.analytics.dailyTrend.slice(1),
      },
    })).toThrow(/window is incomplete/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      analytics: {
        ...ready.analytics,
        severityDistribution: [
          ready.analytics.severityDistribution[1],
          ready.analytics.severityDistribution[0],
          ready.analytics.severityDistribution[2],
        ],
      },
    })).toThrow(/order is invalid/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      analytics: {
        ...ready.analytics,
        typeDistribution: [
          { exceptionType: 'ZZZ', count: 1 },
          { exceptionType: 'AAA', count: 1 },
        ],
      },
    })).toThrow(/order is invalid/);
    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      analytics: {
        ...ready.analytics,
        organizationRanking: Array.from(
          { length: 6 },
          (_, index) => ({
            organizationName: `组织${index}`,
            exceptionCount: 1,
            blockingCount: 0,
          }),
        ),
      },
    })).toThrow(/at most 5/);
  });

  it('accepts explicit zero-valued dashboard analytics', () => {
    const ready = attendanceDashboardResponse();
    const analytics = attendanceDashboardAnalytics();
    const parsed = parseAttendanceDashboardResponse({
      ...ready,
      summary: {
        unresolvedCount: 0,
        affectedEmployeeCount: 0,
        blockingCount: 0,
      },
      exceptions: [],
      analytics: {
        dailyTrend: analytics.dailyTrend.map((item) => ({
          ...item,
          exceptionCount: 0,
          blockingCount: 0,
          affectedEmployeeCount: 0,
        })),
        severityDistribution: [
          { severity: 'INFO', count: 0 },
          { severity: 'WARNING', count: 0 },
          { severity: 'ERROR', count: 0 },
        ],
        typeDistribution: [],
        organizationRanking: [],
      },
    });

    expect(parsed.kind).toBe('DASHBOARD');
    if (parsed.kind !== 'DASHBOARD') {
      throw new TypeError('dashboard response must be ready');
    }
    expect(parsed.analytics.dailyTrend.at(-1)).toMatchObject({
      businessDate: '2026-07-30',
      exceptionCount: 0,
    });
    expect(parsed.analytics.typeDistribution).toEqual([]);
    expect(parsed.analytics.organizationRanking).toEqual([]);
  });

  it('rejects dashboard employee details without drill-down permission', () => {
    const ready = attendanceDashboardResponse();

    expect(() => parseAttendanceDashboardResponse({
      ...ready,
      metadata: {
        ...ready.metadata,
        allowedActions: [],
      },
    })).toThrow(/requires dashboard drill-down permission/);

    const parsed = parseAttendanceDashboardResponse({
      ...ready,
      metadata: {
        ...ready.metadata,
        allowedActions: [],
      },
      exceptions: [],
    });
    expect(parsed.kind).toBe('DASHBOARD');
    if (parsed.kind !== 'DASHBOARD') {
      throw new TypeError('dashboard response must be ready');
    }
    expect(parsed.summary.unresolvedCount).toBe(1);
    expect(parsed.exceptions).toEqual([]);
  });

  it('accepts a workbench payload whose exception board is yesterday', () => {
    const ready = attendanceDashboardResponse();
    const firstException = ready.exceptions[0]!;
    const parsed = parseAttendanceDashboardResponse({
      ...ready,
      businessDate: '2026-07-30',
      exceptions: [{
        ...firstException,
        businessDate: '2026-07-29',
      }],
      analytics: {
        ...ready.analytics,
        dailyTrend: [
          {
            businessDate: '2026-07-23',
            exceptionCount: 0,
            blockingCount: 0,
            affectedEmployeeCount: 0,
          },
          ...ready.analytics.dailyTrend.slice(0, 6).map((point, index) => (
            index === 5
              ? {
                  businessDate: '2026-07-29',
                  exceptionCount: 1,
                  blockingCount: 1,
                  affectedEmployeeCount: 1,
                }
              : point
          )),
        ],
      },
    });

    expect(parsed.kind).toBe('DASHBOARD');
    if (parsed.kind !== 'DASHBOARD') {
      throw new TypeError('dashboard response must be ready');
    }
    expect(parsed.businessDate).toBe('2026-07-30');
    expect(parsed.exceptions[0]?.businessDate).toBe('2026-07-29');
    expect(parsed.analytics.dailyTrend.at(-1)).toMatchObject({
      businessDate: '2026-07-29',
      exceptionCount: 1,
    });
  });

  it('accepts a month workbench list whose exception dates fall earlier in the period', () => {
    const ready = attendanceDashboardResponse();
    const firstException = ready.exceptions[0]!;
    const parsed = parseAttendanceDashboardResponse({
      ...ready,
      businessDate: '2026-07-30',
      exceptions: [{
        ...firstException,
        businessDate: '2026-07-05',
      }],
      analytics: {
        ...ready.analytics,
        dailyTrend: ready.analytics.dailyTrend.map((point, index, all) => (
          index === all.length - 1
            ? {
                ...point,
                exceptionCount: 0,
                blockingCount: 0,
                affectedEmployeeCount: 0,
              }
            : point
        )),
      },
    });

    expect(parsed.kind).toBe('DASHBOARD');
    if (parsed.kind !== 'DASHBOARD') {
      throw new TypeError('dashboard response must be ready');
    }
    expect(parsed.exceptions[0]?.businessDate).toBe('2026-07-05');
  });

  it('fails closed before the upstream projections are synchronized', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({
        code: 'SELF_ATTENDANCE_DASHBOARD_SOURCE_NOT_READY',
        message: '本人当月考勤来源尚未同步或当前账号没有本人范围',
        retryable: true,
      }),
      {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      },
    )));
    await expect(wave7ProjectionGateway.loadToday()).rejects.toMatchObject({
      status: 409,
      code: 'SELF_ATTENDANCE_DASHBOARD_SOURCE_NOT_READY',
      retryable: true,
    });
    vi.unstubAllGlobals();
  });
});

function attendanceDashboardResponse() {
  return {
    kind: 'DASHBOARD',
    title: '今日异常考勤',
    businessDate: '2026-07-30',
    selectedCompanyId: 'company-a',
    metadata: {
      projectionVersion: 'ATTENDANCE-DASHBOARD-2026-07-30-V1',
      sourceVersions: ['ATTENDANCE-CALC-V1'],
      dataAsOf: '2026-07-30T01:00:00Z',
      timeZone: 'Asia/Shanghai',
      periodLabel: '2026-07',
      periodState: 'OPEN',
      scope: {
        type: 'COMPANY',
        reference: 'company-a',
        label: '神州半导体',
      },
      allowedActions: ['DASHBOARD_DRILL_DOWN'],
    },
    summary: {
      unresolvedCount: 1,
      affectedEmployeeCount: 1,
      blockingCount: 1,
    },
    exceptions: [{
      exceptionReference: 'exception-1',
      employeeNumber: 'SZ001',
      employeeName: '张三',
      organizationName: '制造一部',
      businessDate: '2026-07-30',
      exceptionType: 'MISSING_PUNCH_OVERDUE',
      severity: 'ERROR',
      state: 'PENDING_REVIEW',
      exceptionMinutes: 480,
      evidenceSummary: '下班卡缺失',
    }],
    analytics: attendanceDashboardAnalytics(),
    companies: [
      { companyId: 'company-a', companyName: '神州半导体' },
      { companyId: 'company-b', companyName: '神州科技' },
    ],
  };
}

function attendanceDashboardAnalytics() {
  return {
    dailyTrend: [
      {
        businessDate: '2026-07-24',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-25',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-26',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-27',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-28',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-29',
        exceptionCount: 0,
        blockingCount: 0,
        affectedEmployeeCount: 0,
      },
      {
        businessDate: '2026-07-30',
        exceptionCount: 1,
        blockingCount: 1,
        affectedEmployeeCount: 1,
      },
    ],
    severityDistribution: [
      { severity: 'INFO', count: 0 },
      { severity: 'WARNING', count: 0 },
      { severity: 'ERROR', count: 1 },
    ],
    typeDistribution: [{
      exceptionType: 'MISSING_PUNCH_OVERDUE',
      count: 1,
    }],
    organizationRanking: [{
      organizationName: '制造一部',
      exceptionCount: 1,
      blockingCount: 1,
    }],
  };
}

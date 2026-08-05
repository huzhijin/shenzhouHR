import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import {
  dashboardFixture,
  feedbackFixture,
  leaveFixture,
  queuedExportFixture,
  recordsFixture,
  reportFixture,
  selfDashboardFixture,
  wave7FixtureGateway,
} from '../../test/fixtures/wave7ContractFixtures';
import { DashboardRoute, DashboardView } from './DashboardPage';
import {
  EmployeeFeedbackRoute,
  EmployeeFeedbackView,
  EmployeeLeaveView,
  EmployeeRecordsView,
  EmployeeTodayRoute,
} from './EmployeeSelfServicePages';
import {
  AttendanceMonthMatrixView,
  attendanceReportMatrixSnapshotMismatchMessage,
  attendanceReportMatrixSnapshotsMatch,
  createReportExportRequest,
  exportDeliveryForRowCount,
  reportCellDisplayValue,
  reportColumnLabel,
  ReportExportStatus,
  ReportView,
  ReportsRoute,
  reportTypeLabel,
  reportTypeOptions,
} from './ReportsPage';
import type {
  AttendanceMonthMatrixQuery,
  ReportExportCreateRequest,
  ReportQuery,
  Wave7ProjectionGateway,
} from './wave7Gateway';
import type {
  AttendanceMonthMatrixProjection,
  AttendanceReportType,
  AttendanceReportExportView,
  DashboardLoadResult,
  LiveDashboardProjection,
  LiveReportProjection,
} from './wave7Contracts';
import { Wave7AsyncBoundary } from './Wave7Common';

describe('Wave 7 fixture-driven pages', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders session-bound today data and authorized mobile navigation', async () => {
    renderWithRouter(
      <EmployeeTodayRoute
        gateway={wave7FixtureGateway}
        capabilities={[
          'ATTENDANCE_SELF:READ',
          'LEAVE_SELF:READ',
          'ATTENDANCE_FEEDBACK:READ',
        ]}
      />,
      '/me/today',
    );

    expect(await screen.findByRole('heading', {
      name: '我的考勤工作台',
    })).toBeInTheDocument();
    expect(screen.getByText(selfDashboardFixture.today!.shiftLabel!))
      .toBeInTheDocument();
    expect(screen.getByLabelText('本人考勤关键指标'))
      .toHaveTextContent('待处理异常');
    expect(screen.getByRole('navigation', { name: '员工自助' })).toHaveTextContent('今日记录假期反馈');
    const todayLink = screen.getByRole('link', { name: '今日' });
    expect(todayLink).toHaveClass('is-active');
    todayLink.focus();
    expect(todayLink).toHaveFocus();
  });

  it('renders frozen records with both desktop table and mobile card semantics', () => {
    renderWithRouter(<EmployeeRecordsView projection={recordsFixture} />);

    expect(screen.getByRole('status', { name: '本月数据已冻结' }))
      .toHaveTextContent('本月数据已冻结');
    expect(screen.getByRole('region', { name: '本人每日考勤记录' })).toBeInTheDocument();
    expect(document.querySelectorAll('.record-card')).toHaveLength(recordsFixture.records.length);
    expect(screen.getByText(recordsFixture.metadata.projectionVersion))
      .toBeInTheDocument();
    expect(screen.queryByText(recordsFixture.records[0]!.explanationReference!))
      .not.toBeInTheDocument();
  });

  it('renders balances without client-side synthesis and feedback as plain text', () => {
    const { rerender } = renderWithRouter(<EmployeeLeaveView projection={leaveFixture} />);
    expect(screen.getByText('32.00 小时')).toBeInTheDocument();
    expect(screen.getByText('4.00 天')).toBeInTheDocument();
    expect(screen.queryByText(leaveFixture.accounts[0]!.ledgerVersion))
      .not.toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <EmployeeFeedbackView
          projection={feedbackFixture}
          canCreate={false}
        />
      </MemoryRouter>,
    );
    expect(screen.getByText('<script>仅作为纯文本显示</script>')).toBeInTheDocument();
    expect(document.querySelector('script')).toBeNull();
    expect(screen.getByRole('button', { name: '提交反馈' })).toBeDisabled();
    expect(screen.getByRole('note')).toHaveTextContent('当前仅可查看反馈');
    expect(screen.queryByText(feedbackFixture.items[0]!.feedbackReference))
      .not.toBeInTheDocument();
  });

  it('binds dashboard drill-down to the opaque reference and projection version', () => {
    const onDrillDown = vi.fn();
    renderWithRouter(
      <DashboardView projection={dashboardFixture} onDrillDown={onDrillDown} />,
    );

    expect(screen.getByText('样本量不足，已隐藏')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '下钻查看出勤率' }));
    expect(onDrillDown).toHaveBeenCalledWith(
      'report:attendance-rate:v3',
      dashboardFixture.metadata.projectionVersion,
    );
  });

  it('renders real daily anomaly totals and the authorized top-ten list', () => {
    const onOpenReports = vi.fn();
    renderWithRouter(
      <DashboardView
        projection={liveDashboard()}
        onOpenReports={onOpenReports}
      />,
      '/workbench',
    );

    expect(screen.getByRole('heading', {
      level: 1,
      name: '今日异常考勤',
    }))
      .toBeInTheDocument();
    const summary = within(screen.getByLabelText('今日异常汇总指标'));
    expect(summary.getByText('未处理异常').nextElementSibling)
      .toHaveTextContent('1');
    expect(summary.getByText('影响员工').nextElementSibling)
      .toHaveTextContent('1');
    expect(summary.getByText('阻断异常').nextElementSibling)
      .toHaveTextContent('1');
    expect(screen.getByRole('region', { name: '今日异常考勤列表' }))
      .toHaveTextContent('张三');
    expect(screen.getByRole('region', { name: '今日异常考勤列表' }))
      .toHaveTextContent('缺卡逾期');
    expect(screen.getByRole('region', { name: '今日异常考勤列表' }))
      .toHaveTextContent('待复核');
    fireEvent.click(screen.getByRole('button', { name: '查看异常报表' }));
    expect(onOpenReports).toHaveBeenCalledWith({
      reportType: 'EXCEPTIONS',
      period: '2026-07',
      companyId: 'company-a',
      projectionVersion: 'ATTENDANCE-DASHBOARD-2026-07-30-V1',
    });
    expect(screen.queryByRole('button', { name: '打开考勤大屏' }))
      .not.toBeInTheDocument();
  });

  it('shows a verified zero-anomaly result instead of a generic placeholder', () => {
    renderWithRouter(
      <DashboardView
        projection={liveDashboard({
          summary: {
            unresolvedCount: 0,
            affectedEmployeeCount: 0,
            blockingCount: 0,
          },
          exceptions: [],
        })}
      />,
      '/workbench',
    );

    const summary = within(screen.getByLabelText('今日异常汇总指标'));
    expect(summary.getByText('未处理异常').nextElementSibling)
      .toHaveTextContent('0');
    expect(summary.getByText('影响员工').nextElementSibling)
      .toHaveTextContent('0');
    expect(summary.getByText('阻断异常').nextElementSibling)
      .toHaveTextContent('0');
    expect(screen.getByText('今日没有未处理的异常考勤'))
      .toBeInTheDocument();
    expect(screen.queryByText('当前授权范围内暂无可显示数据。'))
      .not.toBeInTheDocument();
  });

  it('requires and reloads an explicit company on the dashboard', async () => {
    const selection: DashboardLoadResult = {
      kind: 'DASHBOARD_COMPANY_SELECTION',
      title: '今日异常考勤',
      businessDate: '2026-07-30',
      selectedCompanyId: null,
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
      message: '请选择公司后查看今日异常考勤',
    };
    const loadDashboard = vi.fn(
      async (companyId?: string): Promise<DashboardLoadResult> => (
        companyId === undefined
          ? selection
          : liveDashboard({
              selectedCompanyId: companyId,
              metadata: {
                ...liveDashboard().metadata,
                scope: {
                  type: 'COMPANY',
                  reference: companyId,
                  label: '神州科技',
                },
              },
            })
      ),
    );
    renderWithRouter(
      <DashboardRoute gateway={gateway({ loadDashboard })} />,
      '/workbench',
    );

    expect(await screen.findByText(/请选择公司后查看今日异常考勤/))
      .toBeInTheDocument();
    expect(loadDashboard).toHaveBeenCalledWith(undefined);
    fireEvent.change(screen.getByLabelText('控制台公司'), {
      target: { value: 'company-b' },
    });

    expect(await screen.findByRole('region', {
      name: '今日异常考勤列表',
    })).toHaveTextContent('张三');
    expect(loadDashboard).toHaveBeenLastCalledWith('company-b');
  });

  it('carries the dashboard projection version into formal reports', async () => {
    renderWithRouter(
      <>
        <DashboardRoute
          gateway={gateway({
            loadDashboard: async () => liveDashboard(),
          })}
        />
        <LocationProbe />
      </>,
      '/workbench',
    );

    fireEvent.click(await screen.findByRole('button', {
      name: '查看异常报表',
    }));

    expect(await screen.findByTestId('location')).toHaveTextContent(
      '/attendance/reports?reportType=EXCEPTIONS'
      + '&period=2026-07&companyId=company-a'
      + '&expectedProjectionVersion='
      + 'ATTENDANCE-DASHBOARD-2026-07-30-V1',
    );
  });

  it('creates an export request from the visible bound query and allowlist', async () => {
    const onCreateExport = vi.fn();
    renderWithRouter(
      <ReportView
        projection={reportFixture}
        canCreateExport
        onCreateExport={onCreateExport}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '创建受控导出' }));
    fireEvent.change(await screen.findByLabelText('导出用途'), {
      target: { value: ' 月度考勤复核 ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建导出' }));

    await waitFor(() => {
      expect(onCreateExport).toHaveBeenCalledWith({
        queryFingerprint: reportFixture.queryFingerprint,
        projectionVersion: reportFixture.metadata.projectionVersion,
        scopeReference: reportFixture.metadata.scope.reference,
        filters: reportFixture.filters,
        selectedFields: reportFixture.exportFieldAllowlist,
        purpose: '月度考勤复核',
      });
    });
  });

  it('rejects fields outside the report allowlist', () => {
    expect(() => createReportExportRequest(
      reportFixture,
      ['scope', 'absence-hours'],
      '复核',
    )).toThrow(/白名单/);
  });

  it('omits backend-only report fields from the table and export request', async () => {
    const onCreateExport = vi.fn();
    const projection = {
      ...reportFixture,
      columns: [
        ...reportFixture.columns,
        { key: 'document-reference' as const, label: '单据引用' },
        { key: 'rate-formula-version' as const, label: '出勤率公式版本' },
      ],
      exportFieldAllowlist: [
        ...reportFixture.exportFieldAllowlist,
        'document-reference' as const,
        'rate-formula-version' as const,
      ],
      rows: reportFixture.rows.map((row) => ({
        ...row,
        values: {
          ...row.values,
          'document-reference': '550e8400-e29b-41d4-a716-446655440000',
          'rate-formula-version': 'ATTENDANCE_RATE_FORMULA_V3',
        },
      })),
    };

    renderWithRouter(
      <ReportView
        projection={projection}
        canCreateExport
        onCreateExport={onCreateExport}
      />,
    );

    expect(screen.queryByText('单据引用')).not.toBeInTheDocument();
    expect(screen.queryByText('出勤率公式版本')).not.toBeInTheDocument();
    expect(screen.queryByText('550e8400-e29b-41d4-a716-446655440000'))
      .not.toBeInTheDocument();
    expect(screen.queryByText('ATTENDANCE_RATE_FORMULA_V3'))
      .not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '创建受控导出' }));
    fireEvent.change(await screen.findByLabelText('导出用途'), {
      target: { value: '月度复核' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建导出' }));

    await waitFor(() => {
      expect(onCreateExport).toHaveBeenCalledWith(expect.objectContaining({
        selectedFields: reportFixture.exportFieldAllowlist,
      }));
    });
    expect(() => createReportExportRequest(
      projection,
      ['rate-formula-version'],
      '复核',
    )).toThrow(/白名单/);
  });

  it('uses fixed business headers and never echoes unknown enum codes', () => {
    const projection = {
      ...reportFixture,
      columns: [
        { key: 'employee-number' as const, label: 'employee_id' },
        { key: 'exception-type' as const, label: 'exception_type' },
        { key: 'exception-state' as const, label: 'exception_state' },
      ],
      exportFieldAllowlist: [
        'employee-number' as const,
        'exception-type' as const,
        'exception-state' as const,
      ],
      rowCount: 1,
      rows: [{
        rowReference: 'report-row-1',
        values: {
          'employee-number': 'SZ-001',
          'exception-type': 'FUTURE_INTERNAL_EXCEPTION',
          'exception-state': 'FUTURE_INTERNAL_STATE',
        },
      }],
    };

    renderWithRouter(
      <ReportView projection={projection} canCreateExport={false} />,
    );

    expect(screen.getByRole('columnheader', { name: '工号' }))
      .toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '异常类型' }))
      .toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '处理状态' }))
      .toBeInTheDocument();
    expect(screen.queryByText(/employee_id|exception_type|exception_state/))
      .not.toBeInTheDocument();
    expect(screen.getAllByText('其他异常')).not.toHaveLength(0);
    expect(screen.getAllByText('其他状态')).not.toHaveLength(0);

    expect(reportColumnLabel('evidence-summary')).toBe('异常说明');
    expect(reportCellDisplayValue(
      'exception-type',
      'NO_SHIFT_OR_CALENDAR',
    )).toBe('未配置班次或日历');
    expect(reportCellDisplayValue('account-type', 'FUTURE_ACCOUNT'))
      .toBe('其他账户');
    expect(reportTypeLabel('FUTURE_REPORT' as AttendanceReportType))
      .toBe('其他报表');
  });

  it('validates the export purpose and applies the 50,000 row threshold', async () => {
    const onCreateExport = vi.fn();
    renderWithRouter(
      <ReportView
        projection={reportFixture}
        canCreateExport
        onCreateExport={onCreateExport}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '创建受控导出' }));
    fireEvent.click(screen.getByRole('button', { name: '创建导出' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('请填写导出用途');
    expect(screen.getByLabelText('导出用途')).toHaveAttribute('aria-invalid', 'true');
    expect(onCreateExport).not.toHaveBeenCalled();
    expect(exportDeliveryForRowCount(50_000)).toBe('SYNCHRONOUS');
    expect(exportDeliveryForRowCount(50_001)).toBe('ASYNCHRONOUS');
  });

  it('renders queued and ready export lifecycle with separate download gating', () => {
    const onDownload = vi.fn();
    const { rerender } = renderWithRouter(
      <ReportExportStatus
        job={queuedExportFixture}
        canDownload
        onDownload={onDownload}
      />,
    );
    expect(screen.getByRole('button', { name: '下载文件' })).toBeDisabled();
    expect(screen.getByText('排队中')).toBeInTheDocument();

    const ready = {
      ...queuedExportFixture,
      state: 'READY' as const,
      canDownload: true,
    };
    rerender(
      <MemoryRouter>
        <ReportExportStatus job={ready} canDownload onDownload={onDownload} />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: '下载文件' }));
    expect(onDownload).toHaveBeenCalledWith(ready.exportReference);
  });
});

describe('Wave 7 async states', () => {
  afterEach(() => {
    cleanup();
  });

  it('distinguishes loading, empty, and non-leaking 403 states', async () => {
    const pending = new Promise<never>(() => undefined);
    const pendingGateway = gateway({
      loadSelfDashboard: () => pending,
    });
    const { unmount } = renderWithRouter(
      <EmployeeTodayRoute
        gateway={pendingGateway}
        capabilities={['ATTENDANCE_SELF:READ']}
      />,
      '/me/today',
    );
    expect(screen.getByLabelText('正在加载')).toHaveAttribute('aria-busy', 'true');
    unmount();

    renderWithRouter(
      <EmployeeFeedbackRoute
        gateway={gateway({
          loadFeedback: async () => ({ ...feedbackFixture, items: [] }),
        })}
        capabilities={['ATTENDANCE_FEEDBACK:READ']}
      />,
      '/me/feedback',
    );
    expect(await screen.findByText('当前授权范围内暂无可显示数据。')).toBeInTheDocument();
    cleanup();

    renderWithRouter(
      <EmployeeTodayRoute
        gateway={gateway({
          loadSelfDashboard: async () => Promise.reject(
            new ApiRequestError(403, {
              code: 'ACCESS_DENIED',
              message: '不得显示敏感范围内容',
            }),
          ),
        })}
        capabilities={['ATTENDANCE_SELF:READ']}
      />,
      '/me/today',
    );
    expect(await screen.findByText('当前账号无权访问该内容。')).toBeInTheDocument();
    expect(screen.queryByText('不得显示敏感范围内容')).not.toBeInTheDocument();
  });

  it('does not misreport a missing dashboard projection as zero anomalies', async () => {
    renderWithRouter(
      <DashboardRoute
        gateway={gateway({
          loadDashboard: async () => Promise.reject(
            new ApiRequestError(409, {
              code: 'ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY',
              message: '服务端内部投影说明',
              retryable: true,
            }),
          ),
        })}
      />,
      '/workbench',
    );

    expect(await screen.findByRole('heading', {
      name: '今日异常考勤尚未生成',
    })).toBeInTheDocument();
    expect(screen.getByText(/完成数据同步后，还需完成考勤计算并发布正式投影/))
      .toBeInTheDocument();
    expect(screen.queryByText('服务端内部投影说明'))
      .not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /重\s*试/ }))
      .toBeInTheDocument();
  });

  it('clears prior protected content before a changed loader resolves', async () => {
    const firstLoader = async () => recordsFixture;
    const nextLoader = () => new Promise<typeof recordsFixture>(() => undefined);
    const renderProjection = (projection: typeof recordsFixture) => (
      <p>{projection.metadata.scope.label} protected projection</p>
    );
    const { rerender } = render(
      <Wave7AsyncBoundary loader={firstLoader} isEmpty={() => false}>
        {renderProjection}
      </Wave7AsyncBoundary>,
    );

    expect(await screen.findByText('本人 protected projection')).toBeInTheDocument();
    rerender(
      <Wave7AsyncBoundary loader={nextLoader} isEmpty={() => false}>
        {renderProjection}
      </Wave7AsyncBoundary>,
    );

    await waitFor(() => {
      expect(screen.queryByText('本人 protected projection')).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText('正在加载')).toBeInTheDocument();
  });
});

describe('Wave 7 formal report route', () => {
  afterEach(() => {
    cleanup();
  });

  it('explains that an empty company directory requires calculation and publication', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) => formalReport(query));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies: async (period) => ({ period, companies: [] }),
          loadReport,
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: '所选月份暂无可查看报表',
    })).toBeInTheDocument();
    expect(screen.getByText(
      '所选月份无已发布正式投影。'
      + '连接数据库或已有原始数据不会自动生成报表，'
      + '需完成考勤计算与正式投影发布。',
    )).toBeInTheDocument();
    expect(loadReport).not.toHaveBeenCalled();
  });

  it('initializes an authorized exception report from URL query parameters', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query));
    const loadReportCompanies = vi.fn(async (period: string) => ({
      period,
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
    }));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies,
          loadReport,
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports?reportType=EXCEPTIONS'
        + '&period=2026-06&companyId=company-b'
        + '&status=PENDING_REVIEW'
        + '&expectedProjectionVersion=FORMAL-DASHBOARD-V1',
    );

    expect(await screen.findByRole('heading', {
      name: 'EXCEPTIONS · 2026-06',
    })).toBeInTheDocument();
    expect(screen.getByLabelText('报表类型')).toHaveValue('EXCEPTIONS');
    expect(screen.getByLabelText('月份')).toHaveValue('2026-06');
    expect(screen.getByLabelText('异常状态')).toHaveValue('PENDING_REVIEW');
    expect(screen.getByLabelText('公司')).toHaveValue('company-b');
    expect(loadReportCompanies).toHaveBeenCalledWith('2026-06');
    expect(loadReport).toHaveBeenCalledWith({
      reportType: 'EXCEPTIONS',
      period: '2026-06',
      companyId: 'company-b',
      status: 'PENDING_REVIEW',
      expectedProjectionVersion: 'FORMAL-DASHBOARD-V1',
      page: 0,
      size: 50,
    });
  });

  it('labels the provisional attendance-rate formula before business sign-off', async () => {
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReport: async (query) => formalReport(query, {
            formulaVersion:
              'ATTENDANCE_RATE_CONFIRMED_OVER_SCHEDULED_V1_PROVISIONAL',
          }),
        })}
        initialReportType="ATTENDANCE_RATE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_RATE · 2026-07',
    })).toBeInTheDocument();
    expect(screen.getByText(/当前出勤率为暂行口径/))
      .toHaveTextContent('排班内确认工作分钟 ÷ 原始应出勤分钟 × 100%');
    expect(screen.getByText('计算公式版本').nextElementSibling)
      .toHaveTextContent(
        'ATTENDANCE_RATE_CONFIRMED_OVER_SCHEDULED_V1_PROVISIONAL',
      );
  });

  it('filters the exception report by a visible status selector', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialReportType="EXCEPTIONS"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'EXCEPTIONS · 2026-07',
    })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('异常状态'), {
      target: { value: 'PENDING_EVIDENCE' },
    });

    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'EXCEPTIONS',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        status: 'PENDING_EVIDENCE',
        expectedProjectionVersion: 'FORMAL-EXCEPTIONS-2026-07-V1',
        page: 0,
        size: 50,
      });
    });
  });

  it('pages through every ordinary formal report instead of stopping at the first 50 rows', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query, {
        rowCount: 120,
        totalPages: 3,
      }));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialReportType="LEAVE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'LEAVE · 2026-07',
    })).toBeInTheDocument();
    let pagination = screen.getByLabelText('报表分页');
    expect(pagination).toHaveTextContent('第 1 / 3 页 · 每页 50 行');
    expect(within(pagination).getByRole('button', { name: '上一页' }))
      .toBeDisabled();

    fireEvent.click(within(pagination).getByRole('button', {
      name: '下一页',
    }));
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'LEAVE',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        expectedProjectionVersion: 'FORMAL-LEAVE-2026-07-V1',
        page: 1,
        size: 50,
      });
    });
    pagination = await screen.findByLabelText('报表分页');
    expect(pagination).toHaveTextContent('第 2 / 3 页 · 每页 50 行');

    fireEvent.click(within(pagination).getByRole('button', {
      name: '下一页',
    }));
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'LEAVE',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        expectedProjectionVersion: 'FORMAL-LEAVE-2026-07-V1',
        page: 2,
        size: 50,
      });
    });
    pagination = await screen.findByLabelText('报表分页');
    expect(within(pagination).getByRole('button', { name: '下一页' }))
      .toBeDisabled();

    fireEvent.click(within(pagination).getByRole('button', {
      name: '上一页',
    }));
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'LEAVE',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        expectedProjectionVersion: 'FORMAL-LEAVE-2026-07-V1',
        page: 1,
        size: 50,
      });
    });
  });

  it('clears combined results and reloads page one after a projection change', async () => {
    const newestProjection = deferred<LiveReportProjection>();
    const loadReport = vi.fn((query?: ReportQuery) => {
      const call = loadReport.mock.calls.length;
      if (call === 1) {
        return Promise.resolve(formalReport(query, {
          rows: [{
            rowReference: 'old-row',
            values: { scope: '旧版授权结果' },
          }],
          rowCount: 120,
          totalPages: 3,
        }));
      }
      if (call === 2) {
        return Promise.reject(new ApiRequestError(409, {
          code: 'ATTENDANCE_REPORT_PROJECTION_CHANGED',
          message: '报表数据版本已更新',
          retryable: false,
        }));
      }
      return newestProjection.promise;
    });
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialReportType="LEAVE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findAllByText('旧版授权结果'))
      .not.toHaveLength(0);
    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));

    await waitFor(() => expect(loadReport).toHaveBeenCalledTimes(3));
    expect(loadReport.mock.calls[1]?.[0]).toMatchObject({
      page: 1,
      expectedProjectionVersion: 'FORMAL-LEAVE-2026-07-V1',
    });
    expect(loadReport.mock.calls[2]?.[0]).toMatchObject({
      page: 0,
    });
    expect(loadReport.mock.calls[2]?.[0])
      .not.toHaveProperty('expectedProjectionVersion');
    expect(screen.queryAllByText('旧版授权结果')).toHaveLength(0);
    expect(screen.getByLabelText('正在加载')).toBeInTheDocument();

    const latestQuery = loadReport.mock.calls[2]?.[0];
    const latest = formalReport(latestQuery, {
      rows: [{
        rowReference: 'latest-row',
        values: { scope: '新版授权结果' },
      }],
      rowCount: 120,
      totalPages: 3,
    });
    newestProjection.resolve({
      ...latest,
      metadata: {
        ...latest.metadata,
        projectionVersion: 'FORMAL-LEAVE-2026-07-V2',
      },
    });

    expect((await screen.findAllByText('新版授权结果')).length)
      .toBeGreaterThan(0);
    expect(screen.getByLabelText('报表分页'))
      .toHaveTextContent('第 1 / 3 页');
  });

  it('drops a stale dashboard version from the URL before rebasing', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) => {
      if (query?.expectedProjectionVersion !== undefined) {
        throw new ApiRequestError(409, {
          code: 'ATTENDANCE_REPORT_PROJECTION_CHANGED',
          message: '报表数据版本已更新',
          retryable: false,
        });
      }
      return formalReport(query);
    });
    renderWithRouter(
      <>
        <ReportsRoute
          gateway={gateway({ loadReport })}
          initialReportType="LEAVE"
          initialPeriod="2026-07"
        />
        <LocationProbe />
      </>,
      '/attendance/reports?reportType=LEAVE&period=2026-07'
        + '&companyId=30000000-0000-0000-0000-000000000001'
        + '&expectedProjectionVersion=STALE-DASHBOARD-V1',
    );

    expect(await screen.findByRole('heading', {
      name: 'LEAVE · 2026-07',
    })).toBeInTheDocument();
    expect(loadReport.mock.calls[0]?.[0]).toMatchObject({
      expectedProjectionVersion: 'STALE-DASHBOARD-V1',
      page: 0,
    });
    expect(loadReport.mock.calls.at(-1)?.[0])
      .not.toHaveProperty('expectedProjectionVersion');
    await waitFor(() => {
      expect(screen.getByTestId('location').textContent)
        .not.toContain('expectedProjectionVersion');
    });
  });

  it('resets ordinary report pagination when type, month, or exception status changes', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query, {
        rowCount: 120,
        totalPages: 3,
      }));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialReportType="LEAVE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    await screen.findByRole('heading', { name: 'LEAVE · 2026-07' });
    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]?.page).toBe(1);
    });

    fireEvent.change(screen.getByLabelText('报表类型'), {
      target: { value: 'EXCEPTIONS' },
    });
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'EXCEPTIONS',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        expectedProjectionVersion: 'FORMAL-LEAVE-2026-07-V1',
        page: 0,
        size: 50,
      });
    });

    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]?.page).toBe(1);
    });
    fireEvent.change(screen.getByLabelText('异常状态'), {
      target: { value: 'PENDING_REVIEW' },
    });
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'EXCEPTIONS',
        period: '2026-07',
        companyId: '30000000-0000-0000-0000-000000000001',
        status: 'PENDING_REVIEW',
        expectedProjectionVersion: 'FORMAL-EXCEPTIONS-2026-07-V1',
        page: 0,
        size: 50,
      });
    });

    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]?.page).toBe(1);
    });
    fireEvent.change(screen.getByLabelText('月份'), {
      target: { value: '2026-06' },
    });
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'EXCEPTIONS',
        period: '2026-06',
        companyId: '30000000-0000-0000-0000-000000000001',
        status: 'PENDING_REVIEW',
        page: 0,
        size: 50,
      });
    });
  });

  it('resets ordinary report pagination when the authorized company changes', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query, {
        rowCount: 120,
        totalPages: 3,
      }));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies: async (period) => ({
            period,
            companies: [
              { companyId: 'company-a', companyName: '神州半导体' },
              { companyId: 'company-b', companyName: '神州科技' },
            ],
          }),
          loadReport,
        })}
        initialReportType="LEAVE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByText('请选择公司后查询正式报表。'))
      .toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('公司'), {
      target: { value: 'company-a' },
    });
    await screen.findByRole('heading', { name: 'LEAVE · 2026-07' });
    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]?.page).toBe(1);
    });

    fireEvent.change(screen.getByLabelText('公司'), {
      target: { value: 'company-b' },
    });
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'LEAVE',
        period: '2026-07',
        companyId: 'company-b',
        page: 0,
        size: 50,
      });
    });
  });

  it('keeps the new company projection after an older request returns late', async () => {
    const lateCompanyA = deferred<LiveReportProjection>();
    let lateCompanyAQuery: ReportQuery | undefined;
    const projectionFor = (
      query: ReportQuery,
      projectionVersion: string,
      rowLabel: string,
    ): LiveReportProjection => {
      const projection = formalReport(query, {
        rows: [{
          rowReference: `${query.companyId}:${query.page ?? 0}`,
          values: { scope: rowLabel },
        }],
        rowCount: 120,
        totalPages: 3,
      });
      return {
        ...projection,
        metadata: {
          ...projection.metadata,
          projectionVersion,
        },
      };
    };
    const loadReport = vi.fn((query?: ReportQuery) => {
      if (query === undefined) {
        throw new TypeError('report query is required');
      }
      if (query.companyId === 'company-a' && query.page === 1) {
        lateCompanyAQuery = query;
        return lateCompanyA.promise;
      }
      const isCompanyA = query.companyId === 'company-a';
      return Promise.resolve(projectionFor(
        query,
        isCompanyA ? 'PROJ-A' : 'PROJ-B',
        isCompanyA ? '公司 A 当前结果' : '公司 B 当前结果',
      ));
    });
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies: async (period) => ({
            period,
            companies: [
              { companyId: 'company-a', companyName: '神州半导体' },
              { companyId: 'company-b', companyName: '神州科技' },
            ],
          }),
          loadReport,
        })}
        initialReportType="LEAVE"
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    await screen.findByText('请选择公司后查询正式报表。');
    fireEvent.change(screen.getByLabelText('公司'), {
      target: { value: 'company-a' },
    });
    expect((await screen.findAllByText('公司 A 当前结果')).length)
      .toBeGreaterThan(0);
    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]).toMatchObject({
        companyId: 'company-a',
        expectedProjectionVersion: 'PROJ-A',
        page: 1,
      });
    });

    fireEvent.change(screen.getByLabelText('公司'), {
      target: { value: 'company-b' },
    });
    expect((await screen.findAllByText('公司 B 当前结果')).length)
      .toBeGreaterThan(0);

    const capturedLateCompanyAQuery = lateCompanyAQuery;
    expect(capturedLateCompanyAQuery).toBeDefined();
    if (capturedLateCompanyAQuery === undefined) {
      throw new TypeError('company A page-two query was not captured');
    }
    await act(async () => {
      lateCompanyA.resolve(projectionFor(
        capturedLateCompanyAQuery,
        'PROJ-A',
        '公司 A 迟到结果',
      ));
      await lateCompanyA.promise;
    });

    fireEvent.click(within(screen.getByLabelText('报表分页')).getByRole(
      'button',
      { name: '下一页' },
    ));
    await waitFor(() => {
      expect(loadReport.mock.calls.at(-1)?.[0]).toMatchObject({
        companyId: 'company-b',
        expectedProjectionVersion: 'PROJ-B',
        page: 1,
      });
    });
  });

  it('ignores duplicated or malformed report URL conditions', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies: async (period) => ({
            period,
            companies: [
              { companyId: 'company-a', companyName: '神州半导体' },
              { companyId: 'company-b', companyName: '神州科技' },
            ],
          }),
          loadReport,
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports?reportType=UNSUPPORTED'
        + '&period=2026-06&period=2026-05'
        + '&companyId=%20company-b&status=UNKNOWN',
    );

    expect(await screen.findByText('请选择公司后查询正式报表。'))
      .toBeInTheDocument();
    expect(screen.getByLabelText('报表类型'))
      .toHaveValue('ATTENDANCE_DETAIL');
    expect(screen.getByLabelText('月份')).toHaveValue('2026-07');
    expect(screen.getByLabelText('公司')).toHaveValue('');
    expect(loadReport).not.toHaveBeenCalled();
  });

  it('switches across all nine server report types', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    const typeSelect = screen.getByLabelText('报表类型');
    expect(typeSelect.querySelectorAll('option')).toHaveLength(9);
    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    expect(screen.getByText('计算公式版本').nextElementSibling)
      .toHaveTextContent('ATTENDANCE_DETAIL_FORMULA_V1');

    for (const option of reportTypeOptions.slice(1)) {
      fireEvent.change(typeSelect, { target: { value: option.value } });
      expect(await screen.findByRole('heading', {
        name: `${option.value} · 2026-07`,
      })).toBeInTheDocument();
    }

    expect(loadReport.mock.calls.map(([query]) => query?.reportType))
      .toEqual(reportTypeOptions.map((option) => option.value));
  });

  it('renders every formal status badge in the employee month matrix', async () => {
    const loadAttendanceMonthMatrix = vi.fn(
      async (query: AttendanceMonthMatrixQuery) => formalMonthMatrix(query),
    );
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReport: async (query) => formalReport(query),
          loadAttendanceMonthMatrix,
        })}
        initialPeriod="2026-06"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: '月度考勤明细矩阵',
    })).toBeInTheDocument();
    expect(screen.getByText(/颜色仅用于快速识别/)).toBeInTheDocument();
    expect(screen.getByRole('region', {
      name: '月度考勤矩阵数据概览',
    })).toHaveTextContent('服务端授权组织');
    expect(screen.getByText('员工总数').parentElement)
      .toHaveTextContent('40');
    expect(screen.getByText('矩阵分页').parentElement)
      .toHaveTextContent('第 1 页 / 2 页 · 每页 20 人');
    expect(screen.getByText(
      /导出文件是平铺正式明细/,
    )).toBeInTheDocument();
    const matrix = screen.getByRole('table', {
      name: '正式月度考勤明细矩阵',
    });
    const badges = Array.from(matrix.querySelectorAll(
      '[data-badge-code]',
    ));
    expect(badges.map((badge) => badge.textContent)).toEqual([
      '迟到',
      '早退',
      '补签',
    ]);
    expect(badges[0]).toHaveAttribute('data-badge-code', 'LATE');
    expect(within(matrix).getByText('08:25')).toBeInTheDocument();
    expect(within(matrix).getByText('18:15')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '汇总明细' }))
      .not.toBeInTheDocument();
    expect(loadAttendanceMonthMatrix).toHaveBeenCalledWith({
      period: '2026-06',
      companyId: '30000000-0000-0000-0000-000000000001',
      expectedProjectionVersion:
        'FORMAL-ATTENDANCE_DETAIL-2026-06-V1',
      page: 0,
      size: 20,
    });

    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => {
      expect(loadAttendanceMonthMatrix).toHaveBeenLastCalledWith({
        period: '2026-06',
        companyId: '30000000-0000-0000-0000-000000000001',
        expectedProjectionVersion:
          'FORMAL-ATTENDANCE_DETAIL-2026-06-V1',
        page: 1,
        size: 20,
      });
    });
  });

  it('compares every formal snapshot binding before mixing matrix data', () => {
    const query: ReportQuery = {
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-06',
      companyId: '30000000-0000-0000-0000-000000000001',
      page: 0,
      size: 50,
    };
    const report = formalReport(query);
    const matrix = formalMonthMatrix({
      period: query.period,
      companyId: query.companyId,
      page: 0,
      size: 20,
    });
    expect(attendanceReportMatrixSnapshotsMatch(report, matrix))
      .toBe(true);

    const mismatches: Record<string, AttendanceMonthMatrixProjection> = {
      projectionVersion: {
        ...matrix,
        metadata: {
          ...matrix.metadata,
          projectionVersion: 'different-projection',
        },
      },
      dataAsOf: {
        ...matrix,
        metadata: {
          ...matrix.metadata,
          dataAsOf: '2026-06-30T01:00:00Z',
        },
      },
      sourceVersions: {
        ...matrix,
        metadata: {
          ...matrix.metadata,
          sourceVersions: ['DIFFERENT-SOURCE-V1'],
        },
      },
      periodState: {
        ...matrix,
        metadata: { ...matrix.metadata, periodState: 'CLOSED' },
      },
      scope: {
        ...matrix,
        metadata: {
          ...matrix.metadata,
          scope: {
            ...matrix.metadata.scope,
            reference: 'scope:different',
          },
        },
      },
      filters: {
        ...matrix,
        filters: { ...matrix.filters, companyId: 'company-different' },
      },
    };
    for (const [binding, mismatched] of Object.entries(mismatches)) {
      expect(
        attendanceReportMatrixSnapshotsMatch(report, mismatched),
        binding,
      ).toBe(false);
    }
  });

  it('fails closed in Chinese and removes export when snapshots differ', async () => {
    const loadAttendanceMonthMatrix = vi.fn(
      async (query: AttendanceMonthMatrixQuery) => {
        const matrix = formalMonthMatrix(query);
        return {
          ...matrix,
          metadata: {
            ...matrix.metadata,
            projectionVersion: 'different-projection',
          },
        };
      },
    );
    renderWithRouter(
      <ReportsRoute
        gateway={formalExportGateway({
          loadReport: async (query) => formalReport(query),
          loadAttendanceMonthMatrix,
        })}
        capabilities={['ATTENDANCE_REPORT:EXPORT_CREATE']}
        initialPeriod="2026-06"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByText(
      attendanceReportMatrixSnapshotMismatchMessage,
    )).toBeInTheDocument();
    expect(screen.queryByRole('table', {
      name: '正式月度考勤明细矩阵',
    })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', {
      name: '创建受控导出',
    })).not.toBeInTheDocument();
  });

  it('returns an out-of-range matrix page to the last valid page', async () => {
    const onPageChange = vi.fn();
    const matrix = formalMonthMatrix({
      period: '2026-06',
      companyId: '30000000-0000-0000-0000-000000000001',
      page: 2,
      size: 20,
    });
    render(
      <AttendanceMonthMatrixView
        matrix={{ ...matrix, rows: [], totalPages: 2 }}
        onPageChange={onPageChange}
      />,
    );

    expect(screen.getByText(
      '当前页码已超出有效范围，正在返回最后一页。',
    )).toBeInTheDocument();
    await waitFor(() => {
      expect(onPageChange).toHaveBeenCalledWith(1);
    });
  });

  it('formats matrix punches with the projection metadata time zone', () => {
    const matrix = formalMonthMatrix({
      period: '2026-06',
      companyId: '30000000-0000-0000-0000-000000000001',
      page: 0,
      size: 20,
    });
    render(
      <AttendanceMonthMatrixView
        matrix={{
          ...matrix,
          metadata: { ...matrix.metadata, timeZone: 'UTC' },
        }}
      />,
    );

    const table = screen.getByRole('table', {
      name: '正式月度考勤明细矩阵',
    });
    expect(within(table).getByText('00:25')).toBeInTheDocument();
    expect(within(table).getByText('10:15')).toBeInTheDocument();
    expect(within(table).queryByText('08:25')).not.toBeInTheDocument();
  });

  it('clears old authorized rows synchronously before a month reload', async () => {
    let requestCount = 0;
    const loadReport = vi.fn((query?: ReportQuery) => {
      requestCount += 1;
      if (requestCount === 1) {
        return Promise.resolve(formalReport(query, {
          rows: [{
            rowReference: 'old-sensitive-row',
            values: { scope: '旧授权范围敏感行' },
          }],
          rowCount: 1,
          totalPages: 1,
        }));
      }
      return new Promise<LiveReportProjection>(() => undefined);
    });
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findAllByText('旧授权范围敏感行'))
      .not.toHaveLength(0);
    fireEvent.change(screen.getByLabelText('月份'), {
      target: { value: '2026-06' },
    });

    expect(screen.queryAllByText('旧授权范围敏感行')).toHaveLength(0);
    expect(screen.getByLabelText('正在加载')).toHaveAttribute(
      'aria-busy',
      'true',
    );
    await waitFor(() => {
      expect(loadReport).toHaveBeenLastCalledWith({
        reportType: 'ATTENDANCE_DETAIL',
        period: '2026-06',
        companyId: '30000000-0000-0000-0000-000000000001',
        page: 0,
        size: 50,
      });
    });
  });

  it('requires an explicit company choice when multiple companies are authorized', async () => {
    const loadReport = vi.fn(async (query?: ReportQuery) =>
      formalReport(query));
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReportCompanies: async (period) => ({
            period,
            companies: [
              { companyId: 'company-a', companyName: '神州半导体' },
              { companyId: 'company-b', companyName: '神州科技' },
            ],
          }),
          loadReport,
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByText('请选择公司后查询正式报表。'))
      .toBeInTheDocument();
    expect(loadReport).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('公司'), {
      target: { value: 'company-b' },
    });

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    expect(loadReport).toHaveBeenCalledWith({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: 'company-b',
      page: 0,
      size: 50,
    });
  });

  it('covers report loading, non-leaking 403, and authorized empty states', async () => {
    const pending = new Promise<LiveReportProjection>(() => undefined);
    const { unmount } = renderWithRouter(
      <ReportsRoute
        gateway={gateway({ loadReport: () => pending })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );
    expect(screen.getByLabelText('正在加载')).toHaveAttribute(
      'aria-busy',
      'true',
    );
    unmount();

    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReport: async () => Promise.reject(new ApiRequestError(403, {
            code: 'REPORT_SCOPE_DENIED',
            message: '服务端敏感范围说明',
          })),
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );
    expect(await screen.findByText('当前账号无权访问该内容。'))
      .toBeInTheDocument();
    expect(screen.queryByText('服务端敏感范围说明'))
      .not.toBeInTheDocument();
    cleanup();

    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReport: async (query) => formalReport(query, {
            rows: [],
            rowCount: 0,
            totalPages: 0,
          }),
        })}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );
    expect(await screen.findByText('当前授权范围内暂无可显示数据。'))
      .toBeInTheDocument();
  });

  it('creates a formal export with a transient password and only visible filters', async () => {
    const pendingCreate = deferred<AttendanceReportExportView>();
    const createReportExport = vi.fn((
      request: ReportExportCreateRequest,
    ) => {
      void request;
      return pendingCreate.promise;
    });
    renderWithRouter(
      <ReportsRoute
        gateway={gateway({
          loadReport: async (query) => formalReport(query),
          createReportExport,
          loadReportExport: async () => formalExport(),
          downloadReportExport: async () => ({
            blob: new Blob(['xlsx'], { type: xlsxMediaType }),
            fileName: 'attendance-report.xlsx',
          }),
        })}
        capabilities={[
          'ATTENDANCE_REPORT:EXPORT_CREATE',
          'ATTENDANCE_REPORT:EXPORT_DOWNLOAD',
        ]}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {
      name: '创建受控导出',
    }));
    fireEvent.change(screen.getByLabelText('导出用途'), {
      target: { value: ' 月度考勤复核 ' },
    });
    const passwordInput = screen.getByLabelText('当前密码');
    fireEvent.change(passwordInput, {
      target: { value: 'Current#Password123' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建导出' }));

    expect(createReportExport).toHaveBeenCalledWith({
      reportType: 'ATTENDANCE_DETAIL',
      projectionVersion: 'FORMAL-ATTENDANCE_DETAIL-2026-07-V1',
      queryFingerprint: 'f'.repeat(64),
      scopeReference: 'scope:server-authorized',
      filters: {
        period: '2026-07',
        scopeReference: 'scope:server-authorized',
        companyId: '30000000-0000-0000-0000-000000000001',
        status: null,
      },
      selectedFields: [
        'scope',
        'scheduled-hours',
        'confirmed-hours',
        'late-count',
        'recognized-overtime-hours',
        'leave-hours',
      ],
      purpose: '月度考勤复核',
      currentPassword: 'Current#Password123',
    });
    const request = createReportExport.mock.calls[0]?.[0];
    expect(request?.filters).not.toHaveProperty('organizationId');
    expect(request?.filters).not.toHaveProperty('employeeId');
    expect(passwordInput).toHaveValue('');
    expect(window.location.href).not.toContain('Current#Password123');
    expect(storageContents()).not.toContain('Current#Password123');

    await act(async () => {
      pendingCreate.resolve(formalExport());
      await pendingCreate.promise;
    });
    expect(await screen.findByRole('heading', { name: '导出任务' }))
      .toBeInTheDocument();
    expect(screen.getByText('已就绪')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('Current#Password123'))
      .not.toBeInTheDocument();
  });

  it('clears the current password when the formal create dialog closes', async () => {
    renderWithRouter(
      <ReportsRoute
        gateway={formalExportGateway()}
        capabilities={['ATTENDANCE_REPORT:EXPORT_CREATE']}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {
      name: '创建受控导出',
    }));
    fireEvent.change(screen.getByLabelText('当前密码'), {
      target: { value: 'Close#Password123' },
    });
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    expect(screen.getByLabelText('当前密码')).toHaveValue('');

    fireEvent.click(screen.getByRole('button', {
      name: '创建受控导出',
    }));
    for (const input of screen.getAllByLabelText('当前密码')) {
      expect(input).toHaveValue('');
    }
    expect(window.location.href).not.toContain('Close#Password123');
    expect(storageContents()).not.toContain('Close#Password123');
  });

  it('does not render sensitive server details after reauthentication fails', async () => {
    const createReportExport = vi.fn(async () => Promise.reject(
      new ApiRequestError(401, {
        code: 'REAUTHENTICATION_FAILED',
        message: '服务端敏感凭证诊断信息',
        retryable: false,
      }),
    ));
    renderWithRouter(
      <ReportsRoute
        gateway={formalExportGateway({ createReportExport })}
        capabilities={['ATTENDANCE_REPORT:EXPORT_CREATE']}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', {
      name: '创建受控导出',
    }));
    fireEvent.change(screen.getByLabelText('导出用途'), {
      target: { value: '月度复核' },
    });
    fireEvent.change(screen.getByLabelText('当前密码'), {
      target: { value: 'Wrong#Password123' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建导出' }));

    expect(await screen.findByText('当前密码验证失败，导出未创建。'))
      .toBeInTheDocument();
    expect(screen.queryByText('服务端敏感凭证诊断信息'))
      .not.toBeInTheDocument();
    expect(screen.getByLabelText('当前密码')).toHaveValue('');
  });

  it('supports manual refresh through queued, building, and failed async states', async () => {
    const queued = formalExport({
      deliveryMode: 'ASYNC',
      status: 'QUEUED',
      completedAt: undefined,
    });
    const loadReportExport = vi.fn()
      .mockResolvedValueOnce({
        ...queued,
        status: 'BUILDING',
      })
      .mockResolvedValueOnce({
        ...queued,
        status: 'FAILED',
        completedAt: '2026-07-29T02:00:00Z',
      });
    renderWithRouter(
      <ReportsRoute
        gateway={formalExportGateway({
          createReportExport: async () => queued,
          loadReportExport,
        })}
        capabilities={['ATTENDANCE_REPORT:EXPORT_CREATE']}
        initialPeriod="2026-07"
      />,
      '/attendance/reports',
    );

    expect(await screen.findByRole('heading', {
      name: 'ATTENDANCE_DETAIL · 2026-07',
    })).toBeInTheDocument();
    await submitFormalExport('异步报表复核', 'Current#Password123');
    expect(await screen.findByText('排队中')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', {
      name: '刷新导出状态',
    }));
    expect(await screen.findByText('运行中')).toBeInTheDocument();
    const refreshButton = await waitFor(() => {
      const button = screen.getByRole('button', {
        name: /刷新导出状态/,
      });
      expect(button).toBeEnabled();
      return button;
    });
    fireEvent.click(refreshButton);
    expect(await screen.findByText('失败')).toBeInTheDocument();
    expect(screen.getByText('导出生成失败，请重新创建。'))
      .toBeInTheDocument();
    expect(loadReportExport).toHaveBeenCalledTimes(2);
    expect(loadReportExport).toHaveBeenNthCalledWith(
      1,
      queued.exportId,
    );
  });

  it('reauthenticates for XLSX download and clears the password immediately', async () => {
    const pendingDownload = deferred<{
      blob: Blob;
      fileName: string;
    }>();
    const downloadReportExport = vi.fn(() => pendingDownload.promise);
    const originalCreateObjectUrl = URL.createObjectURL;
    const originalRevokeObjectUrl = URL.revokeObjectURL;
    const createObjectUrl = vi.fn(() => 'blob:formal-report');
    const revokeObjectUrl = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: createObjectUrl,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectUrl,
    });
    const anchorClick = vi.spyOn(
      HTMLAnchorElement.prototype,
      'click',
    ).mockImplementation(() => {});

    try {
      renderWithRouter(
        <ReportsRoute
          gateway={formalExportGateway({
            createReportExport: async () => formalExport(),
            downloadReportExport,
          })}
          capabilities={[
            'ATTENDANCE_REPORT:EXPORT_CREATE',
            'ATTENDANCE_REPORT:EXPORT_DOWNLOAD',
          ]}
          initialPeriod="2026-07"
        />,
        '/attendance/reports',
      );

      expect(await screen.findByRole('heading', {
        name: 'ATTENDANCE_DETAIL · 2026-07',
      })).toBeInTheDocument();
      await submitFormalExport('下载复核', 'Create#Password123');
      expect(await screen.findByText('已就绪')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: '下载文件' }));
      const passwordInput = screen.getByLabelText(
        '当前密码',
        { selector: '#wave7-download-current-password' },
      );
      fireEvent.change(passwordInput, {
        target: { value: 'Download#Password123' },
      });
      fireEvent.click(screen.getByRole('button', {
        name: '验证并下载',
      }));

      expect(downloadReportExport).toHaveBeenCalledWith(
        formalExportId,
        'Download#Password123',
      );
      expect(passwordInput).toHaveValue('');
      expect(window.location.href).not.toContain('Download#Password123');
      expect(storageContents()).not.toContain('Download#Password123');

      const file = {
        blob: new Blob(['xlsx'], { type: xlsxMediaType }),
        fileName: 'attendance-report-2026-07.xlsx',
      };
      await act(async () => {
        pendingDownload.resolve(file);
        await pendingDownload.promise;
      });
      expect(createObjectUrl).toHaveBeenCalledWith(file.blob);
      expect(anchorClick).toHaveBeenCalledOnce();
      expect(revokeObjectUrl).toHaveBeenCalledWith(
        'blob:formal-report',
      );
      for (const input of screen.getAllByLabelText('当前密码')) {
        expect(input).toHaveValue('');
      }
    } finally {
      anchorClick.mockRestore();
      restoreUrlMethod('createObjectURL', originalCreateObjectUrl);
      restoreUrlMethod('revokeObjectURL', originalRevokeObjectUrl);
    }
  });
});

function renderWithRouter(element: ReactNode, initialPath = '/') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      {element}
    </MemoryRouter>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}{location.search}
    </output>
  );
}

function gateway(
  overrides: Partial<Wave7ProjectionGateway>,
): Wave7ProjectionGateway {
  return {
    ...wave7FixtureGateway,
    ...overrides,
  };
}

function formalExportGateway(
  overrides: Partial<Wave7ProjectionGateway> = {},
): Wave7ProjectionGateway {
  return gateway({
    loadReport: async (query) => formalReport(query),
    createReportExport: async () => formalExport(),
    loadReportExport: async () => formalExport(),
    downloadReportExport: async () => ({
      blob: new Blob(['xlsx'], { type: xlsxMediaType }),
      fileName: 'attendance-report.xlsx',
    }),
    ...overrides,
  });
}

async function submitFormalExport(
  purpose: string,
  currentPassword: string,
) {
  fireEvent.click(screen.getByRole('button', {
    name: '创建受控导出',
  }));
  fireEvent.change(screen.getByLabelText('导出用途'), {
    target: { value: purpose },
  });
  fireEvent.change(screen.getByLabelText('当前密码'), {
    target: { value: currentPassword },
  });
  fireEvent.click(screen.getByRole('button', { name: '创建导出' }));
  expect(await screen.findByRole('heading', { name: '导出任务' }))
    .toBeInTheDocument();
}

const formalExportId = '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f';
const xlsxMediaType =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

function formalExport(
  overrides: Partial<AttendanceReportExportView> = {},
): AttendanceReportExportView {
  return {
    exportId: formalExportId,
    reportType: 'ATTENDANCE_DETAIL',
    period: '2026-07',
    companyId: '30000000-0000-0000-0000-000000000001',
    deliveryMode: 'SYNC',
    status: 'READY',
    purpose: '月度考勤复核',
    rowCount: 2,
    expiresAt: '2026-07-30T01:00:00Z',
    completedAt: '2026-07-29T01:00:00Z',
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function storageContents(): string {
  const entries: string[] = [];
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const key = window.localStorage.key(index);
    if (key !== null) {
      entries.push(`${key}=${window.localStorage.getItem(key) ?? ''}`);
    }
  }
  return entries.join('\n');
}

function restoreUrlMethod(
  key: 'createObjectURL' | 'revokeObjectURL',
  value: typeof URL.createObjectURL | typeof URL.revokeObjectURL | undefined,
) {
  if (value) {
    Object.defineProperty(URL, key, { configurable: true, value });
    return;
  }
  Reflect.deleteProperty(URL, key);
}

function liveDashboard(
  overrides: Partial<LiveDashboardProjection> = {},
): LiveDashboardProjection {
  const summary = overrides.summary ?? {
    unresolvedCount: 1,
    affectedEmployeeCount: 1,
    blockingCount: 1,
  };
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
    metrics: [],
    summary,
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
    analytics: overrides.analytics ?? liveDashboardAnalytics(summary),
    companies: [
      { companyId: 'company-a', companyName: '神州半导体' },
      { companyId: 'company-b', companyName: '神州科技' },
    ],
    ...overrides,
  };
}

function liveDashboardAnalytics(
  summary: LiveDashboardProjection['summary'],
): LiveDashboardProjection['analytics'] {
  return {
    dailyTrend: Array.from({ length: 7 }, (_, index) => ({
      businessDate: `2026-07-${String(index + 24).padStart(2, '0')}`,
      exceptionCount: index === 6 ? summary.unresolvedCount : 0,
      blockingCount: index === 6 ? summary.blockingCount : 0,
      affectedEmployeeCount: index === 6
        ? summary.affectedEmployeeCount
        : 0,
    })),
    severityDistribution: [
      { severity: 'INFO', count: 0 },
      {
        severity: 'WARNING',
        count: summary.unresolvedCount - summary.blockingCount,
      },
      { severity: 'ERROR', count: summary.blockingCount },
    ],
    typeDistribution: summary.unresolvedCount === 0
      ? []
      : [{
          exceptionType: 'MISSING_PUNCH_OVERDUE',
          count: summary.unresolvedCount,
        }],
    organizationRanking: summary.unresolvedCount === 0
      ? []
      : [{
          organizationName: '制造一部',
          exceptionCount: summary.unresolvedCount,
          blockingCount: summary.blockingCount,
        }],
  };
}

function formalReport(
  query?: ReportQuery,
  overrides: Partial<LiveReportProjection> = {},
): LiveReportProjection {
  if (query === undefined) {
    throw new TypeError('report query is required');
  }
  const page = query.page ?? 0;
  const size = query.size ?? 50;
  return {
    ...reportFixture,
    metadata: {
      ...reportFixture.metadata,
      projectionVersion: `FORMAL-${query.reportType}-${query.period}-V1`,
      periodLabel: query.period,
      scope: {
        type: 'ORGANIZATION',
        reference: 'scope:server-authorized',
        label: '服务端授权组织',
      },
    },
    reportType: query.reportType,
    reportTitle: `${query.reportType} · ${query.period}`,
    queryFingerprint: 'f'.repeat(64),
    formulaVersion: `${query.reportType}_FORMULA_V1`,
    filters: {
      period: query.period,
      scopeReference: 'scope:server-authorized',
      companyId: query.companyId
        ?? '30000000-0000-0000-0000-000000000001',
      status: query.status ?? null,
    },
    page,
    size,
    totalPages: 1,
    ...overrides,
  };
}

function formalMonthMatrix(
  query: AttendanceMonthMatrixQuery,
): AttendanceMonthMatrixProjection {
  const dates = Array.from({ length: 30 }, (_, index) => (
    `2026-06-${String(index + 1).padStart(2, '0')}`
  ));
  return {
    kind: 'ATTENDANCE_MONTH_MATRIX',
    metadata: {
      ...reportFixture.metadata,
      projectionVersion:
        `FORMAL-ATTENDANCE_DETAIL-${query.period}-V1`,
      periodLabel: query.period,
      scope: {
        type: 'ORGANIZATION',
        reference: 'scope:server-authorized',
        label: '服务端授权组织',
      },
    },
    queryFingerprint: 'a'.repeat(64),
    formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V1',
    filters: {
      period: query.period,
      scopeReference: 'scope:server-authorized',
      companyId: query.companyId
        ?? '30000000-0000-0000-0000-000000000001',
      organizationId: query.organizationId ?? null,
      employeeId: null,
    },
    dates,
    employeeCount: 40,
    rows: [{
      employeeId: `employee-${query.page ?? 0}`,
      employeeNumber: 'SZ001',
      employeeName: '张三',
      organizationId: 'org-a',
      organizationName: '制造一部',
      days: dates.map((date, index) => ({
        date,
        organizationName: index === 0 ? '制造一部' : null,
        shiftLabel: index === 0 ? '扬州总部班次' : null,
        firstPunchAt: index === 0 ? '2026-06-01T00:25:00Z' : null,
        lastPunchAt: index === 0 ? '2026-06-01T10:15:00Z' : null,
        badges: index === 0
          ? ['LATE', 'EARLY_DEPARTURE', 'PUNCH_CORRECTION']
          : [],
      })),
    }],
    page: query.page ?? 0,
    size: query.size ?? 20,
    totalPages: 2,
  };
}

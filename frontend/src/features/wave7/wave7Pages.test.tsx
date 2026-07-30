import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import {
  dashboardFixture,
  feedbackFixture,
  leaveFixture,
  queuedExportFixture,
  recordsFixture,
  reportFixture,
  todayFixture,
  wave7FixtureGateway,
} from '../../test/fixtures/wave7ContractFixtures';
import { DashboardView } from './DashboardPage';
import {
  EmployeeFeedbackRoute,
  EmployeeFeedbackView,
  EmployeeLeaveView,
  EmployeeRecordsView,
  EmployeeTodayRoute,
} from './EmployeeSelfServicePages';
import {
  createReportExportRequest,
  exportDeliveryForRowCount,
  ReportExportStatus,
  ReportView,
  ReportsRoute,
  reportTypeOptions,
} from './ReportsPage';
import type {
  ReportExportCreateRequest,
  ReportQuery,
  Wave7ProjectionGateway,
} from './wave7Gateway';
import type {
  AttendanceReportExportView,
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

    expect(await screen.findByRole('heading', { name: '今日状态' })).toBeInTheDocument();
    expect(screen.getByText(todayFixture.shiftLabel!)).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '员工自助' })).toHaveTextContent('今日记录假期反馈');
    const todayLink = screen.getByRole('link', { name: '今日' });
    expect(todayLink).toHaveClass('is-active');
    todayLink.focus();
    expect(todayLink).toHaveFocus();
  });

  it('renders frozen records with both desktop table and mobile card semantics', () => {
    renderWithRouter(<EmployeeRecordsView projection={recordsFixture} />);

    expect(screen.getByRole('status', { name: '冻结版本' })).toHaveTextContent('冻结版本');
    expect(screen.getByRole('region', { name: '本人每日考勤记录' })).toBeInTheDocument();
    expect(document.querySelectorAll('.record-card')).toHaveLength(recordsFixture.records.length);
    expect(screen.getAllByText(recordsFixture.metadata.projectionVersion).length).toBeGreaterThan(0);
  });

  it('renders balances without client-side synthesis and feedback as plain text', () => {
    const { rerender } = renderWithRouter(<EmployeeLeaveView projection={leaveFixture} />);
    expect(screen.getByText('32.00 小时')).toBeInTheDocument();
    expect(screen.getByText('4.00 天')).toBeInTheDocument();

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
    const pendingGateway = gateway({ loadToday: () => pending });
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
          loadToday: async () => Promise.reject(new ApiRequestError(403, {
            code: 'ACCESS_DENIED',
            message: '不得显示敏感范围内容',
          })),
        })}
        capabilities={['ATTENDANCE_SELF:READ']}
      />,
      '/me/today',
    );
    expect(await screen.findByText('当前账号无权访问该内容。')).toBeInTheDocument();
    expect(screen.queryByText('不得显示敏感范围内容')).not.toBeInTheDocument();
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

    for (const option of reportTypeOptions.slice(1)) {
      fireEvent.change(typeSelect, { target: { value: option.value } });
      expect(await screen.findByRole('heading', {
        name: `${option.value} · 2026-07`,
      })).toBeInTheDocument();
    }

    expect(loadReport.mock.calls.map(([query]) => query?.reportType))
      .toEqual(reportTypeOptions.map((option) => option.value));
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
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      status: null,
      purpose: '月度考勤复核',
      currentPassword: 'Current#Password123',
    });
    const request = createReportExport.mock.calls[0]?.[0];
    expect(request).not.toHaveProperty('organizationId');
    expect(request).not.toHaveProperty('employeeId');
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
    queryFingerprint: `formal:${query.reportType}:${query.period}`,
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

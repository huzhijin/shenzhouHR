import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
} from './ReportsPage';
import type { Wave7ProjectionGateway } from './wave7Gateway';
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
    expect(screen.getByText('QUEUED')).toBeInTheDocument();

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

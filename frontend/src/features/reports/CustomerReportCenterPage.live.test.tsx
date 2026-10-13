import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as CustomerReportApiModule from './customerReportApi';

const apiMocks = vi.hoisted(() => ({
  exportCustomerReport: vi.fn(),
  loadCustomerReport: vi.fn(),
  loadCustomerReportDirectory: vi.fn(),
  loadCustomerReportScopes: vi.fn(),
  recalculateCustomerReportAndWait: vi.fn(),
  loadPublishedReportPin: vi.fn(),
}));
const realRecalculate = vi.hoisted(() => ({ checkAfterUnknownSubmit: undefined as unknown }));
const workbookMocks = vi.hoisted(() => ({
  downloadCustomerReportWorkbook: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: () => false }));
vi.mock('./customerReportApi', async (importOriginal) => {
  const actual = await importOriginal<typeof CustomerReportApiModule>();
  realRecalculate.checkAfterUnknownSubmit = actual.checkAfterUnknownSubmit;
  return {
    ALL_DEPARTMENTS: '全部部门',
    ALL_EMPLOYEES: '全部员工',
    // The pure decision is real; only network-facing calls are mocked.
    checkAfterUnknownSubmit: actual.checkAfterUnknownSubmit,
    ...apiMocks,
  };
});
vi.mock('./customerReportWorkbook', () => workbookMocks);

import type { CustomerReportDataScope } from './customerReportAccess';
import type {
  CustomerReportDemo,
  CustomerReportFilters,
  CustomerReportKey,
} from './customerReportDemo';
import type { CustomerReportDirectoryEntry } from './customerReportApi';
import { CustomerReportCenterPage } from './CustomerReportCenterPage';
import { defaultQueryPeriod } from './queryPeriod';
import { toLeaveRows } from './customerReportMapper';
import { reportFixture } from '../../test/fixtures/wave7ContractFixtures';
import { leaveTypeCases } from '../../test/fixtures/leaveTypeFixtures';

const scopes: readonly CustomerReportDataScope[] = [
  {
    reference: 'company-a',
    type: 'COMPANY',
    label: '第一工厂',
    actorLabel: '授权范围',
    allowedDepartments: [],
    allowedEmployees: [],
  },
  {
    reference: 'company-b',
    type: 'COMPANY',
    label: '第二工厂',
    actorLabel: '授权范围',
    allowedDepartments: [],
    allowedEmployees: [],
  },
];

const duplicateDirectory: readonly CustomerReportDirectoryEntry[] = [
  {
    employeeId: 'employee-a',
    employeeNo: 'A001',
    employee: '张伟',
    organizationId: 'organization-a',
    department: '研发中心',
  },
  {
    employeeId: 'employee-b',
    employeeNo: 'B002',
    employee: '张伟',
    organizationId: 'organization-a',
    department: '研发中心',
  },
  {
    employeeId: 'employee-c',
    employeeNo: 'C003',
    employee: '李雷',
    organizationId: 'organization-b',
    department: '研发中心',
  },
];

describe('customer report center live directory', () => {
  beforeEach(() => {
    apiMocks.exportCustomerReport.mockReset().mockResolvedValue('live');
    workbookMocks.downloadCustomerReportWorkbook.mockReset().mockResolvedValue(undefined);
    apiMocks.loadCustomerReportScopes.mockReset().mockResolvedValue(scopes);
    apiMocks.loadCustomerReportDirectory.mockReset().mockResolvedValue(duplicateDirectory);
    apiMocks.recalculateCustomerReportAndWait.mockReset()
      .mockResolvedValue({ kind: 'completed', projectionVersion: 'new-pin' });
    apiMocks.loadPublishedReportPin.mockReset()
      .mockResolvedValue({ projectionVersion: 'old-pin', dataAsOf: '2026-10-12T01:00:00Z' });
    apiMocks.loadCustomerReport.mockReset().mockImplementation(
      async (
        reportKey: CustomerReportKey,
        filters: CustomerReportFilters,
        scope: CustomerReportDataScope,
      ) => liveReport(reportKey, filters, scope),
    );
  });

  afterEach(() => {
    cleanup();
  });

  it('shows all leave types and keeps breastfeeding in the combined and separate filters', async () => {
    const leaveRows = toLeaveRows({
      ...reportFixture,
      rows: leaveTypeCases.map(([code], index) => ({
        rowReference: `leave-${code}`,
        drillDownReference: `oa-${code}`,
        values: {
          'employee-name': `请假员工${index}`,
          organization: '测试部门',
          'document-type': code,
          'recognized-hours': '1.00',
          'approval-state': code === 'BREASTFEEDING' ? 'PENDING' : 'APPROVED',
        },
      })),
    });
    apiMocks.loadCustomerReport.mockImplementation(async (key, filters, scope) => {
      const report = liveReport(key, filters, scope);
      return key === 'leave'
        ? { ...report, leaveRows, metadata: { ...report.metadata, rowCount: leaveRows.length } }
        : report;
    });

    render(<CustomerReportCenterPage />);
    await waitFor(() => expect(apiMocks.loadCustomerReportDirectory).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));
    await waitFor(() => expect(within(screen.getByTestId('report-table')).getAllByRole('row')).toHaveLength(14));
    const table = within(screen.getByTestId('report-table'));
    for (const [code, label] of leaveTypeCases) {
      expect(table.getByText(label)).toBeInTheDocument();
      expect(table.queryByText(code)).not.toBeInTheDocument();
    }
    expect(table.getByText('审批中')).toBeInTheDocument();
    expect(table.queryByText('PENDING')).not.toBeInTheDocument();
    expect(table.getAllByText('已通过')).toHaveLength(12);
    expect(screen.getByText('计入考勤的请假记录，包含审批中的普通请假，统一折算为小时')).toBeInTheDocument();

    selectOption('请假类型', '孕检/哺乳假');
    fireEvent.click(screen.getByRole('button', { name: '查询请假统计' }));
    expect(table.getAllByRole('row')).toHaveLength(3);
    expect(table.getByText('孕检假')).toBeInTheDocument();
    expect(table.getByText('哺乳假')).toBeInTheDocument();

    selectOption('请假类型', '哺乳假');
    fireEvent.click(screen.getByRole('button', { name: '查询请假统计' }));
    expect(table.getAllByRole('row')).toHaveLength(2);
    expect(table.getByText('哺乳假')).toBeInTheDocument();
    expect(table.queryByText('孕检假')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '重置请假统计筛选' }));
    expect(table.getAllByRole('row')).toHaveLength(14);
  });

  it('uses organization and employee identities for duplicate display names', async () => {
    render(<CustomerReportCenterPage />);

    await waitFor(() => expect(apiMocks.loadCustomerReportDirectory).toHaveBeenCalled());

    openSearchSelect('员工', '张伟');
    expect(screen.getByRole('option', { name: /张伟A001 · 研发中心/ }))
      .toBeInTheDocument();
    expect(screen.getByRole('option', { name: /张伟B002 · 研发中心/ }))
      .toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('员工'), { target: { value: 'B002' } });
    fireEvent.click(screen.getByRole('option', { name: /张伟B002 · 研发中心/ }));

    await waitFor(() => expect(lastReportFilters()).toMatchObject({
      employee: '张伟',
      employeeId: 'employee-b',
    }));
    await waitFor(() => expect(screen.getByTestId('report-table')).toHaveTextContent('B002'));
    expect(screen.getByTestId('report-table')).not.toHaveTextContent('A001');

    openSearchSelect('部门', 'organization-b');
    fireEvent.click(screen.getByRole('option', { name: '研发中心' }));

    await waitFor(() => expect(lastReportFilters()).toMatchObject({
      department: '研发中心',
      employee: '全部员工',
      organizationId: 'organization-b',
      employeeId: undefined,
    }));
    await waitFor(() => expect(screen.getByTestId('report-table')).toHaveTextContent('C003'));
    expect(screen.getByTestId('report-table')).not.toHaveTextContent('B002');

    selectOption('部门', '全部授权部门');
    await waitFor(() => expect(lastReportFilters()).toMatchObject({
      department: '全部部门',
      employee: '全部员工',
      organizationId: undefined,
      employeeId: undefined,
    }));
    expect(screen.getByText('全部部门 · 全部员工')).toBeInTheDocument();
    expect(screen.queryByText('organization-a')).not.toBeInTheDocument();
    expect(screen.queryByText('organization-b')).not.toBeInTheDocument();
  });

  it('updates work-hour overview cards from the filtered sheet instead of empty sibling tabs', async () => {
    render(<CustomerReportCenterPage />);
    fireEvent.click(await screen.findByRole('tab', { name: '个人月度工时' }));

    await waitFor(() => {
      expect(apiMocks.loadCustomerReport.mock.calls.at(-1)?.[0]).toBe('work-hours');
    });
    await waitFor(() => {
      expect(screen.getByLabelText('当前范围概览')).toHaveTextContent('应出勤工时');
      expect(screen.getByLabelText('当前范围概览')).toHaveTextContent('480.0');
    });

    openSearchSelect('部门', 'organization-b');
    fireEvent.click(screen.getByRole('option', { name: '研发中心' }));

    await waitFor(() => expect(lastReportFilters()).toMatchObject({
      department: '研发中心',
      organizationId: 'organization-b',
    }));
    await waitFor(() => {
      const metrics = screen.getByLabelText('当前范围概览');
      expect(metrics).toHaveTextContent('应出勤工时');
      expect(metrics).toHaveTextContent('160.0');
      expect(metrics).not.toHaveTextContent('480.0');
    });
    expect(screen.getByTestId('report-table')).toHaveTextContent('李雷');
  });

  it('defaults the live company scope to 神州半导体 when several companies are authorized', async () => {
    apiMocks.loadCustomerReportScopes.mockResolvedValue([
      {
        reference: 'company-shanghai',
        type: 'COMPANY',
        label: '上海昇州半导体科技有限公司',
        actorLabel: '授权范围',
        allowedDepartments: [],
        allowedEmployees: [],
      },
      {
        reference: 'company-jiangsu',
        type: 'COMPANY',
        label: '江苏神州半导体科技股份有限公司',
        actorLabel: '授权范围',
        allowedDepartments: [],
        allowedEmployees: [],
      },
    ]);

    render(<CustomerReportCenterPage />);

    await waitFor(() => expect(apiMocks.loadCustomerReport).toHaveBeenCalled());
    const lastCall = apiMocks.loadCustomerReport.mock.calls.at(-1);
    expect(lastCall?.[2]).toMatchObject({ reference: 'company-jiangsu' });
    expect(screen.getByText('授权范围 · 江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();
  });

  it('loads an unfiltered directory on any tab and caches every company-month key', async () => {
    render(<CustomerReportCenterPage />);
    const currentMonth = defaultQueryPeriod().format('YYYY-MM');

    await waitFor(() => expect(directoryCallsFor('company-a', currentMonth)).toHaveLength(1));
    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));
    openSearchSelect('员工', 'A001');
    expect(screen.getByRole('option', { name: /张伟A001/ })).toBeInTheDocument();
    dismissSelect('员工');
    expect(directoryCallsFor('company-a', currentMonth)).toHaveLength(1);
    expect(lastReportFilters().month).toBe(currentMonth);
  });

  it('reads dashboard deep-link query parameters onto the official report', async () => {
    const original = window.location.href;
    window.history.replaceState(
      {},
      '',
      '/attendance/reports?reportType=EXCEPTIONS&period=2026-07&companyId=company-b&expectedProjectionVersion=LIVE-abc',
    );
    render(<CustomerReportCenterPage />);

    expect(await screen.findByRole('tab', { name: '考勤异常总览' }))
      .toHaveAttribute('aria-selected', 'true');
    await waitFor(() => {
      expect(apiMocks.loadCustomerReportScopes).toHaveBeenCalledWith('2026-07');
    });
    await waitFor(() => {
      expect(apiMocks.loadCustomerReport).toHaveBeenCalled();
    });
    const lastCall = apiMocks.loadCustomerReport.mock.calls.at(-1);
    expect(lastCall?.[0]).toBe('exceptions');
    expect(lastCall?.[1]).toMatchObject({ month: '2026-07' });
    expect(lastCall?.[2]).toMatchObject({ reference: 'company-b' });
    expect(lastCall?.[3]).toBe('LIVE-abc');
    window.history.replaceState({}, '', original);
  });

  it('shows the realtime snapshot metadata and refreshes every live input', async () => {
    render(<CustomerReportCenterPage />);

    expect(screen.getByRole('button', { name: '导出当前报表' }))
      .toBeInTheDocument();
    expect(await screen.findByText('核算于 2026-08-12 08:00'))
      .toBeInTheDocument();
    expect(screen.getByText(/暂算 · 期间未关闭/)).toBeInTheDocument();
    const sourceCutoffs = screen.getByText(
      '得力截止 2026-08-12 08:00 · OA截止 未同步',
    );
    expect(sourceCutoffs).toBeInTheDocument();
    expect(sourceCutoffs).toHaveAttribute(
      'title',
      expect.stringContaining('MODEL:ATTENDANCE-RULES-V1'),
    );
    await waitFor(() => expect(apiMocks.loadCustomerReportDirectory).toHaveBeenCalled());

    const scopeCalls = apiMocks.loadCustomerReportScopes.mock.calls.length;
    const directoryCalls = apiMocks.loadCustomerReportDirectory.mock.calls.length;
    const reportCalls = apiMocks.loadCustomerReport.mock.calls.length;
    expect(screen.queryByRole('button', { name: '重新计算所选月份' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '刷新数据' }));

    await waitFor(() => {
      expect(apiMocks.loadCustomerReportScopes.mock.calls.length).toBeGreaterThan(scopeCalls);
      expect(apiMocks.loadCustomerReportDirectory.mock.calls.length).toBeGreaterThan(directoryCalls);
      expect(apiMocks.loadCustomerReport.mock.calls.length).toBeGreaterThan(reportCalls);
    });
  });

  it('searches permission scopes by company label/reference and retries directory failures', async () => {
    apiMocks.loadCustomerReportDirectory
      .mockRejectedValueOnce(new Error('目录加载失败'))
      .mockResolvedValue(duplicateDirectory);
    render(<CustomerReportCenterPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('目录加载失败');
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(apiMocks.loadCustomerReportDirectory).toHaveBeenCalledTimes(2));

    openSearchSelect('权限角色', '第二工厂');
    expect(screen.getByRole('option', { name: /授权范围第二工厂/ })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('权限角色'), {
      target: { value: 'company-b' },
    });
    expect(screen.getByRole('option', { name: /授权范围第二工厂/ }))
      .toBeInTheDocument();
  });

  it('shows 重新计算 only for refresh holders and does not treat 刷新数据 as recalculate', async () => {
    const originalUrl = window.location.href;
    window.history.replaceState({}, '', '/attendance/reports?period=2026-09&companyId=company-a&expectedProjectionVersion=old-pin');
    try {
      apiMocks.loadCustomerReport.mockImplementation(
        async (
          reportKey: CustomerReportKey,
          filters: CustomerReportFilters,
          scope: CustomerReportDataScope,
        ) => ({
          ...liveReport(reportKey, filters, scope),
          metadata: {
            ...liveReport(reportKey, filters, scope).metadata,
            allowedActions: ['REPORT_DRILL_DOWN', 'REPORT_RECALCULATE'],
            sourcesNewerThanPin: true,
          },
        }),
      );
      render(
        <CustomerReportCenterPage
          capabilities={['ATTENDANCE_REPORT:READ', 'ATTENDANCE_REPORT:REFRESH']}
        />,
      );

      expect(await screen.findByText('来源已更新，可重新计算')).toBeInTheDocument();
      expect(apiMocks.loadCustomerReport.mock.calls.at(-1)?.[3]).toBe('old-pin');
      fireEvent.click(screen.getByRole('button', { name: '重新计算所选月份' }));
      await waitFor(() => {
        expect(apiMocks.recalculateCustomerReportAndWait).toHaveBeenCalledWith(
          'company-a',
          '2026-09',
          'MONTH',
          expect.objectContaining({ signal: expect.any(AbortSignal) }),
        );
      });
      await waitFor(() => {
        expect(apiMocks.loadCustomerReport.mock.calls.at(-1)?.[3]).toBeUndefined();
      });
      expect(new URL(window.location.href).searchParams.get('expectedProjectionVersion')).toBeNull();
      expect(new URL(window.location.href).searchParams.get('period')).toBe('2026-09');
      expect(apiMocks.loadCustomerReportScopes).toHaveBeenCalled();
    } finally {
      window.history.replaceState({}, '', originalUrl);
    }
  });

  it('downloads the on-screen workbook instead of a live snapshot job', async () => {
    render(<CustomerReportCenterPage />);
    await waitFor(() => expect(apiMocks.loadCustomerReport).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: '导出当前报表' }));

    await waitFor(() => {
      expect(workbookMocks.downloadCustomerReportWorkbook).toHaveBeenCalledOnce();
    });
    expect(apiMocks.exportCustomerReport).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).toHaveTextContent('已按当前屏幕导出');
    expect(screen.queryByText('正在按当前 LIVE 快照生成导出…')).not.toBeInTheDocument();
  });
});

describe('customer report center export and recalculate boundaries', () => {
  beforeEach(() => {
    workbookMocks.downloadCustomerReportWorkbook.mockReset().mockResolvedValue(undefined);
    apiMocks.loadCustomerReportScopes.mockReset().mockResolvedValue(scopes);
    apiMocks.loadCustomerReportDirectory.mockReset().mockResolvedValue(duplicateDirectory);
    apiMocks.recalculateCustomerReportAndWait.mockReset();
    apiMocks.loadPublishedReportPin.mockReset();
  });

  afterEach(() => {
    cleanup();
  });

  const refreshCapabilities = ['ATTENDANCE_REPORT:READ', 'ATTENDANCE_REPORT:REFRESH',
    'ATTENDANCE_REPORT:EXPORT_CREATE'];

  function recalculableReport(
    reportKey: CustomerReportKey,
    filters: CustomerReportFilters,
    scope: CustomerReportDataScope,
  ): CustomerReportDemo {
    const report = liveReport(reportKey, filters, scope);
    return { ...report, metadata: { ...report.metadata, allowedActions: ['REPORT_RECALCULATE'] } };
  }

  async function waitForInitialReport() {
    // The toolbar mounts before the initial company and report finish loading.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /导出当前报表/ })).toBeEnabled();
    });
  }

  it('keeps export disabled after the first partial page and enables it only once every page arrived', async () => {
    let finish: (report: CustomerReportDemo) => void = () => undefined;
    apiMocks.loadCustomerReport.mockReset().mockImplementation((
      reportKey: CustomerReportKey,
      filters: CustomerReportFilters,
      scope: CustomerReportDataScope,
      _expected: string | undefined,
      onPartial?: (report: CustomerReportDemo) => void,
    ) => new Promise<CustomerReportDemo>((resolve) => {
      onPartial?.(liveReport(reportKey, filters, scope));
      finish = () => resolve(liveReport(reportKey, filters, scope));
    }));
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    const exportButton = await screen.findByRole('button', { name: /导出当前报表/ });
    await waitFor(() => expect(apiMocks.loadCustomerReport).toHaveBeenCalled());
    // The first page is on screen, yet the load is unfinished.
    expect(exportButton).toBeDisabled();
    fireEvent.click(exportButton);
    expect(workbookMocks.downloadCustomerReportWorkbook).not.toHaveBeenCalled();
    finish(liveReport('attendance-detail', { month: defaultQueryPeriod().format('YYYY-MM') } as CustomerReportFilters, scopes[0]!));
    await waitFor(() => expect(exportButton).toBeEnabled());
  });

  it('never exports the pages that arrived before a later page failed', async () => {
    apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
      reportKey: CustomerReportKey,
      filters: CustomerReportFilters,
      scope: CustomerReportDataScope,
      _expected: string | undefined,
      onPartial?: (report: CustomerReportDemo) => void,
    ) => {
      onPartial?.(liveReport(reportKey, filters, scope));
      throw new Error('报表分页记录不完整，请重新查询。');
    });
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    expect(await screen.findByText('报表分页记录不完整，请重新查询。')).toBeInTheDocument();
    const exportButton = screen.getByRole('button', { name: /导出当前报表/ });
    expect(exportButton).toBeDisabled();
    fireEvent.click(exportButton);
    expect(workbookMocks.downloadCustomerReportWorkbook).not.toHaveBeenCalled();
  });

  it('shows an unknown submit outcome, locks every recalculate button, and never resubmits', async () => {
    apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
      reportKey: CustomerReportKey, filters: CustomerReportFilters, scope: CustomerReportDataScope,
    ) => recalculableReport(reportKey, filters, scope));
    apiMocks.recalculateCustomerReportAndWait.mockImplementation(async (
      _company: string, _period: string, _window: string,
      options: { onWaiting?: () => void },
    ) => {
      options.onWaiting?.();
      return { kind: 'unconfirmed', beforeVersion: 'old-pin' };
    });
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    await waitForInitialReport();
    fireEvent.click(screen.getByRole('button', { name: /重新计算所选月份/ }));
    expect(await screen.findByText(/重新计算的结果尚未确认/)).toBeInTheDocument();
    expect(screen.queryByText(/重新计算失败/)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /重新计算所选月份/ }).getAttribute('class') ?? '')
        .not.toContain('ant-btn-loading');
    });
    for (const name of [/重新计算近3天/, /重新计算近一周/, /重新计算所选月份/]) {
      const button = screen.getByRole('button', { name });
      expect(button).toBeDisabled();
      fireEvent.click(button);
    }
    expect(apiMocks.recalculateCustomerReportAndWait).toHaveBeenCalledTimes(1);
  });

  it('keeps the lock while the refresh check is reading and after it fails, and unlocks only on a successful read', async () => {
    apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
      reportKey: CustomerReportKey, filters: CustomerReportFilters, scope: CustomerReportDataScope,
    ) => recalculableReport(reportKey, filters, scope));
    apiMocks.recalculateCustomerReportAndWait.mockResolvedValue({ kind: 'unconfirmed', beforeVersion: 'old-pin' });
    let resolveRead: (value: unknown) => void = () => undefined;
    let rejectRead: (error: unknown) => void = () => undefined;
    apiMocks.loadPublishedReportPin.mockImplementation(() => new Promise((resolve, reject) => {
      resolveRead = resolve;
      rejectRead = reject;
    }));
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    await waitForInitialReport();
    fireEvent.click(screen.getByRole('button', { name: /重新计算所选月份/ }));
    await screen.findByText(/重新计算的结果尚未确认/);
    const month = () => screen.getByRole('button', { name: /重新计算所选月份/ });
    // Tracking has ended; the manual check becomes available only now.
    await waitFor(() => expect(screen.getByRole('button', { name: /刷新数据/ })).toBeEnabled());

    // A read in flight decides nothing yet.
    fireEvent.click(screen.getByRole('button', { name: /刷新数据/ }));
    expect(await screen.findByText(/正在核对报表版本/)).toBeInTheDocument();
    expect(month()).toBeDisabled();

    // A failed read keeps the outcome unknown and does not become a failure.
    rejectRead(new Error('network'));
    expect(await screen.findByText(/核对未完成/)).toBeInTheDocument();
    expect(screen.queryByText(/重新计算失败/)).not.toBeInTheDocument();
    expect(month()).toBeDisabled();

    // A successful read that still shows the old version unlocks the user's own decision.
    fireEvent.click(screen.getByRole('button', { name: /刷新数据/ }));
    await waitFor(() => expect(apiMocks.loadPublishedReportPin).toHaveBeenCalledTimes(2));
    resolveRead({ projectionVersion: 'old-pin', dataAsOf: '2026-10-12T01:00:00Z' });
    expect(await screen.findByText(/不能说明上次提交已结束/)).toBeInTheDocument();
    await waitFor(() => expect(month()).toBeEnabled());
    expect(apiMocks.recalculateCustomerReportAndWait).toHaveBeenCalledTimes(1);
  });

  it('disables manual refresh while automatic tracking runs, so no second tracker replaces it', async () => {
    apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
      reportKey: CustomerReportKey, filters: CustomerReportFilters, scope: CustomerReportDataScope,
    ) => recalculableReport(reportKey, filters, scope));
    let finish: (value: unknown) => void = () => undefined;
    apiMocks.recalculateCustomerReportAndWait.mockImplementation((
      _company: string, _period: string, _window: string,
      options: { onWaiting?: (before: string | null) => void },
    ) => new Promise((resolve) => {
      options.onWaiting?.('old-pin');
      finish = resolve;
    }));
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    await waitForInitialReport();
    fireEvent.click(screen.getByRole('button', { name: /重新计算所选月份/ }));
    expect(await screen.findByText(/结果未知，正在只读核对/)).toBeInTheDocument();
    const refresh = screen.getByRole('button', { name: /刷新数据/ });
    expect(refresh).toBeDisabled();
    fireEvent.click(refresh);
    expect(apiMocks.loadPublishedReportPin).not.toHaveBeenCalled();
    // When tracking ends unconfirmed, loading is released and the manual check becomes available
    // with the baseline recorded during tracking.
    finish({ kind: 'unconfirmed', beforeVersion: 'old-pin' });
    expect(await screen.findByText(/重新计算的结果尚未确认/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /刷新数据/ })).toBeEnabled());
    apiMocks.loadPublishedReportPin.mockResolvedValue({ projectionVersion: 'new-pin', dataAsOf: 'x' });
    fireEvent.click(screen.getByRole('button', { name: /刷新数据/ }));
    expect(await screen.findByText(/已有新版本/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /重新计算所选月份/ })).toBeEnabled());
    expect(apiMocks.recalculateCustomerReportAndWait).toHaveBeenCalledTimes(1);
  });

  it('a manual check that finds a new version drops the deep link old pin before reloading', async () => {
    const originalUrl = window.location.href;
    window.history.replaceState({}, '',
      '/attendance/reports?period=2026-09&companyId=company-a&expectedProjectionVersion=old-pin');
    try {
      apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
        reportKey: CustomerReportKey, filters: CustomerReportFilters, scope: CustomerReportDataScope,
      ) => recalculableReport(reportKey, filters, scope));
      apiMocks.recalculateCustomerReportAndWait.mockResolvedValue({ kind: 'unconfirmed', beforeVersion: 'old-pin' });
      apiMocks.loadPublishedReportPin.mockResolvedValue({ projectionVersion: 'new-pin', dataAsOf: 'x' });
      render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
      await waitFor(() => expect(apiMocks.loadCustomerReport.mock.calls.at(-1)?.[3]).toBe('old-pin'));
      fireEvent.click(await screen.findByRole('button', { name: /重新计算所选月份/ }));
      await screen.findByText(/重新计算的结果尚未确认/);
      // The unknown outcome alone must not touch the pin.
      expect(new URL(window.location.href).searchParams.get('expectedProjectionVersion')).toBe('old-pin');
      await waitFor(() => expect(screen.getByRole('button', { name: /刷新数据/ })).toBeEnabled());
      const loadsBefore = apiMocks.loadCustomerReport.mock.calls.length;
      fireEvent.click(screen.getByRole('button', { name: /刷新数据/ }));
      expect(await screen.findByText(/已有新版本/)).toBeInTheDocument();
      await waitFor(() => expect(apiMocks.loadCustomerReport.mock.calls.length).toBeGreaterThan(loadsBefore));
      // Every load after the check runs without the old pin restriction.
      for (const call of apiMocks.loadCustomerReport.mock.calls.slice(loadsBefore)) {
        expect(call[3]).toBeUndefined();
      }
      expect(new URL(window.location.href).searchParams.get('expectedProjectionVersion')).toBeNull();
      expect(new URL(window.location.href).searchParams.get('period')).toBe('2026-09');
    } finally {
      window.history.replaceState({}, '', originalUrl);
    }
  });

  it('switching company drops the old tracking result instead of refreshing the new view', async () => {
    apiMocks.loadCustomerReport.mockReset().mockImplementation(async (
      reportKey: CustomerReportKey, filters: CustomerReportFilters, scope: CustomerReportDataScope,
    ) => recalculableReport(reportKey, filters, scope));
    let receivedSignal: AbortSignal | undefined;
    apiMocks.recalculateCustomerReportAndWait.mockImplementation((
      _company: string, _period: string, _window: string, options: { signal: AbortSignal },
    ) => new Promise((_resolve, reject) => {
      receivedSignal = options.signal;
      options.signal.addEventListener('abort', () => reject(new DOMException('x', 'AbortError')));
    }));
    render(<CustomerReportCenterPage capabilities={refreshCapabilities} />);
    await waitForInitialReport();
    fireEvent.click(screen.getByRole('button', { name: /重新计算所选月份/ }));
    await waitFor(() => expect(receivedSignal).toBeDefined());
    cleanup();
    expect(receivedSignal?.aborted).toBe(true);
  });
});

function liveReport(
  reportKey: CustomerReportKey,
  filters: CustomerReportFilters,
  scope: CustomerReportDataScope,
): CustomerReportDemo {
  const people = duplicateDirectory.filter((entry) => (
    (filters.organizationId === undefined || entry.organizationId === filters.organizationId)
    && (filters.employeeId === undefined || entry.employeeId === filters.employeeId)
  ));
  const attendanceRows = reportKey === 'attendance-detail'
    ? people.map((entry) => ({
      employeeId: entry.employeeId,
      employeeNo: entry.employeeNo,
      organizationId: entry.organizationId,
      department: entry.department,
      employee: entry.employee,
      days: [],
    }))
    : [];
  const workHoursRows = reportKey === 'work-hours'
    ? people.map((entry) => ({
      employeeId: entry.employeeId,
      organizationId: entry.organizationId,
      department: entry.department,
      employee: entry.employee,
      plannedHours: 160,
      overtimeHours: 8,
      leaveHours: 8,
      actualHours: 152,
    }))
    : [];
  const attendanceRateRows = reportKey === 'attendance-rate'
    ? people.map((entry, index) => ({
      id: index + 1,
      employeeId: entry.employeeId,
      organizationId: entry.organizationId,
      department: entry.department,
      employee: entry.employee,
      scheduledDays: 22,
      actualDays: 21,
      sickLeaveDays: 0,
      hours: 152,
      rate: '95.45%',
    }))
    : [];
  const rowCount = attendanceRows.length + workHoursRows.length + attendanceRateRows.length;
  return {
    metadata: {
      isDemo: false,
      company: scope.label,
      generatedAt: '2026-08-12T00:00:00Z',
      sourceVersions: [
        'MODEL:ATTENDANCE-RULES-V1',
        `SOURCE.DELI_CLOUD:2026-08-12T00:00:00Z:${'a'.repeat(64)}`,
        `SOURCE.OA_ATTENDANCE:UNSYNCED:${'b'.repeat(64)}`,
      ],
      month: filters.month,
      monthLabel: monthLabel(filters.month),
      rowCount,
      periodState: 'OPEN',
      dataScope: scope,
    },
    attendanceRows,
    dailyJournalRows: [],
    overtimeDailyRows: [],
    financeOvertimeRows: [],
    leaveRows: [],
    overtimeRows: [],
    workHoursRows,
    attendanceExceptionRows: [],
    lateRows: [],
    missedPunchRows: [],
    attendanceRateRows,
    annualLeaveRows: [],
  };
}

function openSearchSelect(label: string, search: string) {
  const select = screen.getByLabelText(label);
  fireEvent.mouseDown(select);
  fireEvent.change(select, { target: { value: search } });
}

function selectOption(label: string, optionLabel: string) {
  const select = screen.getByLabelText(label);
  fireEvent.mouseDown(select);
  fireEvent.click(screen.getByRole('option', { name: optionLabel }));
}

function dismissSelect(label: string) {
  fireEvent.keyDown(screen.getByLabelText(label), { key: 'Escape', code: 'Escape' });
}

function lastReportFilters(): CustomerReportFilters {
  return apiMocks.loadCustomerReport.mock.calls.at(-1)?.[1] as CustomerReportFilters;
}

function directoryCallsFor(companyId: string, month: string) {
  return apiMocks.loadCustomerReportDirectory.mock.calls.filter(
    ([calledMonth, calledCompany]) => calledMonth === month && calledCompany === companyId,
  );
}

function monthLabel(month: string): string {
  const [year, number] = month.split('-');
  return `${year}年${number}月`;
}

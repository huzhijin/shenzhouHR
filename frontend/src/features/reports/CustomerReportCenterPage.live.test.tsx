import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  exportCustomerReport: vi.fn(),
  loadCustomerReport: vi.fn(),
  loadCustomerReportDirectory: vi.fn(),
  loadCustomerReportScopes: vi.fn(),
  recalculateCustomerReport: vi.fn(),
}));
const workbookMocks = vi.hoisted(() => ({
  downloadCustomerReportWorkbook: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: () => false }));
vi.mock('./customerReportApi', () => ({
  ALL_DEPARTMENTS: '全部部门',
  ALL_EMPLOYEES: '全部员工',
  ...apiMocks,
}));
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
    apiMocks.recalculateCustomerReport.mockReset().mockResolvedValue(undefined);
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
    expect(screen.queryByRole('button', { name: '重新计算本月' })).not.toBeInTheDocument();
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
    fireEvent.click(screen.getByRole('button', { name: '重新计算本月' }));
    await waitFor(() => {
      expect(apiMocks.recalculateCustomerReport).toHaveBeenCalledWith(
        'company-a',
        expect.stringMatching(/^\d{4}-\d{2}$/),
        'MONTH',
      );
    });
    expect(apiMocks.loadCustomerReportScopes).toHaveBeenCalled();
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

function yearMonth(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function monthLabel(month: string): string {
  const [year, number] = month.split('-');
  return `${year}年${number}月`;
}

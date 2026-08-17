import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  exportCustomerReport: vi.fn(),
  loadCustomerReport: vi.fn(),
  loadCustomerReportDirectory: vi.fn(),
  loadCustomerReportScopes: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: () => false }));
vi.mock('./customerReportApi', () => ({
  ALL_DEPARTMENTS: '全部部门',
  ALL_EMPLOYEES: '全部员工',
  ...apiMocks,
}));

import type { CustomerReportDataScope } from './customerReportAccess';
import type {
  CustomerReportDemo,
  CustomerReportFilters,
  CustomerReportKey,
} from './customerReportDemo';
import type { CustomerReportDirectoryEntry } from './customerReportApi';
import { CustomerReportCenterPage } from './CustomerReportCenterPage';

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
    apiMocks.exportCustomerReport.mockReset().mockResolvedValue(undefined);
    apiMocks.loadCustomerReportScopes.mockReset().mockResolvedValue(scopes);
    apiMocks.loadCustomerReportDirectory.mockReset().mockResolvedValue(duplicateDirectory);
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
    fireEvent.click(screen.getByRole('option', { name: /研发中心organization-b/ }));

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
  });

  it('loads an unfiltered directory on any tab and caches every company-month key', async () => {
    render(<CustomerReportCenterPage />);
    const currentMonth = yearMonth(new Date());
    const previousMonth = yearMonth(new Date(
      new Date().getFullYear(),
      new Date().getMonth() - 1,
      1,
    ));

    await waitFor(() => expect(directoryCallsFor('company-a', currentMonth)).toHaveLength(1));
    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));
    selectOption('月份', monthLabel(previousMonth));

    await waitFor(() => expect(directoryCallsFor('company-a', previousMonth)).toHaveLength(1));
    openSearchSelect('员工', 'A001');
    expect(screen.getByRole('option', { name: /张伟A001/ })).toBeInTheDocument();
    dismissSelect('员工');

    selectOption('月份', monthLabel(currentMonth));
    await waitFor(() => expect(lastReportFilters().month).toBe(currentMonth));
    expect(directoryCallsFor('company-a', currentMonth)).toHaveLength(1);
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
  return {
    metadata: {
      isDemo: false,
      company: scope.label,
      generatedAt: '2026-08-12T00:00:00Z',
      month: filters.month,
      monthLabel: monthLabel(filters.month),
      rowCount: attendanceRows.length,
      dataScope: scope,
    },
    attendanceRows,
    leaveRows: [],
    overtimeRows: [],
    workHoursRows: [],
    attendanceExceptionRows: [],
    lateRows: [],
    missedPunchRows: [],
    attendanceRateRows: [],
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

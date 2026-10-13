import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DashboardView } from './DashboardPage';
import type { LiveDashboardProjection } from './wave7Contracts';

describe('attendance dashboard visualization and drill-through', () => {
  afterEach(() => {
    cleanup();
  });

  it('opens a safe detail drawer from the exception people list', () => {
    const onOpenReports = vi.fn();
    render(
      <MemoryRouter>
        <DashboardView
          projection={dashboardProjection()}
          onOpenReports={onOpenReports}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('region', {
      name: '异常图形汇总',
    })).toHaveTextContent('漏刷');
    const anomalyList = screen.getByRole('region', {
      name: '异常人员列表',
    });
    expect(anomalyList).toHaveTextContent('张三');
    expect(anomalyList).toHaveTextContent('李四');

    fireEvent.click(screen.getByRole('button', { name: /漏刷/ }));
    expect(screen.getByRole('region', { name: '异常人员列表' }))
      .toHaveTextContent('张三');
    expect(screen.getByRole('region', { name: '异常人员列表' }))
      .not.toHaveTextContent('李四');

    fireEvent.click(screen.getAllByRole('button', {
      name: '查看张三的异常详情',
    })[0]!);
    const drawer = screen.getByRole('dialog');
    expect(within(drawer).getByText('异常考勤详情')).toBeInTheDocument();
    expect(within(drawer).getByText('下班卡缺失')).toBeInTheDocument();
    expect(within(drawer).queryByText('exception-1')).not.toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole('button', {
      name: '打开异常详情',
    }));
    expect(onOpenReports).toHaveBeenCalledWith({
      reportType: 'EXCEPTIONS',
      period: '2026-07',
      companyId: 'company-a',
      projectionVersion: 'ATTENDANCE-DASHBOARD-2026-07-30-V1',
      employeeNumber: 'SZ001',
      fromDate: '2026-07-30',
    });
  });

  it('keeps aggregate KPIs but never renders employee details without permission', () => {
    const projection = dashboardProjection();
    render(
      <MemoryRouter>
        <DashboardView
          projection={{
            ...projection,
            metadata: {
              ...projection.metadata,
              allowedActions: [],
            },
          }}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '异常人员' }))
      .toBeInTheDocument();
    expect(screen.getByText(
      '当前账号仅可查看汇总指标，无权查看员工异常明细',
    )).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '异常人员列表' }))
      .not.toBeInTheDocument();
    expect(screen.queryByText('张三')).not.toBeInTheDocument();
    expect(screen.queryByText('SZ001')).not.toBeInTheDocument();
    expect(screen.queryByText('今日没有未处理的异常考勤'))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '异常详情' }))
      .not.toBeInTheDocument();
  });

  it('closes previously rendered employee details after permission is revoked', () => {
    const projection = dashboardProjection();
    const { rerender } = render(
      <MemoryRouter>
        <DashboardView projection={projection} />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getAllByRole('button', {
      name: '查看张三的异常详情',
    })[0]!);
    expect(screen.getByRole('dialog')).toHaveTextContent('张三');

    rerender(
      <MemoryRouter>
        <DashboardView
          projection={{
            ...projection,
            metadata: {
              ...projection.metadata,
              allowedActions: [],
            },
            exceptions: [],
          }}
        />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText('张三')).not.toBeInTheDocument();
    expect(screen.getByText(
      '当前账号仅可查看汇总指标，无权查看员工异常明细',
    )).toBeInTheDocument();
  });
});

function dashboardProjection(): LiveDashboardProjection {
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
    summary: {
      unresolvedCount: 2,
      affectedEmployeeCount: 2,
      blockingCount: 1,
    },
    analytics: {
      dailyTrend: [
        {
          businessDate: '2026-07-29',
          exceptionCount: 1,
          blockingCount: 0,
          affectedEmployeeCount: 1,
        },
        {
          businessDate: '2026-07-30',
          exceptionCount: 2,
          blockingCount: 1,
          affectedEmployeeCount: 2,
        },
      ],
      severityDistribution: [
        { severity: 'INFO', count: 0 },
        { severity: 'WARNING', count: 1 },
        { severity: 'ERROR', count: 1 },
      ],
      typeDistribution: [
        { exceptionType: 'MISSING_PUNCH_OVERDUE', count: 1 },
        { exceptionType: 'LATE', count: 1 },
      ],
      organizationRanking: [
        {
          organizationName: '制造一部',
          exceptionCount: 1,
          blockingCount: 1,
        },
        {
          organizationName: '研发一部',
          exceptionCount: 1,
          blockingCount: 0,
        },
      ],
    },
    exceptions: [
      {
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
      },
      {
        exceptionReference: 'exception-2',
        employeeNumber: 'SZ002',
        employeeName: '李四',
        organizationName: '研发一部',
        businessDate: '2026-07-30',
        exceptionType: 'LATE',
        severity: 'WARNING',
        state: 'OPEN',
        exceptionMinutes: 12,
        evidenceSummary: '首次有效打卡晚于计划开始时间',
      },
    ],
    companies: [
      { companyId: 'company-a', companyName: '神州半导体' },
    ],
  };
}

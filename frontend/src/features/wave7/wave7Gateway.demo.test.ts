import { describe, expect, it, vi } from 'vitest';

import { demoWave7ProjectionGateway as wave7ProjectionGateway } from '../demo/wave7DemoGateway';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

describe('Wave 7 demo projection gateway', () => {
  it('serves the complete runtime-synthetic walkthrough instead of a 503', async () => {
    const [
      today,
      records,
      leave,
      feedback,
      dashboard,
      report,
    ] = await Promise.all([
      wave7ProjectionGateway.loadToday(),
      wave7ProjectionGateway.loadRecords(),
      wave7ProjectionGateway.loadLeave(),
      wave7ProjectionGateway.loadFeedback(),
      wave7ProjectionGateway.loadDashboard(),
      wave7ProjectionGateway.loadReport(),
    ]);

    expect([
      today.kind,
      records.kind,
      leave.kind,
      feedback.kind,
      dashboard.kind,
      report.kind,
    ]).toEqual(['TODAY', 'RECORDS', 'LEAVE', 'FEEDBACK', 'DASHBOARD', 'REPORT']);
    expect(dashboard.title).toContain('考勤管理工作台');
    expect(dashboard.metrics).toHaveLength(7);
    expect(report.reportTitle).toContain('部门考勤统计汇总');
    expect(report.columns.map((column) => column.key)).toEqual(expect.arrayContaining([
      'late-count',
      'early-count',
      'missing-count',
      'recognized-overtime-hours',
      'leave-hours',
    ]));
    expect(report.rowCount).toBe(report.rows.length);
    expect(records.records.length).toBeGreaterThan(5);
    expect(leave.accounts.map((account) => account.label)).toEqual([
      '2026 年年假',
      '调休账户',
    ]);
  });

  it('creates a fresh projection object for each demo load', async () => {
    const first = await wave7ProjectionGateway.loadDashboard();
    const second = await wave7ProjectionGateway.loadDashboard();

    expect(second).not.toBe(first);
    expect(second.metrics).not.toBe(first.metrics);
    expect(second.metadata.dataAsOf).toEqual(expect.any(String));
  });
});

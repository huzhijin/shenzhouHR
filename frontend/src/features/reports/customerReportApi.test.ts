import { beforeEach, describe, expect, it, vi } from 'vitest';

import { reportFixture } from '../../test/fixtures/wave7ContractFixtures';
import type { ReportProjection } from '../wave7/wave7Contracts';
import { defaultCustomerReportDataScope } from './customerReportAccess';
import { loadCustomerReport } from './customerReportApi';

const gatewayMocks = vi.hoisted(() => ({
  loadReport: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => false,
}));

vi.mock('../../shared/runtime/wave7ProjectionGateway', () => ({
  wave7ProjectionGateway: {
    loadReport: gatewayMocks.loadReport,
  },
}));

describe('customer report realtime paging', () => {
  beforeEach(() => {
    gatewayMocks.loadReport.mockReset();
  });

  it('binds every later page to the first realtime snapshot', async () => {
    gatewayMocks.loadReport
      .mockResolvedValueOnce(page('LIVE-a', 'row-1', '员工一'))
      .mockResolvedValueOnce(page('LIVE-a', 'row-2', '员工二'));

    const result = await loadCustomerReport('overtime', {
      month: '2026-08',
      department: '全部部门',
      employee: '全部员工',
    }, defaultCustomerReportDataScope);

    expect(result.overtimeRows).toHaveLength(2);
    expect(result.metadata).toMatchObject({
      generatedAt: reportFixture.metadata.dataAsOf,
      sourceVersions: reportFixture.metadata.sourceVersions,
    });
    expect(gatewayMocks.loadReport).toHaveBeenCalledTimes(2);
    expect(gatewayMocks.loadReport.mock.calls[0]?.[0]
      ?.expectedProjectionVersion).toBeUndefined();
    expect(gatewayMocks.loadReport.mock.calls[1]?.[0])
      .toMatchObject({
        page: 1,
        expectedProjectionVersion: 'LIVE-a',
      });
  });
});

function page(
  projectionVersion: string,
  rowReference: string,
  employee: string,
): ReportProjection {
  return {
    ...reportFixture,
    metadata: {
      ...reportFixture.metadata,
      projectionVersion,
    },
    rowCount: 2,
    rows: [{
      rowReference,
      values: {
        'employee-name': employee,
        organization: '测试部门',
        'recognized-overtime-hours': '1.00',
      },
    }],
  };
}

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { reportFixture } from '../../test/fixtures/wave7ContractFixtures';
import type {
  AttendanceMonthMatrixProjection,
  ReportProjection,
} from '../wave7/wave7Contracts';
import { defaultCustomerReportDataScope } from './customerReportAccess';
import { exportCustomerReport, loadCustomerReport } from './customerReportApi';

const gatewayMocks = vi.hoisted(() => ({
  loadReport: vi.fn(),
  loadAttendanceMonthMatrix: vi.fn(),
  createReportExport: vi.fn(),
  downloadReportExport: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => false,
}));

vi.mock('../../shared/runtime/wave7ProjectionGateway', () => ({
  wave7ProjectionGateway: {
    loadReport: gatewayMocks.loadReport,
    loadAttendanceMonthMatrix: gatewayMocks.loadAttendanceMonthMatrix,
    createReportExport: gatewayMocks.createReportExport,
    downloadReportExport: gatewayMocks.downloadReportExport,
  },
}));

describe('customer report realtime paging', () => {
  beforeEach(() => {
    gatewayMocks.loadReport.mockReset();
    gatewayMocks.createReportExport.mockReset();
    gatewayMocks.downloadReportExport.mockReset();
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

  it('keeps paging the month matrix until employeeCount is reached', async () => {
    gatewayMocks.loadAttendanceMonthMatrix.mockReset();
    gatewayMocks.loadAttendanceMonthMatrix
      .mockResolvedValueOnce(matrixPage('LIVE-m', 0, 2, '一'))
      .mockResolvedValueOnce(matrixPage('LIVE-m', 1, 2, '二'));
    const partials: number[] = [];

    const result = await loadCustomerReport(
      'attendance-detail',
      { month: '2026-08', department: '全部部门', employee: '全部员工' },
      defaultCustomerReportDataScope,
      undefined,
      (partial) => {
        partials.push(partial.attendanceRows.length);
      },
    );

    expect(result.attendanceRows).toHaveLength(2);
    expect(result.metadata.truncated).toBe(false);
    expect(partials[0]).toBe(1);
    expect(gatewayMocks.loadAttendanceMonthMatrix).toHaveBeenCalledTimes(2);
    expect(gatewayMocks.loadAttendanceMonthMatrix.mock.calls[1]?.[0])
      .toMatchObject({
        page: 1,
        size: 50,
        expectedProjectionVersion: 'LIVE-m',
      });
  });

  it('fills remaining month-matrix pages one after another after the first 50', async () => {
    gatewayMocks.loadAttendanceMonthMatrix.mockReset();
    const started: number[] = [];
    const inflight = { current: 0, max: 0 };
    gatewayMocks.loadAttendanceMonthMatrix.mockImplementation(
      async (query: { page?: number }) => {
        inflight.current += 1;
        inflight.max = Math.max(inflight.max, inflight.current);
        started.push(query.page ?? 0);
        const pageIndex = query.page ?? 0;
        const page = matrixPage('LIVE-m', pageIndex, 3, String(pageIndex));
        inflight.current -= 1;
        return page;
      },
    );

    const result = await loadCustomerReport(
      'attendance-detail',
      { month: '2026-08', department: '全部部门', employee: '全部员工' },
      defaultCustomerReportDataScope,
    );

    expect(result.attendanceRows).toHaveLength(3);
    expect(started).toEqual([0, 1, 2]);
    expect(inflight.max).toBe(1);
    expect(gatewayMocks.loadAttendanceMonthMatrix).toHaveBeenCalledTimes(3);
  });

  it('omits a whole-month date range from official month-matrix pages', async () => {
    gatewayMocks.loadAttendanceMonthMatrix.mockReset();
    gatewayMocks.loadAttendanceMonthMatrix.mockResolvedValueOnce(
      matrixPage('LIVE-m', 0, 1, '一'),
    );

    await loadCustomerReport(
      'attendance-detail',
      {
        month: '2026-08',
        department: '全部部门',
        employee: '全部员工',
        fromDate: '2026-08-01',
        toDate: '2026-08-31',
      },
      defaultCustomerReportDataScope,
    );

    expect(gatewayMocks.loadAttendanceMonthMatrix.mock.calls[0]?.[0])
      .toMatchObject({
        period: '2026-08',
        fromDate: undefined,
        toDate: undefined,
      });
  });

  it('falls back to the screen workbook when export binding fails', async () => {
    gatewayMocks.loadReport.mockResolvedValueOnce(reportFixture);
    gatewayMocks.createReportExport.mockRejectedValueOnce(new Error('BINDING_STALE'));
    const fallback = vi.fn();

    const mode = await exportCustomerReport({
      reportKey: 'overtime',
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      reportTitle: '加班单据明细',
      fromDate: '2026-07-01',
      toDate: '2026-07-31',
    }, fallback);

    expect(mode).toBe('screen');
    expect(fallback).toHaveBeenCalled();
    expect(gatewayMocks.createReportExport.mock.calls[0]?.[0]?.filters)
      .not.toHaveProperty('fromDate');
  });

  it('writes the matrix workbook without consulting the ATTENDANCE_DETAIL allowlist', async () => {
    const fallback = vi.fn().mockResolvedValue(undefined);
    const mode = await exportCustomerReport({
      reportKey: 'attendance-detail',
      period: '2026-08',
      companyId: 'company-a',
      reportTitle: '月度考勤明细矩阵',
    }, fallback);
    expect(mode).toBe('screen');
    expect(fallback).toHaveBeenCalled();
    expect(gatewayMocks.loadReport).not.toHaveBeenCalled();
    expect(gatewayMocks.createReportExport).not.toHaveBeenCalled();
  });

  it('surfaces the fallback error instead of claiming success', async () => {
    gatewayMocks.loadReport.mockResolvedValueOnce(reportFixture);
    gatewayMocks.createReportExport.mockRejectedValueOnce(new Error('BINDING_STALE'));
    const fallback = vi.fn().mockRejectedValueOnce(new Error('ExcelJS write failed'));
    await expect(exportCustomerReport({
      reportKey: 'overtime',
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      reportTitle: '加班单据明细',
    }, fallback)).rejects.toThrow('ExcelJS write failed');
  });
});

function matrixPage(
  projectionVersion: string,
  pageIndex: number,
  employeeCount: number,
  name: string,
): AttendanceMonthMatrixProjection {
  return {
    kind: 'ATTENDANCE_MONTH_MATRIX',
    metadata: {
      ...reportFixture.metadata,
      projectionVersion,
      periodLabel: '2026-08',
      periodState: 'OPEN',
      scope: {
        type: 'COMPANY',
        reference: 'company-a',
        label: '江苏神州',
      },
      allowedActions: [],
    },
    queryFingerprint: 'a'.repeat(64),
    formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V3',
    filters: {
      period: '2026-08',
      scopeReference: 'company-a',
      companyId: 'company-a',
      organizationId: null,
      employeeId: null,
    },
    dates: ['2026-08-03'],
    employeeCount,
    page: pageIndex,
    size: 50,
    totalPages: 1,
    rows: [{
      employeeId: `employee-${name}`,
      employeeNumber: `SZST${name}`,
      employeeName: name,
      organizationId: 'org-a',
      organizationName: '测试部门',
      days: [{
        date: '2026-08-03',
        organizationName: '测试部门',
        shiftLabel: '日班',
        firstPunchAt: '2026-08-03T00:20:00Z',
        lastPunchAt: '2026-08-03T10:00:00Z',
        badges: [],
        morning: { text: '08:20', tone: null, punchAt: '2026-08-03T00:20:00Z' },
        afternoon: { text: '18:00', tone: null, punchAt: '2026-08-03T10:00:00Z' },
        merged: false,
        hover: '上班 08:20',
      }],
    }],
  };
}

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

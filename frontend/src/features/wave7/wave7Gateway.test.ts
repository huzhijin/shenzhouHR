import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AttendanceReportType } from './wave7Contracts';
import {
  type ReportQuery,
  wave7ProjectionGateway,
} from './wave7Gateway';

describe('Wave 7 production report gateway', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('uses requestJson with an encoded same-origin query', async () => {
    const query: ReportQuery = {
      reportType: 'EXCEPTIONS',
      period: '2026-07',
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      status: 'PENDING_REVIEW',
      page: 2,
      size: 25,
    };
    const fetchMock = vi.fn(
      async (...args: Parameters<typeof fetch>) => {
        void args;
        return jsonResponse(reportResponse(query));
      },
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await wave7ProjectionGateway.loadReport(query);

    expect(result.kind).toBe('REPORT');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [requestTarget, init] = fetchMock.mock.calls[0]!;
    const target = new URL(String(requestTarget), window.location.origin);
    expect(target.origin).toBe(window.location.origin);
    expect(target.pathname).toBe('/api/v1/attendance-reports');
    expect(target.searchParams.get('reportType')).toBe('EXCEPTIONS');
    expect(target.searchParams.get('period')).toBe('2026-07');
    expect(target.searchParams.get('legalEntityId'))
      .toBe('30000000-0000-0000-0000-000000000001');
    expect(target.searchParams.get('status')).toBe('PENDING_REVIEW');
    expect(target.searchParams.get('page')).toBe('2');
    expect(target.searchParams.get('size')).toBe('25');
    expect(init).toMatchObject({ credentials: 'same-origin' });
  });

  it('loads only the period-bound authorized company directory', async () => {
    const directory = {
      period: '2026-07',
      legalEntities: [
        { legalEntityId: 'company-a', name: '神州半导体' },
        { legalEntityId: 'company-b', name: '神州科技' },
      ],
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(directory));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      wave7ProjectionGateway.loadReportLegalEntities('2026-07'),
    ).resolves.toEqual(directory);
    const target = new URL(
      String(fetchMock.mock.calls[0]?.[0]),
      window.location.origin,
    );
    expect(target.pathname)
      .toBe('/api/v1/attendance-reports/legal-entities');
    expect(target.searchParams.get('period')).toBe('2026-07');
  });

  it('rejects cross-company report responses', async () => {
    const query: ReportQuery = {
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: 'company-a',
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...reportResponse(query),
      filters: {
        ...reportResponse(query).filters,
        legalEntityId: 'company-b',
      },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadReport(query))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_RESPONSE_BODY',
      });
  });

  it('rejects malformed or query-mismatched runtime responses', async () => {
    const query: ReportQuery = {
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
    };
    const mismatched = reportResponse({
      ...query,
      reportType: 'LEAVE',
    });
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(mismatched))
      .mockResolvedValueOnce(jsonResponse({
        ...reportResponse(query),
        formulaVersion: undefined,
      }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadReport(query))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_RESPONSE_BODY',
      });
    await expect(wave7ProjectionGateway.loadReport(query))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_RESPONSE_BODY',
      });
  });

  it('rejects invalid conditions before issuing a request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadReport({
      reportType: 'LATE',
      period: '2026-13',
    })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_QUERY',
    });
    await expect(wave7ProjectionGateway.loadReport())
      .rejects.toMatchObject({
        status: 400,
        code: 'INVALID_REPORT_QUERY',
      });
    await expect(wave7ProjectionGateway.loadReport({
      reportType: 'LATE',
      period: '2026-07',
      legalEntityId: ' company-a',
    })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_QUERY',
    });
    await expect(wave7ProjectionGateway.loadReport({
      reportType: 'LATE',
      period: '2026-07',
      status: 'OPEN',
    })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_QUERY',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('creates and refreshes a strictly bound formal export', async () => {
    const created = exportResponse({
      deliveryMode: 'ASYNC',
      status: 'QUEUED',
      completedAt: undefined,
    });
    const building = {
      ...created,
      status: 'BUILDING',
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(created, 202))
      .mockResolvedValueOnce(jsonResponse(building));
    vi.stubGlobal('fetch', fetchMock);

    const result = await wave7ProjectionGateway.createReportExport?.({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      status: null,
      purpose: ' 月度考勤复核 ',
      currentPassword: 'Current#Password123',
    });
    const refreshed = await wave7ProjectionGateway.loadReportExport?.(
      created.exportId,
    );

    expect(result).toEqual(created);
    expect(refreshed?.status).toBe('BUILDING');
    const [createTarget, createInit] = fetchMock.mock.calls[0]!;
    expect(createTarget).toBe('/api/v1/attendance-reports/exports');
    expect(createInit).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
    });
    expect(String(createTarget)).not.toContain('Current#Password123');
    expect(JSON.parse(String(createInit?.body))).toEqual({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      status: null,
      purpose: '月度考勤复核',
      currentPassword: 'Current#Password123',
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `/api/v1/attendance-reports/exports/${created.exportId}`,
    );
  });

  it('rejects an export response bound to another company', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(
      exportResponse({ legalEntityId: 'company-b' }),
      201,
    ));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.createReportExport?.({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: 'company-a',
      purpose: '跨公司负例',
      currentPassword: 'Current#Password123',
    })).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_REPORT_EXPORT_RESPONSE',
    });
  });

  it('downloads XLSX with password only in the POST body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), {
        status: 200,
        headers: {
          'Content-Type':
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          'Content-Disposition':
            'attachment; filename="attendance-detail-2026-07.xlsx"',
        },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await wave7ProjectionGateway.downloadReportExport?.(
      exportId,
      'Download#Password123',
    );

    expect(result?.fileName).toBe('attendance-detail-2026-07.xlsx');
    expect(result?.blob.size).toBe(4);
    const [target, init] = fetchMock.mock.calls[0]!;
    expect(target).toBe(
      `/api/v1/attendance-reports/exports/${exportId}/download`,
    );
    expect(String(target)).not.toContain('Download#Password123');
    expect(init).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
    });
    expect(JSON.parse(String(init?.body))).toEqual({
      currentPassword: 'Download#Password123',
    });
  });

  it('fails closed on malformed export JSON and non-XLSX files', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        ...exportResponse(),
        completedAt: undefined,
      }, 201))
      .mockResolvedValueOnce(new Response('not a workbook', {
        status: 200,
        headers: {
          'Content-Type': 'text/plain',
          'Content-Disposition': 'attachment; filename="report.xlsx"',
        },
      }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.createReportExport?.({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      purpose: '月度复核',
      currentPassword: 'Current#Password123',
    })).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_REPORT_EXPORT_RESPONSE',
    });
    await expect(wave7ProjectionGateway.downloadReportExport?.(
      exportId,
      'Current#Password123',
    )).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_REPORT_EXPORT_FILE',
    });
  });

  it('rejects a spoofed XLSX response without a ZIP signature', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('not really an xlsx', {
        status: 200,
        headers: {
          'Content-Type':
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          'Content-Disposition':
            'attachment; filename="attendance-report.xlsx"',
        },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.downloadReportExport?.(
      exportId,
      'Current#Password123',
    )).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_REPORT_EXPORT_FILE',
    });
  });

  it('sanitizes authorization errors and rejects secrets in URL inputs', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        code: 'REAUTHENTICATION_FAILED',
        message: 'sensitive server credential detail',
        retryable: false,
      }, 401),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.createReportExport?.({
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      purpose: '月度复核',
      currentPassword: 'Wrong#Password',
    })).rejects.toMatchObject({
      status: 401,
      code: 'REAUTHENTICATION_FAILED',
      message: '当前密码验证失败，操作未完成。',
    });
    await expect(wave7ProjectionGateway.loadReportExport?.(
      `${exportId}/download?currentPassword=leak`,
    )).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_EXPORT_REFERENCE',
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0]))
      .not.toContain('Wrong#Password');
  });
});

const exportId = '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f';

function reportResponse(query: ReportQuery) {
  const page = query.page ?? 0;
  const size = query.size ?? 50;
  return {
    kind: 'REPORT',
    metadata: {
      projectionVersion: 'FORMAL-REPORT-2026-07-V1',
      sourceVersions: ['W5-CALC-V1'],
      dataAsOf: '2026-07-29T01:00:00Z',
      timeZone: 'Asia/Shanghai',
      periodLabel: query.period,
      periodState: 'OPEN',
      scope: {
        type: 'ORGANIZATION',
        reference: 'scope:formal:authorized',
        label: '服务端授权组织',
      },
      allowedActions: ['REPORT_DRILL_DOWN'],
    },
    reportType: query.reportType as AttendanceReportType,
    reportTitle: `${query.reportType} 报表`,
    queryFingerprint: `formal:${query.reportType}:${query.period}`,
    formulaVersion: `${query.reportType}_FORMULA_V1`,
    filters: {
      period: query.period,
      scopeReference: 'scope:formal:authorized',
      legalEntityId: query.legalEntityId
        ?? '30000000-0000-0000-0000-000000000001',
      organizationId: null,
      employeeId: null,
      status: query.status?.trim() ?? null,
    },
    columns: [
      { key: 'employee-number', label: '工号' },
      { key: 'employee-name', label: '姓名' },
    ],
    exportFieldAllowlist: ['employee-number', 'employee-name'],
    rowCount: 51,
    rows: [{
      rowReference: 'formal-row-1',
      values: {
        'employee-number': 'SYN-001',
        'employee-name': '合成员工',
      },
      drillDownReference: null,
    }],
    page,
    size,
    totalPages: 3,
  };
}

function exportResponse(overrides: Record<string, unknown> = {}) {
  return {
    exportId,
    reportType: 'ATTENDANCE_DETAIL',
    period: '2026-07',
    legalEntityId: '30000000-0000-0000-0000-000000000001',
    deliveryMode: 'SYNC',
    status: 'READY',
    purpose: '月度考勤复核',
    rowCount: 51,
    expiresAt: '2026-07-30T01:00:00Z',
    completedAt: '2026-07-29T01:00:00Z',
    ...overrides,
  };
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

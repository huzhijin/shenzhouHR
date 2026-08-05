import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AttendanceReportType } from './wave7Contracts';
import {
  type AttendanceMonthMatrixQuery,
  type ReportExportCreateRequest,
  type ReportQuery,
  wave7ProjectionGateway,
} from './wave7Gateway';

const retiredBoundaryIdKey = ['legal', 'EntityId'].join('');
const retiredDirectoryKey = ['legal', 'Entities'].join('');

describe('Wave 7 production gateway', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('loads the same-origin attendance dashboard without a company query', async () => {
    const response = attendanceDashboardResponse();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response));
    vi.stubGlobal('fetch', fetchMock);

    const result = await wave7ProjectionGateway.loadDashboard();

    expect(result).toMatchObject({
      kind: 'DASHBOARD',
      businessDate: '2026-07-30',
      selectedCompanyId: 'company-a',
      metrics: [],
      analytics: {
        severityDistribution: [
          { severity: 'INFO', count: 0 },
          { severity: 'WARNING', count: 0 },
          { severity: 'ERROR', count: 1 },
        ],
      },
    });
    const [requestTarget, init] = fetchMock.mock.calls[0]!;
    const target = new URL(String(requestTarget), window.location.origin);
    expect(target.origin).toBe(window.location.origin);
    expect(target.pathname).toBe('/api/v1/attendance-dashboards');
    expect(target.search).toBe('');
    expect(init).toMatchObject({ credentials: 'same-origin' });
  });

  it('encodes and verifies an explicitly selected dashboard company', async () => {
    const companyId = 'company/a?view=1';
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(
      attendanceDashboardResponse({
        selectedCompanyId: companyId,
        metadata: {
          ...attendanceDashboardResponse().metadata,
          scope: {
            type: 'COMPANY',
            reference: companyId,
            label: '已选公司',
          },
        },
        companies: [{ companyId, companyName: '已选公司' }],
      }),
    ));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadDashboard(companyId))
      .resolves.toMatchObject({
        kind: 'DASHBOARD',
        selectedCompanyId: companyId,
      });
    const target = new URL(
      String(fetchMock.mock.calls[0]?.[0]),
      window.location.origin,
    );
    expect(target.pathname).toBe('/api/v1/attendance-dashboards');
    expect(target.searchParams.get('companyId')).toBe(companyId);
    expect(target.searchParams.size).toBe(1);
  });

  it('accepts a server-driven dashboard company selection', async () => {
    const selection = {
      kind: 'DASHBOARD_COMPANY_SELECTION',
      title: '今日异常考勤',
      businessDate: '2026-07-30',
      selectedCompanyId: null,
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
      message: '请选择公司后查看今日异常考勤',
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(selection));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadDashboard())
      .resolves.toEqual(selection);
  });

  it('rejects malformed or cross-company dashboard responses', async () => {
    const malformed = {
      ...attendanceDashboardResponse(),
      internalEmployeeId: 'must-not-pass-through',
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(malformed))
      .mockResolvedValueOnce(jsonResponse(attendanceDashboardResponse()));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadDashboard())
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_DASHBOARD_RESPONSE',
        message: '考勤工作台数据暂时无法显示，请刷新后重试。',
      });
    await expect(wave7ProjectionGateway.loadDashboard('company-b'))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_DASHBOARD_RESPONSE',
      });
  });

  it('fails closed on malformed dashboard analytics', async () => {
    const response = attendanceDashboardResponse();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...response,
      analytics: {
        ...response.analytics,
        severityDistribution: [
          { severity: 'ERROR', count: 1 },
          { severity: 'WARNING', count: 0 },
          { severity: 'INFO', count: 0 },
        ],
      },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadDashboard())
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_DASHBOARD_RESPONSE',
      });
  });

  it('rejects invalid dashboard company input before issuing a request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadDashboard(' company-a'))
      .rejects.toMatchObject({
        status: 400,
        code: 'INVALID_DASHBOARD_QUERY',
      });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('uses requestJson with an encoded same-origin query', async () => {
    const query: ReportQuery = {
      reportType: 'EXCEPTIONS',
      period: '2026-07',
      companyId: '30000000-0000-0000-0000-000000000001',
      status: 'PENDING_REVIEW',
      expectedProjectionVersion: 'FORMAL-REPORT-2026-07-V1',
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
    expect(target.searchParams.get('companyId'))
      .toBe('30000000-0000-0000-0000-000000000001');
    expect(target.searchParams.get('status')).toBe('PENDING_REVIEW');
    expect(target.searchParams.get('expectedProjectionVersion'))
      .toBe('FORMAL-REPORT-2026-07-V1');
    expect(target.searchParams.get('page')).toBe('2');
    expect(target.searchParams.get('size')).toBe('25');
    expect(init).toMatchObject({ credentials: 'same-origin' });
  });

  it('loads the employee-paged formal month matrix from its dedicated route', async () => {
    const query: AttendanceMonthMatrixQuery = {
      period: '2026-07',
      companyId: 'company-a',
      organizationId: 'org-a',
      expectedProjectionVersion: 'FORMAL-REPORT-2026-07-V1',
      page: 1,
      size: 20,
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(
      monthMatrixResponse(query),
    ));
    vi.stubGlobal('fetch', fetchMock);

    const result = await wave7ProjectionGateway
      .loadAttendanceMonthMatrix?.(query);

    expect(result?.kind).toBe('ATTENDANCE_MONTH_MATRIX');
    const target = new URL(
      String(fetchMock.mock.calls[0]?.[0]),
      window.location.origin,
    );
    expect(target.pathname)
      .toBe('/api/v1/attendance-reports/month-matrix');
    expect(Object.fromEntries(target.searchParams)).toEqual({
      period: '2026-07',
      companyId: 'company-a',
      organizationId: 'org-a',
      expectedProjectionVersion: 'FORMAL-REPORT-2026-07-V1',
      page: '1',
      size: '20',
    });
  });

  it('fails closed when a month matrix contains presentation colors', async () => {
    const query: AttendanceMonthMatrixQuery = {
      period: '2026-07',
      companyId: 'company-a',
    };
    const response = monthMatrixResponse(query);
    const firstDay = response.rows[0]!.days[0]!;
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...response,
      rows: [{
        ...response.rows[0]!,
        days: [{ ...firstDay, color: '#ff0000' }, ...response.rows[0]!.days.slice(1)],
      }],
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway
      .loadAttendanceMonthMatrix?.(query))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_RESPONSE_BODY',
      });
  });

  it('loads only the period-bound authorized company directory', async () => {
    const directory = {
      period: '2026-07',
      companies: [
        { companyId: 'company-a', companyName: '神州半导体' },
        { companyId: 'company-b', companyName: '神州科技' },
      ],
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(directory));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      wave7ProjectionGateway.loadReportCompanies('2026-07'),
    ).resolves.toEqual(directory);
    const target = new URL(
      String(fetchMock.mock.calls[0]?.[0]),
      window.location.origin,
    );
    expect(target.pathname)
      .toBe('/api/v1/attendance-reports/companies');
    expect(target.searchParams.get('period')).toBe('2026-07');
  });

  it('rejects a retired directory response shape', async () => {
    const directory = {
      period: '2026-07',
      companies: [{
        companyId: 'company-a',
        companyName: '神州半导体',
      }],
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...directory,
      [retiredDirectoryKey]: directory.companies,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      wave7ProjectionGateway.loadReportCompanies('2026-07'),
    ).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_RESPONSE_BODY',
      message: '报表数据暂时无法显示，请刷新后重试。',
    });
  });

  it('rejects cross-company report responses', async () => {
    const query: ReportQuery = {
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: 'company-a',
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...reportResponse(query),
      filters: {
        ...reportResponse(query).filters,
        companyId: 'company-b',
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

  it('rejects report and matrix responses from another projection version', async () => {
    const reportQuery: ReportQuery = {
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      companyId: 'company-a',
      expectedProjectionVersion: 'FORMAL-REPORT-2026-07-V1',
    };
    const matrixQuery: AttendanceMonthMatrixQuery = {
      period: '2026-07',
      companyId: 'company-a',
      expectedProjectionVersion: 'FORMAL-REPORT-2026-07-V1',
    };
    const report = reportResponse(reportQuery);
    const matrix = monthMatrixResponse(matrixQuery);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        ...report,
        metadata: {
          ...report.metadata,
          projectionVersion: 'FORMAL-REPORT-2026-07-V2',
        },
      }))
      .mockResolvedValueOnce(jsonResponse({
        ...matrix,
        metadata: {
          ...matrix.metadata,
          projectionVersion: 'FORMAL-REPORT-2026-07-V2',
        },
      }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadReport(reportQuery))
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_RESPONSE_BODY',
      });
    await expect(wave7ProjectionGateway
      .loadAttendanceMonthMatrix?.(matrixQuery))
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
      companyId: ' company-a',
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
    await expect(wave7ProjectionGateway.loadReport({
      reportType: 'LATE',
      period: '2026-07',
      expectedProjectionVersion: ' projection-1',
    })).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_QUERY',
    });
    await expect(wave7ProjectionGateway
      .loadAttendanceMonthMatrix?.({
        period: '2026-07',
        expectedProjectionVersion: 'p'.repeat(129),
      }))
      .rejects.toMatchObject({
        status: 400,
        code: 'INVALID_REPORT_QUERY',
      });
    const retiredQuery = {
      reportType: 'LATE',
      period: '2026-07',
      [retiredBoundaryIdKey]: 'company-a',
    } as unknown as ReportQuery;
    await expect(wave7ProjectionGateway.loadReport(retiredQuery))
      .rejects.toMatchObject({
        status: 400,
        code: 'INVALID_REPORT_QUERY',
      });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects a retired export request property before issuing a request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const retiredRequest = {
      ...exportCreateRequest(),
      [retiredBoundaryIdKey]: 'company-a',
    } as unknown as ReportExportCreateRequest;

    await expect(
      wave7ProjectionGateway.createReportExport?.(retiredRequest),
    ).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REPORT_EXPORT_REQUEST',
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
      ...exportCreateRequest(),
      purpose: ' 月度考勤复核 ',
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
      projectionVersion: 'FORMAL-REPORT-2026-07-V1',
      queryFingerprint: 'a'.repeat(64),
      scopeReference: 'scope:formal:authorized',
      filters: {
        period: '2026-07',
        scopeReference: 'scope:formal:authorized',
        companyId: '30000000-0000-0000-0000-000000000001',
        organizationId: null,
        employeeId: null,
        status: null,
      },
      selectedFields: ['employee-number', 'employee-name'],
      purpose: '月度考勤复核',
      currentPassword: 'Current#Password123',
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      `/api/v1/attendance-reports/exports/${created.exportId}`,
    );
  });

  it('rejects an export response bound to another company', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(
      exportResponse({ companyId: 'company-b' }),
      201,
    ));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.createReportExport?.({
      ...exportCreateRequest({
        filters: {
          ...exportCreateRequest().filters,
          companyId: 'company-a',
        },
      }),
      purpose: '跨公司负例',
    })).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_REPORT_EXPORT_RESPONSE',
      message: '导出状态暂时无法读取，请刷新后重试。',
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
      ...exportCreateRequest(),
      purpose: '月度复核',
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
      message: '导出文件暂时无法使用，请重新导出。',
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
      ...exportCreateRequest(),
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

function exportCreateRequest(
  overrides: Partial<ReportExportCreateRequest> = {},
): ReportExportCreateRequest {
  return {
    reportType: 'ATTENDANCE_DETAIL',
    projectionVersion: 'FORMAL-REPORT-2026-07-V1',
    queryFingerprint: 'a'.repeat(64),
    scopeReference: 'scope:formal:authorized',
    filters: {
      period: '2026-07',
      scopeReference: 'scope:formal:authorized',
      companyId: '30000000-0000-0000-0000-000000000001',
      organizationId: null,
      employeeId: null,
      status: null,
    },
    selectedFields: ['employee-number', 'employee-name'],
    purpose: '月度考勤复核',
    currentPassword: 'Current#Password123',
    ...overrides,
  };
}

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
    queryFingerprint: 'a'.repeat(64),
    formulaVersion: `${query.reportType}_FORMULA_V1`,
    filters: {
      period: query.period,
      scopeReference: 'scope:formal:authorized',
      companyId: query.companyId
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

function monthMatrixResponse(query: AttendanceMonthMatrixQuery) {
  const dates = datesForPeriod(query.period);
  const companyId = query.companyId ?? 'company-a';
  const page = query.page ?? 0;
  const size = query.size ?? 50;
  return {
    kind: 'ATTENDANCE_MONTH_MATRIX',
    metadata: {
      projectionVersion: 'FORMAL-REPORT-2026-07-V1',
      sourceVersions: ['W5-CALC-V1', 'OA-V2'],
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
    queryFingerprint: 'a'.repeat(64),
    formulaVersion: 'ATTENDANCE_MONTH_MATRIX_V1',
    filters: {
      period: query.period,
      scopeReference: 'scope:formal:authorized',
      companyId,
      organizationId: query.organizationId ?? null,
      employeeId: null,
    },
    dates,
    employeeCount: 40,
    rows: [{
      employeeId: 'employee-a',
      employeeNumber: 'SZ001',
      employeeName: '张三',
      organizationId: 'org-a',
      organizationName: '制造一部',
      days: dates.map((date, index) => ({
        date,
        organizationName: index === 0 ? '制造一部' : null,
        shiftLabel: index === 0 ? '扬州总部班次' : null,
        firstPunchAt: index === 0 ? '2026-07-01T00:25:00Z' : null,
        lastPunchAt: index === 0 ? '2026-07-01T10:15:00Z' : null,
        badges: index === 0
          ? ['LATE', 'EARLY_DEPARTURE', 'PUNCH_CORRECTION']
          : [],
      })),
    }],
    page,
    size,
    totalPages: Math.ceil(40 / size),
  };
}

function datesForPeriod(period: string): string[] {
  const [yearValue, monthValue] = period.split('-');
  const days = new Date(Date.UTC(
    Number(yearValue),
    Number(monthValue),
    0,
  )).getUTCDate();
  return Array.from({ length: days }, (_, index) => (
    `${period}-${String(index + 1).padStart(2, '0')}`
  ));
}

function exportResponse(overrides: Record<string, unknown> = {}) {
  return {
    exportId,
    reportType: 'ATTENDANCE_DETAIL',
    period: '2026-07',
    companyId: '30000000-0000-0000-0000-000000000001',
    deliveryMode: 'SYNC',
    status: 'READY',
    purpose: '月度考勤复核',
    rowCount: 51,
    expiresAt: '2026-07-30T01:00:00Z',
    completedAt: '2026-07-29T01:00:00Z',
    ...overrides,
  };
}

function attendanceDashboardResponse(
  overrides: Record<string, unknown> = {},
) {
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
    summary: {
      unresolvedCount: 1,
      affectedEmployeeCount: 1,
      blockingCount: 1,
    },
    exceptions: [{
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
    }],
    analytics: attendanceDashboardAnalytics(),
    companies: [
      { companyId: 'company-a', companyName: '神州半导体' },
      { companyId: 'company-b', companyName: '神州科技' },
    ],
    ...overrides,
  };
}

function attendanceDashboardAnalytics() {
  return {
    dailyTrend: Array.from({ length: 7 }, (_, index) => ({
      businessDate: `2026-07-${String(index + 24).padStart(2, '0')}`,
      exceptionCount: index === 6 ? 1 : 0,
      blockingCount: index === 6 ? 1 : 0,
      affectedEmployeeCount: index === 6 ? 1 : 0,
    })),
    severityDistribution: [
      { severity: 'INFO', count: 0 },
      { severity: 'WARNING', count: 0 },
      { severity: 'ERROR', count: 1 },
    ],
    typeDistribution: [{
      exceptionType: 'MISSING_PUNCH_OVERDUE',
      count: 1,
    }],
    organizationRanking: [{
      organizationName: '制造一部',
      exceptionCount: 1,
      blockingCount: 1,
    }],
  };
}

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

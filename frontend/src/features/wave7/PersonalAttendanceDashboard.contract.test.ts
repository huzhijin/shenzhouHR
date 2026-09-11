import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createDemoSelfAttendanceDashboardProjection,
} from '../demo/wave7Demo';
import { parseSelfAttendanceDashboardResponse } from './wave7Contracts';
import { wave7ProjectionGateway } from './wave7Gateway';

describe('self attendance dashboard contract', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('accepts the strict self-only response shape', () => {
    const response = createDemoSelfAttendanceDashboardProjection();

    expect(parseSelfAttendanceDashboardResponse(response)).toEqual(response);
  });

  it('fails closed on identity, organization and case properties', () => {
    const response = createDemoSelfAttendanceDashboardProjection();
    const unsafeResponses = [
      { ...response, employeeId: 'employee-private' },
      { ...response, companyId: 'company-private' },
      {
        ...response,
        metadata: {
          ...response.metadata,
          allowedActions: [],
        },
      },
      {
        ...response,
        recentExceptions: [{
          ...response.recentExceptions[0],
          caseId: 'case-private',
        }],
      },
    ];

    for (const unsafeResponse of unsafeResponses) {
      expect(() => parseSelfAttendanceDashboardResponse(unsafeResponse))
        .toThrow(TypeError);
    }
  });

  it('rejects non-self scope and inconsistent exception totals', () => {
    const response = createDemoSelfAttendanceDashboardProjection();

    expect(() => parseSelfAttendanceDashboardResponse({
      ...response,
      metadata: {
        ...response.metadata,
        scope: {
          type: 'ORGANIZATION',
          reference: 'organization:private',
          label: '组织范围',
        },
      },
    })).toThrow(TypeError);
    expect(() => parseSelfAttendanceDashboardResponse({
      ...response,
      metadata: {
        ...response.metadata,
        scope: {
          type: 'SELF',
          reference: 'employee-private',
          label: '本人',
        },
      },
    })).toThrow(TypeError);
    expect(() => parseSelfAttendanceDashboardResponse({
      ...response,
      metadata: {
        ...response.metadata,
        scope: {
          type: 'SELF',
          reference: 'current-principal',
          label: '其他员工',
        },
      },
    })).toThrow(TypeError);
    expect(() => parseSelfAttendanceDashboardResponse({
      ...response,
      summary: {
        ...response.summary,
        unresolvedExceptionCount: 3,
      },
    })).toThrow(TypeError);
  });

  it('loads the exact same-origin self endpoint without query parameters', async () => {
    const response = createDemoSelfAttendanceDashboardProjection();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadSelfDashboard())
      .resolves.toEqual(response);

    const [requestTarget, init] = fetchMock.mock.calls[0]!;
    const target = new URL(String(requestTarget), window.location.origin);
    expect(target.origin).toBe(window.location.origin);
    expect(target.pathname).toBe('/api/v1/me/attendance-dashboard');
    expect(target.search).toBe('');
    expect(init).toMatchObject({ credentials: 'same-origin' });
  });

  it('maps malformed self responses to one safe gateway error', async () => {
    const response = createDemoSelfAttendanceDashboardProjection();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...response,
      metadata: {
        ...response.metadata,
        scope: {
          type: 'COMPANY',
          reference: 'company:private',
          label: '公司范围',
        },
      },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(wave7ProjectionGateway.loadSelfDashboard())
      .rejects.toMatchObject({
        status: 502,
        code: 'INVALID_SELF_ATTENDANCE_DASHBOARD_RESPONSE',
        retryable: true,
      });
  });
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

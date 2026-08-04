import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  ApiRequestError,
  requestJson,
} from '../../shared/api/apiClient';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
} from './attendanceSetupFeedback';

describe('attendance setup mutation feedback', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([
    ['true', 'replay'],
    ['false', 'success'],
  ] as const)(
    'uses only trusted replay response metadata for the %s success state',
    async (header, state) => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(new Response(JSON.stringify({ accepted: true }), {
          status: 200,
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Replayed': header,
          },
        })),
      );
      const response = await requestJson<{ accepted: boolean }>(
        '/api/v1/attendance-setup/test-mutation',
        { method: 'POST', body: '{}' },
      );

      const notice = mutationSuccessNotice(response, '操作已完成。');

      expect(notice.state).toBe(state);
      expect(notice.message).toContain('操作已完成。');
      expect(notice.message.includes('已经成功完成')).toBe(header === 'true');
      expect(notice.message).not.toMatch(/幂等|重放/);
    },
  );

  it.each([
    [403, 'ACCESS_DENIED', '403'],
    [404, 'RESOURCE_NOT_AVAILABLE', '404'],
    [409, 'ASSIGNMENT_OVERLAP', '409'],
    [409, 'VERSION_CONFLICT', 'stale'],
    [412, 'PRECONDITION_FAILED', 'stale'],
    [409, 'IDEMPOTENCY_KEY_REUSED', 'idempotency-conflict'],
    [422, 'VALIDATION_ERROR', 'validation'],
  ] as const)(
    'maps HTTP %i / %s to the distinct %s UI state',
    (status, code, state) => {
      const notice = mutationFailureNotice(new ApiRequestError(status, {
        code,
        correlationId: 'correlation-1',
      }));

      expect(notice.state).toBe(state);
      expect(notice.correlationId).toBe('correlation-1');
      expect(notice.message).not.toHaveLength(0);
    },
  );

  it('preserves server field issue messages for an actionable validation state', () => {
    const notice = mutationFailureNotice(new ApiRequestError(422, {
      code: 'VALIDATION_ERROR',
      fieldErrors: [
        { field: 'effectiveFrom', code: 'FUTURE_REQUIRED', message: '生效日必须在未来' },
        { field: 'calendarId', code: 'NOT_COMPATIBLE', message: '日历与地点不兼容' },
      ],
    }));

    expect(notice).toMatchObject({
      kind: 'warning',
      state: 'validation',
      message: '生效日必须在未来；日历与地点不兼容',
    });
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import type * as ApiClientModule from '../../shared/api/apiClient';
import {
  checkAfterUnknownSubmit,
  isRecalculationOutcomeUnknown,
  loadPublishedReportPin,
  recalculateCustomerReportAndWait,
} from './customerReportApi';

const mocks = vi.hoisted(() => ({
  requestJson: vi.fn(),
  recalculate: vi.fn(),
}));

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: () => false }));

vi.mock('../../shared/api/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiClientModule>();
  return { ...actual, requestJson: mocks.requestJson };
});

vi.mock('../../shared/runtime/wave7ProjectionGateway', () => ({
  wave7ProjectionGateway: { recalculateAttendanceReport: mocks.recalculate },
}));

const COMPANY = '41000000-0000-0000-0000-000000000003';

function pin(version: string | null, overrides: Record<string, unknown> = {}) {
  return {
    sheet: 'makeup',
    companyId: COMPANY,
    fromDate: '2026-09-01',
    toDate: '2026-09-30',
    omittedMonths: [],
    projectionVersion: version,
    dataAsOf: version === null ? null : '2026-10-12T01:00:00Z',
    ...overrides,
  };
}

const noSleep = () => Promise.resolve();

function run(signal = new AbortController().signal, pollLimit = 3) {
  return recalculateCustomerReportAndWait(COMPANY, '2026-09', 'MONTH', {
    signal,
    pollLimit,
    sleep: noSleep,
  });
}

describe('recalculate wait states', () => {
  beforeEach(() => {
    mocks.requestJson.mockReset();
    mocks.recalculate.mockReset();
  });

  it('reports completed only from the submit response itself', async () => {
    mocks.requestJson.mockResolvedValueOnce(pin('old'));
    mocks.recalculate.mockResolvedValueOnce({ projectionVersion: 'new' });
    await expect(run()).resolves.toEqual({ kind: 'completed', projectionVersion: 'new' });
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
  });

  it('treats a gateway timeout as unknown, never resubmits, and confirms only by read-only pin checks', async () => {
    mocks.requestJson
      .mockResolvedValueOnce(pin('old'))
      .mockResolvedValueOnce(pin('old'))
      .mockResolvedValueOnce(pin('new'));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(504, { code: 'GATEWAY_TIMEOUT' }));
    // A new version proves the month changed, not that this request produced it.
    await expect(run()).resolves.toEqual({ kind: 'updated', projectionVersion: 'new' });
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
    expect(mocks.requestJson).toHaveBeenCalledTimes(3);
    for (const [path] of mocks.requestJson.mock.calls) {
      expect(String(path)).toContain('/api/v1/attendance-report-queries/makeup?');
    }
  });

  it('a network drop during submit is unknown as well and is not resubmitted', async () => {
    mocks.requestJson.mockResolvedValue(pin('old'));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(0, { code: 'NETWORK_REQUEST_FAILED' }));
    await expect(run()).resolves.toEqual({ kind: 'unconfirmed', beforeVersion: 'old' });
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
  });

  it('ends as unconfirmed, not running or failed, when no new version appears in the polling budget', async () => {
    mocks.requestJson.mockResolvedValue(pin('old'));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(408, { code: 'REQUEST_TIMEOUT' }));
    await expect(run(undefined, 4)).resolves.toEqual({ kind: 'unconfirmed', beforeVersion: 'old' });
    expect(mocks.requestJson).toHaveBeenCalledTimes(5);
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
  });

  it('does not depend on the browser clock: an older dataAsOf on a new version still counts as an update', async () => {
    mocks.requestJson
      .mockResolvedValueOnce(pin('old'))
      .mockResolvedValueOnce(pin('new', { dataAsOf: '2020-01-01T00:00:00Z' }));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(504, {}));
    await expect(run()).resolves.toEqual({ kind: 'updated', projectionVersion: 'new' });
  });

  it('shows permission and business rejections as the real error', async () => {
    mocks.requestJson.mockResolvedValue(pin('old'));
    const denied = new ApiRequestError(403, { code: 'ACCESS_DENIED', message: '无权重新计算' });
    mocks.recalculate.mockRejectedValueOnce(denied);
    await expect(run()).rejects.toBe(denied);
    const closed = new ApiRequestError(409, { code: 'ATTENDANCE_REPORT_PERIOD_CLOSED' });
    mocks.recalculate.mockRejectedValueOnce(closed);
    await expect(run()).rejects.toBe(closed);
    expect(isRecalculationOutcomeUnknown(denied)).toBe(false);
    expect(isRecalculationOutcomeUnknown(new ApiRequestError(500, {}))).toBe(false);
  });

  it('a wrong-month or wrong-company pin response is rejected, not read as this month', async () => {
    mocks.requestJson.mockResolvedValueOnce(pin('x', { toDate: '2026-10-31' }));
    await expect(loadPublishedReportPin(COMPANY, '2026-09')).rejects.toThrow();
    mocks.requestJson.mockResolvedValueOnce(pin('x', { omittedMonths: [{ month: '2026-09' }] }));
    await expect(loadPublishedReportPin(COMPANY, '2026-09')).rejects.toThrow();
    mocks.requestJson.mockResolvedValueOnce(pin('x', { companyId: 'other' }));
    await expect(loadPublishedReportPin(COMPANY, '2026-09')).rejects.toThrow();
    mocks.requestJson.mockResolvedValueOnce(pin(null));
    await expect(loadPublishedReportPin(COMPANY, '2026-09')).resolves.toBeNull();
  });

  it('a wrong-month pin during polling is never counted as an update and keeps the outcome unknown', async () => {
    mocks.requestJson
      .mockResolvedValueOnce(pin('old'))
      .mockResolvedValue(pin('new', { fromDate: '2026-08-01', toDate: '2026-08-31' }));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(504, {}));
    await expect(run()).resolves.toEqual({ kind: 'unconfirmed', beforeVersion: 'old' });
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
  });

  it('after an unknown submit, failing read-only checks stay unknown instead of becoming a failure', async () => {
    mocks.requestJson
      .mockResolvedValueOnce(pin('old'))
      .mockRejectedValueOnce(new ApiRequestError(500, { code: 'INTERNAL_ERROR' }))
      .mockRejectedValueOnce(new ApiRequestError(403, { code: 'ACCESS_DENIED' }))
      .mockRejectedValueOnce(new ApiRequestError(0, { code: 'NETWORK_REQUEST_FAILED' }));
    mocks.recalculate.mockRejectedValueOnce(new ApiRequestError(504, {}));
    await expect(run(undefined, 3)).resolves.toEqual({ kind: 'unconfirmed', beforeVersion: 'old' });
    expect(mocks.recalculate).toHaveBeenCalledTimes(1);
  });

  it('aborting (company/month switch or unmount) stops tracking without an outcome', async () => {
    const controller = new AbortController();
    mocks.requestJson.mockResolvedValueOnce(pin('old'));
    mocks.recalculate.mockImplementationOnce(async () => {
      controller.abort();
      throw new ApiRequestError(504, {});
    });
    await expect(run(controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(mocks.requestJson).toHaveBeenCalledTimes(1);
  });

  it('every pin read carries an abortable bounded signal', async () => {
    mocks.requestJson.mockResolvedValueOnce(pin('old'));
    await loadPublishedReportPin(COMPANY, '2026-09', new AbortController().signal);
    const init = mocks.requestJson.mock.calls[0]?.[1] as RequestInit;
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('a manual refresh unlocks only after a successful read, never on a failed one', () => {
    const failed = checkAfterUnknownSubmit('old', { ok: false });
    expect(failed.unlock).toBe(false);
    expect(failed.message).toContain('仍未知');
    const same = checkAfterUnknownSubmit('old', { ok: true, pin: { projectionVersion: 'old', dataAsOf: 'x' } });
    expect(same.unlock).toBe(true);
    expect(same.message).toContain('尚未看到新结果');
    expect(same.message).toContain('不能说明上次提交已结束');
    expect(same.message).not.toContain('可再次提交');
    const changed = checkAfterUnknownSubmit('old', { ok: true, pin: { projectionVersion: 'new', dataAsOf: 'x' } });
    expect(changed.unlock).toBe(true);
    expect(changed.message).toContain('无法确认');
    const none = checkAfterUnknownSubmit(null, { ok: true, pin: null });
    expect(none.unlock).toBe(true);
  });
});

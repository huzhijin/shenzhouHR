import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { requestJson } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import { mutationSuccessNotice } from './attendanceSetupFeedback';

describe('AttendanceSetupNotice replay state', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('renders a distinct replay state only from the trusted response header', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ accepted: true }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Replayed': 'true',
        },
      })),
    );
    const response = await requestJson<{ accepted: boolean }>(
      '/api/v1/attendance-setup/test-mutation',
      { method: 'POST', body: '{}' },
    );

    const view = render(
      <AttendanceSetupNotice
        notice={mutationSuccessNotice(response, '操作已完成。')}
      />,
    );

    expect(view.container.querySelector('[data-state="replay"]')).toBeInTheDocument();
    expect(screen.getByText(/已经成功完成/)).toBeInTheDocument();
    expect(screen.queryByText(/幂等|重放/)).not.toBeInTheDocument();
  });

  it('keeps internal correlation metadata out of business notices', () => {
    render(
      <AttendanceSetupNotice
        notice={{
          kind: 'error',
          message: '保存失败，请稍后重试。',
          correlationId: 'attendance-request-internal-500',
          state: 'error',
        }}
      />,
    );

    expect(screen.getByText('保存失败，请稍后重试。')).toBeInTheDocument();
    expect(screen.queryByText(/attendance-request-internal-500/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关联 ID|关联标识/)).not.toBeInTheDocument();
  });
});

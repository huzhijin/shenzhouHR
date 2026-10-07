import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import { Wave7AsyncBoundary } from './Wave7Common';

describe('Wave7AsyncBoundary error display', () => {
  afterEach(cleanup);

  it('does not expose gateway correlation metadata in a business error', async () => {
    const loader = vi.fn().mockRejectedValue(new ApiRequestError(503, {
      code: 'DASHBOARD_UNAVAILABLE',
      message: '考勤工作台暂时不可用，请稍后重试。',
      correlationId: 'dashboard-request-internal-503',
      retryable: true,
    }));

    render(
      <Wave7AsyncBoundary
        loader={loader}
        isEmpty={() => false}
      >
        {() => <div>工作台内容</div>}
      </Wave7AsyncBoundary>,
    );

    expect(
      await screen.findByText('考勤工作台暂时不可用，请稍后重试。'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/dashboard-request-internal-503/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关联 ID|关联标识/)).not.toBeInTheDocument();
  });

  it('renders a page-specific title and description for an empty resource', async () => {
    render(
      <Wave7AsyncBoundary
        loader={async () => []}
        isEmpty={(items) => items.length === 0}
        emptyTitle="当前月份无正式数据"
        emptyDescription="请先完成计算与发布。"
      >
        {() => <div>不应显示</div>}
      </Wave7AsyncBoundary>,
    );

    expect(await screen.findByRole('heading', {
      name: '当前月份无正式数据',
    })).toBeInTheDocument();
    expect(screen.getByText('请先完成计算与发布。')).toBeInTheDocument();
    expect(screen.queryByText('不应显示')).not.toBeInTheDocument();
  });
});

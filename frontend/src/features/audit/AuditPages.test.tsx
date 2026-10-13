import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import { AuditDetailPage } from './AuditDetailPage';
import { AuditPage } from './AuditPage';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

const eventId = '9400000000000000001';
const resourceId = '9100000000000000002';
const requestId = 'synthetic-request-001';
const correlationId = 'synthetic-correlation-001';

describe('audit pages business-facing identifiers', () => {
  afterEach(() => {
    cleanup();
  });

  it('keeps event ids in links without rendering event, resource, or correlation ids', async () => {
    const view = render(
      <MemoryRouter initialEntries={['/access/audit']}>
        <Routes>
          <Route path="/access/audit" element={<AuditPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const [actionLink] = await screen.findAllByRole('link', { name: '解锁账号' });
    expect(actionLink).toBeDefined();
    expect(actionLink).toHaveAttribute('href', `/access/audit/${eventId}`);
    expect(screen.getAllByText('本地账号')).not.toHaveLength(0);
    expect(view.baseElement).not.toHaveTextContent(eventId);
    expect(view.baseElement).not.toHaveTextContent(resourceId);
    expect(view.baseElement).not.toHaveTextContent(correlationId);
    expect(view.baseElement).not.toHaveTextContent('关联 ID');
  });

  it('renders audit detail using business labels without exposing internal ids', async () => {
    const view = render(
      <MemoryRouter initialEntries={[`/access/audit/${eventId}`]}>
        <Routes>
          <Route path="/access/audit/:auditEventId" element={<AuditDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: '解锁账号' }))
      .toBeInTheDocument();
    expect(screen.getAllByText('本地账号')).not.toHaveLength(0);
    expect(screen.getByText('完成本地联调验证')).toBeInTheDocument();
    expect(screen.getByText('锁定 → 启用')).toBeInTheDocument();
    expect(view.baseElement).not.toHaveTextContent(eventId);
    expect(view.baseElement).not.toHaveTextContent(resourceId);
    expect(view.baseElement).not.toHaveTextContent(requestId);
    expect(view.baseElement).not.toHaveTextContent(correlationId);
    expect(view.baseElement).not.toHaveTextContent('事件 ID');
    expect(view.baseElement).not.toHaveTextContent('请求 ID');
    expect(view.baseElement).not.toHaveTextContent('关联 ID');
  });
});

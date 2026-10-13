import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import {
  ApiErrorState,
  VersionAuditPanel,
} from './PeopleCommon';

const auditApi = vi.hoisted(() => ({
  listAuditEvents: vi.fn(),
}));

vi.mock('../audit/auditApi', () => auditApi);

describe('ApiErrorState business display', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows a business error without exposing its correlation metadata', () => {
    render(
      <ApiErrorState
        error={new ApiRequestError(503, {
          code: 'PEOPLE_SERVICE_UNAVAILABLE',
          message: '人员数据暂时不可用，请稍后重试。',
          correlationId: 'people-request-internal-503',
          retryable: true,
        })}
      />,
    );

    expect(screen.getByText('人员数据暂时不可用，请稍后重试。')).toBeInTheDocument();
    expect(screen.queryByText(/people-request-internal-503/)).not.toBeInTheDocument();
    expect(screen.queryByText(/关联 ID|关联标识/)).not.toBeInTheDocument();
  });
});

describe('VersionAuditPanel capability boundary', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('does not query audit events without AUDIT:READ', async () => {
    render(
      <VersionAuditPanel
        versions={[]}
        resourceType="EMPLOYEE"
        resourceId="10000000-0000-0000-0000-000000000001"
        canReadAudit={false}
      />,
    );

    fireEvent.click(screen.getByRole('tab', { name: '审计追踪' }));
    await waitFor(() => expect(screen.getByText('当前账号无权访问')).toBeInTheDocument());
    expect(auditApi.listAuditEvents).not.toHaveBeenCalled();
  });

  it('shows the audit action without exposing event or correlation identifiers', async () => {
    auditApi.listAuditEvents.mockResolvedValue({
      items: [{
        eventId: '94000000-0000-0000-0000-000000000001',
        occurredAt: '2026-07-30T08:00:00Z',
        actorDisplayName: '考勤管理员',
        action: 'EMPLOYEE_VERSION_CREATED',
        resourceType: 'EMPLOYEE',
        resourceId: '10000000-0000-0000-0000-000000000001',
        result: 'SUCCESS',
        correlationId: 'people-audit-internal-001',
      }],
      total: 1,
      page: 0,
      size: 20,
    });

    const view = render(
      <VersionAuditPanel
        versions={[]}
        resourceType="EMPLOYEE"
        resourceId="10000000-0000-0000-0000-000000000001"
        canReadAudit
      />,
    );

    fireEvent.click(screen.getByRole('tab', { name: '审计追踪' }));

    expect(await screen.findAllByText('创建员工档案版本')).not.toHaveLength(0);
    expect(view.baseElement).not.toHaveTextContent('people-audit-internal-001');
    expect(view.baseElement).not.toHaveTextContent('94000000-0000-0000-0000-000000000001');
    expect(view.baseElement).not.toHaveTextContent('关联 ID');
  });
});

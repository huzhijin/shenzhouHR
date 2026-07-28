import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import { VersionAuditPanel } from './PeopleCommon';

const auditApi = vi.hoisted(() => ({
  listAuditEvents: vi.fn(),
}));

vi.mock('../audit/auditApi', () => auditApi);

describe('VersionAuditPanel capability boundary', () => {
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
});

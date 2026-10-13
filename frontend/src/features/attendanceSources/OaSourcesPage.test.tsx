import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const apiMocks = vi.hoisted(() => ({
  listAttendanceSources: vi.fn(),
  listOaDocuments: vi.fn(),
}));
vi.mock('./attendanceSourceApi', () => apiMocks);

import { leaveTypeCases } from '../../test/fixtures/leaveTypeFixtures';
import { OaSourcesPage } from './OaSourcesPage';

describe('OA source leave labels', () => {
  afterEach(cleanup);

  it('shows every leave type in Chinese without dropping pending or unusual leave types', async () => {
    apiMocks.listAttendanceSources.mockResolvedValue({
      items: [{ sourceId: 'oa-test', sourceType: 'OA_ATTENDANCE', displayName: '测试 OA' }],
      page: 0, size: 100, totalElements: 1, totalPages: 1,
    });
    apiMocks.listOaDocuments.mockResolvedValue({
      items: leaveTypeCases.map(([leaveType], index) => ({
        documentId: `document-${index}`,
        sourceDocumentId: `source-document-${index}`,
        sourceVersion: 'v1',
        documentType: 'LEAVE',
        leaveType,
        sourceStatus: 'PENDING',
        effectiveCandidate: true,
        employeeNumber: `TEST-${index}`,
        employeeName: `测试员工${index}`,
        department: '测试部门',
        intervalStart: '2026-09-09T01:00:00Z',
        intervalEndExclusive: '2026-09-09T02:00:00Z',
      })),
      page: 0, size: 20, totalElements: 13, totalPages: 1,
    });

    render(<OaSourcesPage />);

    const region = await screen.findByRole('region', { name: '办公系统考勤单据' });
    const table = within(within(region).getByRole('table'));
    expect(table.getAllByRole('row')).toHaveLength(14);
    for (const [code, label] of leaveTypeCases) {
      expect(table.getByText(`请${label}`)).toBeInTheDocument();
      expect(table.queryByText(`请${code}`)).not.toBeInTheDocument();
    }
    expect(table.getAllByText('审批中')).toHaveLength(13);
    expect(apiMocks.listOaDocuments).toHaveBeenCalledWith('oa-test', 0, 20, undefined);
  });
});

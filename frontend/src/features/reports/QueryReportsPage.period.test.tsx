import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: vi.fn(() => true) }));

import { QueryReportsPage } from './QueryReportsPage';

const capabilities = [
  'ATTENDANCE_REPORT:EXPORT_CREATE',
  'ATTENDANCE_REPORT:EXPORT_DOWNLOAD',
] as const;

function renderSheet(sheet: string) {
  return render(
    <MemoryRouter initialEntries={[`/attendance/queries/${sheet}`]}>
      <Routes>
        <Route
          path="/attendance/queries/:sheet"
          element={<QueryReportsPage capabilities={capabilities} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe('QueryReportsPage period and export chrome', () => {
  afterEach(() => {
    cleanup();
  });

  it('shows start and end dates without a month picker on leave and matrix', () => {
    renderSheet('leave');
    expect(screen.getByText('起止日期')).toBeInTheDocument();
    expect(screen.queryByText('月份')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /导\s*出/ })).toBeInTheDocument();
  });

  it('shows start and end dates on absence and leave statistic sheets', () => {
    renderSheet('absence-stat');
    expect(screen.getByRole('heading', { name: '旷工统计表' })).toBeInTheDocument();
    expect(screen.getByText('起止日期')).toBeInTheDocument();
    cleanup();
    renderSheet('leave-stat');
    expect(screen.getByRole('heading', { name: '请假统计表' })).toBeInTheDocument();
    expect(screen.getByText('起止日期')).toBeInTheDocument();
    expect(screen.queryByText('请假汇总')).not.toBeInTheDocument();
  });

  it('keeps a natural-year picker on annual-leave statistics', () => {
    renderSheet('annual-leave-stat');
    expect(screen.getByText('自然年')).toBeInTheDocument();
    expect(screen.queryByText('月份')).not.toBeInTheDocument();
    expect(screen.queryByText('起止日期')).not.toBeInTheDocument();
  });
});

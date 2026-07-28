import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { AttendanceBigScreenPage } from './AttendanceBigScreenPage';

describe('AttendanceBigScreenPage', () => {
  it('shows the full attendance operations story and switches scope', () => {
    render(
      <MemoryRouter>
        <AttendanceBigScreenPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '考勤运行总览' })).toBeInTheDocument();
    expect(screen.getByText(
      (_text, element) => element?.tagName === 'STRONG' && element.textContent === '96.8%',
    )).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看报表' }))
      .toHaveAttribute('href', '/attendance/reports');
    expect(screen.getByRole('link', { name: '返回工作台' }))
      .toHaveAttribute('href', '/workbench');

    fireEvent.click(screen.getByRole('radio', { name: '制造一部' }));
    expect(screen.getAllByText(
      (_text, element) => element?.tagName === 'STRONG' && element.textContent === '95.8%',
    )).not.toHaveLength(0);
    expect(screen.getByText(
      (_text, element) => element?.tagName === 'STRONG' && element.textContent === '42 人',
    )).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '查看指标口径' }));
    expect(screen.getByRole('dialog', { name: '大屏指标口径' })).toBeInTheDocument();
  });
});

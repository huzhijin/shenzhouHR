import {
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';

import '../../shared/i18n/i18n';
import { RulesHomePage } from './RulesHomePage';

describe('RulesHomePage', () => {
  afterEach(cleanup);

  it('shows the authorized daily setup in a clear sequence', () => {
    renderRulesHome(['POLICY:READ', 'ATTENDANCE_SETUP:READ']);

    expect(screen.getByRole('heading', { level: 1, name: '规则与考勤设置' }))
      .toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '日常考勤设置' }))
      .toBeInTheDocument();
    expect(screen.getByText('一句话看懂考勤设置')).toBeInTheDocument();
    expect(screen.getByText(/地点决定“在哪里”/)).toBeInTheDocument();
    expect(screen.getByText('第 1 步')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '设置地点' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '设置班次' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '设置工作日历' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '建立考勤组' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '配置考勤规则' })).toBeInTheDocument();
  });

  it('does not expose attendance setup links without its read capability', () => {
    renderRulesHome(['POLICY:READ']);

    expect(screen.queryByRole('heading', { name: '日常考勤设置' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '设置班次' }))
      .not.toBeInTheDocument();
    expect(screen.queryByText('一句话看懂考勤设置')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '高级规则模板（一般不用）' }))
      .toBeInTheDocument();
  });

  it('opens the selected authorized setting', () => {
    renderRulesHome(['POLICY:READ', 'ATTENDANCE_SETUP:READ']);

    expect(screen.getByRole('button', { name: '设置地点：打开设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '设置班次：打开设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '设置工作日历：打开设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '建立考勤组：打开设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '配置考勤规则：打开设置' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '设置班次：打开设置' }));
    expect(screen.getByTestId('rules-location')).toHaveTextContent('/rules/shifts');
  });
});

function renderRulesHome(capabilities: string[]) {
  return render(
    <MemoryRouter initialEntries={['/rules']}>
      <RulesHomePage capabilities={capabilities} />
      <LocationProbe />
    </MemoryRouter>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="rules-location">{location.pathname}</output>;
}

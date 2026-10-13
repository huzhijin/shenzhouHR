import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../shared/api/apiClient', () => ({ requestJson: vi.fn() }));

vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: vi.fn(() => true) }));

import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';

import {
  DayPunchLocationPanel,
  isMobilePunch,
  punchClockLabel,
} from './DayPunchLocationPanel';

describe('DayPunchLocationPanel', () => {
  afterEach(() => {
    cleanup();
    vi.mocked(isDemoMode).mockReturnValue(true);
    vi.mocked(requestJson).mockReset();
  });

  it('labels gps as mobile and fingerprint as machine', () => {
    expect(isMobilePunch('gps')).toBe(true);
    expect(isMobilePunch('out_work')).toBe(true);
    expect(isMobilePunch('fp')).toBe(false);
    expect(punchClockLabel('2026-09-15T00:32:00Z')).toMatch(/\d{2}:\d{2}/);
  });

  it('lists demo punches and hides view-location without capability', () => {
    render(
      <DayPunchLocationPanel
        companyId="company-1"
        employeeId="emp-1"
        businessDate="2026-09-15"
      />,
    );
    expect(screen.getByText('手机')).toBeInTheDocument();
    expect(screen.getByText('考勤机')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看位置' })).not.toBeInTheDocument();
  });

  it('opens reason then shows address without plotting unknown coordinates', () => {
    render(
      <DayPunchLocationPanel
        companyId="company-1"
        employeeId="emp-1"
        businessDate="2026-09-15"
        canViewLocation
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '查看位置' }));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '核对大连手机打卡' } });
    fireEvent.click(screen.getByRole('button', { name: '查 看' }));
    expect(screen.getByText('辽宁省大连市甘井子区演示路1号')).toBeInTheDocument();
    expect(screen.getByText('地图暂不可用')).toBeInTheDocument();
    expect(screen.queryByText(/121\.|38\./)).not.toBeInTheDocument();
  });
  const punch = { rawFactId: 'raw-1', punchedAt: '2026-09-15T00:32:00Z', method: 'gps',
    sourceLabel: '手机', viewLocationAvailable: true, reasonRequired: true };
  const view = { rawFactId: 'raw-1', punchedAt: punch.punchedAt, method: 'gps', locationText: '测试地址',
    mapPointAvailable: false, mapLongitude: null, mapLatitude: null, mapUnavailableReason: 'UNKNOWN_SYSTEM' };

  it('requests self punches without an employee id and opens own location without a reason', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    vi.mocked(requestJson).mockResolvedValueOnce({ punches: [{ ...punch, reasonRequired: false }] })
      .mockResolvedValueOnce(view);
    render(<DayPunchLocationPanel businessDate="2026-09-15" selfService />);
    fireEvent.click(await screen.findByRole('button', { name: '查看位置' }));
    expect(await screen.findByText('测试地址')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(requestJson).toHaveBeenNthCalledWith(1, '/api/v1/me/attendance/day-punches?businessDate=2026-09-15');
    expect(requestJson).toHaveBeenNthCalledWith(2, '/api/v1/checkins/raw-1/location');
  });

  it('requires a manager reason and renders a real map image with decode-failure fallback', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    vi.mocked(requestJson).mockResolvedValueOnce({ punches: [punch] })
      .mockResolvedValueOnce({ ...view, mapPointAvailable: true, mapLongitude: 121.614, mapLatitude: 38.914,
        mapImageDataUrl: 'data:image/png;base64,iVBORw0KGgo=' });
    render(<DayPunchLocationPanel companyId="company" employeeId="employee" businessDate="2026-09-15" canViewLocation />);
    fireEvent.click(await screen.findByRole('button', { name: '查看位置' }));
    expect(screen.getByRole('button', { name: '查 看' })).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '核对位置' } });
    fireEvent.click(screen.getByRole('button', { name: '查 看' }));
    const image = await screen.findByRole('img', { name: '本次打卡位置单点地图' });
    expect(image).toHaveAttribute('src', 'data:image/png;base64,iVBORw0KGgo=');
    expect(requestJson).toHaveBeenLastCalledWith('/api/v1/checkins/raw-1/location?reason=' + encodeURIComponent('核对位置'));
    expect(screen.queryByText(/121\.614|38\.914/)).not.toBeInTheDocument();
    fireEvent.error(image);
    expect(screen.getByText('地图暂不可用')).toBeInTheDocument();
    expect(screen.getByText('测试地址')).toBeInTheDocument();
  });

  it('honors server scope denial even when the session has location capability', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    vi.mocked(requestJson).mockResolvedValueOnce({ punches: [{ ...punch, viewLocationAvailable: false }] });
    render(<DayPunchLocationPanel companyId="company" employeeId="employee" businessDate="2026-09-15" canViewLocation />);
    expect(await screen.findByText('手机')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '查看位置' })).not.toBeInTheDocument();
  });

  it('shows a self location API error without a hidden reason dialog', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    vi.mocked(requestJson).mockResolvedValueOnce({ punches: [{ ...punch, reasonRequired: false }] })
      .mockRejectedValueOnce(new Error('不在授权范围内'));
    render(<DayPunchLocationPanel businessDate="2026-09-15" selfService />);
    fireEvent.click(await screen.findByRole('button', { name: '查看位置' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('不在授权范围内');
  });

  it('does not show a previous day location response after the selected day changes', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    let finish!: (value: typeof view) => void;
    vi.mocked(requestJson).mockResolvedValueOnce({ punches: [{ ...punch, reasonRequired: false }] })
      .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }))
      .mockResolvedValueOnce({ punches: [] });
    const { rerender } = render(<DayPunchLocationPanel businessDate="2026-09-15" selfService />);
    fireEvent.click(await screen.findByRole('button', { name: '查看位置' }));
    rerender(<DayPunchLocationPanel businessDate="2026-09-16" selfService />);
    await act(async () => finish(view));
    await waitFor(() => expect(screen.getByText('当天没有原始打卡。')).toBeInTheDocument());
    expect(screen.queryByText('测试地址')).not.toBeInTheDocument();
  });

});

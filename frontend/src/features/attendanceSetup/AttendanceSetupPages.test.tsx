import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoCalendars,
  demoShifts,
  demoShiftVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import { AttendanceGroupsPage } from './AttendanceGroupsPage';
import { AttendancePolicyPage } from './AttendancePolicyPage';
import { CalendarDaysDialog } from './CalendarDialogs';
import { CalendarsPage } from './CalendarsPage';
import { PolicySimulationPanel } from './PolicySimulationPanel';
import { ShiftVersionDialog } from './ShiftDialogs';
import { ShiftsPage } from './ShiftsPage';
import type {
  AttendancePolicyKind,
  CalendarDayView,
  PolicySimulationBatchView,
  WorkCalendarView,
} from './attendanceSetupTypes';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

const capabilities = [
  'ATTENDANCE_SETUP:READ',
  'ATTENDANCE_SETUP:MANAGE_GROUP',
  'ATTENDANCE_SETUP:ASSIGN',
  'ATTENDANCE_SETUP:MANAGE_SHIFT',
  'ATTENDANCE_SETUP:MANAGE_CALENDAR',
  'ATTENDANCE_SETUP:MANAGE_POLICY',
];
const mealPolicyVersionId = '25200000-0000-0000-0000-000000000001';
const lateGracePolicyVersionId = '25200000-0000-0000-0000-000000000002';
const monthlyExemptionPolicyVersionId = '25200000-0000-0000-0000-000000000003';

describe('attendance setup demo pages', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('renders effective attendance assignments with natural-month continuity', async () => {
    renderPage(<AttendanceGroupsPage capabilities={capabilities} />);

    expect((await screen.findAllByText('苏州一号晶圆厂')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('9200000000000000001:2026-07')).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '分配人员' })).toBeEnabled();
  });

  it('renders seasonal immutable versions on one effective timeline', async () => {
    renderPage(<ShiftsPage capabilities={capabilities} />);

    expect(await screen.findByRole(
      'list',
      { name: '规则生效时间轴' },
      { timeout: 10_000 },
    )).toBeInTheDocument();
    expect(screen.getByText('冬春季白班')).toBeInTheDocument();
    expect(screen.getByText('夏秋季白班')).toBeInTheDocument();
    const segmentTracks = screen.getAllByRole(
      'region',
      { name: /版本 \d+ 工作段类型/ },
    );
    expect(segmentTracks.length).toBeGreaterThan(0);
    for (const track of segmentTracks) {
      expect(track).toHaveAttribute('tabindex', '0');
    }

    fireEvent.click(screen.getByRole('button', { name: '新建班次版本' }));
    const starts = screen.getAllByLabelText('开始时间');
    const ends = screen.getAllByLabelText('结束时间');
    const startOffsets = screen.getAllByLabelText('开始日偏移');
    expect(starts).toHaveLength(3);
    expect(ends).toHaveLength(3);
    expect(startOffsets).toHaveLength(3);
    expect(Array.from(starts, (input) => (input as HTMLInputElement).value))
      .toEqual(['20:00', '01:00', '01:15']);
    expect(Array.from(ends, (input) => (input as HTMLInputElement).value))
      .toEqual(['01:00', '01:15', '04:00']);
  });

  it('loads later shift pages instead of making records after the first page unreachable', async () => {
    const baseShift = requiredDemoItem(demoShifts);
    const shiftItems = [0, 20].map((index) => ({
      ...baseShift,
      shiftId: `shift-${String(index + 1).padStart(2, '0')}`,
      code: `SHIFT-${String(index + 1).padStart(2, '0')}`,
      name: `班次 ${index + 1}`,
    }));
    const listSpy = vi.spyOn(attendanceSetupApi, 'listShifts')
      .mockImplementation((page = 0, size = 20) => Promise.resolve({
        items: page === 0 ? shiftItems.slice(0, 1) : shiftItems.slice(1),
        total: 21,
        page,
        size,
      }));

    const view = renderPage(<ShiftsPage capabilities={capabilities} />);

    expect((await screen.findAllByRole(
      'button',
      { name: 'SHIFT-01 班次 1' },
    )).length).toBeGreaterThan(0);
    const pagination = requiredElement(
      view.container,
      'nav[aria-label="班次模板分页"]',
    );
    fireEvent.click(requiredElement(pagination, '.ant-pagination-next button'));

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledWith(1, 20);
    });
    expect((await screen.findAllByRole(
      'button',
      { name: 'SHIFT-21 班次 21' },
    )).length).toBeGreaterThan(0);
  });

  it('submits an explicit future boundary when deactivating a shift version', async () => {
    const version = requiredDemoItem(demoShiftVersions, 1);
    const statusSpy = vi.spyOn(attendanceSetupApi, 'changeShiftVersionStatus')
      .mockResolvedValue({ ...version, status: 'INACTIVE' });
    const view = renderPage(<ShiftsPage capabilities={capabilities} />);
    const timeline = await screen.findByRole('list', { name: '规则生效时间轴' });
    const deactivateButtons = within(timeline).getAllByRole(
      'button',
      { name: '停用' },
    );

    expect(requiredItem(deactivateButtons, 0)).toBeDisabled();
    fireEvent.click(requiredItem(deactivateButtons, 1));
    const modal = requiredElement(view.baseElement, '.ant-modal');
    fireEvent.change(within(modal).getByLabelText('未来停用生效边界'), {
      target: { value: '2027-01-01' },
    });
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '验证未来班次承接边界' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: /停\s*用/ }));

    await waitFor(() => {
      expect(statusSpy).toHaveBeenCalledWith(
        requiredDemoItem(demoShifts).shiftId,
        version,
        'INACTIVE',
        '2027-01-01',
        '验证未来班次承接边界',
      );
    });
  });

  it('renders a year-boundary warning and the selected annual calendar', async () => {
    renderPage(<CalendarsPage capabilities={capabilities} />);

    expect((await screen.findAllByText(/苏州一号厂 2026 工作日历/)).length).toBeGreaterThan(0);
    expect(screen.getByRole('region', { name: '工作日历族' })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: '不可变日历版本' }))
      .toBeInTheDocument();
    expect(screen.getByText('跨年边界独立解析')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存日期覆盖' })).toBeDisabled();
  });

  it('exposes calendar-family update and publish operations to calendar managers', async () => {
    const family: WorkCalendarView = {
      ...requiredDemoItem(demoCalendars),
      status: 'DRAFT',
    };
    vi.spyOn(attendanceSetupApi, 'listCalendars').mockResolvedValue({
      items: [family],
      total: 1,
      page: 0,
      size: 20,
    });
    vi.spyOn(attendanceSetupApi, 'listCalendarVersions').mockResolvedValue({
      items: [family],
      total: 1,
      page: 0,
      size: 20,
    });
    vi.spyOn(attendanceSetupApi, 'listCalendarVersionDays').mockResolvedValue({
      items: [],
      total: 0,
      page: 0,
      size: 100,
    });
    const updateSpy = vi.spyOn(attendanceSetupApi, 'updateCalendar')
      .mockResolvedValue({ ...family, name: '更新后的日历族', rowVersion: 9 });
    const publishSpy = vi.spyOn(attendanceSetupApi, 'publishCalendar')
      .mockResolvedValue({ ...family, status: 'PUBLISHED', rowVersion: 9 });
    const view = renderPage(<CalendarsPage capabilities={capabilities} />);

    const editButtons = await screen.findAllByRole('button', {
      name: `编辑年度日历 ${family.code}`,
    });
    fireEvent.click(requiredItem(editButtons, 0));
    let modal = requiredElement(view.baseElement, '.ant-modal');
    fireEvent.change(within(modal).getByLabelText('名称'), {
      target: { value: '更新后的日历族' },
    });
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '修订日历族元数据' },
    });
    fireEvent.submit(requiredElement(modal, 'form'));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(
        family,
        expect.objectContaining({
          name: '更新后的日历族',
          reason: '修订日历族元数据',
        }),
      );
    });
    await waitFor(() => {
      expect(view.baseElement.querySelector('.ant-modal')).not.toBeInTheDocument();
    });

    fireEvent.click(requiredItem(screen.getAllByRole('button', {
      name: `发布日历族 ${family.code}`,
    }), 0));
    modal = requiredElement(view.baseElement, '.ant-modal');
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '发布日历族' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: /发\s*布/ }));

    await waitFor(() => {
      expect(publishSpy).toHaveBeenCalledWith(family, '发布日历族');
    });
  });

  it('submits an explicit future boundary when deactivating a calendar family', async () => {
    const family = requiredDemoItem(demoCalendars);
    const statusSpy = vi.spyOn(attendanceSetupApi, 'changeCalendarStatus')
      .mockResolvedValue({ ...family, status: 'INACTIVE', rowVersion: 9 });
    const view = renderPage(<CalendarsPage capabilities={capabilities} />);

    const deactivateButtons = await screen.findAllByRole('button', {
      name: `停用日历族 ${family.code}`,
    });
    fireEvent.click(requiredItem(deactivateButtons, 0));
    const modal = requiredElement(view.baseElement, '.ant-modal');
    fireEvent.change(within(modal).getByLabelText('未来停用生效边界'), {
      target: { value: '2026-12-31' },
    });
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '验证日历族停用承接边界' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: /停\s*用/ }));

    await waitFor(() => {
      expect(statusSpy).toHaveBeenCalledWith(
        family,
        'INACTIVE',
        '2026-12-31',
        '验证日历族停用承接边界',
      );
    });
  });

  it('selects the highest immutable calendar version even when the server returns newest-first', async () => {
    const family = requiredDemoItem(demoCalendars);
    const older: WorkCalendarView = {
      ...family,
      calendarVersionId: 'calendar-version-older',
      versionNumber: 1,
      status: 'PUBLISHED',
    };
    const newest: WorkCalendarView = {
      ...family,
      calendarVersionId: 'calendar-version-newest',
      versionNumber: 2,
      status: 'DRAFT',
    };
    vi.spyOn(attendanceSetupApi, 'listCalendarVersions').mockResolvedValue({
      items: [newest, older],
      total: 2,
      page: 0,
      size: 100,
    });
    const daysSpy = vi.spyOn(attendanceSetupApi, 'listCalendarVersionDays')
      .mockResolvedValue({ items: [], total: 0, page: 0, size: 100 });

    renderPage(<CalendarsPage capabilities={capabilities} />);

    await waitFor(() => {
      expect(daysSpy).toHaveBeenCalledWith(
        family.calendarId,
        newest.calendarVersionId,
        expect.any(String),
        expect.any(String),
        0,
        100,
      );
    });
    expect(screen.getByRole('button', { name: '保存日期覆盖' })).toBeEnabled();
  });

  it('keeps every WAVE-3 setup page read-only while exposing immutable history to AUDITOR', async () => {
    const readOnlyCapabilities = ['ATTENDANCE_SETUP:READ', 'AUDIT:READ'];
    const groupsView = renderPage(
      <AttendanceGroupsPage capabilities={readOnlyCapabilities} />,
    );

    expect((await screen.findAllByText('a'.repeat(64))).length).toBeGreaterThan(0);
    expect(screen.getAllByText('c'.repeat(64)).length).toBeGreaterThan(0);
    expect(await screen.findByRole(
      'region',
      { name: '地点修订历史' },
      { timeout: 10_000 },
    )).toBeInTheDocument();
    expect(await screen.findByRole(
      'region',
      { name: '考勤组修订历史' },
      { timeout: 10_000 },
    )).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '考勤设置' }))
      .toHaveAttribute('href', '/rules/attendance-groups');
    expect(screen.queryByRole('button', { name: '新建考勤地点' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建考勤组' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '分配人员' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /追加.*修订/ }))
      .not.toBeInTheDocument();
    groupsView.unmount();

    const shiftsView = renderPage(<ShiftsPage capabilities={readOnlyCapabilities} />);
    expect((await screen.findAllByText(
      '7f5dd34ba0f08fe28c835f45a8f4354c7f5dd34ba0f08fe28c835f45a8f4354c',
      {},
      { timeout: 10_000 },
    )).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: '考勤设置' }))
      .toHaveAttribute('href', '/rules/attendance-groups');
    expect(screen.queryByRole('button', { name: '新建班次模板' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建班次版本' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '停用' })).not.toBeInTheDocument();
    shiftsView.unmount();

    renderPage(<CalendarsPage capabilities={readOnlyCapabilities} />);
    expect((await screen.findAllByText('d'.repeat(64))).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: '考勤设置' }))
      .toHaveAttribute('href', '/rules/attendance-groups');
    expect(screen.queryByRole('button', { name: '新建年度日历' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '追加工作日历版本' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '保存日期覆盖' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
  }, 45_000);

  it('rejects overlapping shift segments before invoking the mutation', async () => {
    const onSubmit = vi.fn();
    const view = render(
      <ShiftVersionDialog
        open
        processing={false}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText('生效日'), {
      target: { value: '2027-01-01' },
    });
    fireEvent.change(requiredItem(screen.getAllByLabelText('开始时间'), 1), {
      target: { value: '00:30' },
    });
    fireEvent.change(screen.getByLabelText('变更原因'), {
      target: { value: '验证重叠工作段' },
    });
    fireEvent.submit(requiredElement(view.baseElement, '.ant-modal form'));

    expect(await screen.findByText('工作段必须按时间顺序排列且不能重叠。'))
      .toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('rejects duplicate dates within one calendar version before invoking the mutation', async () => {
    const onSubmit = vi.fn();
    const days: CalendarDayView[] = [{
      calendarDayId: 'day-1',
      calendarId: 'calendar-1',
      calendarVersionId: 'calendar-version-1',
      businessDate: '2027-01-01',
      dayType: 'PUBLIC_HOLIDAY',
      rowVersion: 1,
      changeReason: '元旦',
    }, {
      calendarDayId: 'day-2',
      calendarId: 'calendar-1',
      calendarVersionId: 'calendar-version-1',
      businessDate: '2027-01-02',
      dayType: 'WEEKEND',
      rowVersion: 1,
      changeReason: '周末',
    }];
    const view = render(
      <CalendarDaysDialog
        open
        processing={false}
        days={days}
        onSubmit={onSubmit}
        onCancel={vi.fn()}
      />,
    );

    fireEvent.change(requiredItem(screen.getAllByLabelText('开始日期'), 1), {
      target: { value: '2027-01-01' },
    });
    fireEvent.change(screen.getByLabelText('变更原因'), {
      target: { value: '验证重复日期' },
    });
    fireEvent.submit(requiredElement(view.baseElement, '.ant-modal form'));

    expect(await screen.findByText('同一日历版本内每个日期只能出现一次。'))
      .toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('runs a server-shaped policy simulation without writing a formal result', async () => {
    renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '运行试算' }));

    await waitFor(() => {
      expect(screen.getByText(/正式结果未写入/)).toBeInTheDocument();
    });
    expect(screen.getAllByText('否').length).toBeGreaterThan(0);
  });

  it('resolves a deep-linked non-meal policy from its exact server context', async () => {
    renderPolicyPage(lateGracePolicyVersionId);

    expect(await screen.findByText('迟到分钟宽限')).toBeInTheDocument();
    expect(screen.getByLabelText('已发布策略版本 ID')).toHaveValue(
      lateGracePolicyVersionId,
    );
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();
    expect(screen.getByText('graceMinutes')).toBeInTheDocument();
    expect(screen.queryByText('晚餐窗口开始')).not.toBeInTheDocument();
  });

  it('fails a missing direct policy-version route closed', async () => {
    const view = renderPolicyPage('missing-policy-version');

    await waitFor(() => {
      expect(view.container.querySelector('[data-state="404"]')).toBeInTheDocument();
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览影响' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建策略绑定' })).toBeDisabled();
  });

  it('submits a local-current-day simulation payload with a strict knowledge cutoff and no default punches', async () => {
    const onSimulate = vi.fn();
    const beforeRender = new Date();
    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="policy-version"
        processing={false}
        onSimulate={onSimulate}
      />,
    );

    const businessDate = (screen.getByLabelText('开始日期') as HTMLInputElement).value;
    const correctionAsOf = (screen.getByLabelText('补正判断日') as HTMLInputElement).value;

    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(onSimulate).toHaveBeenCalledWith({
        employeeId: '9200000000000000001',
        businessDate,
        correctionAsOf,
        punches: [],
      });
    });
    const after = new Date();

    expect([localDate(beforeRender), localDate(after)]).toContain(businessDate);
    expect(correctionAsOf).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/,
    );
    expect(Date.parse(correctionAsOf)).toBeGreaterThanOrEqual(beforeRender.getTime() - 1_000);
    expect(Date.parse(correctionAsOf)).toBeLessThanOrEqual(after.getTime());
  });

  it.each([
    ['missing seconds', '2026-07-27T12:00+08:00'],
    ['missing Z/offset suffix', '2026-07-27T12:00:00'],
  ])('rejects a simulation correction instant %s', async (_caseName, correctionAsOf) => {
    const onSimulate = vi.fn();
    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="policy-version"
        processing={false}
        onSimulate={onSimulate}
      />,
    );
    const correctionInput = screen.getByLabelText('补正判断日');

    fireEvent.change(correctionInput, { target: { value: correctionAsOf } });
    submitSimulationForm(view.container);
    expect(await screen.findByText('必须填写带时区偏移的 RFC3339 时间')).toBeInTheDocument();
    expect(onSimulate).toHaveBeenCalledTimes(0);
  });

  it('submits a simulation correction instant with a Z suffix as an exact authoritative payload', async () => {
    const onSimulate = vi.fn();
    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="policy-version"
        processing={false}
        onSimulate={onSimulate}
      />,
    );
    const businessDate = (screen.getByLabelText('开始日期') as HTMLInputElement).value;
    const correctionInput = screen.getByLabelText('补正判断日');

    fireEvent.change(correctionInput, { target: { value: '2026-07-27T12:00:00Z' } });
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(onSimulate).toHaveBeenCalledTimes(1);
    });
    expect(onSimulate).toHaveBeenCalledWith({
      employeeId: '9200000000000000001',
      businessDate,
      correctionAsOf: '2026-07-27T12:00:00Z',
      punches: [],
    });
  });

  it('clears the previous simulation and shows local loading for a new request', async () => {
    const pending = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockResolvedValueOnce(simulationResult('旧试算结果'))
      .mockReturnValueOnce(pending.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '运行试算' }));
    expect(await screen.findByText('旧试算结果')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '运行试算' }));
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(2);
    });

    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    expect(within(resultRegion).queryByText('旧试算结果')).not.toBeInTheDocument();
    expect(within(resultRegion).getByLabelText('正在加载')).toBeInTheDocument();
  });

  it('shows a local error without retaining the previous simulation after failure', async () => {
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockResolvedValueOnce(simulationResult('旧试算结果'))
      .mockRejectedValueOnce(new Error('simulation failed'));
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '运行试算' }));
    expect(await screen.findByText('旧试算结果')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '运行试算' }));

    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    await waitFor(() => {
      expect(within(resultRegion).getByText('服务暂时不可用，请稍后重试。')).toBeInTheDocument();
    });
    expect(within(resultRegion).queryByText('旧试算结果')).not.toBeInTheDocument();
  });

  it('keeps the latest request loading and result when an older request resolves last', async () => {
    const requestA = deferred<PolicySimulationBatchView>();
    const requestB = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockReturnValueOnce(requestA.promise)
      .mockReturnValueOnce(requestB.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(2);
    });

    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    await act(async () => {
      requestB.resolve(simulationResult('最新 B 结果'));
    });
    expect(within(resultRegion).getByText('最新 B 结果')).toBeInTheDocument();
    expect(within(resultRegion).queryByText('过期 A 结果')).not.toBeInTheDocument();
    expect(within(resultRegion).queryByLabelText('正在加载')).not.toBeInTheDocument();

    await act(async () => {
      requestA.resolve(simulationResult('过期 A 结果'));
    });
    expect(within(resultRegion).getByText('最新 B 结果')).toBeInTheDocument();
    expect(within(resultRegion).queryByText('过期 A 结果')).not.toBeInTheDocument();
    expect(within(resultRegion).queryByLabelText('正在加载')).not.toBeInTheDocument();
  });

  it('keeps the latest result when an older request rejects after it succeeds', async () => {
    const requestA = deferred<PolicySimulationBatchView>();
    const requestB = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockReturnValueOnce(requestA.promise)
      .mockReturnValueOnce(requestB.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(2);
    });

    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    await act(async () => {
      requestB.resolve(simulationResult('最新 B 结果'));
    });
    expect(within(resultRegion).getByText('最新 B 结果')).toBeInTheDocument();
    expect(within(resultRegion).queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    expect(within(resultRegion).queryByLabelText('正在加载')).not.toBeInTheDocument();

    await act(async () => {
      requestA.reject(new Error('stale A failure'));
    });
    expect(within(resultRegion).getByText('最新 B 结果')).toBeInTheDocument();
    expect(within(resultRegion).queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    expect(within(resultRegion).queryByLabelText('正在加载')).not.toBeInTheDocument();
  });

  it('clears an existing simulation result when the policy kind changes', async () => {
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockResolvedValueOnce(simulationResult('切换 kind 前的结果'));
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    expect(await screen.findByText('切换 kind 前的结果')).toBeInTheDocument();

    fireEvent.click(screen.getByText('迟到宽限'));
    await waitFor(() => {
      expect(screen.queryByText('切换 kind 前的结果')).not.toBeInTheDocument();
    });
    fireEvent.click(screen.getByText('晚餐扣除'));
    expect(screen.queryByText('切换 kind 前的结果')).not.toBeInTheDocument();
  });

  it('clears an existing simulation error when the policy kind changes', async () => {
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockRejectedValueOnce(new Error('simulation failed'));
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    expect(await screen.findByText('服务暂时不可用，请稍后重试。')).toBeInTheDocument();

    fireEvent.click(screen.getByText('迟到宽限'));
    await waitFor(() => {
      expect(screen.queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    });
  });

  it('clears pending loading and invalidates a visible-kind response when the policy kind changes', async () => {
    const pending = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy').mockReturnValueOnce(pending.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    expect(
      within(requiredElement(view.container, '.policy-simulation-result')).getByLabelText('正在加载'),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByText('迟到宽限'));
    await waitFor(() => {
      expect(
        within(requiredElement(view.container, '.policy-simulation-result'))
          .queryByLabelText('正在加载'),
      ).not.toBeInTheDocument();
    });
    expect(
      within(requiredElement(view.container, '.policy-simulation-result'))
        .queryByText('服务暂时不可用，请稍后重试。'),
    ).not.toBeInTheDocument();

    await act(async () => {
      pending.resolve(simulationResult(
        '切换 kind 后的过期响应',
        'MONTHLY_LATE_EXEMPTION',
      ));
    });
    expect(
      within(requiredElement(view.container, '.policy-simulation-result'))
        .queryByText('切换 kind 后的过期响应'),
    ).not.toBeInTheDocument();
    expect(
      within(requiredElement(view.container, '.policy-simulation-result'))
        .queryByText('服务暂时不可用，请稍后重试。'),
    ).not.toBeInTheDocument();
    expect(
      within(requiredElement(view.container, '.policy-simulation-result'))
        .queryByLabelText('正在加载'),
    ).not.toBeInTheDocument();
  });

  it('invalidates an in-flight simulation and clears state when the route context changes', async () => {
    const previousResult = simulationResult('旧 route 结果');
    const pending = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockResolvedValueOnce(previousResult)
      .mockRejectedValueOnce(new Error('route error'))
      .mockReturnValueOnce(pending.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(view.container);
    expect(await screen.findByText('旧 route 结果')).toBeInTheDocument();
    changePolicyRoute(lateGracePolicyVersionId);
    await waitFor(() => {
      expect(screen.queryByText('旧 route 结果')).not.toBeInTheDocument();
    });
    expect(await screen.findByText('迟到分钟宽限')).toBeInTheDocument();

    submitSimulationForm(view.container);
    expect(await screen.findByText('服务暂时不可用，请稍后重试。')).toBeInTheDocument();
    changePolicyRoute(monthlyExemptionPolicyVersionId);
    await waitFor(() => {
      expect(screen.queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    });
    expect(await screen.findByRole('heading', { name: '自然月迟到豁免' }))
      .toBeInTheDocument();

    submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(3);
    });
    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    expect(within(resultRegion).getByLabelText('正在加载')).toBeInTheDocument();
    changePolicyRoute(mealPolicyVersionId);
    await waitFor(() => {
      expect(within(resultRegion).queryByLabelText('正在加载')).not.toBeInTheDocument();
    });

    await act(async () => {
      pending.resolve(simulationResult('切换 route 后的过期响应'));
    });
    expect(within(resultRegion).queryByText('切换 route 后的过期响应')).not.toBeInTheDocument();
  }, 30_000);

  it('settles pending success and rejection after unmount without stale update warnings', async () => {
    const pendingSuccess = deferred<PolicySimulationBatchView>();
    const pendingRejection = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockReturnValueOnce(pendingSuccess.promise)
      .mockReturnValueOnce(pendingRejection.promise);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const successView = renderPolicyPage();
    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(successView.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    successView.unmount();
    await act(async () => {
      pendingSuccess.resolve(simulationResult('卸载后的过期结果'));
    });

    const rejectionView = renderPolicyPage();
    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    submitSimulationForm(rejectionView.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(2);
    });
    rejectionView.unmount();
    await act(async () => {
      pendingRejection.reject(new Error('stale rejection after unmount'));
    });

    const warningOutput = consoleError.mock.calls
      .flat()
      .map((argument) => String(argument))
      .join('\n');
    expect(warningOutput).not.toMatch(
      /state update on an unmounted component|can't perform a react state update|not wrapped in act/i,
    );
  });

  it('does not render a different policy kind as a fallback result', () => {
    render(
      <PolicySimulationPanel
        policyKind="LATE_GRACE"
        policyVersionId="late-version"
        processing={false}
        result={simulationResult('仅属于晚餐策略')}
        onSimulate={vi.fn()}
      />,
    );

    expect(screen.queryByText('仅属于晚餐策略')).not.toBeInTheDocument();
  });

  it('keeps AUDITOR-style read-only access free of simulation, impact and management controls', async () => {
    render(
      <MemoryRouter initialEntries={[`/rules/attendance-policy/${mealPolicyVersionId}`]}>
        <Routes>
          <Route
            path="/rules/attendance-policy/:versionId"
            element={<AttendancePolicyPage capabilities={['ATTENDANCE_SETUP:READ', 'AUDIT:READ']} />}
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();
    expect(screen.getAllByText('1'.repeat(64)).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览影响' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建策略绑定' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建策略草稿' })).not.toBeInTheDocument();
  });
});

function renderPage(element: React.ReactNode) {
  return render(<MemoryRouter>{element}</MemoryRouter>);
}

function renderPolicyPage(versionId = mealPolicyVersionId) {
  return render(
    <MemoryRouter initialEntries={[`/rules/attendance-policy/${versionId}`]}>
      <Routes>
        <Route
          path="/rules/attendance-policy"
          element={<AttendancePolicyPage capabilities={capabilities} />}
        />
        <Route
          path="/rules/attendance-policy/:versionId"
          element={<AttendancePolicyPage capabilities={capabilities} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function submitSimulationForm(container: HTMLElement) {
  fireEvent.submit(requiredElement(container, '.policy-simulation-workbench form'));
}

function changePolicyRoute(versionId: string) {
  fireEvent.change(screen.getByLabelText('已发布策略版本 ID'), {
    target: { value: versionId },
  });
  fireEvent.click(screen.getByRole('button', { name: '选择' }));
}

function localDate(value: Date): string {
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${value.getFullYear()}-${month}-${day}`;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function simulationResult(
  explanation: string,
  policyKind: AttendancePolicyKind = 'MEAL_DEDUCTION',
): PolicySimulationBatchView {
  return {
    configurationDigest: 'a'.repeat(64),
    results: [{
      policyKind,
      status: 'MATCHED',
      policyVersionId: 'policy-version',
      configurationDigest: 'a'.repeat(64),
      matched: true,
      consumesAllowance: false,
      predictedMonthlyConsumption: 0,
      usageProvenance: 'TEST',
      usageKnowledgeTime: '2026-07-27T12:00:00+08:00',
      explanation,
      writesFormalResult: false,
    }],
  };
}

function requiredElement(container: HTMLElement, selector: string): HTMLElement {
  const element = container.querySelector<HTMLElement>(selector);
  if (!element) throw new Error(`Missing test element: ${selector}`);
  return element;
}

function requiredItem<T>(items: readonly T[], index: number): T {
  const item = items[index];
  if (item === undefined) throw new Error(`Missing test item at index ${index}`);
  return item;
}

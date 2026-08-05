import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import * as employeeApi from '../employee/employeeApi';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoCalendars,
  demoPolicyCatalog,
  demoPolicyVersions,
  demoShifts,
  demoShiftVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import { AttendanceGroupsPage } from './AttendanceGroupsPage';
import { AttendancePolicyLifecyclePanel } from './AttendancePolicyLifecyclePanel';
import {
  policyLifecycleMinimumDate,
  utcDateAfter,
} from './attendancePolicyLifecycleDates';
import { loadAllAttendanceDirectoryItems } from './attendanceDirectory';
import { AttendancePolicyPage } from './AttendancePolicyPage';
import { CalendarDaysDialog } from './CalendarDialogs';
import { CalendarsPage } from './CalendarsPage';
import { PolicySimulationPanel } from './PolicySimulationPanel';
import { ShiftVersionDialog } from './ShiftDialogs';
import { ShiftsPage } from './ShiftsPage';
import type * as referenceData from '../referenceData';
import type {
  AttendancePolicyKind,
  AttendancePolicyVersionView,
  CalendarDayView,
  PolicySimulationBatchView,
  WorkCalendarView,
} from './attendanceSetupTypes';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

// These page tests exercise the form contract while the shared business selector
// has its own integration coverage. Keeping a native select here makes the
// directory boundaries deterministic and preserves the user-facing labels.
vi.mock('../referenceData', async (importOriginal) => {
  const actual = await importOriginal<typeof referenceData>();
  const { createElement, useEffect } = await import('react');
  return {
    ...actual,
    CompanySelect: ({
      value,
      onChange,
      id,
      disabled,
    }: {
      value?: string;
      onChange?: (value: string | undefined) => void;
      id?: string;
      disabled?: boolean;
    }) => {
      useEffect(() => {
        if (!value) onChange?.('9700000000000000001');
      }, [onChange, value]);
      return createElement(
        'select',
        {
          id,
          disabled,
          value: value ?? '',
          onChange: (event: { target: { value: string } }) => {
            onChange?.(event.target.value || undefined);
          },
        },
        createElement('option', { value: '' }, '请选择公司'),
        createElement(
          'option',
          { value: '9700000000000000001' },
          '江苏神州半导体科技股份有限公司',
        ),
        createElement(
          'option',
          { value: '9700000000000000002' },
          '第二测试公司',
        ),
      );
    },
    EmployeeSelect: ({
      value,
      onChange,
      id,
      disabled,
    }: {
      value?: string;
      onChange?: (value: string | undefined) => void;
      id?: string;
      disabled?: boolean;
    }) => createElement(
      'select',
      {
        id,
        disabled,
        value: value ?? '',
        onChange: (event: { target: { value: string } }) => {
          onChange?.(event.target.value || undefined);
        },
      },
      createElement('option', { value: '' }, '请选择员工'),
      createElement(
        'option',
        { value: 'd0000000-0000-0000-0000-000000000001' },
        '周明（SYN-0001）· 人力资源部',
      ),
    ),
  };
});

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

describe('attendance setup demo pages', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('renders effective attendance assignments with business labels only', async () => {
    renderPage(<AttendanceGroupsPage capabilities={capabilities} />);

    expect((await screen.findAllByText('苏州一号晶圆厂')).length).toBeGreaterThan(0);
    expect((await screen.findAllByText('历史员工（名称未找到）')).length)
      .toBeGreaterThan(0);
    expect(screen.queryByText(/已归档/)).not.toBeInTheDocument();
    expect(screen.getAllByText('暑期产线轮班安排').length).toBeGreaterThan(0);
    expect(screen.queryByText('9200000000000000001:2026-07')).not.toBeInTheDocument();
    expect(screen.queryByText('9200000000000000001')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '分配人员' })).toBeEnabled();
  });

  it('opens a controlled cross-group transfer form with a real target selector', async () => {
    renderPage(<AttendanceGroupsPage capabilities={capabilities} />);

    const sourceGroups = await screen.findAllByRole('button', {
      name: 'FAB-A-4D2N 一号厂 A 班四班两倒',
    });
    fireEvent.click(sourceGroups[0]!);
    const transferButtons = await screen.findAllByRole('button', {
      name: /调配考勤组/,
    }, { timeout: 5_000 });
    expect(screen.getAllByText('已调配（历史归属）').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('可调配').length).toBeGreaterThan(0);
    const historicalRows = screen.getAllByText('暑期产线轮班安排')
      .map((reasonCell) => reasonCell.closest('tr'))
      .filter((row): row is HTMLTableRowElement => row !== null);
    expect(historicalRows.length).toBeGreaterThan(0);
    historicalRows.forEach((historicalRow) => {
      expect(within(historicalRow).queryByRole('button', {
        name: /调配考勤组/,
      })).not.toBeInTheDocument();
    });
    expect(transferButtons[0]).toBeEnabled();
    fireEvent.click(transferButtons[0]!);

    expect(await screen.findByRole('dialog', { name: '调配考勤组' }))
      .toBeInTheDocument();
    expect(screen.getByLabelText('当前考勤组')).toBeDisabled();
    expect(screen.getByLabelText('调配生效日')).toHaveAttribute('type', 'date');
    const targetGroup = screen.getByLabelText('目标考勤组');
    fireEvent.mouseDown(targetGroup);
    expect((await screen.findAllByText('FAB-B-DAY · 一号厂 B 班白班')).length)
      .toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '确认调配' })).toBeEnabled();
  });

  it('loads every business-label page within the server page-size limit', async () => {
    const loadPage = vi.fn((page: number, size: number) => Promise.resolve({
      items: Array.from(
        { length: page === 6 ? 15 : 100 },
        (_, index) => page * size + index,
      ),
      total: 615,
    }));

    const items = await loadAllAttendanceDirectoryItems(loadPage);

    expect(items).toHaveLength(615);
    expect(loadPage).toHaveBeenCalledTimes(7);
    expect(loadPage.mock.calls.every(([, size]) => size === 100)).toBe(true);
  });

  it('loads and reloads the employee directory within the selected company', async () => {
    const employeeDirectory = vi.spyOn(employeeApi, 'getEmployees')
      .mockImplementation((page, size) => Promise.resolve({
        items: [],
        total: 0,
        page,
        size,
      }));

    renderPage(<AttendanceGroupsPage capabilities={capabilities} />);

    await waitFor(() => {
      expect(employeeDirectory).toHaveBeenCalledWith(0, 100, {
        companyId: '9700000000000000001',
        sort: 'employeeNumber',
      });
    });
    expect(employeeDirectory.mock.calls.every(([, , filters]) => Boolean(filters?.companyId)))
      .toBe(true);

    fireEvent.change(document.getElementById('attendance-groups-company')!, {
      target: { value: '9700000000000000002' },
    });

    await waitFor(() => {
      expect(employeeDirectory).toHaveBeenCalledWith(0, 100, {
        companyId: '9700000000000000002',
        sort: 'employeeNumber',
      });
    });
  });

  it('keeps calendar and shift names when the employee directory is forbidden', async () => {
    vi.spyOn(employeeApi, 'getEmployees').mockRejectedValue(new ApiRequestError(403, {
      code: 'FORBIDDEN',
      retryable: false,
    }));

    renderPage(<AttendanceGroupsPage capabilities={['ATTENDANCE_SETUP:READ']} />);

    expect((await screen.findAllByText('苏州一号厂 2026 工作日历（CN-SZ-2026）')).length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('一号厂 A 班白班（FAB-A-DAY）').length)
      .toBeGreaterThan(0);
    expect((await screen.findAllByText('员工名称暂不可用')).length)
      .toBeGreaterThan(0);
    expect(screen.queryByText(/已归档/)).not.toBeInTheDocument();
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
      expect(listSpy).toHaveBeenCalledWith(1, 20, '9700000000000000001');
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

  it('renders the selected annual calendar without technical boundary hints', async () => {
    renderPage(<CalendarsPage capabilities={capabilities} />);

    expect((await screen.findAllByText(/苏州一号厂 2026 工作日历/)).length).toBeGreaterThan(0);
    expect(await screen.findByRole('region', { name: '工作日历族' }))
      .toBeInTheDocument();
    expect(await screen.findByRole('region', { name: '日历版本' })).toBeInTheDocument();
    expect(screen.queryByText('跨年边界独立解析')).not.toBeInTheDocument();
    expect(screen.queryByText('[start_date, end_exclusive)')).not.toBeInTheDocument();
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

  it('keeps every setup page read-only while exposing business-readable history to AUDITOR', async () => {
    const readOnlyCapabilities = ['ATTENDANCE_SETUP:READ', 'AUDIT:READ'];
    const groupsView = renderPage(
      <AttendanceGroupsPage capabilities={readOnlyCapabilities} />,
    );

    expect((await screen.findAllByText('苏州一号晶圆厂')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('一号厂 A 班四班两倒').length).toBeGreaterThan(0);
    expect(screen.queryByText('a'.repeat(64))).not.toBeInTheDocument();
    expect(screen.queryByText('c'.repeat(64))).not.toBeInTheDocument();
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
    expect(screen.queryByRole('button', { name: '新建地点' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '新建考勤组' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '分配人员' }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /追加.*修订/ }))
      .not.toBeInTheDocument();
    groupsView.unmount();

    const shiftsView = renderPage(<ShiftsPage capabilities={readOnlyCapabilities} />);
    expect(await screen.findByText('冬春季白班', {}, { timeout: 10_000 }))
      .toBeInTheDocument();
    expect(screen.queryByText(
      '7f5dd34ba0f08fe28c835f45a8f4354c7f5dd34ba0f08fe28c835f45a8f4354c',
    )).not.toBeInTheDocument();
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
    expect((await screen.findAllByText(/苏州一号厂 2026 工作日历/)).length)
      .toBeGreaterThan(0);
    expect(screen.queryByText('d'.repeat(64))).not.toBeInTheDocument();
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
    const simulationSpy = vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy');
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    await submitSimulationForm(view.container);

    await waitFor(() => {
      expect(simulationSpy).toHaveBeenCalledTimes(1);
    });
    expect(screen.getByText('试算仅用于预览，不会修改正式考勤结果。'))
      .toBeInTheDocument();
    expect(screen.getByText('判定状态')).toBeInTheDocument();
    expect(screen.queryByText(/configurationDigest|writesFormalResult|策略版本 ID/))
      .not.toBeInTheDocument();
  });

  it('resolves a deep-linked non-meal policy from its exact server context', async () => {
    renderPolicyPage(lateGracePolicyVersionId);

    expect(await screen.findByRole('heading', { name: '迟到宽限' })).toBeInTheDocument();
    expect(await screen.findByText('江苏神州半导体科技股份有限公司'))
      .toBeInTheDocument();
    expect(screen.getByText('当前策略版本：V1')).toBeInTheDocument();
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();
    expect(screen.queryByText(lateGracePolicyVersionId)).not.toBeInTheDocument();
    expect(screen.queryByText('graceMinutes')).not.toBeInTheDocument();
    expect(screen.queryByText('晚餐窗口开始')).not.toBeInTheDocument();
  });

  it('puts lifecycle editing before bindings and explains how to revise a published version', async () => {
    const view = renderPolicyPage();

    const creatorHeading = await screen.findByRole('heading', { name: '创建策略草稿' });
    const creator = requiredElement(view.container, '.attendance-draft-creator');
    const versionButton = (await screen.findAllByRole('button', { name: '版本 1' }))[0]!;
    const catalogSection = requiredClosest(
      screen.getByRole('heading', { name: '用餐时段扣除' }).closest('section'),
    );
    const lifecycleSection = requiredClosest(
      screen.getByRole('heading', { name: '策略版本生命周期' }).closest('section'),
    );
    const bindingSection = requiredClosest(
      screen.getByRole('heading', { name: '策略绑定' }).closest('section'),
    );

    expect(creator).toContainElement(creatorHeading);
    expect(within(creator).getByText(
      '先创建草稿版本，再保存规则参数、校验并发布；发布后才能绑定考勤组。',
    )).toBeInTheDocument();
    expect(creator.compareDocumentPosition(versionButton)
      & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(catalogSection.compareDocumentPosition(lifecycleSection)
      & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(lifecycleSection.compareDocumentPosition(bindingSection)
      & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
    expect(within(lifecycleSection).getByText('已发布版本不可直接编辑'))
      .toBeInTheDocument();
    expect(within(lifecycleSection).getByText(
      '如需修改，请在上方基于 V1 创建策略草稿：选择未来生效日期，并填写至少 2 个字符的变更原因。进入草稿后才能修改参数、校验和发布。',
    )).toBeInTheDocument();
  });

  it('promotes the default lifecycle version into the route and enables version actions', async () => {
    renderPolicyBasePage();

    expect(await screen.findByText('当前策略版本：V1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '绑定规则到考勤组' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: '返回上一页' }));
    expect(await screen.findByText('规则入口来源页')).toBeInTheDocument();
    expect(screen.queryByText('当前策略版本：V1')).not.toBeInTheDocument();
  });

  it('blocks a policy draft whose effective date is earlier than tomorrow', async () => {
    const createSpy = vi.spyOn(attendanceSetupApi, 'createAttendancePolicyDraft');
    const view = renderPolicyPage();
    const creator = await waitFor(() => requiredElement(
      view.container,
      '.attendance-draft-creator',
    ));
    const dateInput = within(creator).getByLabelText('生效日');

    expect(dateInput).toHaveAttribute('min', utcDateAfter(1));
    fireEvent.change(dateInput, { target: { value: utcDateAfter(0) } });
    fireEvent.change(within(creator).getByLabelText('变更原因'), {
      target: { value: '验证未来生效约束' },
    });
    fireEvent.click(within(creator).getByRole('button', { name: '创建策略草稿' }));

    expect(await screen.findByText(
      '生命周期操作生效日必须不早于允许下限，并处于当前版本有效期内。',
    )).toBeInTheDocument();
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('blocks saving draft parameters with an effective date earlier than tomorrow', async () => {
    const published = requiredDemoItem(demoPolicyVersions);
    const draft: AttendancePolicyVersionView = {
      ...published,
      scopedVersionId: 'policy-draft-invalid-effective-from',
      versionNumber: 2,
      status: 'DRAFT',
      effectiveFrom: utcDateAfter(7),
      changeReason: '创建未来生效草稿',
      validation: { valid: false, issues: [], validatedAt: null },
      publishedAt: null,
      rowVersion: 0,
    };
    vi.spyOn(attendanceSetupApi, 'listAttendancePolicyVersions').mockResolvedValue({
      items: [draft],
      total: 1,
      page: 0,
      size: 20,
    });
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersion').mockResolvedValue(draft);
    const updateSpy = vi.spyOn(attendanceSetupApi, 'updateAttendancePolicyDraft');
    const view = render(
      <AttendancePolicyLifecyclePanel
        templateId={draft.templateId}
        companyId={draft.companyId}
        selectedVersionId={draft.scopedVersionId}
        fields={requiredDemoItem(demoPolicyCatalog).fields}
        canManage
        onSelectVersion={vi.fn()}
      />,
    );
    const controls = await waitFor(() => requiredElement(
      view.container,
      '.attendance-lifecycle-controls',
    ));
    const effectiveFromInput = within(controls).getByLabelText('生效日');

    expect(effectiveFromInput).toHaveAttribute('min', utcDateAfter(1));
    fireEvent.change(effectiveFromInput, { target: { value: utcDateAfter(0) } });
    fireEvent.change(within(controls).getByLabelText('变更原因'), {
      target: { value: '验证草稿保存日期门禁' },
    });
    fireEvent.click(within(controls).getByRole('button', { name: '保存草稿参数' }));

    expect(await screen.findByText(
      '生命周期操作生效日必须不早于允许下限，并处于当前版本有效期内。',
    )).toBeInTheDocument();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('selects the successor draft returned after saving parameters', async () => {
    const published = requiredDemoItem(demoPolicyVersions);
    const draft: AttendancePolicyVersionView = {
      ...published,
      scopedVersionId: 'policy-draft-v2',
      versionNumber: 2,
      status: 'DRAFT',
      effectiveFrom: '2026-09-01',
      changeReason: '创建未来生效草稿',
      validation: { valid: false, issues: [], validatedAt: null },
      publishedAt: null,
      rowVersion: 0,
    };
    const successor: AttendancePolicyVersionView = {
      ...draft,
      scopedVersionId: 'policy-draft-v3',
      versionNumber: 3,
      changeReason: '调整晚餐扣除分钟',
    };
    vi.spyOn(attendanceSetupApi, 'listAttendancePolicyVersions').mockResolvedValue({
      items: [draft],
      total: 1,
      page: 0,
      size: 20,
    });
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersion').mockResolvedValue(draft);
    const updateSpy = vi.spyOn(attendanceSetupApi, 'updateAttendancePolicyDraft')
      .mockResolvedValue(successor);
    const onSelectVersion = vi.fn();

    const view = render(
      <AttendancePolicyLifecyclePanel
        templateId={draft.templateId}
        companyId={draft.companyId}
        selectedVersionId={draft.scopedVersionId}
        fields={requiredDemoItem(demoPolicyCatalog).fields}
        canManage
        onSelectVersion={onSelectVersion}
      />,
    );

    const controls = await waitFor(() => requiredElement(
      view.container,
      '.attendance-lifecycle-controls',
    ));
    expect(within(requiredElement(
      view.container,
      '.attendance-draft-creator',
    )).getByLabelText('生效日')).toHaveAttribute(
      'min',
      policyLifecycleMinimumDate(draft.effectiveFrom),
    );
    fireEvent.change(within(controls).getByLabelText('扣除分钟'), {
      target: { value: '45' },
    });
    fireEvent.change(within(controls).getByLabelText('变更原因'), {
      target: { value: '调整晚餐扣除分钟' },
    });
    fireEvent.click(within(controls).getByRole('button', { name: '保存草稿参数' }));

    await waitFor(() => {
      expect(updateSpy).toHaveBeenCalledWith(draft, expect.objectContaining({
        effectiveFrom: '2026-09-01',
        reason: '调整晚餐扣除分钟',
        parameters: expect.arrayContaining([
          { key: 'deductionMinutes', value: 45 },
        ]),
      }));
      expect(onSelectVersion).toHaveBeenCalledWith(
        successor.scopedVersionId,
        { replace: true },
      );
    });
  });

  it('fails a missing direct policy-version route closed', async () => {
    const view = renderPolicyPage('missing-policy-version');

    await waitFor(() => {
      expect(view.container.querySelector('[data-state="404"]')).toBeInTheDocument();
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览影响' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '绑定规则到考勤组' })).toBeDisabled();
  });

  it('submits a local-current-day simulation using a selected employee and no default punches', async () => {
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
    const correctionAsOf = (screen.getByLabelText('数据截至时间') as HTMLInputElement).value;

    expect(screen.getByLabelText('数据截至时间')).toHaveAttribute('type', 'datetime-local');
    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(onSimulate).toHaveBeenCalledWith({
        employeeId: 'd0000000-0000-0000-0000-000000000001',
        businessDate,
        correctionAsOf: expect.stringMatching(
          /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/,
        ),
        punches: [],
      });
    });
    const after = new Date();

    expect([localDate(beforeRender), localDate(after)]).toContain(businessDate);
    expect(correctionAsOf).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{3})?)?$/,
    );
    const submitted = onSimulate.mock.calls[0]?.[0] as { correctionAsOf: string };
    expect(Date.parse(submitted.correctionAsOf)).toBeGreaterThanOrEqual(
      beforeRender.getTime() - 1_000,
    );
    expect(Date.parse(submitted.correctionAsOf)).toBeLessThanOrEqual(after.getTime());
  });

  it('keeps simulation guidance and submission controls in a dedicated action group', () => {
    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="policy-version"
        processing={false}
        onSimulate={vi.fn()}
      />,
    );

    const note = screen.getByText('试算仅用于预览，不会修改正式考勤结果。');
    const actions = requiredElement(view.container, '.policy-simulation-actions');
    const punchList = requiredElement(view.container, '.simulation-punch-list');

    expect(note).toHaveClass('policy-simulation-note');
    expect(note).not.toHaveClass('form-help');
    expect(actions).toContainElement(note);
    expect(within(actions).getByRole('button', { name: '运行试算' })).toBeInTheDocument();
    expect(punchList).not.toContainElement(note);
    expect(within(punchList).getByRole('button', { name: '添加打卡' })).toBeInTheDocument();
  });

  it('requires the user-friendly data cutoff field without exposing RFC3339 rules', async () => {
    const onSimulate = vi.fn();
    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="policy-version"
        processing={false}
        onSimulate={onSimulate}
      />,
    );
    const correctionInput = screen.getByLabelText('数据截至时间');

    fireEvent.change(correctionInput, { target: { value: '' } });
    await submitSimulationForm(view.container);
    expect(await screen.findByText('请选择数据截至时间')).toBeInTheDocument();
    expect(screen.queryByText(/RFC3339|时区偏移/)).not.toBeInTheDocument();
    expect(onSimulate).toHaveBeenCalledTimes(0);
  });

  it('converts a local data cutoff selection into the offset-bearing API payload', async () => {
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
    const correctionInput = screen.getByLabelText('数据截至时间');

    fireEvent.change(correctionInput, { target: { value: '2026-07-27T12:00:00' } });
    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(onSimulate).toHaveBeenCalledTimes(1);
    });
    expect(onSimulate).toHaveBeenCalledWith({
      employeeId: 'd0000000-0000-0000-0000-000000000001',
      businessDate,
      correctionAsOf: expect.stringMatching(
        /^2026-07-27T12:00:00[+-]\d{2}:\d{2}$/,
      ),
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
    await submitSimulationForm(view.container);
    expect(await screen.findByText('旧试算结果')).toBeInTheDocument();

    await submitSimulationForm(view.container);
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
    await submitSimulationForm(view.container);
    expect(await screen.findByText('旧试算结果')).toBeInTheDocument();

    await submitSimulationForm(view.container);

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
    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    await submitSimulationForm(view.container);
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
    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    await submitSimulationForm(view.container);
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
    await submitSimulationForm(view.container);
    expect(await screen.findByText('切换 kind 前的结果')).toBeInTheDocument();

    selectPolicyKind('迟到宽限');
    await waitFor(() => {
      expect(screen.queryByText('切换 kind 前的结果')).not.toBeInTheDocument();
    });
    selectPolicyKind('用餐时段扣除');
    expect(screen.queryByText('切换 kind 前的结果')).not.toBeInTheDocument();
  });

  it('clears an existing simulation error when the policy kind changes', async () => {
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy')
      .mockRejectedValueOnce(new Error('simulation failed'));
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    await submitSimulationForm(view.container);
    expect(await screen.findByText('服务暂时不可用，请稍后重试。')).toBeInTheDocument();

    selectPolicyKind('迟到宽限');
    await waitFor(() => {
      expect(screen.queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    });
  });

  it('clears pending loading and invalidates a visible-kind response when the policy kind changes', async () => {
    const pending = deferred<PolicySimulationBatchView>();
    vi.spyOn(attendanceSetupApi, 'simulateAttendancePolicy').mockReturnValueOnce(pending.promise);
    const view = renderPolicyPage();

    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    expect(
      within(requiredElement(view.container, '.policy-simulation-result')).getByLabelText('正在加载'),
    ).toBeInTheDocument();

    selectPolicyKind('迟到宽限');
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
    await submitSimulationForm(view.container);
    expect(await screen.findByText('旧 route 结果')).toBeInTheDocument();
    await changePolicyRoute('LATE_GRACE');
    await waitFor(() => {
      expect(screen.queryByText('旧 route 结果')).not.toBeInTheDocument();
    });
    expect(await screen.findByRole('heading', { name: '迟到宽限' })).toBeInTheDocument();

    await submitSimulationForm(view.container);
    expect(await screen.findByText('服务暂时不可用，请稍后重试。')).toBeInTheDocument();
    await changePolicyRoute('MONTHLY_LATE_EXEMPTION');
    await waitFor(() => {
      expect(screen.queryByText('服务暂时不可用，请稍后重试。')).not.toBeInTheDocument();
    });
    expect(await screen.findByRole('heading', { name: '每月迟到豁免' }))
      .toBeInTheDocument();

    await submitSimulationForm(view.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(3);
    });
    const resultRegion = requiredElement(view.container, '.policy-simulation-result');
    expect(within(resultRegion).getByLabelText('正在加载')).toBeInTheDocument();
    await changePolicyRoute('MEAL_DEDUCTION');
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
    await submitSimulationForm(successView.container);
    await waitFor(() => {
      expect(attendanceSetupApi.simulateAttendancePolicy).toHaveBeenCalledTimes(1);
    });
    successView.unmount();
    await act(async () => {
      pendingSuccess.resolve(simulationResult('卸载后的过期结果'));
    });

    const rejectionView = renderPolicyPage();
    expect(await screen.findByText('晚餐窗口开始')).toBeInTheDocument();
    await submitSimulationForm(rejectionView.container);
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

  it('renders the server total deduction and each matched meal window', () => {
    const result = simulationResult('周六午餐与晚餐均命中');
    result.results[0] = {
      ...requiredItem(result.results, 0),
      deductionMinutes: 105,
      matchedMealWindows: [{
        windowId: 'SATURDAY_LUNCH',
        mealType: 'LUNCH',
        source: 'SATURDAY_LUNCH',
        windowStart: '12:00',
        windowEnd: '13:00',
        deductionMinutes: 60,
        triggerMinutes: 0,
      }, {
        windowId: 'SATURDAY_DINNER',
        mealType: 'DINNER',
        source: 'SATURDAY_OVERRIDE',
        windowStart: '17:00',
        windowEnd: '19:00',
        deductionMinutes: 45,
        triggerMinutes: 60,
      }],
    };

    const view = render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="meal-version"
        processing={false}
        result={result}
        onSimulate={vi.fn()}
      />,
    );
    const resultRegion = requiredElement(
      view.container,
      '.policy-simulation-result',
    );

    expect(within(resultRegion).getByText('105')).toBeInTheDocument();
    expect(within(resultRegion).getByText(
      '午餐 12:00–13:00 · 60 分钟；晚餐 17:00–19:00 · 45 分钟',
    )).toBeInTheDocument();
    expect(within(resultRegion).queryByText(/SATURDAY_|\[12:00/))
      .not.toBeInTheDocument();
  });

  it('does not expose structured backend details in a simulation explanation', () => {
    const result = simulationResult(
      '{"policyVersionId":"550e8400-e29b-41d4-a716-446655440000","rowVersion":3}',
    );
    result.results[0] = {
      ...requiredItem(result.results, 0),
      usageKnowledgeTime: 'INTERNAL_INVALID_TIMESTAMP',
    };

    render(
      <PolicySimulationPanel
        policyKind="MEAL_DEDUCTION"
        policyVersionId="meal-version"
        processing={false}
        result={result}
        onSimulate={vi.fn()}
      />,
    );

    expect(screen.getByText('当前结果请结合规则配置确认。'))
      .toBeInTheDocument();
    expect(screen.queryByText(/policyVersionId|rowVersion|550e8400/))
      .not.toBeInTheDocument();
    expect(screen.queryByText('INTERNAL_INVALID_TIMESTAMP'))
      .not.toBeInTheDocument();
    expect(screen.getAllByText('—')).not.toHaveLength(0);
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
    expect(screen.queryByText('1'.repeat(64))).not.toBeInTheDocument();
    expect(screen.getByText('当前策略版本：V1')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览影响' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '绑定规则到考勤组' }))
      .not.toBeInTheDocument();
    expect(screen.getByText('当前账号为只读权限')).toBeInTheDocument();
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

function renderPolicyBasePage() {
  return render(
    <MemoryRouter
      initialEntries={['/rules/source', '/rules/attendance-policy']}
      initialIndex={1}
    >
      <HistoryBackButton />
      <Routes>
        <Route path="/rules/source" element={<p>规则入口来源页</p>} />
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

function HistoryBackButton() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(-1)}>
      返回上一页
    </button>
  );
}

async function submitSimulationForm(container: HTMLElement) {
  await selectSimulationEmployee(container);
  fireEvent.submit(requiredElement(container, '.policy-simulation-workbench form'));
}

async function selectSimulationEmployee(container: HTMLElement) {
  const input = within(container).getByLabelText('员工') as HTMLSelectElement;
  const employeeId = 'd0000000-0000-0000-0000-000000000001';
  if (input.value === employeeId) return;

  fireEvent.change(input, { target: { value: employeeId } });
  await waitFor(() => {
    expect(input).toHaveValue(employeeId);
  });
}

async function changePolicyRoute(policyKind: AttendancePolicyKind) {
  const labels: Record<AttendancePolicyKind, string> = {
    MEAL_DEDUCTION: '用餐时段扣除',
    LATE_GRACE: '迟到宽限',
    MONTHLY_LATE_EXEMPTION: '每月迟到豁免',
  };
  selectPolicyKind(labels[policyKind]);
  const company = await screen.findByLabelText('公司') as HTMLSelectElement;
  if (!company.value) {
    fireEvent.change(company, { target: { value: '9700000000000000001' } });
  }

  const versionTable = await screen.findByRole(
    'region',
    { name: '策略版本生命周期' },
  );
  fireEvent.click(within(versionTable).getByRole('button', { name: '版本 1' }));
  await screen.findByText('当前策略版本：V1');
}

function selectPolicyKind(label: string) {
  const control = screen.getByRole('radiogroup', { name: 'segmented control' });
  fireEvent.click(within(control).getByTitle(label));
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
      matchedMealWindows: [],
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

function requiredClosest(value: Element | null): HTMLElement {
  if (!(value instanceof HTMLElement)) throw new Error('Missing closest test element');
  return value;
}

function requiredItem<T>(items: readonly T[], index: number): T {
  const item = items[index];
  if (item === undefined) throw new Error(`Missing test item at index ${index}`);
  return item;
}

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoAssignments,
  demoCalendarDays,
  demoCalendars,
  demoGroups,
  demoLocations,
  demoShifts,
  demoShiftVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import { AttendanceGroupsPage } from './AttendanceGroupsPage';
import { CalendarsPage } from './CalendarsPage';
import { ShiftsPage } from './ShiftsPage';
import type {
  AssignmentView,
  AttendanceGroupView,
  CalendarDayView,
  LocationView,
  ShiftTemplateView,
  ShiftVersionView,
  WorkCalendarView,
} from './attendanceSetupTypes';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

describe('attendance setup parent-child pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('keeps the selected shift while browsing templates and resets versions for a new shift', async () => {
    const shiftA = shift('shift-a', 'SHIFT-A', '班次 A');
    const shiftB = shift('shift-b', 'SHIFT-B', '班次 B');
    const listShifts = vi.spyOn(attendanceSetupApi, 'listShifts')
      .mockImplementation((page = 0, size = 20) => Promise.resolve(
        paged(page === 0 ? [shiftA] : [shiftB], 21, page, size),
      ));
    const listVersions = vi.spyOn(attendanceSetupApi, 'listShiftVersions')
      .mockImplementation((shiftId, page = 0, size = 20) => Promise.resolve(
        paged(
          [shiftVersion(
            shiftId,
            `${shiftId}-version-${page + 1}`,
            page + 1,
          )],
          21,
          page,
          size,
        ),
      ));
    const view = renderPage(<ShiftsPage capabilities={[]} />);

    expect((await screen.findAllByRole('button', { name: 'SHIFT-A 班次 A' })).length)
      .toBeGreaterThan(0);
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(shiftA.shiftId, 0, 20);
    });

    goToNextPage(view.container, '班次版本分页');
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(shiftA.shiftId, 1, 20);
    });

    goToNextPage(view.container, '班次模板分页');
    await waitFor(() => {
      expect(listShifts).toHaveBeenCalledWith(1, 20);
    });
    expect((await screen.findAllByRole('button', { name: 'SHIFT-B 班次 B' })).length)
      .toBeGreaterThan(0);
    expect(listVersions.mock.calls.some(([shiftId]) => shiftId === shiftB.shiftId))
      .toBe(false);
    expect(lastCall(listVersions)).toEqual([shiftA.shiftId, 1, 20]);

    clickFirstButton('SHIFT-B 班次 B');
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(shiftB.shiftId, 0, 20);
    });
  });

  it('keeps selected locations and groups across parent pages and resets every child page on selection', async () => {
    const locationA = location('location-a', 'LOC-A', '地点 A');
    const locationB = location('location-b', 'LOC-B', '地点 B');
    const groupA = group('group-a', 'GROUP-A', '考勤组 A');
    const groupB = group('group-b', 'GROUP-B', '考勤组 B');
    vi.spyOn(attendanceSetupApi, 'listLocations')
      .mockImplementation((page = 0, size = 20) => Promise.resolve(
        paged(page === 0 ? [locationA] : [locationB], 21, page, size),
      ));
    vi.spyOn(attendanceSetupApi, 'listAttendanceGroups')
      .mockImplementation((_asOf, page = 0, size = 20) => Promise.resolve(
        paged(page === 0 ? [groupA] : [groupB], 21, page, size),
      ));
    const locationRevisions = vi.spyOn(attendanceSetupApi, 'listLocationRevisions')
      .mockImplementation((locationId, page = 0, size = 20) => Promise.resolve(
        paged([location(
          locationId,
          `REV-${locationId}`,
          `修订 ${locationId}`,
        )], 21, page, size),
      ));
    const groupRevisions = vi.spyOn(attendanceSetupApi, 'listAttendanceGroupRevisions')
      .mockImplementation((groupId, page = 0, size = 20) => Promise.resolve(
        paged([group(
          groupId,
          `REV-${groupId}`,
          `修订 ${groupId}`,
        )], 21, page, size),
      ));
    const assignments = vi.spyOn(attendanceSetupApi, 'listAssignments')
      .mockImplementation((groupId, _asOf, page = 0, size = 20) => Promise.resolve(
        paged([assignment(groupId, `${groupId}-assignment`)], 21, page, size),
      ));
    const view = renderPage(<AttendanceGroupsPage capabilities={[]} />);

    expect((await screen.findAllByRole('button', { name: 'LOC-A 地点 A' })).length)
      .toBeGreaterThan(0);
    expect((await screen.findAllByRole('button', { name: 'GROUP-A 考勤组 A' })).length)
      .toBeGreaterThan(0);
    await waitFor(() => {
      expect(locationRevisions).toHaveBeenCalledWith(locationA.locationId, 0, 20);
      expect(groupRevisions).toHaveBeenCalledWith(groupA.groupId, 0, 20);
      expect(assignments).toHaveBeenCalledWith(groupA.groupId, undefined, 0, 20);
    });

    goToNextPage(view.container, '地点修订历史分页');
    goToNextPage(view.container, '考勤组修订历史分页');
    goToNextPage(view.container, '人员归属分页');
    await waitFor(() => {
      expect(locationRevisions).toHaveBeenCalledWith(locationA.locationId, 1, 20);
      expect(groupRevisions).toHaveBeenCalledWith(groupA.groupId, 1, 20);
      expect(assignments).toHaveBeenCalledWith(groupA.groupId, undefined, 1, 20);
    });

    goToNextPage(view.container, '考勤地点分页');
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'LOC-B 地点 B' }).length)
        .toBeGreaterThan(0);
    });
    goToNextPage(view.container, '考勤组分页');
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'GROUP-B 考勤组 B' }).length)
        .toBeGreaterThan(0);
    });
    expect(locationRevisions.mock.calls.some(([id]) => id === locationB.locationId))
      .toBe(false);
    expect(groupRevisions.mock.calls.some(([id]) => id === groupB.groupId))
      .toBe(false);
    expect(assignments.mock.calls.some(([id]) => id === groupB.groupId)).toBe(false);

    clickFirstButton('LOC-B 地点 B');
    clickFirstButton('GROUP-B 考勤组 B');
    await waitFor(() => {
      expect(locationRevisions).toHaveBeenCalledWith(locationB.locationId, 0, 20);
      expect(groupRevisions).toHaveBeenCalledWith(groupB.groupId, 0, 20);
      expect(assignments).toHaveBeenCalledWith(groupB.groupId, undefined, 0, 20);
    });
  }, 30_000);

  it('keeps calendar family and version context stable until the user selects a new parent', async () => {
    const currentYear = new Date().getFullYear();
    const familyA = calendar(
      'calendar-a',
      'calendar-a-v1',
      'CAL-A',
      '日历 A',
      1,
      currentYear,
    );
    const familyB = calendar(
      'calendar-b',
      'calendar-b-v1',
      'CAL-B',
      '日历 B',
      1,
      currentYear,
    );
    const versionA1 = { ...familyA };
    const versionA21 = calendar(
      familyA.calendarId,
      'calendar-a-v21',
      familyA.code,
      familyA.name,
      21,
      currentYear,
    );
    const versionB1 = { ...familyB };
    const listCalendars = vi.spyOn(attendanceSetupApi, 'listCalendars')
      .mockImplementation((_year, page = 0, size = 20) => Promise.resolve(
        paged(page === 0 ? [familyA] : [familyB], 21, page, size),
      ));
    const listVersions = vi.spyOn(attendanceSetupApi, 'listCalendarVersions')
      .mockImplementation((calendarId, page = 0, size = 20) => {
        if (calendarId === familyB.calendarId) {
          return Promise.resolve(paged([versionB1], 1, page, size));
        }
        return Promise.resolve(paged(
          page === 0 ? [versionA1] : [versionA21],
          21,
          page,
          size,
        ));
      });
    const listDays = vi.spyOn(attendanceSetupApi, 'listCalendarVersionDays')
      .mockImplementation((
        calendarId,
        versionId,
        _from,
        _to,
        page = 0,
        size = 100,
      ) => Promise.resolve(paged(
        [calendarDay(calendarId, versionId, `${versionId}-day-${page}`)],
        201,
        page,
        size,
      )));
    const view = renderPage(<CalendarsPage capabilities={[]} />);

    expect((await screen.findAllByRole('button', { name: 'CAL-A 日历 A' })).length)
      .toBeGreaterThan(0);
    await waitFor(() => {
      expect(listDays).toHaveBeenCalledWith(
        familyA.calendarId,
        versionA1.calendarVersionId,
        expect.any(String),
        expect.any(String),
        0,
        100,
      );
    });

    goToNextPage(view.container, '日历日期分页');
    await waitFor(() => {
      expect(lastCall(listDays).slice(0, 2)).toEqual([
        familyA.calendarId,
        versionA1.calendarVersionId,
      ]);
      expect(lastCall(listDays).slice(4)).toEqual([1, 100]);
    });

    goToNextPage(view.container, '日历版本分页');
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(familyA.calendarId, 1, 20);
    });
    expect((await screen.findAllByRole('button', { name: '版本 21' })).length)
      .toBeGreaterThan(0);
    expect(lastCall(listDays).slice(0, 2)).toEqual([
      familyA.calendarId,
      versionA1.calendarVersionId,
    ]);
    expect(lastCall(listDays).slice(4)).toEqual([1, 100]);

    clickFirstButton('版本 21');
    await waitFor(() => {
      expect(listDays).toHaveBeenCalledWith(
        familyA.calendarId,
        versionA21.calendarVersionId,
        expect.any(String),
        expect.any(String),
        0,
        100,
      );
    });

    goToNextPage(view.container, '工作日历分页');
    await waitFor(() => {
      expect(listCalendars).toHaveBeenCalledWith(currentYear, 1, 20);
    });
    expect((await screen.findAllByRole('button', { name: 'CAL-B 日历 B' })).length)
      .toBeGreaterThan(0);
    expect(listVersions.mock.calls.some(([id]) => id === familyB.calendarId))
      .toBe(false);
    expect(lastCall(listDays).slice(0, 2)).toEqual([
      familyA.calendarId,
      versionA21.calendarVersionId,
    ]);

    clickFirstButton('CAL-B 日历 B');
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(familyB.calendarId, 0, 20);
      expect(listDays).toHaveBeenCalledWith(
        familyB.calendarId,
        versionB1.calendarVersionId,
        expect.any(String),
        expect.any(String),
        0,
        100,
      );
    });
  });

  it('clamps parent and child empty tail pages back to the last valid page', async () => {
    const shiftA = shift('shift-a', 'SHIFT-A', '班次 A');
    let shiftCollectionShrank = false;
    let versionCollectionShrank = false;
    const listShifts = vi.spyOn(attendanceSetupApi, 'listShifts')
      .mockImplementation((page = 0, size = 20) => {
        if (page === 1) {
          shiftCollectionShrank = true;
          return Promise.resolve(paged([], 1, page, size));
        }
        return Promise.resolve(paged(
          [shiftA],
          shiftCollectionShrank ? 1 : 21,
          page,
          size,
        ));
      });
    const listVersions = vi.spyOn(attendanceSetupApi, 'listShiftVersions')
      .mockImplementation((shiftId, page = 0, size = 20) => {
        if (page === 1) {
          versionCollectionShrank = true;
          return Promise.resolve(paged([], 1, page, size));
        }
        return Promise.resolve(paged(
          [shiftVersion(shiftId, `${shiftId}-version`, 1)],
          versionCollectionShrank ? 1 : 21,
          page,
          size,
        ));
      });
    const view = renderPage(<ShiftsPage capabilities={[]} />);

    expect((await screen.findAllByRole('button', { name: 'SHIFT-A 班次 A' })).length)
      .toBeGreaterThan(0);
    await screen.findByRole('navigation', { name: '班次版本分页' });
    goToNextPage(view.container, '班次版本分页');
    await waitFor(() => {
      expect(listVersions).toHaveBeenCalledWith(shiftA.shiftId, 1, 20);
      expect(lastCall(listVersions)).toEqual([shiftA.shiftId, 0, 20]);
    });

    goToNextPage(view.container, '班次模板分页');
    await waitFor(() => {
      expect(listShifts).toHaveBeenCalledWith(1, 20);
      expect(lastCall(listShifts)).toEqual([0, 20]);
    });
    expect((await screen.findAllByRole('button', { name: 'SHIFT-A 班次 A' })).length)
      .toBeGreaterThan(0);
  });

  it('uses the day after a future shift-version start as the deactivation minimum', async () => {
    const shiftA = shift('shift-a', 'SHIFT-A', '班次 A');
    const version = {
      ...shiftVersion(shiftA.shiftId, 'shift-a-version', 1),
      effectiveFrom: '2099-06-10',
      effectiveTo: null,
      status: 'PUBLISHED' as const,
    };
    vi.spyOn(attendanceSetupApi, 'listShifts')
      .mockResolvedValue(paged([shiftA], 1, 0, 20));
    vi.spyOn(attendanceSetupApi, 'listShiftVersions')
      .mockResolvedValue(paged([version], 1, 0, 20));
    const deactivate = vi.spyOn(attendanceSetupApi, 'changeShiftVersionStatus');
    const view = renderPage(
      <ShiftsPage capabilities={['ATTENDANCE_SETUP:MANAGE_SHIFT']} />,
    );
    const timeline = await screen.findByRole('list', { name: '规则生效时间轴' });

    fireEvent.click(within(timeline).getByRole('button', { name: '停用' }));
    const modal = requiredElement(view.baseElement, '.ant-modal');
    const boundary = within(modal).getByLabelText('未来停用生效边界');
    expect(boundary).toHaveAttribute('min', '2099-06-11');
    expect(boundary).toHaveValue('2099-06-11');

    fireEvent.change(boundary, { target: { value: '2099-06-10' } });
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '验证班次版本起始日边界' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: /停\s*用/ }));

    expect(await screen.findByText('请选择晚于今天的停用生效边界。'))
      .toBeInTheDocument();
    expect(deactivate).not.toHaveBeenCalled();
  });

  it('uses the day after a future calendar-version start as the deactivation minimum', async () => {
    const target = {
      ...calendar(
        'calendar-a',
        'calendar-a-v1',
        'CAL-A',
        '日历 A',
        1,
        new Date().getFullYear(),
      ),
      effectiveFrom: '2099-06-10',
      effectiveTo: '2100-01-01',
      status: 'PUBLISHED' as const,
    };
    vi.spyOn(attendanceSetupApi, 'listCalendars')
      .mockResolvedValue(paged([target], 1, 0, 20));
    vi.spyOn(attendanceSetupApi, 'listCalendarVersions')
      .mockResolvedValue(paged([target], 1, 0, 20));
    const deactivate = vi.spyOn(attendanceSetupApi, 'deactivateCalendarVersion');
    const view = renderPage(
      <CalendarsPage capabilities={['ATTENDANCE_SETUP:MANAGE_CALENDAR']} />,
    );

    const buttons = await screen.findAllByRole('button', { name: '停用 版本 1' });
    const button = buttons[0];
    if (!button) throw new Error('Missing calendar deactivation button');
    fireEvent.click(button);
    const modal = requiredElement(view.baseElement, '.ant-modal');
    const boundary = within(modal).getByLabelText('未来停用生效边界');
    expect(boundary).toHaveAttribute('min', '2099-06-11');
    expect(boundary).toHaveValue('2099-06-11');

    fireEvent.change(boundary, { target: { value: '2099-06-10' } });
    fireEvent.change(within(modal).getByLabelText('变更原因'), {
      target: { value: '验证日历版本起始日边界' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: /停\s*用/ }));

    expect(await screen.findByText('请选择晚于今天的停用生效边界。'))
      .toBeInTheDocument();
    expect(deactivate).not.toHaveBeenCalled();
  });
});

function renderPage(element: React.ReactNode) {
  return render(<MemoryRouter>{element}</MemoryRouter>);
}

function goToNextPage(container: HTMLElement, label: string): void {
  const pagination = requiredElement(
    container,
    `nav[aria-label="${label}"]`,
  );
  fireEvent.click(requiredElement(pagination, '.ant-pagination-next button'));
}

function clickFirstButton(name: string): void {
  const button = screen.getAllByRole('button', { name })[0];
  if (!button) throw new Error(`Missing test button: ${name}`);
  fireEvent.click(button);
}

function paged<T>(
  items: T[],
  total: number,
  page: number,
  size: number,
): { items: T[]; total: number; page: number; size: number } {
  return { items, total, page, size };
}

function shift(
  shiftId: string,
  code: string,
  name: string,
): ShiftTemplateView {
  return { ...requiredDemoItem(demoShifts), shiftId, code, name };
}

function shiftVersion(
  shiftId: string,
  shiftVersionId: string,
  versionNumber: number,
): ShiftVersionView {
  return {
    ...requiredDemoItem(demoShiftVersions),
    shiftId,
    shiftVersionId,
    versionNumber,
  };
}

function location(
  locationId: string,
  code: string,
  name: string,
): LocationView {
  return {
    ...requiredDemoItem(demoLocations),
    locationId,
    locationRevisionId: `${locationId}-revision`,
    code,
    name,
  };
}

function group(
  groupId: string,
  code: string,
  name: string,
): AttendanceGroupView {
  return {
    ...requiredDemoItem(demoGroups),
    groupId,
    groupRevisionId: `${groupId}-revision`,
    code,
    name,
  };
}

function assignment(groupId: string, assignmentId: string): AssignmentView {
  return {
    ...requiredDemoItem(demoAssignments),
    groupId,
    assignmentId,
  };
}

function calendar(
  calendarId: string,
  calendarVersionId: string,
  code: string,
  name: string,
  versionNumber: number,
  calendarYear: number,
): WorkCalendarView {
  return {
    ...requiredDemoItem(demoCalendars),
    calendarId,
    calendarVersionId,
    code,
    name,
    versionNumber,
    calendarYear,
    effectiveFrom: `${calendarYear}-01-01`,
    effectiveTo: `${calendarYear + 1}-01-01`,
  };
}

function calendarDay(
  calendarId: string,
  calendarVersionId: string,
  calendarDayId: string,
): CalendarDayView {
  return {
    ...requiredDemoItem(demoCalendarDays),
    calendarId,
    calendarVersionId,
    calendarDayId,
  };
}

function lastCall<T extends unknown[]>(
  mock: { mock: { calls: T[] } },
): T {
  const call = mock.mock.calls.at(-1);
  if (!call) throw new Error('Expected at least one mock call');
  return call;
}

function requiredElement(container: HTMLElement, selector: string): HTMLElement {
  const element = container.querySelector<HTMLElement>(selector);
  if (!element) throw new Error(`Missing test element: ${selector}`);
  return element;
}

import { afterEach, describe, expect, it, vi } from 'vitest';

import * as attendanceApi from './attendanceSetupApi';
import {
  createAttendanceGroup,
  listLocations,
  replaceCalendarDays,
  simulateAttendancePolicy,
} from './attendanceSetupApi';
import {
  demoAssignments,
  demoBindings,
  demoCalendars,
  demoGroups,
  demoLocations,
  demoPolicyVersions,
  demoShifts,
  demoShiftVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import type {
  AttendanceGroupInput,
  LocationInput,
  PolicyBindingInput,
  PolicySimulationInput,
  ShiftTemplateInput,
  ShiftVersionInput,
  WorkCalendarInput,
  WorkCalendarVersionInput,
} from './attendanceSetupTypes';
import type { WorkCalendarView } from './attendanceSetupTypes';

const runtimeMode = vi.hoisted(() => ({ demo: false }));

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => runtimeMode.demo,
}));

describe('attendance setup API boundary', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    runtimeMode.demo = false;
  });

  it('uses the runtime /api/v1 path while the OpenAPI path item remains prefix-free', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      items: [],
      total: 0,
      page: 0,
      size: 100,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await listLocations();

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/attendance-setup/locations?page=0&size=100',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('keeps every attendance directory inside the explicitly selected company', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse({
      items: [],
      total: 0,
      page: 2,
      size: 25,
    })));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.listLocations(2, 25, 'company/1');
    await attendanceApi.listAttendanceGroups('2026-08-01', 2, 25, 'company/1');
    await attendanceApi.listShifts(2, 25, 'company/1');
    await attendanceApi.listCalendars(2026, 2, 25, 'company/1');
    await attendanceApi.listPolicyBindings(
      'group/1',
      '2026-08-01',
      2,
      25,
      'company/1',
    );

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/attendance-setup/locations?companyId=company%2F1&page=2&size=25',
      '/api/v1/attendance-setup/groups?companyId=company%2F1&asOf=2026-08-01&page=2&size=25',
      '/api/v1/attendance-setup/shifts?companyId=company%2F1&page=2&size=25',
      '/api/v1/attendance-setup/calendars?companyId=company%2F1&year=2026&page=2&size=25',
      '/api/v1/attendance-setup/policy-bindings?companyId=company%2F1&groupId=group%2F1&asOf=2026-08-01&page=2&size=25',
    ]);
  });

  it('reads immutable calendar days through the selected version route', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      items: [],
      total: 0,
      page: 0,
      size: 100,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.listCalendarVersionDays(
      'calendar/1',
      'version/2',
      '2026-12-29',
      '2026-12-31',
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/attendance-setup/calendars/calendar%2F1/versions/version%2F2/days?from=2026-12-29&to=2026-12-31&page=0&size=100',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('reads real location and group revision history through paginated family routes', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse({
      items: [],
      total: 0,
      page: 0,
      size: 100,
    })));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.listLocationRevisions('location/1');
    await attendanceApi.listAttendanceGroupRevisions('group/1');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/attendance-setup/locations/location%2F1/revisions?page=0&size=100',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/attendance-setup/groups/group%2F1/revisions?page=0&size=100',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('requests policy-binding history through the paginated contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      items: [],
      total: 0,
      page: 2,
      size: 25,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.listPolicyBindings('group/1', '2026-08-01', 2, 25);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/attendance-setup/policy-bindings?groupId=group%2F1&asOf=2026-08-01&page=2&size=25',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('resolves a direct policy-version route without guessing from binding page one', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      scopedVersionId: 'version/2',
      templateId: 'template/3',
      companyId: 'company/4',
      policyKind: 'LATE_GRACE',
    }));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.getAttendancePolicyVersionContext('version/2');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/attendance-setup/policy-lifecycle/versions/version%2F2/context',
      expect.objectContaining({ credentials: 'same-origin' }),
    );
  });

  it('sends an explicit future business boundary for version and calendar deactivation', async () => {
    const shift = requiredDemoItem(demoShifts);
    const shiftVersion = requiredDemoItem(demoShiftVersions);
    const calendar = requiredDemoItem(demoCalendars);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(shiftVersion))
      .mockResolvedValueOnce(jsonResponse(calendar))
      .mockResolvedValueOnce(jsonResponse(calendar));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.changeShiftVersionStatus(
      shift.shiftId,
      shiftVersion,
      'INACTIVE',
      '2026-09-01',
      '未来停用班次版本',
    );
    await attendanceApi.deactivateCalendarVersion(
      calendar,
      '2026-09-01',
      '未来停用日历版本',
    );
    await attendanceApi.changeCalendarStatus(
      calendar,
      'INACTIVE',
      '2026-09-01',
      '未来停用日历',
    );

    expect(requestBody(fetchMock, 1)).toEqual({
      status: 'INACTIVE',
      businessEffectiveFrom: '2026-09-01',
      reason: '未来停用班次版本',
    });
    expect(requestBody(fetchMock, 2)).toEqual({
      businessEffectiveFrom: '2026-09-01',
      reason: '未来停用日历版本',
    });
    expect(requestBody(fetchMock, 3)).toEqual({
      status: 'INACTIVE',
      businessEffectiveFrom: '2026-09-01',
      reason: '未来停用日历',
    });
  });

  it('keeps demo deactivation boundaries consistent with authoritative history protection', async () => {
    runtimeMode.demo = true;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const shift = requiredDemoItem(demoShifts);
    const shiftVersion = requiredDemoItem(demoShiftVersions, 1);
    const calendar = requiredDemoItem(demoCalendars);

    await expect(attendanceApi.changeShiftVersionStatus(
      shift.shiftId,
      shiftVersion,
      'INACTIVE',
      shiftVersion.effectiveFrom,
      '拒绝历史班次边界',
    )).rejects.toMatchObject({
      status: 409,
      code: 'SHIFT_VERSION_HISTORY_PROTECTED',
      retryable: false,
    });
    await expect(attendanceApi.deactivateCalendarVersion(
      calendar,
      calendar.effectiveFrom,
      '拒绝历史日历版本边界',
    )).rejects.toMatchObject({
      status: 409,
      code: 'WORK_CALENDAR_HISTORY_PROTECTED',
      retryable: false,
    });
    await expect(attendanceApi.changeCalendarStatus(
      calendar,
      'INACTIVE',
      '2099-01-01',
      '拒绝超出日历期间的边界',
    )).rejects.toMatchObject({
      status: 409,
      code: 'WORK_CALENDAR_DEACTIVATION_OUT_OF_RANGE',
      retryable: false,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('uses bounded pagination on every remaining W3 collection route', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse({
      items: [],
      total: 0,
      page: 2,
      size: 25,
    })));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.listAttendanceGroups('2026-08-01', 2, 25);
    await attendanceApi.listAssignments('group/1', '2026-08-01', 2, 25);
    await attendanceApi.listShifts(2, 25);
    await attendanceApi.listShiftVersions('shift/1', 2, 25);
    await attendanceApi.listCalendars(2026, 2, 25);
    await attendanceApi.listCalendarVersions('calendar/1', 2, 25);
    await attendanceApi.listCalendarDays(
      'calendar/1',
      '2026-12-29',
      '2026-12-31',
      2,
      25,
    );

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/attendance-setup/groups?page=2&size=25&asOf=2026-08-01',
      '/api/v1/attendance-setup/groups/group%2F1/assignments?page=2&size=25&asOf=2026-08-01',
      '/api/v1/attendance-setup/shifts?page=2&size=25',
      '/api/v1/attendance-setup/shifts/shift%2F1/versions?page=2&size=25',
      '/api/v1/attendance-setup/calendars?page=2&size=25&year=2026',
      '/api/v1/attendance-setup/calendars/calendar%2F1/versions?page=2&size=25',
      '/api/v1/attendance-setup/calendars/calendar%2F1/days?from=2026-12-29&to=2026-12-31&page=2&size=25',
    ]);
  });

  it('patches calendar days through the immutable version route with concurrency headers', async () => {
    const calendar = requiredDemoItem(demoCalendars);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(calendar));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.patchCalendarVersionDays(
      calendar,
      [{ businessDate: '2026-12-31', dayType: 'WORKDAY' }],
      '追加日历日期修订',
    );

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(path).toBe(
      `/api/v1/attendance-setup/calendars/${calendar.calendarId}/versions/${calendar.calendarVersionId}/days`,
    );
    expect(init.method).toBe('PATCH');
    expect(headers.get('If-Match')).toBe(`"${calendar.rowVersion}"`);
    expect(headers.get('Idempotency-Key'))
      .toMatch(/^attendance-calendar-version-days-patch:/);
    expect(headers.get('X-Change-Reason'))
      .toBe(`UTF-8''${encodeURIComponent('追加日历日期修订')}`);
  });

  it('sends idempotency and strong optimistic-concurrency headers to real routes', async () => {
    const created = {
      groupId: 'group-1',
      companyId: 'company-1',
      code: 'FAB-A',
      groupRevisionId: 'group-revision-1',
      revisionNumber: 1,
      name: '晶圆厂 A 班',
      locationId: 'location-1',
      locationRevisionId: 'location-revision-1',
      calendarId: 'calendar-1',
      shiftTemplateId: 'shift-template-1',
      status: 'ACTIVE',
      effectiveFrom: '2026-08-01',
      effectiveTo: null,
      snapshotDigest: 'a'.repeat(64),
      rowVersion: 1,
      changeReason: '建立考勤组',
      updatedAt: '2026-07-26T08:00:00Z',
    };
    const calendar: WorkCalendarView = {
      calendarId: 'calendar-1',
      companyId: 'company-1',
      locationId: 'location-1',
      code: 'CN-SZ-2026',
      calendarVersionId: 'calendar-version-1',
      versionNumber: 1,
      name: '苏州 2026 工作日历',
      calendarYear: 2026,
      timeZone: 'Asia/Shanghai',
      status: 'DRAFT',
      effectiveFrom: '2026-01-01',
      effectiveTo: '2027-01-01',
      snapshotDigest: 'b'.repeat(64),
      rowVersion: 8,
      changeReason: '年末复核',
      updatedAt: '2026-07-26T08:00:00Z',
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(created, 201))
      .mockResolvedValueOnce(jsonResponse({ ...calendar, rowVersion: 9 }));
    vi.stubGlobal('fetch', fetchMock);

    await createAttendanceGroup({
      companyId: created.companyId,
      code: created.code,
      name: created.name,
      locationId: created.locationId,
      calendarId: created.calendarId,
      shiftTemplateId: created.shiftTemplateId,
      effectiveFrom: created.effectiveFrom,
      effectiveTo: null,
      reason: created.changeReason,
    });
    await replaceCalendarDays(calendar, [{
      businessDate: '2026-12-31',
      dayType: 'WORKDAY',
      shiftVersionOverrideId: 'shift-version-1',
    }], '复核年末生产日');

    const createHeaders = new Headers((fetchMock.mock.calls[0]?.[1] as RequestInit).headers);
    expect(createHeaders.get('Idempotency-Key')).toMatch(/^attendance-group-create:/);
    expect(createHeaders.get('X-Change-Reason'))
      .toBe(`UTF-8''${encodeURIComponent('建立考勤组')}`);
    expect(JSON.parse((fetchMock.mock.calls[0]?.[1] as RequestInit).body as string))
      .toEqual({
        companyId: 'company-1',
        code: 'FAB-A',
        name: '晶圆厂 A 班',
        locationId: 'location-1',
        calendarId: 'calendar-1',
        shiftTemplateId: 'shift-template-1',
        effectiveFrom: '2026-08-01',
        effectiveTo: null,
        reason: '建立考勤组',
      });
    const updateHeaders = new Headers((fetchMock.mock.calls[1]?.[1] as RequestInit).headers);
    expect(updateHeaders.get('If-Match')).toBe('"8"');
    expect(updateHeaders.get('X-Change-Reason'))
      .toBe(`UTF-8''${encodeURIComponent('复核年末生产日')}`);
    expect(JSON.parse((fetchMock.mock.calls[1]?.[1] as RequestInit).body as string))
      .toEqual([{
        businessDate: '2026-12-31',
        dayType: 'WORKDAY',
        shiftVersionOverrideId: 'shift-version-1',
      }]);
  });

  it('transfers an assignment with target group, business date, and controlled headers', async () => {
    const assignment = requiredDemoItem(demoAssignments);
    const targetGroup = requiredDemoItem(demoGroups, 1);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      ...assignment,
      assignmentId: 'assignment-successor',
      groupId: targetGroup.groupId,
      effectiveFrom: '2026-08-20',
      rowVersion: 0,
      changeReason: '调配到白班',
    }));
    vi.stubGlobal('fetch', fetchMock);

    await attendanceApi.transferAssignment(
      assignment.groupId,
      assignment.assignmentId,
      assignment.rowVersion,
      {
        targetGroupId: targetGroup.groupId,
        effectiveFrom: '2026-08-20',
        reason: '调配到白班',
      },
    );

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(path).toBe(
      `/api/v1/attendance-setup/groups/${assignment.groupId}`
        + `/assignments/${assignment.assignmentId}/transfer`,
    );
    expect(init.method).toBe('POST');
    expect(headers.get('If-Match')).toBe(`"${assignment.rowVersion}"`);
    expect(headers.get('Idempotency-Key'))
      .toMatch(/^attendance-assignment-transfer:/);
    expect(headers.get('X-Change-Reason'))
      .toBe(`UTF-8''${encodeURIComponent('调配到白班')}`);
    expect(JSON.parse(String(init.body))).toEqual({
      targetGroupId: targetGroup.groupId,
      effectiveFrom: '2026-08-20',
      reason: '调配到白班',
    });
  });

  it('short-circuits before every business fetch in demo mode', async () => {
    runtimeMode.demo = true;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const locations = await listLocations();
    const simulation = await simulateAttendancePolicy({
      employeeId: '9200000000000000001',
      businessDate: '2026-08-01',
      correctionAsOf: '2026-08-01T12:00:00+08:00',
      punches: [{
        direction: 'ENTRY',
        instant: '2026-08-01T08:15:00+08:00',
        association: 'SCHEDULED_WORK',
      }],
    });

    expect(locations.items[0]?.name).toBe('苏州一号晶圆厂');
    expect(simulation.results[2]).toMatchObject({
      matched: true,
      consumesAllowance: true,
      writesFormalResult: false,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('keeps a stable binding family while demo replacement appends a revision', async () => {
    runtimeMode.demo = true;
    const binding = requiredDemoItem(demoBindings);

    const successor = await attendanceApi.updatePolicyBinding(binding, {
      policyKind: binding.policyKind,
      policyVersionId: binding.policyVersionId,
      groupId: binding.groupId,
      groupRevisionId: binding.groupRevisionId,
      effectiveFrom: binding.effectiveFrom,
      effectiveTo: binding.effectiveTo,
      reason: '演示绑定后继修订',
      impactToken: 'demo-impact-token',
    });

    expect(successor.bindingId).toBe(binding.bindingId);
    expect(successor.bindingRevisionId).not.toBe(binding.bindingRevisionId);
    expect(successor.revisionNumber).toBe(binding.revisionNumber + 1);
  });

  it('keeps every exported attendance API data-driven and network-free in demo mode', async () => {
    runtimeMode.demo = true;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const location = requiredDemoItem(demoLocations);
    const group = requiredDemoItem(demoGroups);
    const assignment = requiredDemoItem(demoAssignments);
    const shift = requiredDemoItem(demoShifts);
    const shiftVersion = requiredDemoItem(demoShiftVersions, 1);
    const calendar = requiredDemoItem(demoCalendars);
    const binding = requiredDemoItem(demoBindings);
    const policyVersion = requiredDemoItem(demoPolicyVersions);
    const locationInput: LocationInput = {
      companyId: location.companyId,
      code: location.code,
      name: location.name,
      timeZone: location.timeZone,
      effectiveFrom: location.effectiveFrom,
      effectiveTo: location.effectiveTo,
      reason: '演示地点变更',
    };
    const groupInput: AttendanceGroupInput = {
      companyId: group.companyId,
      code: group.code,
      name: group.name,
      locationId: group.locationId,
      calendarId: group.calendarId,
      shiftTemplateId: group.shiftTemplateId,
      effectiveFrom: group.effectiveFrom,
      effectiveTo: group.effectiveTo,
      reason: '演示考勤组变更',
    };
    const shiftInput: ShiftTemplateInput = {
      companyId: shift.companyId,
      locationId: shift.locationId,
      code: shift.code,
      name: shift.name,
      reason: '演示班次变更',
    };
    const shiftVersionInput: ShiftVersionInput = {
      effectiveFrom: shiftVersion.effectiveFrom,
      effectiveTo: shiftVersion.effectiveTo,
      segments: shiftVersion.segments,
      reason: '演示班次版本变更',
    };
    const calendarInput: WorkCalendarInput = {
      companyId: calendar.companyId,
      locationId: calendar.locationId,
      code: calendar.code,
      name: calendar.name,
      calendarYear: calendar.calendarYear,
      timeZone: calendar.timeZone,
      effectiveFrom: calendar.effectiveFrom,
      effectiveTo: calendar.effectiveTo,
      reason: '演示日历变更',
    };
    const calendarVersionInput: WorkCalendarVersionInput = {
      name: calendar.name,
      calendarYear: calendar.calendarYear,
      timeZone: calendar.timeZone,
      effectiveFrom: calendar.effectiveFrom,
      effectiveTo: calendar.effectiveTo,
      reason: '演示日历版本变更',
    };
    const bindingInput: PolicyBindingInput = {
      policyKind: binding.policyKind,
      policyVersionId: binding.policyVersionId,
      groupId: binding.groupId,
      groupRevisionId: binding.groupRevisionId,
      effectiveFrom: binding.effectiveFrom,
      effectiveTo: binding.effectiveTo,
      reason: '演示策略绑定变更',
      impactToken: 'demo-impact-token',
    };
    const simulationInput = lateGraceSimulation();
    const calls = {
      listLocations: () => attendanceApi.listLocations(),
      listLocationRevisions: () => attendanceApi.listLocationRevisions(location.locationId),
      updateLocation: () => attendanceApi.updateLocation(location.locationId, location.rowVersion, locationInput),
      changeLocationStatus: () => attendanceApi.changeLocationStatus(location, 'deactivate', '演示停用地点'),
      listAttendanceGroups: () => attendanceApi.listAttendanceGroups('2026-08-01'),
      listAttendanceGroupRevisions: () => attendanceApi.listAttendanceGroupRevisions(
        group.groupId,
      ),
      createAttendanceGroup: () => attendanceApi.createAttendanceGroup(groupInput),
      updateAttendanceGroup: () => attendanceApi.updateAttendanceGroup(group.groupId, group.rowVersion, groupInput),
      changeAttendanceGroupStatus: () => attendanceApi.changeAttendanceGroupStatus(group, 'deactivate', '演示停用考勤组'),
      listAssignments: () => attendanceApi.listAssignments(group.groupId, '2026-08-01'),
      createAssignment: () => attendanceApi.createAssignment(group.groupId, {
        employeeId: assignment.employeeId,
        effectiveFrom: assignment.effectiveFrom,
        effectiveTo: assignment.effectiveTo,
        reason: '演示人员分配',
      }),
      updateAssignment: () => attendanceApi.updateAssignment(
        group.groupId,
        assignment.assignmentId,
        assignment.rowVersion,
        {
          employeeId: assignment.employeeId,
          effectiveFrom: assignment.effectiveFrom,
          effectiveTo: assignment.effectiveTo,
          reason: '演示人员分配更新',
        },
      ),
      transferAssignment: () => attendanceApi.transferAssignment(
        group.groupId,
        assignment.assignmentId,
        assignment.rowVersion,
        {
          targetGroupId: requiredDemoItem(demoGroups, 1).groupId,
          effectiveFrom: '2026-08-20',
          reason: '演示人员跨组调配',
        },
      ),
      listShifts: () => attendanceApi.listShifts(),
      createShift: () => attendanceApi.createShift(shiftInput),
      updateShift: () => attendanceApi.updateShift(shift, shiftInput),
      changeShiftStatus: () => attendanceApi.changeShiftStatus(
        shift,
        'INACTIVE',
        '演示停用班次',
      ),
      listShiftVersions: () => attendanceApi.listShiftVersions(shift.shiftId),
      createShiftVersion: () => attendanceApi.createShiftVersion(shift.shiftId, shiftVersionInput),
      updateShiftVersion: () => attendanceApi.updateShiftVersion(shift.shiftId, shiftVersion, shiftVersionInput),
      publishShiftVersion: () => attendanceApi.publishShiftVersion(shift.shiftId, shiftVersion, '演示发布班次版本'),
      changeShiftVersionStatus: () => attendanceApi.changeShiftVersionStatus(
        shift.shiftId,
        shiftVersion,
        'INACTIVE',
        '2026-09-01',
        '演示停用班次版本',
      ),
      listCalendars: () => attendanceApi.listCalendars(calendar.calendarYear),
      createCalendar: () => attendanceApi.createCalendar(calendarInput),
      updateCalendar: () => attendanceApi.updateCalendar(calendar, calendarInput),
      listCalendarVersions: () => attendanceApi.listCalendarVersions(calendar.calendarId),
      createCalendarVersion: () => attendanceApi.createCalendarVersion(
        calendar.calendarId,
        calendarVersionInput,
      ),
      updateCalendarVersion: () => attendanceApi.updateCalendarVersion(
        calendar,
        calendarVersionInput,
      ),
      publishCalendarVersion: () => attendanceApi.publishCalendarVersion(
        calendar,
        '演示发布日历版本',
      ),
      deactivateCalendarVersion: () => attendanceApi.deactivateCalendarVersion(
        calendar,
        '2026-09-01',
        '演示停用日历版本',
      ),
      publishCalendar: () => attendanceApi.publishCalendar(calendar, '演示发布日历'),
      changeCalendarStatus: () => attendanceApi.changeCalendarStatus(
        calendar,
        'INACTIVE',
        '2026-09-01',
        '演示停用日历',
      ),
      listCalendarDays: () => attendanceApi.listCalendarDays(calendar.calendarId, '2026-01-01', '2026-12-31'),
      listCalendarVersionDays: () => attendanceApi.listCalendarVersionDays(
        calendar.calendarId,
        calendar.calendarVersionId,
        '2026-01-01',
        '2026-12-31',
      ),
      patchCalendarVersionDays: () => attendanceApi.patchCalendarVersionDays(
        calendar,
        [{
          businessDate: '2026-12-31',
          dayType: 'WORKDAY',
          shiftVersionOverrideId: shiftVersion.shiftVersionId,
        }],
        '演示追加日历日期修订',
      ),
      replaceCalendarDays: () => attendanceApi.replaceCalendarDays(calendar, [{
        businessDate: '2026-12-31',
        dayType: 'WORKDAY',
        shiftVersionOverrideId: shiftVersion.shiftVersionId,
      }], '演示更新日历日'),
      listPolicyCatalog: () => attendanceApi.listPolicyCatalog(),
      listPolicyBindings: () => attendanceApi.listPolicyBindings(group.groupId, '2026-08-01'),
      createPolicyBinding: () => attendanceApi.createPolicyBinding(bindingInput),
      updatePolicyBinding: () => attendanceApi.updatePolicyBinding(binding, bindingInput),
      listAttendancePolicyVersions: () => attendanceApi.listAttendancePolicyVersions(
        policyVersion.templateId,
        policyVersion.companyId,
      ),
      getAttendancePolicyVersion: () => attendanceApi.getAttendancePolicyVersion(
        policyVersion.templateId,
        policyVersion.companyId,
        policyVersion.scopedVersionId,
      ),
      getAttendancePolicyVersionContext: () => attendanceApi
        .getAttendancePolicyVersionContext(policyVersion.scopedVersionId),
      createAttendancePolicyDraft: () => attendanceApi.createAttendancePolicyDraft(
        policyVersion.templateId,
        policyVersion.companyId,
        {
        basedOnVersionId: policyVersion.scopedVersionId,
        effectiveFrom: '2026-09-01',
        effectiveTo: null,
        reason: '演示创建策略草稿',
        },
      ),
      updateAttendancePolicyDraft: () => attendanceApi.updateAttendancePolicyDraft(policyVersion, {
        parameters: policyVersion.parameters,
        effectiveFrom: policyVersion.effectiveFrom,
        effectiveTo: policyVersion.effectiveTo,
        reason: '演示更新策略草稿',
      }),
      validateAttendancePolicyVersion: () => attendanceApi.validateAttendancePolicyVersion(policyVersion, '演示校验策略版本'),
      publishAttendancePolicyVersion: () => attendanceApi.publishAttendancePolicyVersion(policyVersion, '演示发布策略版本'),
      deactivateAttendancePolicyVersion: () => attendanceApi.deactivateAttendancePolicyVersion(
        policyVersion,
        '2026-09-01',
        '演示停用策略版本',
      ),
      rollbackAttendancePolicyVersion: () => attendanceApi.rollbackAttendancePolicyVersion(
        policyVersion,
        policyVersion.scopedVersionId,
        '2026-10-01',
        '演示回滚策略版本',
      ),
      simulateAttendancePolicy: () => attendanceApi.simulateAttendancePolicy(simulationInput),
      previewPolicyImpact: () => attendanceApi.previewPolicyImpact(bindingInput),
      resolveAttendanceConfiguration: () => attendanceApi.resolveAttendanceConfiguration(assignment.employeeId, '2026-08-01'),
    } satisfies Record<keyof typeof attendanceApi, () => Promise<unknown>>;

    expect(Object.keys(calls).sort()).toEqual(Object.keys(attendanceApi).sort());
    await Promise.all(Object.values(calls).map((call) => call()));

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function requestBody(fetchMock: ReturnType<typeof vi.fn>, callNumber: number): unknown {
  const init = fetchMock.mock.calls[callNumber - 1]?.[1] as RequestInit | undefined;
  return JSON.parse(String(init?.body));
}

function lateGraceSimulation(): PolicySimulationInput {
  return {
    employeeId: '9200000000000000001',
    businessDate: '2026-08-01',
    correctionAsOf: '2026-08-01T12:00:00+08:00',
    punches: [{
      direction: 'ENTRY',
      instant: '2026-08-01T08:15:00+08:00',
      association: 'SCHEDULED_WORK',
    }],
  };
}

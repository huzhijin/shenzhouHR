import {
  ApiRequestError,
  changeReasonHeaders,
  createIdempotencyKey,
  requestJson,
  versionHeaders,
} from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  demoAssignments,
  demoBindings,
  demoCalendarDays,
  demoCalendars,
  demoConfiguration,
  demoGroups,
  demoLocations,
  demoPolicyCatalog,
  demoPolicyImpact,
  demoPolicyVersions,
  demoShiftVersions,
  demoShifts,
  demoSimulation,
  requiredDemoItem,
} from './attendanceSetupDemo';
import type {
  AssignmentInput,
  AssignmentView,
  AttendancePolicyDraftInput,
  AttendancePolicyDraftUpdateInput,
  AttendancePolicyVersionSummary,
  AttendancePolicyVersionView,
  AttendanceConfigurationView,
  AttendanceGroupInput,
  AttendanceGroupView,
  CalendarDayInput,
  CalendarDayView,
  LocationInput,
  LocationView,
  Page,
  PolicyBindingInput,
  PolicyBindingPreviewInput,
  PolicyBindingView,
  PolicyImpactView,
  PolicySimulationInput,
  PolicySimulationBatchView,
  PolicyTemplateDefinition,
  PolicyValidationResult,
  ShiftTemplateInput,
  ShiftTemplateView,
  ShiftVersionInput,
  ShiftVersionView,
  WorkCalendarInput,
  WorkCalendarVersionInput,
  WorkCalendarView,
} from './attendanceSetupTypes';

const basePath = '/api/v1/attendance-setup';

export function listLocations(page = 0, size = 100): Promise<Page<LocationView>> {
  if (isDemoMode()) return Promise.resolve(demoPage(demoLocations, page, size));
  return requestJson<Page<LocationView>>(
    `${basePath}/locations?page=${page}&size=${size}`,
  );
}

export function listLocationRevisions(
  locationId: string,
  page = 0,
  size = 100,
): Promise<Page<LocationView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoLocations.filter((item) => item.locationId === locationId),
      page,
      size,
    ));
  }
  return requestJson<Page<LocationView>>(
    `${basePath}/locations/${encodeURIComponent(locationId)}/revisions?page=${page}&size=${size}`,
  );
}

export function createLocation(input: LocationInput): Promise<LocationView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoLocations),
      ...input,
      locationId: 'demo-location-created',
      status: 'ACTIVE',
      rowVersion: 1,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<LocationView>(`${basePath}/locations`, {
    method: 'POST',
    headers: changeReasonHeaders(input.reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-location-create'),
    }),
    body: JSON.stringify(input),
  });
}

export function updateLocation(
  locationId: string,
  rowVersion: number,
  input: LocationInput,
): Promise<LocationView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoLocations),
      ...input,
      locationId,
      rowVersion: rowVersion + 1,
      changeReason: input.reason,
    });
  }
  return requestJson<LocationView>(`${basePath}/locations/${encodeURIComponent(locationId)}`, {
    method: 'PUT',
    headers: changeReasonHeaders(
      input.reason,
      versionHeaders(rowVersion, createIdempotencyKey('attendance-location-update')),
    ),
    body: JSON.stringify(input),
  });
}

export function changeLocationStatus(
  location: LocationView,
  action: 'activate' | 'deactivate',
  reason: string,
): Promise<LocationView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...location,
      status: action === 'activate' ? 'ACTIVE' : 'INACTIVE',
      rowVersion: location.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<LocationView>(
    `${basePath}/locations/${encodeURIComponent(location.locationId)}/${action}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          location.rowVersion,
          createIdempotencyKey(`attendance-location-${action}`),
        ),
      ),
      body: JSON.stringify({ reason }),
    },
  );
}

export function listAttendanceGroups(
  asOf?: string,
  page = 0,
  size = 100,
): Promise<Page<AttendanceGroupView>> {
  if (isDemoMode()) return Promise.resolve(demoPage(demoGroups, page, size));
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (asOf) params.set('asOf', asOf);
  return requestJson<Page<AttendanceGroupView>>(`${basePath}/groups?${params}`);
}

export function listAttendanceGroupRevisions(
  groupId: string,
  page = 0,
  size = 100,
): Promise<Page<AttendanceGroupView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoGroups.filter((item) => item.groupId === groupId),
      page,
      size,
    ));
  }
  return requestJson<Page<AttendanceGroupView>>(
    `${basePath}/groups/${encodeURIComponent(groupId)}/revisions?page=${page}&size=${size}`,
  );
}

export function createAttendanceGroup(input: AttendanceGroupInput): Promise<AttendanceGroupView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoGroups),
      ...input,
      groupId: 'demo-group-created',
      status: 'ACTIVE',
      rowVersion: 1,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<AttendanceGroupView>(`${basePath}/groups`, {
    method: 'POST',
    headers: changeReasonHeaders(input.reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-group-create'),
    }),
    body: JSON.stringify(input),
  });
}

export function updateAttendanceGroup(
  groupId: string,
  rowVersion: number,
  input: AttendanceGroupInput,
): Promise<AttendanceGroupView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoGroups),
      ...input,
      groupId,
      rowVersion: rowVersion + 1,
      changeReason: input.reason,
    });
  }
  return requestJson<AttendanceGroupView>(`${basePath}/groups/${encodeURIComponent(groupId)}`, {
    method: 'PUT',
    headers: changeReasonHeaders(
      input.reason,
      versionHeaders(rowVersion, createIdempotencyKey('attendance-group-update')),
    ),
    body: JSON.stringify(input),
  });
}

export function changeAttendanceGroupStatus(
  group: AttendanceGroupView,
  action: 'activate' | 'deactivate',
  reason: string,
): Promise<AttendanceGroupView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...group,
      status: action === 'activate' ? 'ACTIVE' : 'INACTIVE',
      rowVersion: group.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<AttendanceGroupView>(
    `${basePath}/groups/${encodeURIComponent(group.groupId)}/${action}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          group.rowVersion,
          createIdempotencyKey(`attendance-group-${action}`),
        ),
      ),
      body: JSON.stringify({ reason }),
    },
  );
}

export function listAssignments(
  groupId: string,
  asOf?: string,
  page = 0,
  size = 100,
): Promise<Page<AssignmentView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoAssignments.filter((item) => item.groupId === groupId),
      page,
      size,
    ));
  }
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (asOf) params.set('asOf', asOf);
  return requestJson<Page<AssignmentView>>(
    `${basePath}/groups/${encodeURIComponent(groupId)}/assignments?${params}`,
  );
}

export function createAssignment(
  groupId: string,
  input: AssignmentInput,
): Promise<AssignmentView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoAssignments),
      ...input,
      assignmentId: 'demo-assignment-created',
      groupId,
      rowVersion: 1,
      monthlyContextKey: `${input.employeeId}:${input.effectiveFrom.slice(0, 7)}`,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<AssignmentView>(
    `${basePath}/groups/${encodeURIComponent(groupId)}/assignments`,
    {
      method: 'POST',
      headers: changeReasonHeaders(input.reason, {
        'Idempotency-Key': createIdempotencyKey('attendance-assignment-create'),
      }),
      body: JSON.stringify(input),
    },
  );
}

export function updateAssignment(
  groupId: string,
  assignmentId: string,
  rowVersion: number,
  input: AssignmentInput,
): Promise<AssignmentView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoAssignments),
      ...input,
      assignmentId,
      groupId,
      rowVersion: rowVersion + 1,
      monthlyContextKey: `${input.employeeId}:${input.effectiveFrom.slice(0, 7)}`,
      changeReason: input.reason,
    });
  }
  return requestJson<AssignmentView>(
    `${basePath}/groups/${encodeURIComponent(groupId)}/assignments/${encodeURIComponent(assignmentId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          rowVersion,
          createIdempotencyKey('attendance-assignment-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function listShifts(page = 0, size = 100): Promise<Page<ShiftTemplateView>> {
  if (isDemoMode()) return Promise.resolve(demoPage(demoShifts, page, size));
  return requestJson<Page<ShiftTemplateView>>(
    `${basePath}/shifts?page=${page}&size=${size}`,
  );
}

export function createShift(input: ShiftTemplateInput): Promise<ShiftTemplateView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoShifts),
      ...input,
      shiftId: 'demo-shift-created',
      status: 'ACTIVE',
      rowVersion: 1,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<ShiftTemplateView>(`${basePath}/shifts`, {
    method: 'POST',
    headers: changeReasonHeaders(input.reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-shift-create'),
    }),
    body: JSON.stringify(input),
  });
}

export function updateShift(
  shift: ShiftTemplateView,
  input: ShiftTemplateInput,
): Promise<ShiftTemplateView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...shift,
      ...input,
      rowVersion: shift.rowVersion + 1,
      changeReason: input.reason,
    });
  }
  return requestJson<ShiftTemplateView>(
    `${basePath}/shifts/${encodeURIComponent(shift.shiftId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          shift.rowVersion,
          createIdempotencyKey('attendance-shift-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function changeShiftStatus(
  shift: ShiftTemplateView,
  status: 'ACTIVE' | 'INACTIVE',
  reason: string,
): Promise<ShiftTemplateView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...shift,
      status,
      rowVersion: shift.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<ShiftTemplateView>(
    `${basePath}/shifts/${encodeURIComponent(shift.shiftId)}/status`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          shift.rowVersion,
          createIdempotencyKey('attendance-shift-status'),
        ),
      ),
      body: JSON.stringify({ status, reason }),
    },
  );
}

export function listShiftVersions(
  shiftId: string,
  page = 0,
  size = 100,
): Promise<Page<ShiftVersionView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoShiftVersions.filter((item) => item.shiftId === shiftId),
      page,
      size,
    ));
  }
  return requestJson<Page<ShiftVersionView>>(
    `${basePath}/shifts/${encodeURIComponent(shiftId)}/versions?page=${page}&size=${size}`,
  );
}

export function createShiftVersion(
  shiftId: string,
  input: ShiftVersionInput,
): Promise<ShiftVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoShiftVersions),
      ...input,
      shiftVersionId: 'demo-shift-version-created',
      shiftId,
      versionNumber: demoShiftVersions.length + 1,
      status: 'DRAFT',
      snapshotDigest: '0'.repeat(64),
      rowVersion: 1,
      changeReason: input.reason,
      publishedAt: null,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<ShiftVersionView>(
    `${basePath}/shifts/${encodeURIComponent(shiftId)}/versions`,
    {
      method: 'POST',
      headers: changeReasonHeaders(input.reason, {
        'Idempotency-Key': createIdempotencyKey('attendance-shift-version-create'),
      }),
      body: JSON.stringify(input),
    },
  );
}

export function updateShiftVersion(
  shiftId: string,
  version: ShiftVersionView,
  input: ShiftVersionInput,
): Promise<ShiftVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...version,
      ...input,
      rowVersion: version.rowVersion + 1,
      changeReason: input.reason,
    });
  }
  return requestJson<ShiftVersionView>(
    `${basePath}/shifts/${encodeURIComponent(shiftId)}/versions/${encodeURIComponent(version.shiftVersionId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-shift-version-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function publishShiftVersion(
  shiftId: string,
  version: ShiftVersionView,
  reason: string,
): Promise<ShiftVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...version,
      status: 'PUBLISHED',
      rowVersion: version.rowVersion + 1,
      changeReason: reason,
      publishedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<ShiftVersionView>(
    `${basePath}/shifts/${encodeURIComponent(shiftId)}/versions/${encodeURIComponent(version.shiftVersionId)}/publish`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-shift-version-publish'),
        ),
      ),
      body: JSON.stringify({ reason }),
    },
  );
}

export function changeShiftVersionStatus(
  shiftId: string,
  version: ShiftVersionView,
  status: 'INACTIVE',
  businessEffectiveFrom: string,
  reason: string,
): Promise<ShiftVersionView> {
  if (isDemoMode()) {
    const boundaryError = demoDeactivationBoundaryError(
      businessEffectiveFrom,
      version.effectiveFrom,
      version.effectiveTo,
      'SHIFT_VERSION',
    );
    if (boundaryError) return Promise.reject(boundaryError);
    return Promise.resolve({
      ...version,
      status,
      rowVersion: version.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<ShiftVersionView>(
    `${basePath}/shifts/${encodeURIComponent(shiftId)}/versions/${encodeURIComponent(version.shiftVersionId)}/status`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-shift-version-status'),
        ),
      ),
      body: JSON.stringify({ status, businessEffectiveFrom, reason }),
    },
  );
}

export function listCalendars(
  year?: number,
  page = 0,
  size = 100,
): Promise<Page<WorkCalendarView>> {
  const filtered = demoCalendars.filter(
    (item) => year === undefined || item.calendarYear === year,
  );
  if (isDemoMode()) {
    return Promise.resolve(demoPage(filtered, page, size));
  }
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (year !== undefined) params.set('year', String(year));
  return requestJson<Page<WorkCalendarView>>(`${basePath}/calendars?${params}`);
}

export function listCalendarVersions(
  calendarId: string,
  page = 0,
  size = 100,
): Promise<Page<WorkCalendarView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoCalendars.filter((item) => item.calendarId === calendarId),
      page,
      size,
    ));
  }
  return requestJson<Page<WorkCalendarView>>(
    `${basePath}/calendars/${encodeURIComponent(calendarId)}/versions?page=${page}&size=${size}`,
  );
}

export function createCalendar(input: WorkCalendarInput): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoCalendars),
      ...input,
      calendarId: 'demo-calendar-created',
      status: 'DRAFT',
      rowVersion: 1,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<WorkCalendarView>(`${basePath}/calendars`, {
    method: 'POST',
    headers: changeReasonHeaders(input.reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-calendar-create'),
    }),
    body: JSON.stringify(input),
  });
}

export function updateCalendar(
  calendar: WorkCalendarView,
  input: WorkCalendarInput,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      ...input,
      rowVersion: calendar.rowVersion + 1,
      changeReason: input.reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function createCalendarVersion(
  calendarId: string,
  input: WorkCalendarVersionInput,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    const current = requiredDemoItem(
      demoCalendars.filter((item) => item.calendarId === calendarId),
    );
    return Promise.resolve({
      ...current,
      ...input,
      calendarVersionId: `${current.calendarVersionId}-draft`,
      versionNumber: current.versionNumber + 1,
      status: 'DRAFT',
      snapshotDigest: '0'.repeat(64),
      rowVersion: 0,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendarId)}/versions`,
    {
      method: 'POST',
      headers: changeReasonHeaders(input.reason, {
        'Idempotency-Key': createIdempotencyKey('attendance-calendar-version-create'),
      }),
      body: JSON.stringify(input),
    },
  );
}

export function updateCalendarVersion(
  calendar: WorkCalendarView,
  input: WorkCalendarVersionInput,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      ...input,
      calendarVersionId: `${calendar.calendarVersionId}-successor`,
      versionNumber: calendar.versionNumber + 1,
      status: 'DRAFT',
      snapshotDigest: '0'.repeat(64),
      rowVersion: 0,
      changeReason: input.reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${encodeURIComponent(calendar.calendarVersionId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-version-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function publishCalendarVersion(
  calendar: WorkCalendarView,
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      status: 'PUBLISHED',
      rowVersion: calendar.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${encodeURIComponent(calendar.calendarVersionId)}/publish`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-version-publish'),
        ),
      ),
      body: JSON.stringify({ reason }),
    },
  );
}

export function deactivateCalendarVersion(
  calendar: WorkCalendarView,
  businessEffectiveFrom: string,
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    const boundaryError = demoDeactivationBoundaryError(
      businessEffectiveFrom,
      calendar.effectiveFrom,
      calendar.effectiveTo,
      'WORK_CALENDAR',
    );
    if (boundaryError) return Promise.reject(boundaryError);
    return Promise.resolve({
      ...calendar,
      status: 'INACTIVE',
      rowVersion: calendar.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${encodeURIComponent(calendar.calendarVersionId)}/deactivate`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-version-deactivate'),
        ),
      ),
      body: JSON.stringify({ businessEffectiveFrom, reason }),
    },
  );
}

export function publishCalendar(
  calendar: WorkCalendarView,
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      status: 'PUBLISHED',
      rowVersion: calendar.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/publish`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-publish'),
        ),
      ),
      body: JSON.stringify({ reason }),
    },
  );
}

export function changeCalendarStatus(
  calendar: WorkCalendarView,
  status: 'INACTIVE',
  businessEffectiveFrom: string,
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    const boundaryError = demoDeactivationBoundaryError(
      businessEffectiveFrom,
      calendar.effectiveFrom,
      calendar.effectiveTo,
      'WORK_CALENDAR',
    );
    if (boundaryError) return Promise.reject(boundaryError);
    return Promise.resolve({
      ...calendar,
      status,
      rowVersion: calendar.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/status`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-status'),
        ),
      ),
      body: JSON.stringify({ status, businessEffectiveFrom, reason }),
    },
  );
}

export function listCalendarDays(
  calendarId: string,
  from: string,
  to: string,
  page = 0,
  size = 100,
): Promise<Page<CalendarDayView>> {
  const filtered = demoCalendarDays.filter(
    (item) => item.calendarId === calendarId
      && item.businessDate >= from
      && item.businessDate <= to,
  );
  if (isDemoMode()) {
    return Promise.resolve(demoPage(filtered, page, size));
  }
  const params = new URLSearchParams({
    from,
    to,
    page: String(page),
    size: String(size),
  });
  return requestJson<Page<CalendarDayView>>(
    `${basePath}/calendars/${encodeURIComponent(calendarId)}/days?${params}`,
  );
}

export function listCalendarVersionDays(
  calendarId: string,
  versionId: string,
  from: string,
  to: string,
  page = 0,
  size = 100,
): Promise<Page<CalendarDayView>> {
  const filtered = demoCalendarDays.filter(
    (item) => item.calendarId === calendarId
      && item.calendarVersionId === versionId
      && item.businessDate >= from
      && item.businessDate <= to,
  );
  if (isDemoMode()) {
    return Promise.resolve(demoPage(filtered, page, size));
  }
  const params = new URLSearchParams({
    from,
    to,
    page: String(page),
    size: String(size),
  });
  return requestJson<Page<CalendarDayView>>(
    `${basePath}/calendars/${encodeURIComponent(calendarId)}/versions/${encodeURIComponent(versionId)}/days?${params}`,
  );
}

export function replaceCalendarDays(
  calendar: WorkCalendarView,
  days: CalendarDayInput[],
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      rowVersion: calendar.rowVersion + 1,
      changeReason: reason,
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/days`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-days-replace'),
        ),
      ),
      body: JSON.stringify(days),
    },
  );
}

export function patchCalendarVersionDays(
  calendar: WorkCalendarView,
  days: CalendarDayInput[],
  reason: string,
): Promise<WorkCalendarView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...calendar,
      calendarVersionId: `${calendar.calendarVersionId}-successor`,
      versionNumber: calendar.versionNumber + 1,
      status: 'DRAFT',
      snapshotDigest: '0'.repeat(64),
      rowVersion: 0,
      changeReason: reason,
      updatedAt: '2026-07-26T08:00:00Z',
    });
  }
  return requestJson<WorkCalendarView>(
    `${basePath}/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${encodeURIComponent(calendar.calendarVersionId)}/days`,
    {
      method: 'PATCH',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          calendar.rowVersion,
          createIdempotencyKey('attendance-calendar-version-days-patch'),
        ),
      ),
      body: JSON.stringify(days),
    },
  );
}

export function listPolicyCatalog(): Promise<PolicyTemplateDefinition[]> {
  if (isDemoMode()) return Promise.resolve([...demoPolicyCatalog]);
  return requestJson<PolicyTemplateDefinition[]>(`${basePath}/policy-catalog`);
}

export function listPolicyBindings(
  groupId?: string,
  asOf?: string,
  page = 0,
  size = 100,
): Promise<Page<PolicyBindingView>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoBindings.filter(
        (item) => groupId === undefined || item.groupId === groupId,
      ),
      page,
      size,
    ));
  }
  const params = new URLSearchParams();
  if (groupId) params.set('groupId', groupId);
  if (asOf) params.set('asOf', asOf);
  params.set('page', String(page));
  params.set('size', String(size));
  return requestJson<Page<PolicyBindingView>>(`${basePath}/policy-bindings?${params}`);
}

export function createPolicyBinding(input: PolicyBindingInput): Promise<PolicyBindingView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...requiredDemoItem(demoBindings),
      ...input,
      bindingId: 'demo-policy-binding-created',
      bindingRevisionId: 'demo-policy-binding-created-revision-1',
      revisionNumber: 1,
      status: 'ACTIVE',
      snapshotDigest: '4'.repeat(64),
      rowVersion: 1,
      changeReason: input.reason,
    });
  }
  return requestJson<PolicyBindingView>(`${basePath}/policy-bindings`, {
    method: 'POST',
    headers: changeReasonHeaders(input.reason, {
      'Idempotency-Key': createIdempotencyKey('attendance-policy-binding-create'),
    }),
    body: JSON.stringify(input),
  });
}

export function updatePolicyBinding(
  binding: PolicyBindingView,
  input: PolicyBindingInput,
): Promise<PolicyBindingView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...binding,
      ...input,
      bindingId: binding.bindingId,
      bindingRevisionId: `${binding.bindingRevisionId}-successor`,
      revisionNumber: binding.revisionNumber + 1,
      status: 'ACTIVE',
      snapshotDigest: '5'.repeat(64),
      rowVersion: 0,
      changeReason: input.reason,
    });
  }
  return requestJson<PolicyBindingView>(
    `${basePath}/policy-bindings/${encodeURIComponent(binding.bindingId)}`,
    {
      method: 'PUT',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          binding.rowVersion,
          createIdempotencyKey('attendance-policy-binding-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function listAttendancePolicyVersions(
  templateId: string,
  legalEntityId: string,
  page = 0,
  size = 20,
): Promise<Page<AttendancePolicyVersionSummary>> {
  if (isDemoMode()) {
    return Promise.resolve(demoPage(
      demoPolicyVersions.filter((version) => version.templateId === templateId),
      page,
      size,
    ));
  }
  const params = new URLSearchParams({
    legalEntityId,
    page: String(page),
    size: String(size),
  });
  return requestJson<Page<AttendancePolicyVersionSummary>>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(templateId)}/versions?${params}`,
  );
}

export function getAttendancePolicyVersion(
  templateId: string,
  legalEntityId: string,
  versionId: string,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    const version = demoPolicyVersions.find(
      (candidate) => candidate.templateId === templateId
        && candidate.legalEntityId === legalEntityId
        && candidate.scopedVersionId === versionId,
    );
    return version
      ? Promise.resolve(version)
      : Promise.reject(new ApiRequestError(404, {
        code: 'RESOURCE_NOT_AVAILABLE',
        retryable: false,
      }));
  }
  const params = new URLSearchParams({ legalEntityId });
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(templateId)}/versions/${encodeURIComponent(versionId)}?${params}`,
  );
}

export function getAttendancePolicyVersionContext(
  versionId: string,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    const version = demoPolicyVersions.find(
      (candidate) => candidate.scopedVersionId === versionId,
    );
    return version
      ? Promise.resolve(version)
      : Promise.reject(new ApiRequestError(404, {
        code: 'RESOURCE_NOT_AVAILABLE',
        retryable: false,
      }));
  }
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/versions/${encodeURIComponent(versionId)}/context`,
  );
}

export function createAttendancePolicyDraft(
  templateId: string,
  legalEntityId: string,
  input: AttendancePolicyDraftInput,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    const base = demoPolicyVersions.find((version) => version.templateId === templateId)
      ?? requiredDemoItem(demoPolicyVersions);
    return Promise.resolve({
      ...base,
      scopedVersionId: 'demo-policy-draft',
      legalEntityId,
      versionNumber: base.versionNumber + 1,
      status: 'DRAFT',
      effectiveFrom: input.effectiveFrom,
      effectiveTo: input.effectiveTo ?? null,
      changeReason: input.reason,
      rowVersion: 0,
      publishedAt: null,
      snapshotDigest: null,
      validation: { valid: false, issues: [], validatedAt: null },
    });
  }
  const params = new URLSearchParams({ legalEntityId });
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(templateId)}/versions?${params}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(input.reason, {
        'Idempotency-Key': createIdempotencyKey('attendance-policy-draft-create'),
      }),
      body: JSON.stringify(input),
    },
  );
}

export function updateAttendancePolicyDraft(
  version: AttendancePolicyVersionView,
  input: AttendancePolicyDraftUpdateInput,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...version,
      ...input,
      changeReason: input.reason,
      rowVersion: version.rowVersion + 1,
      validation: { valid: false, issues: [], validatedAt: null },
    });
  }
  const params = new URLSearchParams({ legalEntityId: version.legalEntityId });
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(version.templateId)}/versions/${encodeURIComponent(version.scopedVersionId)}?${params}`,
    {
      method: 'PATCH',
      headers: changeReasonHeaders(
        input.reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-policy-draft-update'),
        ),
      ),
      body: JSON.stringify(input),
    },
  );
}

export function validateAttendancePolicyVersion(
  version: AttendancePolicyVersionView,
  reason: string,
): Promise<PolicyValidationResult> {
  if (isDemoMode()) {
    return Promise.resolve({
      valid: version.parameters.length > 0,
      issues: [],
      validatedAt: '2026-07-26T08:00:00Z',
    });
  }
  const params = new URLSearchParams({ legalEntityId: version.legalEntityId });
  return requestJson<PolicyValidationResult>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(version.templateId)}/versions/${encodeURIComponent(version.scopedVersionId)}/validate?${params}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-policy-version-validate'),
        ),
      ),
    },
  );
}

export function publishAttendancePolicyVersion(
  version: AttendancePolicyVersionView,
  reason: string,
): Promise<AttendancePolicyVersionView> {
  return changePolicyVersionLifecycle(version, 'publish', reason);
}

export function deactivateAttendancePolicyVersion(
  version: AttendancePolicyVersionView,
  effectiveFrom: string,
  reason: string,
): Promise<AttendancePolicyVersionView> {
  return changePolicyVersionLifecycle(
    version, 'deactivate', reason, effectiveFrom,
  );
}

export function rollbackAttendancePolicyVersion(
  version: AttendancePolicyVersionView,
  targetVersionId: string,
  effectiveFrom: string,
  reason: string,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...version,
      scopedVersionId: 'demo-policy-rollback',
      versionNumber: version.versionNumber + 1,
      status: 'PUBLISHED',
      rollbackOfScopedVersionId: targetVersionId,
      effectiveFrom,
      rowVersion: 0,
      changeReason: reason,
    });
  }
  const params = new URLSearchParams({ legalEntityId: version.legalEntityId });
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(version.templateId)}/versions/${encodeURIComponent(version.scopedVersionId)}/rollback?${params}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey('attendance-policy-version-rollback'),
        ),
      ),
      body: JSON.stringify({ targetVersionId, effectiveFrom, reason }),
    },
  );
}

export function simulateAttendancePolicy(
  input: PolicySimulationInput,
): Promise<PolicySimulationBatchView> {
  if (isDemoMode()) return Promise.resolve(demoSimulation(input));
  return requestJson<PolicySimulationBatchView>(`${basePath}/policy-simulations`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function previewPolicyImpact(
  input: PolicyBindingPreviewInput,
): Promise<PolicyImpactView> {
  if (isDemoMode()) return Promise.resolve(demoPolicyImpact);
  return requestJson<PolicyImpactView>(`${basePath}/policy-impact-preview`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function resolveAttendanceConfiguration(
  employeeId: string,
  businessDate: string,
): Promise<AttendanceConfigurationView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...demoConfiguration,
      employeeId,
      businessDate,
    });
  }
  const params = new URLSearchParams({ employeeId, businessDate });
  return requestJson<AttendanceConfigurationView>(`${basePath}/resolve?${params}`);
}

function demoPage<T>(
  items: readonly T[],
  page = 0,
  size = 100,
): Page<T> {
  const offset = page * size;
  return {
    items: items.slice(offset, offset + size),
    total: items.length,
    page,
    size,
  };
}

function demoDeactivationBoundaryError(
  boundary: string,
  effectiveFrom: string,
  effectiveTo: string | null | undefined,
  resource: 'SHIFT_VERSION' | 'WORK_CALENDAR',
): ApiRequestError | undefined {
  const today = new Date().toISOString().slice(0, 10);
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(boundary)
    || boundary <= today
    || boundary <= effectiveFrom
  ) {
    return new ApiRequestError(409, {
      code: `${resource}_HISTORY_PROTECTED`,
      message: '停用必须声明晚于今天和版本起点的未来业务边界',
      retryable: false,
    });
  }
  if (effectiveTo && boundary > effectiveTo) {
    return new ApiRequestError(409, {
      code: `${resource}_DEACTIVATION_OUT_OF_RANGE`,
      message: '停用边界不得晚于版本的半开期间终点',
      retryable: false,
    });
  }
  return undefined;
}

function changePolicyVersionLifecycle(
  version: AttendancePolicyVersionView,
  action: 'publish' | 'deactivate',
  reason: string,
  effectiveFrom?: string,
): Promise<AttendancePolicyVersionView> {
  if (isDemoMode()) {
    return Promise.resolve({
      ...version,
      status: 'PUBLISHED',
      rowVersion: version.rowVersion + 1,
      changeReason: reason,
      publishedAt: action === 'publish' ? '2026-07-26T08:00:00Z' : version.publishedAt,
      snapshotDigest: action === 'publish' ? '5'.repeat(64) : version.snapshotDigest,
      deactivationEffectiveFrom: action === 'deactivate'
        ? effectiveFrom ?? null
        : version.deactivationEffectiveFrom,
    });
  }
  const params = new URLSearchParams({ legalEntityId: version.legalEntityId });
  return requestJson<AttendancePolicyVersionView>(
    `${basePath}/policy-lifecycle/${encodeURIComponent(version.templateId)}/versions/${encodeURIComponent(version.scopedVersionId)}/${action}?${params}`,
    {
      method: 'POST',
      headers: changeReasonHeaders(
        reason,
        versionHeaders(
          version.rowVersion,
          createIdempotencyKey(`attendance-policy-version-${action}`),
        ),
      ),
      body: JSON.stringify(action === 'deactivate'
        ? { effectiveFrom, reason }
        : { reason }),
    },
  );
}

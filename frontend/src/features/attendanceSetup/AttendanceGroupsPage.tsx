import {
  IconCalendarTime,
  IconGitBranch,
  IconMapPin,
  IconPlayerPlay,
  IconPlayerStop,
  IconUserPlus,
} from '@tabler/icons-react';
import { Input } from 'antd';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { ConfirmationDialog, StatusBadge } from '../../shared/components/FeedbackComponents';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { getEmployees } from '../employee/employeeApi';
import {
  changeAttendanceGroupStatus,
  changeLocationStatus,
  createAssignment,
  createAttendanceGroup,
  createLocation,
  listAssignments,
  listAttendanceGroupRevisions,
  listAttendanceGroups,
  listCalendars,
  listLocationRevisions,
  listLocations,
  listShifts,
  updateAssignment,
  updateAttendanceGroup,
  updateLocation,
} from './attendanceSetupApi';
import {
  AssignmentDialog,
  AttendanceGroupDialog,
  LocationDialog,
} from './AttendanceGroupDialogs';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
  type AttendanceSetupNotice as Notice,
} from './attendanceSetupFeedback';
import {
  loadAllAttendanceDirectoryItems,
  missingDirectoryLabel,
} from './attendanceDirectory';
import type {
  AssignmentInput,
  AssignmentView,
  AttendanceGroupInput,
  AttendanceGroupView,
  LocationInput,
  LocationView,
} from './attendanceSetupTypes';

type LifecycleTarget =
  | { type: 'location'; value: LocationView }
  | { type: 'group'; value: AttendanceGroupView };

export function AttendanceGroupsPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [asOf, setAsOf] = useState('');
  const [selectedLocationId, setSelectedLocationId] = useState('');
  const [selectedGroupId, setSelectedGroupId] = useState('');
  const [locationOpen, setLocationOpen] = useState(false);
  const [groupOpen, setGroupOpen] = useState(false);
  const [assignmentOpen, setAssignmentOpen] = useState(false);
  const [editingLocation, setEditingLocation] = useState<LocationView>();
  const [editingGroup, setEditingGroup] = useState<AttendanceGroupView>();
  const [editingAssignment, setEditingAssignment] = useState<AssignmentView>();
  const [statusTarget, setStatusTarget] = useState<LifecycleTarget>();
  const [statusReason, setStatusReason] = useState('');
  const [processing, setProcessing] = useState(false);
  const [notice, setNotice] = useState<Notice>();
  const [locationPage, setLocationPage] = useState(0);
  const [locationPageSize, setLocationPageSize] = useState(20);
  const [groupPage, setGroupPage] = useState(0);
  const [groupPageSize, setGroupPageSize] = useState(20);
  const [locationRevisionPage, setLocationRevisionPage] = useState(0);
  const [locationRevisionPageSize, setLocationRevisionPageSize] = useState(20);
  const [groupRevisionPage, setGroupRevisionPage] = useState(0);
  const [groupRevisionPageSize, setGroupRevisionPageSize] = useState(20);
  const [assignmentPage, setAssignmentPage] = useState(0);
  const [assignmentPageSize, setAssignmentPageSize] = useState(20);
  const overviewLoader = useMemo(
    () => () => Promise.all([
      listLocations(locationPage, locationPageSize),
      listAttendanceGroups(asOf || undefined, groupPage, groupPageSize),
    ]),
    [asOf, groupPage, groupPageSize, locationPage, locationPageSize],
  );
  const overview = useAsyncResource(
    overviewLoader,
    ([locations, groups]) => locations.total === 0 && groups.total === 0,
    [asOf, groupPage, groupPageSize, locationPage, locationPageSize],
  );
  const firstGroupId = overview.resource.status === 'ready'
    ? overview.resource.data[1].items[0]?.groupId ?? ''
    : '';
  const firstLocationId = overview.resource.status === 'ready'
    ? overview.resource.data[0].items[0]?.locationId ?? ''
    : '';
  const effectiveLocationId = selectedLocationId || firstLocationId;
  const effectiveGroupId = selectedGroupId || firstGroupId;
  const locationRevisionLoader = useMemo(
    () => () => effectiveLocationId
      ? listLocationRevisions(
        effectiveLocationId,
        locationRevisionPage,
        locationRevisionPageSize,
      )
      : Promise.resolve({
        items: [],
        total: 0,
        page: locationRevisionPage,
        size: locationRevisionPageSize,
      }),
    [effectiveLocationId, locationRevisionPage, locationRevisionPageSize],
  );
  const locationRevisions = useAsyncResource(
    locationRevisionLoader,
    (page) => page.total === 0,
    [effectiveLocationId, locationRevisionPage, locationRevisionPageSize],
  );
  const groupRevisionLoader = useMemo(
    () => () => effectiveGroupId
      ? listAttendanceGroupRevisions(
        effectiveGroupId,
        groupRevisionPage,
        groupRevisionPageSize,
      )
      : Promise.resolve({
        items: [],
        total: 0,
        page: groupRevisionPage,
        size: groupRevisionPageSize,
      }),
    [effectiveGroupId, groupRevisionPage, groupRevisionPageSize],
  );
  const groupRevisions = useAsyncResource(
    groupRevisionLoader,
    (page) => page.total === 0,
    [effectiveGroupId, groupRevisionPage, groupRevisionPageSize],
  );
  const assignmentLoader = useMemo(
    () => () => effectiveGroupId
      ? listAssignments(
        effectiveGroupId,
        asOf || undefined,
        assignmentPage,
        assignmentPageSize,
      )
      : Promise.resolve({
        items: [],
        total: 0,
        page: assignmentPage,
        size: assignmentPageSize,
      }),
    [assignmentPage, assignmentPageSize, effectiveGroupId, asOf],
  );
  const assignments = useAsyncResource(
    assignmentLoader,
    (page) => page.total === 0,
    [assignmentPage, assignmentPageSize, effectiveGroupId, asOf],
  );
  const locationDirectory = useAsyncResource(
    () => loadAllAttendanceDirectoryItems((page, size) => listLocations(page, size)),
    () => false,
    [],
  );
  const calendarDirectory = useAsyncResource(
    () => loadAllAttendanceDirectoryItems(
      (page, size) => listCalendars(undefined, page, size),
    ),
    () => false,
    [],
  );
  const shiftDirectory = useAsyncResource(
    () => loadAllAttendanceDirectoryItems((page, size) => listShifts(page, size)),
    () => false,
    [],
  );
  const employeeDirectory = useAsyncResource(
    () => loadAllAttendanceDirectoryItems((page, size) => getEmployees(
        page,
        size,
        { sort: 'employeeNumber' },
      )),
    () => false,
    [],
  );
  const locationLabels = locationDirectory.resource.status === 'ready'
    ? new Map(locationDirectory.resource.data.map((location) => [
      location.locationId,
      `${location.name}（${location.code}）`,
    ]))
    : new Map<string, string>();
  const calendarLabels = calendarDirectory.resource.status === 'ready'
    ? new Map(calendarDirectory.resource.data.map((calendar) => [
      calendar.calendarId,
      `${calendar.name}（${calendar.code}）`,
    ]))
    : new Map<string, string>();
  const shiftLabels = shiftDirectory.resource.status === 'ready'
    ? new Map(shiftDirectory.resource.data.map((shift) => [
      shift.shiftId,
      `${shift.name}（${shift.code}）`,
    ]))
    : new Map<string, string>();
  const employeeLabels = employeeDirectory.resource.status === 'ready'
    ? new Map(employeeDirectory.resource.data.map((employee) => [
      employee.employeeId,
      `${employee.displayName}（${employee.employeeNumber}）`,
    ]))
    : new Map<string, string>();
  useEffect(() => {
    if (!selectedLocationId && firstLocationId) {
      setSelectedLocationId(firstLocationId);
    }
  }, [firstLocationId, selectedLocationId]);
  useEffect(() => {
    if (!selectedGroupId && firstGroupId) {
      setSelectedGroupId(firstGroupId);
    }
  }, [firstGroupId, selectedGroupId]);
  useEffect(() => {
    setLocationRevisionPage(0);
  }, [effectiveLocationId]);
  useEffect(() => {
    setGroupRevisionPage(0);
    setAssignmentPage(0);
  }, [effectiveGroupId]);
  useEffect(() => {
    if (overview.resource.status !== 'ready') return;
    const [locationsPage, groupsPage] = overview.resource.data;
    if (locationsPage.total > 0 && locationsPage.items.length === 0) {
      setLocationPage(lastPage(locationsPage.total, locationsPage.size));
    }
    if (groupsPage.total > 0 && groupsPage.items.length === 0) {
      setGroupPage(lastPage(groupsPage.total, groupsPage.size));
    }
  }, [overview.resource]);
  useEffect(() => {
    if (
      locationRevisions.resource.status === 'ready'
      && locationRevisions.resource.data.total > 0
      && locationRevisions.resource.data.items.length === 0
    ) {
      setLocationRevisionPage(lastPage(
        locationRevisions.resource.data.total,
        locationRevisions.resource.data.size,
      ));
    }
  }, [locationRevisions.resource]);
  useEffect(() => {
    if (
      groupRevisions.resource.status === 'ready'
      && groupRevisions.resource.data.total > 0
      && groupRevisions.resource.data.items.length === 0
    ) {
      setGroupRevisionPage(lastPage(
        groupRevisions.resource.data.total,
        groupRevisions.resource.data.size,
      ));
    }
  }, [groupRevisions.resource]);
  useEffect(() => {
    if (
      assignments.resource.status === 'ready'
      && assignments.resource.data.total > 0
      && assignments.resource.data.items.length === 0
    ) {
      setAssignmentPage(lastPage(
        assignments.resource.data.total,
        assignments.resource.data.size,
      ));
    }
  }, [assignments.resource]);
  const canManageGroups = capabilities.includes('ATTENDANCE_SETUP:MANAGE_GROUP');
  const canAssign = capabilities.includes('ATTENDANCE_SETUP:ASSIGN');

  const runMutation = async (
    operation: () => Promise<unknown>,
    successMessage: string,
    close: () => void,
  ) => {
    setProcessing(true);
    setNotice(undefined);
    try {
      const response = await operation();
      setNotice(mutationSuccessNotice(response, successMessage));
      close();
      overview.reload();
      locationRevisions.reload();
      groupRevisions.reload();
      assignments.reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const submitLocation = (input: LocationInput) => {
    void runMutation(
      () => editingLocation
        ? updateLocation(editingLocation.locationId, editingLocation.rowVersion, input)
        : createLocation(input),
      editingLocation
        ? t('attendanceSetup.locationRolledOver')
        : t('attendanceSetup.locationCreated'),
      () => {
        setLocationOpen(false);
        setEditingLocation(undefined);
      },
    );
  };
  const submitGroup = (input: AttendanceGroupInput) => {
    void runMutation(
      () => editingGroup
        ? updateAttendanceGroup(editingGroup.groupId, editingGroup.rowVersion, input)
        : createAttendanceGroup(input),
      editingGroup
        ? t('attendanceSetup.groupRolledOver')
        : t('attendanceSetup.groupCreated'),
      () => {
        setGroupOpen(false);
        setEditingGroup(undefined);
      },
    );
  };
  const submitAssignment = (input: AssignmentInput) => {
    if (!effectiveGroupId) return;
    void runMutation(
      () => editingAssignment
        ? updateAssignment(
          effectiveGroupId,
          editingAssignment.assignmentId,
          editingAssignment.rowVersion,
          input,
        )
        : createAssignment(effectiveGroupId, input),
      editingAssignment
        ? t('attendanceSetup.assignmentRolledOver')
        : t('attendanceSetup.assignmentCreated'),
      () => {
        setAssignmentOpen(false);
        setEditingAssignment(undefined);
      },
    );
  };
  const confirmStatus = () => {
    if (!statusTarget || statusReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    const action = statusTarget.value.status === 'ACTIVE' ? 'deactivate' : 'activate';
    void runMutation(
      () => statusTarget.type === 'location'
        ? changeLocationStatus(statusTarget.value, action, statusReason.trim())
        : changeAttendanceGroupStatus(statusTarget.value, action, statusReason.trim()),
      t('attendanceSetup.statusChanged'),
      () => {
        setStatusTarget(undefined);
        setStatusReason('');
      },
    );
  };
  const openLocationDialog = () => {
    setEditingLocation(undefined);
    setLocationOpen(true);
  };
  const closeLocationDialog = () => {
    setLocationOpen(false);
    setEditingLocation(undefined);
  };
  const openGroupDialog = () => {
    setEditingGroup(undefined);
    setGroupOpen(true);
  };
  const closeGroupDialog = () => {
    setGroupOpen(false);
    setEditingGroup(undefined);
  };
  const openAssignmentDialog = () => {
    setEditingAssignment(undefined);
    setAssignmentOpen(true);
  };
  const closeAssignmentDialog = () => {
    setAssignmentOpen(false);
    setEditingAssignment(undefined);
  };
  const changeAsOf = (event: ChangeEvent<HTMLInputElement>) => {
    setAsOf(event.target.value);
    setGroupPage(0);
    setAssignmentPage(0);
  };
  const selectLocation = (locationId: string) => () => {
    setSelectedLocationId(locationId);
    setLocationRevisionPage(0);
  };
  const selectGroup = (groupId: string) => () => {
    setSelectedGroupId(groupId);
    setGroupRevisionPage(0);
    setAssignmentPage(0);
  };
  const editLocation = (location: LocationView) => () => {
    setEditingLocation(location);
    setLocationOpen(true);
  };
  const editGroup = (group: AttendanceGroupView) => () => {
    setEditingGroup(group);
    setGroupOpen(true);
  };
  const editAssignment = (assignment: AssignmentView) => () => {
    setEditingAssignment(assignment);
    setAssignmentOpen(true);
  };
  const chooseLocationStatus = (location: LocationView) => () => {
    setStatusTarget({ type: 'location', value: location });
  };
  const chooseGroupStatus = (group: AttendanceGroupView) => () => {
    setStatusTarget({ type: 'group', value: group });
  };
  const closeStatusDialog = () => {
    setStatusTarget(undefined);
    setStatusReason('');
  };
  const changeStatusReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setStatusReason(event.target.value);
  };

  return (
    <>
      <PageHeader
        title={t('attendanceSetup.groups')}
        description={t('attendanceSetup.groupsDescription')}
        breadcrumbs={[
          { label: t('attendanceSetup.home'), path: '/rules/attendance-groups' },
          { label: t('attendanceSetup.groups') },
        ]}
        actions={canManageGroups ? (
          <>
            <AccessibleButton
              label={t('attendanceSetup.createLocation')}
              icon={<IconMapPin aria-hidden="true" stroke={2} />}
              onClick={openLocationDialog}
            >
              {t('attendanceSetup.createLocation')}
            </AccessibleButton>
            <AccessibleButton
              label={t('attendanceSetup.createGroup')}
              type="primary"
              onClick={openGroupDialog}
            >
              {t('attendanceSetup.createGroup')}
            </AccessibleButton>
          </>
        ) : undefined}
      />
      <AttendanceSetupNotice notice={notice} />
      <section className="attendance-context-bar" aria-label={t('attendanceSetup.queryAsOf')}>
        <IconCalendarTime aria-hidden="true" stroke={2} />
        <label htmlFor="attendance-as-of">{t('attendanceSetup.asOf')}</label>
        <Input
          id="attendance-as-of"
          type="date"
          value={asOf}
          onChange={changeAsOf}
        />
        <AccessibleButton label={t('common.refresh')} onClick={overview.reload}>
          {t('common.refresh')}
        </AccessibleButton>
      </section>
      {overview.resource.status === 'loading' || overview.resource.status === 'partial-loading'
        ? <StatePanel state={overview.resource.status} />
        : null}
      {'error' in overview.resource ? (
        <StatePanel
          state={overview.resource.status}
          description={overview.resource.error.message}
          onRetry={overview.reload}
        />
      ) : null}
      {overview.resource.status === 'empty' ? <StatePanel state="empty" /> : null}
      {overview.resource.status === 'ready' ? (
        <>
          <section className="content-surface attendance-section">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.locationSection')}</h2>
                <p>{t('attendanceSetup.locationSectionDescription')}</p>
              </div>
            </div>
            {overview.resource.data[0].items.length === 0
              ? <StatePanel state="empty" description={t('attendanceSetup.noLocations')} />
              : (
                <DataTable
                  rows={overview.resource.data[0].items}
                  rowKey={(location) => location.locationId}
                  ariaLabel={t('attendanceSetup.locationSection')}
                  columns={[
                    { key: 'code', title: t('attendanceSetup.code'), render: (location) => location.code },
                    {
                      key: 'name',
                      title: t('attendanceSetup.name'),
                      render: (location) => (
                        <AccessibleButton
                          label={`${location.code} ${location.name}`}
                          type={location.locationId === effectiveLocationId ? 'primary' : 'link'}
                          onClick={selectLocation(location.locationId)}
                        >
                          {location.name}
                        </AccessibleButton>
                      ),
                    },
                    { key: 'timezone', title: t('attendanceSetup.timeZone'), render: (location) => location.timeZone },
                    { key: 'revision', title: t('attendanceSetup.revision'), render: (location) => location.revisionNumber },
                    { key: 'period', title: t('attendanceSetup.period'), render: (location) => formatPeriod(location.effectiveFrom, location.effectiveTo, t('attendanceSetup.longTerm')) },
                    { key: 'status', title: t('attendanceSetup.status'), render: (location) => <StatusBadge status={location.status} /> },
                    ...(canManageGroups ? [{
                      key: 'actions',
                      title: t('common.actions'),
                      render: (location: LocationView) => (
                        <div className="table-actions">
                          <AccessibleButton
                            label={`${t('attendanceSetup.rolloverLocation')} ${location.name}`}
                            type="text"
                            icon={<IconGitBranch aria-hidden="true" stroke={2} />}
                            onClick={editLocation(location)}
                          >
                            {t('attendanceSetup.appendRevision')}
                          </AccessibleButton>
                          <AccessibleButton
                            label={`${location.status === 'ACTIVE'
                              ? t('attendanceSetup.deactivate')
                              : t('attendanceSetup.activate')} ${location.name}`}
                            type="text"
                            icon={location.status === 'ACTIVE'
                              ? <IconPlayerStop aria-hidden="true" stroke={2} />
                              : <IconPlayerPlay aria-hidden="true" stroke={2} />}
                            onClick={chooseLocationStatus(location)}
                          >
                            {location.status === 'ACTIVE'
                              ? t('attendanceSetup.deactivate')
                              : t('attendanceSetup.activate')}
                          </AccessibleButton>
                        </div>
                      ),
                    }] : []),
                  ]}
                />
              )}
            <ResourcePagination
              ariaLabel={t('attendanceSetup.locationPagination')}
              page={overview.resource.data[0].page}
              pageSize={overview.resource.data[0].size}
              total={overview.resource.data[0].total}
              onChange={(page, pageSize) => {
                setLocationPage(pageSize === locationPageSize ? page : 0);
                setLocationPageSize(pageSize);
              }}
            />
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.locationRevisionHistory')}</h2>
                <p>{t('attendanceSetup.locationRevisionHistoryDescription')}</p>
              </div>
              <AccessibleButton
                label={t('common.refresh')}
                disabled={!effectiveLocationId}
                onClick={locationRevisions.reload}
              >
                {t('common.refresh')}
              </AccessibleButton>
            </div>
            {!effectiveLocationId
              ? <StatePanel state="empty" description={t('attendanceSetup.selectLocation')} />
              : null}
            {effectiveLocationId && (
              locationRevisions.resource.status === 'loading'
              || locationRevisions.resource.status === 'partial-loading'
            ) ? <StatePanel state={locationRevisions.resource.status} /> : null}
            {'error' in locationRevisions.resource ? (
              <StatePanel
                state={locationRevisions.resource.status}
                description={locationRevisions.resource.error.message}
                onRetry={locationRevisions.reload}
              />
            ) : null}
            {effectiveLocationId && locationRevisions.resource.status === 'empty'
              ? <StatePanel state="empty" description={t('attendanceSetup.noLocationRevisions')} />
              : null}
            {locationRevisions.resource.status === 'ready' ? (
              <>
                <DataTable
                  rows={locationRevisions.resource.data.items}
                  rowKey={(location) => location.locationRevisionId}
                  ariaLabel={t('attendanceSetup.locationRevisionHistory')}
                  columns={[
                    { key: 'code', title: t('attendanceSetup.code'), render: (location) => location.code },
                    { key: 'name', title: t('attendanceSetup.name'), render: (location) => location.name },
                    { key: 'timezone', title: t('attendanceSetup.timeZone'), render: (location) => location.timeZone },
                    { key: 'revision', title: t('attendanceSetup.revision'), render: (location) => location.revisionNumber },
                    { key: 'period', title: t('attendanceSetup.period'), render: (location) => formatPeriod(location.effectiveFrom, location.effectiveTo, t('attendanceSetup.longTerm')) },
                    { key: 'status', title: t('attendanceSetup.status'), render: (location) => <StatusBadge status={location.status} /> },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (location) => location.changeReason },
                  ]}
                />
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.locationRevisionPagination')}
                  page={locationRevisions.resource.data.page}
                  pageSize={locationRevisions.resource.data.size}
                  total={locationRevisions.resource.data.total}
                  onChange={(page, pageSize) => {
                    setLocationRevisionPage(
                      pageSize === locationRevisionPageSize ? page : 0,
                    );
                    setLocationRevisionPageSize(pageSize);
                  }}
                />
              </>
            ) : null}
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.groupSection')}</h2>
                <p>{t('attendanceSetup.groupSectionDescription')}</p>
              </div>
            </div>
            {overview.resource.data[1].items.length === 0
              ? <StatePanel state="empty" description={t('attendanceSetup.noGroups')} />
              : (
                <DataTable
                  rows={overview.resource.data[1].items}
                  rowKey={(group) => group.groupId}
                  ariaLabel={t('attendanceSetup.groupSection')}
                  columns={[
                    {
                      key: 'name',
                      title: t('attendanceSetup.name'),
                      render: (group) => (
                        <AccessibleButton
                          label={`${group.code} ${group.name}`}
                          type={group.groupId === effectiveGroupId ? 'primary' : 'link'}
                          onClick={selectGroup(group.groupId)}
                        >
                          {group.code} · {group.name}
                        </AccessibleButton>
                      ),
                    },
                    { key: 'revision', title: t('attendanceSetup.revision'), render: (group) => group.revisionNumber },
                    { key: 'period', title: t('attendanceSetup.period'), render: (group) => formatPeriod(group.effectiveFrom, group.effectiveTo, t('attendanceSetup.longTerm')) },
                    {
                      key: 'location',
                      title: '地点',
                      render: (group) => locationLabels.get(group.locationId)
                        ?? missingDirectoryLabel(locationDirectory.resource.status, '地点'),
                    },
                    { key: 'calendar', title: '工作日历', render: (group) => calendarLabels.get(group.calendarId) ?? missingDirectoryLabel(calendarDirectory.resource.status, '日历') },
                    { key: 'shift', title: '班次', render: (group) => shiftLabels.get(group.shiftTemplateId) ?? missingDirectoryLabel(shiftDirectory.resource.status, '班次') },
                    { key: 'status', title: t('attendanceSetup.status'), render: (group) => <StatusBadge status={group.status} /> },
                    ...(canManageGroups ? [{
                      key: 'actions',
                      title: t('common.actions'),
                      render: (group: AttendanceGroupView) => (
                        <div className="table-actions">
                          <AccessibleButton
                            label={`${t('attendanceSetup.rolloverGroup')} ${group.name}`}
                            type="text"
                            icon={<IconGitBranch aria-hidden="true" stroke={2} />}
                            onClick={editGroup(group)}
                          >
                            {t('attendanceSetup.appendRevision')}
                          </AccessibleButton>
                          <AccessibleButton
                            label={`${group.status === 'ACTIVE'
                              ? t('attendanceSetup.deactivate')
                              : t('attendanceSetup.activate')} ${group.name}`}
                            type="text"
                            icon={group.status === 'ACTIVE'
                              ? <IconPlayerStop aria-hidden="true" stroke={2} />
                              : <IconPlayerPlay aria-hidden="true" stroke={2} />}
                            onClick={chooseGroupStatus(group)}
                          >
                            {group.status === 'ACTIVE'
                              ? t('attendanceSetup.deactivate')
                              : t('attendanceSetup.activate')}
                          </AccessibleButton>
                        </div>
                      ),
                    }] : []),
                  ]}
                />
              )}
            <ResourcePagination
              ariaLabel={t('attendanceSetup.groupPagination')}
              page={overview.resource.data[1].page}
              pageSize={overview.resource.data[1].size}
              total={overview.resource.data[1].total}
              onChange={(page, pageSize) => {
                setGroupPage(pageSize === groupPageSize ? page : 0);
                setGroupPageSize(pageSize);
              }}
            />
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.groupRevisionHistory')}</h2>
                <p>{t('attendanceSetup.groupRevisionHistoryDescription')}</p>
              </div>
              <AccessibleButton
                label={t('common.refresh')}
                disabled={!effectiveGroupId}
                onClick={groupRevisions.reload}
              >
                {t('common.refresh')}
              </AccessibleButton>
            </div>
            {!effectiveGroupId
              ? <StatePanel state="empty" description={t('attendanceSetup.selectGroup')} />
              : null}
            {effectiveGroupId && (
              groupRevisions.resource.status === 'loading'
              || groupRevisions.resource.status === 'partial-loading'
            ) ? <StatePanel state={groupRevisions.resource.status} /> : null}
            {'error' in groupRevisions.resource ? (
              <StatePanel
                state={groupRevisions.resource.status}
                description={groupRevisions.resource.error.message}
                onRetry={groupRevisions.reload}
              />
            ) : null}
            {effectiveGroupId && groupRevisions.resource.status === 'empty'
              ? <StatePanel state="empty" description={t('attendanceSetup.noGroupRevisions')} />
              : null}
            {groupRevisions.resource.status === 'ready' ? (
              <>
                <DataTable
                  rows={groupRevisions.resource.data.items}
                  rowKey={(group) => group.groupRevisionId}
                  ariaLabel={t('attendanceSetup.groupRevisionHistory')}
                  columns={[
                    { key: 'code', title: t('attendanceSetup.code'), render: (group) => group.code },
                    { key: 'name', title: t('attendanceSetup.name'), render: (group) => group.name },
                    { key: 'revision', title: t('attendanceSetup.revision'), render: (group) => group.revisionNumber },
                    { key: 'period', title: t('attendanceSetup.period'), render: (group) => formatPeriod(group.effectiveFrom, group.effectiveTo, t('attendanceSetup.longTerm')) },
                    { key: 'location', title: '地点', render: (group) => locationLabels.get(group.locationId) ?? missingDirectoryLabel(locationDirectory.resource.status, '地点') },
                    { key: 'calendar', title: '工作日历', render: (group) => calendarLabels.get(group.calendarId) ?? missingDirectoryLabel(calendarDirectory.resource.status, '日历') },
                    { key: 'shift', title: '班次', render: (group) => shiftLabels.get(group.shiftTemplateId) ?? missingDirectoryLabel(shiftDirectory.resource.status, '班次') },
                    { key: 'status', title: t('attendanceSetup.status'), render: (group) => <StatusBadge status={group.status} /> },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (group) => group.changeReason },
                  ]}
                />
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.groupRevisionPagination')}
                  page={groupRevisions.resource.data.page}
                  pageSize={groupRevisions.resource.data.size}
                  total={groupRevisions.resource.data.total}
                  onChange={(page, pageSize) => {
                    setGroupRevisionPage(
                      pageSize === groupRevisionPageSize ? page : 0,
                    );
                    setGroupRevisionPageSize(pageSize);
                  }}
                />
              </>
            ) : null}
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.assignmentSection')}</h2>
                <p>{t('attendanceSetup.assignmentSectionDescription')}</p>
              </div>
              {canAssign && effectiveGroupId ? (
                <AccessibleButton
                  label={t('attendanceSetup.createAssignment')}
                  type="primary"
                  icon={<IconUserPlus aria-hidden="true" stroke={2} />}
                  onClick={openAssignmentDialog}
                >
                  {t('attendanceSetup.createAssignment')}
                </AccessibleButton>
              ) : null}
            </div>
            {!effectiveGroupId ? <StatePanel state="empty" description={t('attendanceSetup.selectGroup')} /> : null}
            {effectiveGroupId && (assignments.resource.status === 'loading' || assignments.resource.status === 'partial-loading')
              ? <StatePanel state={assignments.resource.status} />
              : null}
            {'error' in assignments.resource ? (
              <StatePanel
                state={assignments.resource.status}
                description={assignments.resource.error.message}
                onRetry={assignments.reload}
              />
            ) : null}
            {effectiveGroupId && assignments.resource.status === 'empty'
              ? <StatePanel state="empty" description={t('attendanceSetup.noAssignments')} />
              : null}
            {assignments.resource.status === 'ready' ? (
              <>
                <DataTable
                  rows={assignments.resource.data.items}
                  rowKey={(assignment) => assignment.assignmentId}
                  ariaLabel={t('attendanceSetup.assignmentSection')}
                  columns={[
                    { key: 'employee', title: '员工', render: (assignment) => employeeLabels.get(assignment.employeeId) ?? missingDirectoryLabel(employeeDirectory.resource.status, '员工') },
                    { key: 'period', title: t('attendanceSetup.period'), render: (assignment) => formatPeriod(assignment.effectiveFrom, assignment.effectiveTo, t('attendanceSetup.longTerm')) },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (assignment) => assignment.changeReason },
                    ...(canAssign ? [{
                      key: 'actions',
                      title: t('common.actions'),
                      render: (assignment: AssignmentView) => (
                        <AccessibleButton
                          label={`${t('attendanceSetup.rolloverAssignment')} ${employeeLabels.get(assignment.employeeId) ?? '员工'}`}
                          type="text"
                          icon={<IconGitBranch aria-hidden="true" stroke={2} />}
                          onClick={editAssignment(assignment)}
                        >
                          {t('attendanceSetup.appendSuccessor')}
                        </AccessibleButton>
                      ),
                    }] : []),
                  ]}
                />
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.assignmentPagination')}
                  page={assignments.resource.data.page}
                  pageSize={assignments.resource.data.size}
                  total={assignments.resource.data.total}
                  onChange={(page, pageSize) => {
                    setAssignmentPage(pageSize === assignmentPageSize ? page : 0);
                    setAssignmentPageSize(pageSize);
                  }}
                />
              </>
            ) : null}
          </section>
        </>
      ) : null}
      <LocationDialog
        key={editingLocation?.locationRevisionId ?? 'create-location'}
        open={locationOpen}
        processing={processing}
        initialValues={editingLocation ? {
          companyId: editingLocation.companyId,
          code: editingLocation.code,
          name: editingLocation.name,
          timeZone: editingLocation.timeZone,
          effectiveFrom: suggestedSuccessorDate(editingLocation.effectiveFrom),
          effectiveTo: editingLocation.effectiveTo,
          reason: '',
        } : undefined}
        onSubmit={submitLocation}
        onCancel={closeLocationDialog}
      />
      <AttendanceGroupDialog
        key={editingGroup?.groupRevisionId ?? 'create-group'}
        open={groupOpen}
        processing={processing}
        initialValues={editingGroup ? {
          companyId: editingGroup.companyId,
          code: editingGroup.code,
          name: editingGroup.name,
          locationId: editingGroup.locationId,
          calendarId: editingGroup.calendarId,
          shiftTemplateId: editingGroup.shiftTemplateId,
          effectiveFrom: suggestedSuccessorDate(editingGroup.effectiveFrom),
          effectiveTo: editingGroup.effectiveTo,
          reason: '',
        } : undefined}
        locations={overview.resource.status === 'ready' ? overview.resource.data[0].items : []}
        onSubmit={submitGroup}
        onCancel={closeGroupDialog}
      />
      <AssignmentDialog
        key={editingAssignment?.assignmentId ?? 'create-assignment'}
        open={assignmentOpen}
        processing={processing}
        initialValues={editingAssignment ? {
          employeeId: editingAssignment.employeeId,
          effectiveFrom: suggestedSuccessorDate(editingAssignment.effectiveFrom),
          effectiveTo: editingAssignment.effectiveTo,
          reason: '',
        } : undefined}
        onSubmit={submitAssignment}
        onCancel={closeAssignmentDialog}
      />
      <ConfirmationDialog
        open={Boolean(statusTarget)}
        title={t('attendanceSetup.confirmStatus', {
          action: statusTarget?.value.status === 'ACTIVE'
            ? t('attendanceSetup.deactivate')
            : t('attendanceSetup.activate'),
          name: statusTarget?.value.name ?? '',
        })}
        description={(
          <Input.TextArea
            aria-label={t('attendanceSetup.reason')}
            value={statusReason}
            placeholder={t('attendanceSetup.reasonRequired')}
            onChange={changeStatusReason}
          />
        )}
        processing={processing}
        danger={statusTarget?.value.status === 'ACTIVE'}
        onConfirm={confirmStatus}
        onCancel={closeStatusDialog}
      />
    </>
  );
}

export default AttendanceGroupsPage;

function formatPeriod(
  effectiveFrom: string,
  effectiveTo: string | null | undefined,
  longTerm: string,
): string {
  return `${effectiveFrom} → ${effectiveTo ?? longTerm}`;
}

function suggestedSuccessorDate(currentEffectiveFrom: string, now = new Date()): string {
  const [year, month, day] = currentEffectiveFrom.split('-').map(Number);
  const dayAfterCurrent = new Date(year ?? 0, (month ?? 1) - 1, (day ?? 0) + 1);
  const tomorrow = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() + 1,
  );
  const candidate = dayAfterCurrent > tomorrow ? dayAfterCurrent : tomorrow;
  return localDate(candidate);
}

function localDate(value: Date): string {
  return [
    String(value.getFullYear()).padStart(4, '0'),
    String(value.getMonth() + 1).padStart(2, '0'),
    String(value.getDate()).padStart(2, '0'),
  ].join('-');
}

function lastPage(total: number, pageSize: number): number {
  return Math.max(0, Math.ceil(total / Math.max(1, pageSize)) - 1);
}

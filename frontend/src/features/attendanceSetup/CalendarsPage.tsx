import {
  IconCalendarPlus,
  IconCalendarStats,
  IconEdit,
  IconPlayerStop,
  IconUpload,
  IconVersions,
} from '@tabler/icons-react';
import { Alert, Input, InputNumber } from 'antd';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';

import {
  ConfirmationDialog,
  StatusBadge,
} from '../../shared/components/FeedbackComponents';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  createCalendar,
  createCalendarVersion,
  changeCalendarStatus,
  deactivateCalendarVersion,
  listCalendarVersionDays,
  listCalendarVersions,
  listCalendars,
  patchCalendarVersionDays,
  publishCalendar,
  publishCalendarVersion,
  updateCalendar,
  updateCalendarVersion,
} from './attendanceSetupApi';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import { CalendarDaysDialog, CalendarDialog } from './CalendarDialogs';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
  type AttendanceSetupNotice as Notice,
} from './attendanceSetupFeedback';
import type {
  CalendarDayInput,
  WorkCalendarInput,
  WorkCalendarVersionInput,
  WorkCalendarView,
} from './attendanceSetupTypes';

interface DateRange {
  from: string;
  to: string;
}

export function CalendarsPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const currentYear = new Date().getFullYear();
  const [year, setYear] = useState(currentYear);
  const [selectedCalendarId, setSelectedCalendarId] = useState('');
  const [selectedCalendarVersionId, setSelectedCalendarVersionId] = useState('');
  const [draftRange, setDraftRange] = useState<DateRange>({
    from: `${currentYear}-12-29`,
    to: `${currentYear}-12-31`,
  });
  const [range, setRange] = useState<DateRange>(draftRange);
  const [calendarOpen, setCalendarOpen] = useState(false);
  const [calendarDialogIntent, setCalendarDialogIntent] = useState<
    'create-family' | 'update-family' | 'create-version' | 'revise-version'
  >('create-family');
  const [editingCalendar, setEditingCalendar] = useState<WorkCalendarView>();
  const [lifecycleTarget, setLifecycleTarget] = useState<WorkCalendarView>();
  const [lifecycleScope, setLifecycleScope] = useState<'family' | 'version'>(
    'version',
  );
  const [lifecycleReason, setLifecycleReason] = useState('');
  const [lifecycleEffectiveFrom, setLifecycleEffectiveFrom] = useState(utcTomorrow);
  const [daysOpen, setDaysOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [notice, setNotice] = useState<Notice>();
  const [calendarPage, setCalendarPage] = useState(0);
  const [calendarPageSize, setCalendarPageSize] = useState(20);
  const [versionPage, setVersionPage] = useState(0);
  const [versionPageSize, setVersionPageSize] = useState(20);
  const [dayPage, setDayPage] = useState(0);
  const [dayPageSize, setDayPageSize] = useState(100);
  const calendarLoader = useMemo(
    () => () => listCalendars(year, calendarPage, calendarPageSize),
    [calendarPage, calendarPageSize, year],
  );
  const calendars = useAsyncResource(
    calendarLoader,
    (page) => page.total === 0,
    [calendarPage, calendarPageSize, year],
  );
  const firstCalendarId = calendars.resource.status === 'ready'
    ? calendars.resource.data.items[0]?.calendarId ?? ''
    : '';
  const effectiveCalendarId = selectedCalendarId || firstCalendarId;
  const selectedFamily = calendars.resource.status === 'ready'
    ? calendars.resource.data.items.find((item) => item.calendarId === effectiveCalendarId)
    : undefined;
  const versionLoader = useMemo(
    () => () => effectiveCalendarId
      ? listCalendarVersions(effectiveCalendarId, versionPage, versionPageSize)
      : Promise.resolve({
        items: [],
        total: 0,
        page: versionPage,
        size: versionPageSize,
      }),
    [effectiveCalendarId, versionPage, versionPageSize],
  );
  const versions = useAsyncResource(
    versionLoader,
    (page) => page.total === 0,
    [effectiveCalendarId, versionPage, versionPageSize],
  );
  const matchingVersionItems = versions.resource.status === 'ready'
    && versions.resource.data.items.every(
      (item) => item.calendarId === effectiveCalendarId,
    )
    ? versions.resource.data.items
    : undefined;
  const latestCalendarVersionId = matchingVersionItems
    ? matchingVersionItems.reduce<WorkCalendarView | undefined>(
      (latest, item) => !latest || item.versionNumber > latest.versionNumber ? item : latest,
      undefined,
    )?.calendarVersionId ?? ''
    : '';
  const effectiveCalendarVersionId = selectedCalendarVersionId || latestCalendarVersionId;
  const selectedCalendar = matchingVersionItems
    ? matchingVersionItems.find(
      (item) => item.calendarVersionId === effectiveCalendarVersionId,
    )
    : undefined;
  const dayLoader = useMemo(
    () => () => effectiveCalendarId && effectiveCalendarVersionId
      ? listCalendarVersionDays(
        effectiveCalendarId,
        effectiveCalendarVersionId,
        range.from,
        range.to,
        dayPage,
        dayPageSize,
      )
      : Promise.resolve({
        items: [],
        total: 0,
        page: dayPage,
        size: dayPageSize,
      }),
    [
      dayPage,
      dayPageSize,
      effectiveCalendarId,
      effectiveCalendarVersionId,
      range,
    ],
  );
  const days = useAsyncResource(dayLoader, (page) => page.total === 0, [
    dayPage,
    dayPageSize,
    effectiveCalendarId,
    effectiveCalendarVersionId,
    range.from,
    range.to,
  ]);
  useEffect(() => {
    if (!selectedCalendarId && firstCalendarId) {
      setSelectedCalendarId(firstCalendarId);
    }
  }, [firstCalendarId, selectedCalendarId]);
  useEffect(() => {
    if (!selectedCalendarVersionId && latestCalendarVersionId) {
      setSelectedCalendarVersionId(latestCalendarVersionId);
    }
  }, [latestCalendarVersionId, selectedCalendarVersionId]);
  useEffect(() => {
    setVersionPage(0);
    setDayPage(0);
  }, [effectiveCalendarId]);
  useEffect(() => {
    setDayPage(0);
  }, [effectiveCalendarVersionId]);
  useEffect(() => {
    if (
      calendars.resource.status === 'ready'
      && calendars.resource.data.total > 0
      && calendars.resource.data.items.length === 0
    ) {
      setCalendarPage(lastPage(
        calendars.resource.data.total,
        calendars.resource.data.size,
      ));
    }
  }, [calendars.resource]);
  useEffect(() => {
    if (
      versions.resource.status === 'ready'
      && versions.resource.data.total > 0
      && versions.resource.data.items.length === 0
    ) {
      setVersionPage(lastPage(
        versions.resource.data.total,
        versions.resource.data.size,
      ));
    }
  }, [versions.resource]);
  useEffect(() => {
    if (
      days.resource.status === 'ready'
      && days.resource.data.total > 0
      && days.resource.data.items.length === 0
    ) {
      setDayPage(lastPage(days.resource.data.total, days.resource.data.size));
    }
  }, [days.resource]);
  const canManage = capabilities.includes('ATTENDANCE_SETUP:MANAGE_CALENDAR');
  const coverage = days.resource.status === 'ready'
    ? calendarCoverage(days.resource.data.items, range, days.resource.data.total)
    : undefined;

  const saveCalendar = async (input: WorkCalendarInput) => {
    setProcessing(true);
    setNotice(undefined);
    try {
      const versionInput = calendarVersionInput(input);
      const response = calendarDialogIntent === 'create-family'
        ? await createCalendar(input)
        : calendarDialogIntent === 'update-family'
          ? await updateCalendar(requiredCalendar(editingCalendar), input)
        : calendarDialogIntent === 'create-version'
          ? await createCalendarVersion(effectiveCalendarId, versionInput)
          : await updateCalendarVersion(requiredCalendar(editingCalendar), versionInput);
      setCalendarOpen(false);
      setEditingCalendar(undefined);
      setSelectedCalendarId(response.calendarId);
      setSelectedCalendarVersionId(response.calendarVersionId);
      setNotice(mutationSuccessNotice(
        response,
        calendarDialogIntent === 'create-family'
          ? t('attendanceSetup.calendarCreated')
          : calendarDialogIntent === 'update-family'
            ? t('attendanceSetup.calendarUpdated')
          : calendarDialogIntent === 'create-version'
            ? t('attendanceSetup.calendarVersionCreated')
            : t('attendanceSetup.calendarVersionUpdated'),
      ));
      calendars.reload();
      versions.reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const changeLifecycle = async () => {
    if (!lifecycleTarget) return;
    if (
      lifecycleTarget.status !== 'DRAFT'
      && !isValidDeactivationDate(lifecycleEffectiveFrom, lifecycleTarget)
    ) {
      setNotice({
        kind: 'warning',
        message: t('attendanceSetup.futureLifecycleBoundaryRequired'),
      });
      return;
    }
    if (lifecycleReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    setProcessing(true);
    setNotice(undefined);
    try {
      const reason = lifecycleReason.trim();
      const response = lifecycleScope === 'family'
        ? lifecycleTarget.status === 'DRAFT'
          ? await publishCalendar(lifecycleTarget, reason)
          : await changeCalendarStatus(
            lifecycleTarget,
            'INACTIVE',
            lifecycleEffectiveFrom,
            reason,
          )
        : lifecycleTarget.status === 'DRAFT'
          ? await publishCalendarVersion(lifecycleTarget, reason)
          : await deactivateCalendarVersion(
            lifecycleTarget,
            lifecycleEffectiveFrom,
            reason,
          );
      setLifecycleTarget(undefined);
      setLifecycleScope('version');
      setLifecycleReason('');
      setLifecycleEffectiveFrom(utcTomorrow());
      setNotice(mutationSuccessNotice(response, t('attendanceSetup.statusChanged')));
      calendars.reload();
      versions.reload();
      days.reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const saveDays = async (input: CalendarDayInput[], reason: string) => {
    if (!selectedCalendar) return;
    setProcessing(true);
    setNotice(undefined);
    try {
      const response = await patchCalendarVersionDays(selectedCalendar, input, reason);
      setDaysOpen(false);
      setNotice(mutationSuccessNotice(
        response,
        t('attendanceSetup.calendarDaysSaved'),
      ));
      calendars.reload();
      versions.reload();
      days.reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const applyRange = () => {
    setDayPage(0);
    setRange(draftRange);
  };
  const openCalendarCreator = () => {
    setCalendarDialogIntent('create-family');
    setEditingCalendar(undefined);
    setCalendarOpen(true);
  };
  const openVersionCreator = () => {
    if (!selectedCalendar && !selectedFamily) return;
    setCalendarDialogIntent('create-version');
    setEditingCalendar(selectedCalendar ?? selectedFamily);
    setCalendarOpen(true);
  };
  const openCalendarFamilyEditor = (calendar: WorkCalendarView) => () => {
    setCalendarDialogIntent('update-family');
    setEditingCalendar(calendar);
    setCalendarOpen(true);
  };
  const openDaysDialog = () => setDaysOpen(true);
  const changeCalendarYear = (value: number | null) => {
    if (value === null) return;
    setYear(value);
    setSelectedCalendarId('');
    setSelectedCalendarVersionId('');
    setCalendarPage(0);
    setVersionPage(0);
    setDayPage(0);
    setDraftRange({ from: `${value}-12-29`, to: `${value}-12-31` });
    setRange({ from: `${value}-12-29`, to: `${value}-12-31` });
  };
  const selectCalendarFamily = (calendarId: string) => () => {
    setSelectedCalendarId(calendarId);
    setSelectedCalendarVersionId('');
    setVersionPage(0);
    setDayPage(0);
  };
  const selectCalendarVersion = (calendarVersionId: string) => () => {
    setSelectedCalendarVersionId(calendarVersionId);
    setDayPage(0);
  };
  const openCalendarEditor = (calendar: WorkCalendarView) => () => {
    setCalendarDialogIntent('revise-version');
    setEditingCalendar(calendar);
    setCalendarOpen(true);
  };
  const chooseLifecycleTarget = (
    scope: 'family' | 'version',
    calendar: WorkCalendarView,
  ) => () => {
    setLifecycleScope(scope);
    setLifecycleTarget(calendar);
    setLifecycleEffectiveFrom(deactivationBoundary(calendar.effectiveFrom));
  };
  const changeRangeStart = (event: ChangeEvent<HTMLInputElement>) => {
    setDraftRange({ ...draftRange, from: event.target.value });
  };
  const changeRangeEnd = (event: ChangeEvent<HTMLInputElement>) => {
    setDraftRange({ ...draftRange, to: event.target.value });
  };
  const submitCalendar = (input: WorkCalendarInput) => {
    void saveCalendar(input);
  };
  const closeCalendarDialog = () => {
    setCalendarOpen(false);
    setEditingCalendar(undefined);
    setCalendarDialogIntent('create-family');
  };
  const submitDays = (input: CalendarDayInput[], reason: string) => {
    void saveDays(input, reason);
  };
  const closeDaysDialog = () => setDaysOpen(false);
  const changeLifecycleReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setLifecycleReason(event.target.value);
  };
  const changeLifecycleEffectiveFrom = (event: ChangeEvent<HTMLInputElement>) => {
    setLifecycleEffectiveFrom(event.target.value);
  };
  const confirmLifecycleChange = () => {
    void changeLifecycle();
  };
  const closeLifecycleDialog = () => {
    setLifecycleTarget(undefined);
    setLifecycleScope('version');
    setLifecycleReason('');
    setLifecycleEffectiveFrom(utcTomorrow());
  };

  return (
    <>
      <PageHeader
        title={t('attendanceSetup.calendars')}
        description={t('attendanceSetup.calendarsDescription')}
        breadcrumbs={[
          { label: t('attendanceSetup.home'), path: '/rules/attendance-groups' },
          { label: t('attendanceSetup.calendars') },
        ]}
        actions={canManage ? (
          <>
            <AccessibleButton
              label={t('attendanceSetup.createCalendar')}
              icon={<IconCalendarPlus aria-hidden="true" stroke={2} />}
              onClick={openCalendarCreator}
            >
              {t('attendanceSetup.createCalendar')}
            </AccessibleButton>
            <AccessibleButton
              label={t('attendanceSetup.createCalendarVersion')}
              icon={<IconVersions aria-hidden="true" stroke={2} />}
              disabled={!effectiveCalendarId}
              onClick={openVersionCreator}
            >
              {t('attendanceSetup.createCalendarVersion')}
            </AccessibleButton>
            <AccessibleButton
              label={t('attendanceSetup.saveDays')}
              type="primary"
              disabled={!selectedCalendar || selectedCalendar.status !== 'DRAFT'}
              onClick={openDaysDialog}
            >
              {t('attendanceSetup.saveDays')}
            </AccessibleButton>
          </>
        ) : undefined}
      />
      <AttendanceSetupNotice notice={notice} />
      <Alert
        className="attendance-boundary-note"
        showIcon
        type="info"
        title={t('attendanceSetup.yearBoundaryTitle')}
        description={t('attendanceSetup.yearBoundaryDescription')}
      />
      <section className="attendance-context-bar" aria-label={t('attendanceSetup.queryAsOf')}>
        <IconCalendarStats aria-hidden="true" stroke={2} />
        <label htmlFor="attendance-calendar-year">{t('attendanceSetup.calendarYear')}</label>
        <InputNumber
          id="attendance-calendar-year"
          min={2000}
          max={2100}
          value={year}
          onChange={changeCalendarYear}
        />
      </section>
      {calendars.resource.status === 'loading' || calendars.resource.status === 'partial-loading'
        ? <StatePanel state={calendars.resource.status} />
        : null}
      {'error' in calendars.resource ? (
        <StatePanel
          state={calendars.resource.status}
          description={calendars.resource.error.message}
          onRetry={calendars.reload}
        />
      ) : null}
      {calendars.resource.status === 'empty'
        ? <StatePanel state="empty" description={t('attendanceSetup.noCalendars')} />
        : null}
      {calendars.resource.status === 'ready' ? (
        <>
          <section className="content-surface attendance-section">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.calendarFamilies')}</h2>
                <p>{t('attendanceSetup.calendarFamiliesDescription')}</p>
              </div>
            </div>
            <DataTable
              rows={calendars.resource.data.items}
              rowKey={(calendar) => calendar.calendarId}
              ariaLabel={t('attendanceSetup.calendarFamilies')}
              columns={[
                {
                  key: 'name',
                  title: t('attendanceSetup.name'),
                  render: (calendar) => (
                    <AccessibleButton
                      label={`${calendar.code} ${calendar.name}`}
                      type={calendar.calendarId === effectiveCalendarId
                        ? 'primary'
                        : 'link'}
                      onClick={selectCalendarFamily(calendar.calendarId)}
                    >
                      {calendar.code} · {calendar.name}
                    </AccessibleButton>
                  ),
                },
                { key: 'versionNumber', title: t('attendanceSetup.version'), render: (calendar) => calendar.versionNumber },
                { key: 'year', title: t('attendanceSetup.calendarYear'), render: (calendar) => calendar.calendarYear },
                { key: 'location', title: t('attendanceSetup.locationId'), render: (calendar) => <code>{calendar.locationId}</code> },
                { key: 'status', title: t('attendanceSetup.status'), render: (calendar) => <StatusBadge status={calendar.status} /> },
                ...(canManage ? [{
                  key: 'actions',
                  title: t('common.actions'),
                  render: (calendar: WorkCalendarView) => (
                    <div className="table-actions">
                      <AccessibleButton
                        label={`${t('attendanceSetup.updateCalendar')} ${calendar.code}`}
                        type="text"
                        icon={<IconEdit aria-hidden="true" stroke={2} />}
                        disabled={calendar.status !== 'DRAFT'}
                        onClick={openCalendarFamilyEditor(calendar)}
                      >
                        {t('common.edit')}
                      </AccessibleButton>
                      <AccessibleButton
                        label={`${calendar.status === 'DRAFT'
                          ? t('attendanceSetup.publishCalendarFamily')
                          : t('attendanceSetup.deactivateCalendarFamily')} ${calendar.code}`}
                        type="text"
                        disabled={calendar.status === 'INACTIVE'
                          || (calendar.status === 'PUBLISHED'
                            && !canFutureDeactivate(calendar))}
                        icon={calendar.status === 'DRAFT'
                          ? <IconUpload aria-hidden="true" stroke={2} />
                          : <IconPlayerStop aria-hidden="true" stroke={2} />}
                        onClick={chooseLifecycleTarget('family', calendar)}
                      >
                        {calendar.status === 'DRAFT'
                          ? t('attendanceSetup.publishCalendarFamily')
                          : t('attendanceSetup.deactivateCalendarFamily')}
                      </AccessibleButton>
                    </div>
                  ),
                }] : []),
              ]}
            />
            <ResourcePagination
              ariaLabel={t('attendanceSetup.calendarPagination')}
              page={calendars.resource.data.page}
              pageSize={calendars.resource.data.size}
              total={calendars.resource.data.total}
              onChange={(page, pageSize) => {
                setCalendarPage(pageSize === calendarPageSize ? page : 0);
                setCalendarPageSize(pageSize);
              }}
            />
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.calendarVersions')}</h2>
                <p>{t('attendanceSetup.calendarVersionsDescription')}</p>
              </div>
              <AccessibleButton label={t('common.refresh')} onClick={versions.reload}>
                {t('common.refresh')}
              </AccessibleButton>
            </div>
            {versions.resource.status === 'loading'
              || versions.resource.status === 'partial-loading'
              ? <StatePanel state={versions.resource.status} />
              : null}
            {'error' in versions.resource ? (
              <StatePanel
                state={versions.resource.status}
                description={versions.resource.error.message}
                onRetry={versions.reload}
              />
            ) : null}
            {versions.resource.status === 'empty'
              ? <StatePanel state="empty" description={t('attendanceSetup.noCalendarVersions')} />
              : null}
            {versions.resource.status === 'ready' ? (
              <>
                <DataTable
                  rows={versions.resource.data.items}
                  rowKey={(calendar) => calendar.calendarVersionId}
                  ariaLabel={t('attendanceSetup.calendarVersions')}
                  columns={[
                    {
                      key: 'versionNumber',
                      title: t('attendanceSetup.version'),
                      render: (calendar) => (
                        <AccessibleButton
                          label={`${t('attendanceSetup.version')} ${calendar.versionNumber}`}
                          type={calendar.calendarVersionId === effectiveCalendarVersionId
                            ? 'primary'
                            : 'link'}
                          onClick={selectCalendarVersion(calendar.calendarVersionId)}
                        >
                          {calendar.versionNumber}
                        </AccessibleButton>
                      ),
                    },
                    { key: 'year', title: t('attendanceSetup.calendarYear'), render: (calendar) => calendar.calendarYear },
                    { key: 'timezone', title: t('attendanceSetup.timeZone'), render: (calendar) => calendar.timeZone },
                    { key: 'period', title: t('attendanceSetup.period'), render: (calendar) => `${calendar.effectiveFrom} → ${calendar.effectiveTo}` },
                    { key: 'digest', title: t('attendanceSetup.snapshotDigest'), render: (calendar) => <code className="attendance-digest">{calendar.snapshotDigest}</code> },
                    { key: 'status', title: t('attendanceSetup.status'), render: (calendar) => <StatusBadge status={calendar.status} /> },
                    { key: 'rowVersion', title: t('attendanceSetup.rowVersion'), render: (calendar) => calendar.rowVersion },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (calendar) => calendar.changeReason },
                    ...(canManage ? [{
                      key: 'actions',
                      title: t('common.actions'),
                      render: (calendar: WorkCalendarView) => (
                        <div className="table-actions">
                          <AccessibleButton
                            label={`${t('common.edit')} ${t('attendanceSetup.version')} ${calendar.versionNumber}`}
                            type="text"
                            icon={<IconEdit aria-hidden="true" stroke={2} />}
                            disabled={calendar.status !== 'DRAFT'}
                            onClick={openCalendarEditor(calendar)}
                          >
                            {t('common.edit')}
                          </AccessibleButton>
                          <AccessibleButton
                            label={`${calendar.status === 'DRAFT'
                              ? t('attendanceSetup.publish')
                              : t('attendanceSetup.deactivate')} ${t('attendanceSetup.version')} ${calendar.versionNumber}`}
                            type="text"
                            disabled={calendar.status === 'INACTIVE'
                              || (calendar.status === 'PUBLISHED'
                                && !canFutureDeactivate(calendar))}
                            icon={calendar.status === 'DRAFT'
                              ? <IconUpload aria-hidden="true" stroke={2} />
                              : <IconPlayerStop aria-hidden="true" stroke={2} />}
                            onClick={chooseLifecycleTarget('version', calendar)}
                          >
                            {calendar.status === 'DRAFT'
                              ? t('attendanceSetup.publish')
                              : t('attendanceSetup.deactivate')}
                          </AccessibleButton>
                        </div>
                      ),
                    }] : []),
                  ]}
                />
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.calendarVersionPagination')}
                  page={versions.resource.data.page}
                  pageSize={versions.resource.data.size}
                  total={versions.resource.data.total}
                  onChange={(page, pageSize) => {
                    setVersionPage(pageSize === versionPageSize ? page : 0);
                    setVersionPageSize(pageSize);
                  }}
                />
              </>
            ) : null}
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.dayType')}</h2>
                <p>
                  {range.from} → {range.to}
                  {selectedCalendar
                    ? ` · ${t('attendanceSetup.version')} ${selectedCalendar.versionNumber}`
                    : ''}
                </p>
              </div>
            </div>
            <div className="attendance-range-controls">
              <label htmlFor="calendar-days-from">{t('attendanceSetup.fromDate')}</label>
              <Input
                id="calendar-days-from"
                type="date"
                value={draftRange.from}
                onChange={changeRangeStart}
              />
              <label htmlFor="calendar-days-to">{t('attendanceSetup.toDate')}</label>
              <Input
                id="calendar-days-to"
                type="date"
                value={draftRange.to}
                onChange={changeRangeEnd}
              />
              <AccessibleButton
                label={t('attendanceSetup.loadDays')}
                onClick={applyRange}
              >
                {t('attendanceSetup.loadDays')}
              </AccessibleButton>
            </div>
            {days.resource.status === 'loading' || days.resource.status === 'partial-loading'
              ? <StatePanel state={days.resource.status} />
              : null}
            {'error' in days.resource ? (
              <StatePanel
                state={days.resource.status}
                description={days.resource.error.message}
                onRetry={days.reload}
              />
            ) : null}
            {days.resource.status === 'empty'
              ? <StatePanel state="empty" description={t('attendanceSetup.noCalendarDays')} />
              : null}
            {days.resource.status === 'ready' ? (
              <>
                <Alert
                  className="attendance-boundary-note"
                  showIcon
                  type={coverage?.complete ? 'success' : 'warning'}
                  title={coverage?.complete
                    ? t('attendanceSetup.calendarCoverageComplete', {
                      count: coverage.expected,
                    })
                    : t('attendanceSetup.calendarCoverageIncomplete', {
                      expected: coverage?.expected ?? 0,
                      actual: coverage?.actual ?? 0,
                    })}
                />
                <DataTable
                  rows={days.resource.data.items}
                  rowKey={(day) => day.calendarDayId}
                  ariaLabel={t('attendanceSetup.dayType')}
                  columns={[
                    { key: 'date', title: t('attendanceSetup.fromDate'), render: (day) => day.businessDate },
                    { key: 'type', title: t('attendanceSetup.dayType'), render: (day) => dayTypeLabel(day.dayType, t) },
                    {
                      key: 'shift',
                      title: t('attendanceSetup.shiftVersionId'),
                      render: (day) => day.shiftVersionOverrideId
                        ? <code>{day.shiftVersionOverrideId}</code>
                        : t('common.none'),
                    },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (day) => day.changeReason },
                  ]}
                />
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.calendarDayPagination')}
                  page={days.resource.data.page}
                  pageSize={days.resource.data.size}
                  total={days.resource.data.total}
                  pageSizeOptions={[20, 50, 100, 200, 366]}
                  onChange={(page, pageSize) => {
                    setDayPage(pageSize === dayPageSize ? page : 0);
                    setDayPageSize(pageSize);
                  }}
                />
              </>
            ) : null}
          </section>
        </>
      ) : null}
      <CalendarDialog
        key={`${calendarDialogIntent}-${editingCalendar?.calendarId ?? 'create'}-${editingCalendar?.calendarVersionId ?? 'create'}`}
        open={calendarOpen}
        processing={processing}
        intent={calendarDialogIntent}
        calendarYear={year}
        initialValues={editingCalendar ? {
          legalEntityId: editingCalendar.legalEntityId,
          locationId: editingCalendar.locationId,
          code: editingCalendar.code,
          name: editingCalendar.name,
          calendarYear: editingCalendar.calendarYear,
          timeZone: editingCalendar.timeZone,
          effectiveFrom: editingCalendar.effectiveFrom,
          effectiveTo: editingCalendar.effectiveTo,
          reason: '',
        } : undefined}
        onSubmit={submitCalendar}
        onCancel={closeCalendarDialog}
      />
      <CalendarDaysDialog
        open={daysOpen}
        processing={processing}
        days={days.resource.status === 'ready' ? days.resource.data.items : []}
        onSubmit={submitDays}
        onCancel={closeDaysDialog}
      />
      <ConfirmationDialog
        open={Boolean(lifecycleTarget)}
        title={lifecycleScope === 'family'
          ? t('attendanceSetup.calendarFamilyLifecycle')
          : t('attendanceSetup.calendarVersionLifecycle')}
        description={(
          <div className="form-grid">
            {lifecycleTarget && lifecycleTarget.status !== 'DRAFT' ? (
              <>
                <label htmlFor="calendar-lifecycle-effective-from">
                  {t('attendanceSetup.futureLifecycleBoundary')}
                </label>
                <Input
                  id="calendar-lifecycle-effective-from"
                  type="date"
                  min={deactivationBoundary(lifecycleTarget.effectiveFrom)}
                  max={lifecycleTarget?.effectiveTo}
                  value={lifecycleEffectiveFrom}
                  onChange={changeLifecycleEffectiveFrom}
                />
              </>
            ) : null}
            <label htmlFor="calendar-lifecycle-reason">
              {t('attendanceSetup.reason')}
            </label>
            <Input.TextArea
              id="calendar-lifecycle-reason"
              value={lifecycleReason}
              onChange={changeLifecycleReason}
            />
          </div>
        )}
        confirmText={lifecycleScope === 'family'
          ? lifecycleTarget?.status === 'DRAFT'
            ? t('attendanceSetup.publishCalendarFamily')
            : t('attendanceSetup.deactivateCalendarFamily')
          : lifecycleTarget?.status === 'DRAFT'
            ? t('attendanceSetup.publish')
            : t('attendanceSetup.deactivate')}
        processing={processing}
        onConfirm={confirmLifecycleChange}
        onCancel={closeLifecycleDialog}
      />
    </>
  );
}

export default CalendarsPage;

function dayTypeLabel(dayType: string, t: (key: string) => string): string {
  if (dayType === 'WORKDAY') return t('attendanceSetup.workingDay');
  if (dayType === 'SPECIAL_WORKDAY') return t('attendanceSetup.specialWorkingDay');
  if (dayType === 'WEEKEND') return t('attendanceSetup.weekend');
  return t('attendanceSetup.holiday');
}

function calendarCoverage(
  days: Array<{ businessDate: string }>,
  range: DateRange,
  total: number,
): { actual: number; complete: boolean; expected: number } {
  const start = Date.parse(`${range.from}T00:00:00Z`);
  const end = Date.parse(`${range.to}T00:00:00Z`);
  const expected = Number.isFinite(start) && Number.isFinite(end) && end >= start
    ? Math.floor((end - start) / 86_400_000) + 1
    : 0;
  const dates = days.map((day) => day.businessDate);
  const uniqueDates = new Set(dates);
  const everyDateInRange = dates.every(
    (date) => date >= range.from && date <= range.to,
  );
  return {
    actual: total,
    expected,
    complete: expected > 0
      && uniqueDates.size === dates.length
      && total === expected
      && everyDateInRange,
  };
}

function calendarVersionInput(input: WorkCalendarInput): WorkCalendarVersionInput {
  return {
    name: input.name,
    calendarYear: input.calendarYear,
    timeZone: input.timeZone,
    effectiveFrom: input.effectiveFrom,
    effectiveTo: input.effectiveTo,
    reason: input.reason,
  };
}

function requiredCalendar(calendar?: WorkCalendarView): WorkCalendarView {
  if (!calendar) {
    throw new Error('ATTENDANCE_CALENDAR_VERSION_REQUIRED');
  }
  return calendar;
}

function deactivationBoundary(effectiveFrom: string): string {
  return maxDate(utcTomorrow(), dayAfter(effectiveFrom));
}

function isValidDeactivationDate(
  value: string,
  calendar: WorkCalendarView,
): boolean {
  return value >= deactivationBoundary(calendar.effectiveFrom)
    && value <= calendar.effectiveTo;
}

function canFutureDeactivate(calendar: WorkCalendarView): boolean {
  return deactivationBoundary(calendar.effectiveFrom) <= calendar.effectiveTo;
}

function utcTomorrow(now = new Date()): string {
  return utcDate(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate() + 1,
  );
}

function dayAfter(value: string): string {
  const [year, month, day] = value.split('-').map(Number);
  return utcDate(year ?? 0, (month ?? 1) - 1, (day ?? 0) + 1);
}

function utcDate(year: number, zeroBasedMonth: number, day: number): string {
  return new Date(Date.UTC(year, zeroBasedMonth, day)).toISOString().slice(0, 10);
}

function maxDate(first: string, second: string): string {
  return first >= second ? first : second;
}

function lastPage(total: number, pageSize: number): number {
  return Math.max(0, Math.ceil(total / Math.max(1, pageSize)) - 1);
}

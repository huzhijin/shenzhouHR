import {
  IconClockPlus,
  IconEdit,
  IconPlayerPlay,
  IconPlayerStop,
  IconVersions,
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
import {
  createShift,
  createShiftVersion,
  changeShiftStatus,
  changeShiftVersionStatus,
  listShifts,
  listShiftVersions,
  publishShiftVersion,
  updateShift,
  updateShiftVersion,
} from './attendanceSetupApi';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
  type AttendanceSetupNotice as Notice,
} from './attendanceSetupFeedback';
import { ShiftTemplateDialog, ShiftVersionDialog } from './ShiftDialogs';
import type {
  ShiftTemplateInput,
  ShiftTemplateView,
  ShiftVersionInput,
  ShiftVersionView,
} from './attendanceSetupTypes';

export function ShiftsPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [selectedShiftId, setSelectedShiftId] = useState('');
  const [templateOpen, setTemplateOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [editingShift, setEditingShift] = useState<ShiftTemplateView>();
  const [editingVersion, setEditingVersion] = useState<ShiftVersionView>();
  const [publishTarget, setPublishTarget] = useState<ShiftVersionView>();
  const [publishReason, setPublishReason] = useState('');
  const [statusTarget, setStatusTarget] = useState<
    { type: 'shift'; value: ShiftTemplateView }
    | { type: 'version'; value: ShiftVersionView }
  >();
  const [statusReason, setStatusReason] = useState('');
  const [statusEffectiveFrom, setStatusEffectiveFrom] = useState(utcTomorrow);
  const [processing, setProcessing] = useState(false);
  const [notice, setNotice] = useState<Notice>();
  const [shiftPage, setShiftPage] = useState(0);
  const [shiftPageSize, setShiftPageSize] = useState(20);
  const [versionPage, setVersionPage] = useState(0);
  const [versionPageSize, setVersionPageSize] = useState(20);
  const shifts = useAsyncResource(
    () => listShifts(shiftPage, shiftPageSize),
    (page) => page.total === 0,
    [shiftPage, shiftPageSize],
  );
  const firstShiftId = shifts.resource.status === 'ready'
    ? shifts.resource.data.items[0]?.shiftId ?? ''
    : '';
  const effectiveShiftId = selectedShiftId || firstShiftId;
  const versionLoader = useMemo(
    () => () => effectiveShiftId
      ? listShiftVersions(effectiveShiftId, versionPage, versionPageSize)
      : Promise.resolve({
        items: [],
        total: 0,
        page: versionPage,
        size: versionPageSize,
      }),
    [effectiveShiftId, versionPage, versionPageSize],
  );
  const versions = useAsyncResource(
    versionLoader,
    (page) => page.total === 0,
    [effectiveShiftId, versionPage, versionPageSize],
  );
  useEffect(() => {
    if (!selectedShiftId && firstShiftId) {
      setSelectedShiftId(firstShiftId);
    }
  }, [firstShiftId, selectedShiftId]);
  useEffect(() => {
    setVersionPage(0);
  }, [effectiveShiftId]);
  useEffect(() => {
    if (
      shifts.resource.status === 'ready'
      && shifts.resource.data.total > 0
      && shifts.resource.data.items.length === 0
    ) {
      setShiftPage(lastPage(shifts.resource.data.total, shifts.resource.data.size));
    }
  }, [shifts.resource]);
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
  const canManage = capabilities.includes('ATTENDANCE_SETUP:MANAGE_SHIFT');

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
      shifts.reload();
      versions.reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const submitTemplate = (input: ShiftTemplateInput) => {
    void runMutation(
      () => editingShift ? updateShift(editingShift, input) : createShift(input),
      editingShift
        ? t('attendanceSetup.shiftUpdated')
        : t('attendanceSetup.shiftCreated'),
      () => {
        setTemplateOpen(false);
        setEditingShift(undefined);
      },
    );
  };
  const submitVersion = (input: ShiftVersionInput) => {
    if (!effectiveShiftId) return;
    void runMutation(
      () => editingVersion
        ? updateShiftVersion(effectiveShiftId, editingVersion, normalizeTimes(input))
        : createShiftVersion(effectiveShiftId, normalizeTimes(input)),
      editingVersion
        ? t('attendanceSetup.shiftVersionUpdated')
        : t('attendanceSetup.shiftVersionCreated'),
      () => {
        setVersionOpen(false);
        setEditingVersion(undefined);
      },
    );
  };

  const changeStatus = () => {
    if (!statusTarget) return;
    if (
      statusTarget.type === 'version'
      && !isValidDeactivationDate(statusEffectiveFrom, statusTarget.value)
    ) {
      setNotice({
        kind: 'warning',
        message: t('attendanceSetup.futureLifecycleBoundaryRequired'),
      });
      return;
    }
    if (statusReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    if (statusTarget.type === 'shift') {
      const targetStatus = statusTarget.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
      void runMutation(
        () => changeShiftStatus(
          statusTarget.value,
          targetStatus,
          statusReason.trim(),
        ),
        t('attendanceSetup.statusChanged'),
        closeStatusDialog,
      );
      return;
    }
    if (!effectiveShiftId) return;
    if (statusTarget.value.status !== 'PUBLISHED') return;
    void runMutation(
      () => changeShiftVersionStatus(
        effectiveShiftId,
        statusTarget.value,
        'INACTIVE',
        statusEffectiveFrom,
        statusReason.trim(),
      ),
      t('attendanceSetup.statusChanged'),
      closeStatusDialog,
    );
  };

  const closeStatusDialog = () => {
    setStatusTarget(undefined);
    setStatusReason('');
    setStatusEffectiveFrom(utcTomorrow());
  };
  const publish = () => {
    if (!effectiveShiftId || !publishTarget) return;
    if (publishReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    void runMutation(
      () => publishShiftVersion(effectiveShiftId, publishTarget, publishReason.trim()),
      t('attendanceSetup.shiftVersionPublished'),
      () => {
        setPublishTarget(undefined);
        setPublishReason('');
      },
    );
  };
  const openShiftCreator = () => {
    setEditingShift(undefined);
    setTemplateOpen(true);
  };
  const openVersionCreator = () => {
    setEditingVersion(undefined);
    setVersionOpen(true);
  };
  const selectShift = (shiftId: string) => () => {
    setSelectedShiftId(shiftId);
    setVersionPage(0);
  };
  const openShiftEditor = (shift: ShiftTemplateView) => () => {
    setEditingShift(shift);
    setTemplateOpen(true);
  };
  const chooseShiftStatus = (shift: ShiftTemplateView) => () => {
    setStatusTarget({ type: 'shift', value: shift });
    setStatusEffectiveFrom(utcTomorrow());
  };
  const openVersionEditor = (version: ShiftVersionView) => () => {
    setEditingVersion(version);
    setVersionOpen(true);
  };
  const choosePublishTarget = (version: ShiftVersionView) => () => {
    setPublishTarget(version);
  };
  const chooseVersionStatus = (version: ShiftVersionView) => () => {
    setStatusTarget({ type: 'version', value: version });
    setStatusEffectiveFrom(deactivationBoundary(version.effectiveFrom));
  };
  const closeTemplateDialog = () => {
    setTemplateOpen(false);
    setEditingShift(undefined);
  };
  const closeVersionDialog = () => {
    setVersionOpen(false);
    setEditingVersion(undefined);
  };
  const changePublishReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setPublishReason(event.target.value);
  };
  const closePublishDialog = () => setPublishTarget(undefined);
  const changeStatusReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setStatusReason(event.target.value);
  };
  const changeStatusEffectiveFrom = (event: ChangeEvent<HTMLInputElement>) => {
    setStatusEffectiveFrom(event.target.value);
  };

  return (
    <>
      <PageHeader
        title={t('attendanceSetup.shifts')}
        description={t('attendanceSetup.shiftsDescription')}
        breadcrumbs={[
          { label: t('attendanceSetup.home'), path: '/rules/attendance-groups' },
          { label: t('attendanceSetup.shifts') },
        ]}
        actions={canManage ? (
          <>
            <AccessibleButton
              label={t('attendanceSetup.createShift')}
              icon={<IconClockPlus aria-hidden="true" stroke={2} />}
              onClick={openShiftCreator}
            >
              {t('attendanceSetup.createShift')}
            </AccessibleButton>
            <AccessibleButton
              label={t('attendanceSetup.createShiftVersion')}
              type="primary"
              icon={<IconVersions aria-hidden="true" stroke={2} />}
              disabled={!effectiveShiftId}
              onClick={openVersionCreator}
            >
              {t('attendanceSetup.createShiftVersion')}
            </AccessibleButton>
          </>
        ) : undefined}
      />
      <AttendanceSetupNotice notice={notice} />
      {shifts.resource.status === 'loading' || shifts.resource.status === 'partial-loading'
        ? <StatePanel state={shifts.resource.status} />
        : null}
      {'error' in shifts.resource ? (
        <StatePanel
          state={shifts.resource.status}
          description={shifts.resource.error.message}
          onRetry={shifts.reload}
        />
      ) : null}
      {shifts.resource.status === 'empty'
        ? <StatePanel state="empty" description={t('attendanceSetup.noShifts')} />
        : null}
      {shifts.resource.status === 'ready' ? (
        <>
          <section className="content-surface attendance-section">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.shifts')}</h2>
                <p>{t('attendanceSetup.shiftsDescription')}</p>
              </div>
            </div>
            <DataTable
              rows={shifts.resource.data.items}
              rowKey={(shift) => shift.shiftId}
              ariaLabel={t('attendanceSetup.shifts')}
              columns={[
                {
                  key: 'name',
                  title: t('attendanceSetup.name'),
                  render: (shift) => (
                    <AccessibleButton
                      label={`${shift.code} ${shift.name}`}
                      type={shift.shiftId === effectiveShiftId ? 'primary' : 'link'}
                      onClick={selectShift(shift.shiftId)}
                    >
                      {shift.code} · {shift.name}
                    </AccessibleButton>
                  ),
                },
                { key: 'location', title: t('attendanceSetup.locationId'), render: (shift) => <code>{shift.locationId}</code> },
                { key: 'status', title: t('attendanceSetup.status'), render: (shift) => <StatusBadge status={shift.status} /> },
                { key: 'reason', title: t('attendanceSetup.reason'), render: (shift) => shift.changeReason },
                ...(canManage ? [{
                  key: 'actions',
                  title: t('common.actions'),
                  render: (shift: ShiftTemplateView) => (
                    <div className="table-actions">
                      <AccessibleButton
                        label={t('common.edit')}
                        type="text"
                        icon={<IconEdit aria-hidden="true" stroke={2} />}
                        onClick={openShiftEditor(shift)}
                      >
                        {t('common.edit')}
                      </AccessibleButton>
                      <AccessibleButton
                        label={shift.status === 'ACTIVE'
                          ? t('attendanceSetup.deactivate')
                          : t('attendanceSetup.activate')}
                        type="text"
                        icon={shift.status === 'ACTIVE'
                          ? <IconPlayerStop aria-hidden="true" stroke={2} />
                          : <IconPlayerPlay aria-hidden="true" stroke={2} />}
                        onClick={chooseShiftStatus(shift)}
                      >
                        {shift.status === 'ACTIVE'
                          ? t('attendanceSetup.deactivate')
                          : t('attendanceSetup.activate')}
                      </AccessibleButton>
                    </div>
                  ),
                }] : []),
              ]}
            />
            <ResourcePagination
              ariaLabel={t('attendanceSetup.shiftPagination')}
              page={shifts.resource.data.page}
              pageSize={shifts.resource.data.size}
              total={shifts.resource.data.total}
              onChange={(page, pageSize) => {
                setShiftPage(pageSize === shiftPageSize ? page : 0);
                setShiftPageSize(pageSize);
              }}
            />
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.seasonalTimeline')}</h2>
                <p>{t('attendanceSetup.seasonalTimelineDescription')}</p>
              </div>
              <AccessibleButton
                label={t('common.refresh')}
                onClick={versions.reload}
              >
                {t('common.refresh')}
              </AccessibleButton>
            </div>
            {versions.resource.status === 'loading' || versions.resource.status === 'partial-loading'
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
              ? <StatePanel state="empty" description={t('attendanceSetup.noShiftVersions')} />
              : null}
            {versions.resource.status === 'ready' ? (
              <>
                <ol className="rule-effective-timeline" aria-label={t('attendanceSetup.seasonalTimeline')}>
                  {versions.resource.data.items.map((version) => (
                    <li key={version.shiftVersionId} className="rule-effective-timeline__item">
                      <div className="rule-effective-timeline__marker" aria-hidden="true" />
                      <article>
                        <header>
                          <div>
                            <strong>{t('attendanceSetup.version')} {version.versionNumber}</strong>
                            <span>{formatPeriod(version.effectiveFrom, version.effectiveTo, t('attendanceSetup.longTerm'))}</span>
                          </div>
                          <StatusBadge status={version.status} />
                        </header>
                        <div
                          className="shift-segment-track"
                          role="region"
                          aria-label={`${t('attendanceSetup.version')} ${version.versionNumber} ${t('attendanceSetup.segmentType')}`}
                          tabIndex={0}
                        >
                          {version.segments.map((segment) => (
                            <span
                              className={`shift-segment shift-segment--${segment.segmentType.toLowerCase()}`}
                              key={`${version.shiftVersionId}-${segment.segmentType}-${segment.startDayOffset}-${segment.startLocalTime}-${segment.endDayOffset}-${segment.endLocalTime}`}
                            >
                              <strong>{segment.segmentType}</strong>
                              {segment.startDayOffset === 1 ? `${t('attendanceSetup.nextDay')} ` : ''}
                              {segment.startLocalTime.slice(0, 5)}–{segment.endLocalTime.slice(0, 5)}
                              {segment.endDayOffset === 1 ? ` · ${t('attendanceSetup.nextDay')}` : ''}
                            </span>
                          ))}
                        </div>
                        <dl className="metric-list">
                          <div>
                            <dt>{t('attendanceSetup.timeZone')}</dt>
                            <dd>{version.timeZone}</dd>
                          </div>
                          <div>
                            <dt>{t('attendanceSetup.snapshotDigest')}</dt>
                            <dd><code className="attendance-digest">{version.snapshotDigest}</code></dd>
                          </div>
                        </dl>
                        <footer>
                          <span>{version.changeReason}</span>
                          {canManage ? (
                            <div className="table-actions">
                              {version.status === 'DRAFT' ? (
                                <>
                                  <AccessibleButton
                                    label={t('common.edit')}
                                    icon={<IconEdit aria-hidden="true" stroke={2} />}
                                    onClick={openVersionEditor(version)}
                                  >
                                    {t('common.edit')}
                                  </AccessibleButton>
                                  <AccessibleButton
                                    label={t('attendanceSetup.publish')}
                                    type="primary"
                                    onClick={choosePublishTarget(version)}
                                  >
                                    {t('attendanceSetup.publish')}
                                  </AccessibleButton>
                                </>
                              ) : version.status === 'PUBLISHED' ? (
                                <AccessibleButton
                                  label={t('attendanceSetup.deactivate')}
                                  disabled={!canFutureDeactivate(version)}
                                  onClick={chooseVersionStatus(version)}
                                >
                                  {t('attendanceSetup.deactivate')}
                                </AccessibleButton>
                              ) : null}
                            </div>
                          ) : null}
                        </footer>
                      </article>
                    </li>
                  ))}
                </ol>
                <ResourcePagination
                  ariaLabel={t('attendanceSetup.shiftVersionPagination')}
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
        </>
      ) : null}
      <ShiftTemplateDialog
        key={editingShift?.shiftId ?? 'create-shift'}
        open={templateOpen}
        processing={processing}
        initialValues={editingShift ? {
          legalEntityId: editingShift.legalEntityId,
          locationId: editingShift.locationId,
          code: editingShift.code,
          name: editingShift.name,
          reason: '',
        } : undefined}
        onSubmit={submitTemplate}
        onCancel={closeTemplateDialog}
      />
      <ShiftVersionDialog
        key={editingVersion?.shiftVersionId ?? 'create-shift-version'}
        open={versionOpen}
        processing={processing}
        initialValues={editingVersion ? {
          effectiveFrom: editingVersion.effectiveFrom,
          effectiveTo: editingVersion.effectiveTo,
          segments: editingVersion.segments,
          reason: '',
        } : undefined}
        onSubmit={submitVersion}
        onCancel={closeVersionDialog}
      />
      <ConfirmationDialog
        open={Boolean(publishTarget)}
        title={t('attendanceSetup.publish')}
        description={(
          <Input.TextArea
            aria-label={t('attendanceSetup.publishReason')}
            value={publishReason}
            placeholder={t('attendanceSetup.reasonRequired')}
            onChange={changePublishReason}
          />
        )}
        confirmText={t('attendanceSetup.publish')}
        processing={processing}
        onConfirm={publish}
        onCancel={closePublishDialog}
      />
      <ConfirmationDialog
        open={Boolean(statusTarget)}
        title={statusTarget?.type === 'shift'
          ? t('attendanceSetup.shiftLifecycle')
          : t('attendanceSetup.shiftVersionLifecycle')}
        description={(
          <div className="form-grid">
            {statusTarget?.type === 'version' ? (
              <>
                <label htmlFor="shift-status-effective-from">
                  {t('attendanceSetup.futureLifecycleBoundary')}
                </label>
                <Input
                  id="shift-status-effective-from"
                  type="date"
                  min={deactivationBoundary(statusTarget.value.effectiveFrom)}
                  max={statusTarget.value.effectiveTo ?? undefined}
                  value={statusEffectiveFrom}
                  onChange={changeStatusEffectiveFrom}
                />
              </>
            ) : null}
            <label htmlFor="shift-status-reason">
              {t('attendanceSetup.reason')}
            </label>
            <Input.TextArea
              id="shift-status-reason"
              value={statusReason}
              onChange={changeStatusReason}
            />
          </div>
        )}
        confirmText={statusTarget?.value.status === 'ACTIVE'
          || statusTarget?.value.status === 'PUBLISHED'
          ? t('attendanceSetup.deactivate')
          : statusTarget?.type === 'shift'
            ? t('attendanceSetup.activate')
            : t('attendanceSetup.deactivate')}
        processing={processing}
        onConfirm={changeStatus}
        onCancel={closeStatusDialog}
      />
    </>
  );
}

export default ShiftsPage;

function normalizeTimes(input: ShiftVersionInput): ShiftVersionInput {
  return {
    ...input,
    segments: input.segments.map((segment) => ({
      ...segment,
      startLocalTime: normalizeTime(segment.startLocalTime),
      endLocalTime: normalizeTime(segment.endLocalTime),
    })),
  };
}

function normalizeTime(value: string): string {
  return value.length === 5 ? `${value}:00` : value;
}

function formatPeriod(
  effectiveFrom: string,
  effectiveTo: string | null | undefined,
  longTerm: string,
): string {
  return `${effectiveFrom} → ${effectiveTo ?? longTerm}`;
}

function deactivationBoundary(effectiveFrom: string): string {
  return maxDate(utcTomorrow(), dayAfter(effectiveFrom));
}

function isValidDeactivationDate(
  value: string,
  version: ShiftVersionView,
): boolean {
  return value >= deactivationBoundary(version.effectiveFrom)
    && (version.effectiveTo === null
      || version.effectiveTo === undefined
      || value <= version.effectiveTo);
}

function canFutureDeactivate(version: ShiftVersionView): boolean {
  return version.effectiveTo === null
    || version.effectiveTo === undefined
    || deactivationBoundary(version.effectiveFrom) <= version.effectiveTo;
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

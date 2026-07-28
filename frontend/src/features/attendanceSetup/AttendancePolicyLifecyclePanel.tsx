import { IconGitBranch, IconHistory, IconPlayerPlay, IconRefresh } from '@tabler/icons-react';
import { Alert, Input, Select } from 'antd';
import { useEffect, useMemo, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  createAttendancePolicyDraft,
  deactivateAttendancePolicyVersion,
  getAttendancePolicyVersion,
  listAttendancePolicyVersions,
  publishAttendancePolicyVersion,
  rollbackAttendancePolicyVersion,
  updateAttendancePolicyDraft,
  validateAttendancePolicyVersion,
} from './attendanceSetupApi';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
  type AttendanceSetupNotice as Notice,
} from './attendanceSetupFeedback';
import {
  isPolicyLifecycleBoundaryValid,
  policyLifecycleMinimumDate,
  utcDateAfter,
} from './attendancePolicyLifecycleDates';
import type {
  PolicyFieldDefinition,
  PolicyParameterValue,
} from './attendanceSetupTypes';

interface LifecyclePanelProps {
  templateId: string;
  legalEntityId: string;
  selectedVersionId: string;
  fields: PolicyFieldDefinition[];
  canManage: boolean;
  onSelectVersion: (versionId: string) => void;
}

type LifecycleAction = 'validate' | 'publish' | 'deactivate' | 'rollback';

export function AttendancePolicyLifecyclePanel({
  templateId,
  legalEntityId,
  selectedVersionId,
  fields,
  canManage,
  onSelectVersion,
}: LifecyclePanelProps) {
  const { t } = useTranslation();
  const [notice, setNotice] = useState<Notice>();
  const [processing, setProcessing] = useState(false);
  const [draftReason, setDraftReason] = useState('');
  const [draftEffectiveFrom, setDraftEffectiveFrom] = useState(futureDate(30));
  const [actionReason, setActionReason] = useState('');
  const [actionEffectiveFrom, setActionEffectiveFrom] = useState(utcDateAfter(1));
  const [rollbackTarget, setRollbackTarget] = useState('');
  const [parameterValues, setParameterValues] = useState<Record<string, string>>({});
  const [editEffectiveFrom, setEditEffectiveFrom] = useState('');
  const [editEffectiveTo, setEditEffectiveTo] = useState('');
  const [versionPage, setVersionPage] = useState(0);
  const [versionPageSize, setVersionPageSize] = useState(20);
  const versionsLoader = useMemo(
    () => () => templateId && legalEntityId
      ? listAttendancePolicyVersions(
        templateId,
        legalEntityId,
        versionPage,
        versionPageSize,
      )
      : Promise.resolve({
        items: [],
        total: 0,
        page: versionPage,
        size: versionPageSize,
      }),
    [legalEntityId, templateId, versionPage, versionPageSize],
  );
  const versions = useAsyncResource(
    versionsLoader,
    (page) => page.items.length === 0,
    [legalEntityId, templateId, versionPage, versionPageSize],
  );
  const effectiveVersionId = selectedVersionId
    || (versions.resource.status === 'ready'
      ? versions.resource.data.items[0]?.scopedVersionId ?? ''
      : '');
  const detailLoader = useMemo(
    () => () => templateId && legalEntityId && effectiveVersionId
      ? getAttendancePolicyVersion(templateId, legalEntityId, effectiveVersionId)
      : Promise.resolve(null),
    [effectiveVersionId, legalEntityId, templateId],
  );
  const detail = useAsyncResource(
    detailLoader,
    (value) => value === null,
    [effectiveVersionId, legalEntityId, templateId],
  );
  const selected = detail.resource.status === 'ready'
    && detail.resource.data?.scopedVersionId === effectiveVersionId
    && detail.resource.data.templateId === templateId
    && detail.resource.data.legalEntityId === legalEntityId
    ? detail.resource.data
    : undefined;

  useEffect(() => {
    if (!selected) return;
    setParameterValues(Object.fromEntries(
      selected.parameters.map((parameter) => [
        parameter.key,
        displayParameterValue(parameter.value),
      ]),
    ));
    setEditEffectiveFrom(selected.effectiveFrom);
    setEditEffectiveTo(selected.effectiveTo ?? '');
    setActionEffectiveFrom(policyLifecycleMinimumDate(selected.effectiveFrom));
  }, [selected]);

  useEffect(() => {
    setVersionPage(0);
  }, [legalEntityId, templateId]);

  const reload = () => {
    versions.reload();
    detail.reload();
  };

  const run = async (
    operation: () => Promise<unknown>,
    successMessage: string,
  ) => {
    setProcessing(true);
    setNotice(undefined);
    try {
      const response = await operation();
      setNotice(mutationSuccessNotice(response, successMessage));
      setActionReason('');
      reload();
    } catch (caught: unknown) {
      setNotice(mutationFailureNotice(caught));
    } finally {
      setProcessing(false);
    }
  };

  const createDraft = () => {
    if (!templateId || draftReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    void run(async () => {
      const created = await createAttendancePolicyDraft(templateId, legalEntityId, {
        basedOnVersionId: selected?.scopedVersionId ?? null,
        effectiveFrom: draftEffectiveFrom,
        effectiveTo: null,
        reason: draftReason.trim(),
      });
      setVersionPage(0);
      onSelectVersion(created.scopedVersionId);
      setDraftReason('');
      return created;
    }, t('attendanceSetup.policyDraftCreated'));
  };

  const saveDraft = () => {
    if (!selected || actionReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    const parameters = policyParametersForSave(fields, parameterValues);
    void run(
      () => updateAttendancePolicyDraft(selected, {
        parameters,
        effectiveFrom: editEffectiveFrom,
        effectiveTo: editEffectiveTo || null,
        reason: actionReason.trim(),
      }),
      t('attendanceSetup.policyDraftSaved'),
    );
  };

  const executeLifecycle = (action: LifecycleAction) => {
    if (!selected || actionReason.trim().length < 2) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.reasonRequired') });
      return;
    }
    if (action === 'rollback' && !rollbackTarget.trim()) {
      setNotice({ kind: 'warning', message: t('attendanceSetup.rollbackTargetRequired') });
      return;
    }
    if (
      (action === 'deactivate' || action === 'rollback')
      && !isPolicyLifecycleBoundaryValid(actionEffectiveFrom, selected)
    ) {
      setNotice({
        kind: 'warning',
        message: t('attendanceSetup.futureEffectiveFromRequired'),
      });
      return;
    }
    const operations = {
      validate: () => validateAttendancePolicyVersion(selected, actionReason.trim()),
      publish: () => publishAttendancePolicyVersion(selected, actionReason.trim()),
      deactivate: () => deactivateAttendancePolicyVersion(
        selected,
        actionEffectiveFrom,
        actionReason.trim(),
      ),
      rollback: () => rollbackAttendancePolicyVersion(
        selected,
        rollbackTarget.trim(),
        actionEffectiveFrom,
        actionReason.trim(),
      ),
    };
    void run(operations[action], t(`attendanceSetup.policyLifecycle.${action}`));
  };
  const selectVersion = (versionId: string) => () => onSelectVersion(versionId);
  const changeEditEffectiveFrom = (event: ChangeEvent<HTMLInputElement>) => {
    setEditEffectiveFrom(event.target.value);
  };
  const changeEditEffectiveTo = (event: ChangeEvent<HTMLInputElement>) => {
    setEditEffectiveTo(event.target.value);
  };
  const selectParameterValue = (key: string) => (value: string) => {
    setParameterValues({ ...parameterValues, [key]: value });
  };
  const changeParameterValue = (key: string) => (
    event: ChangeEvent<HTMLInputElement>,
  ) => {
    setParameterValues({ ...parameterValues, [key]: event.target.value });
  };
  const changeActionReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setActionReason(event.target.value);
  };
  const changeActionEffectiveFrom = (event: ChangeEvent<HTMLInputElement>) => {
    setActionEffectiveFrom(event.target.value);
  };
  const changeRollbackTarget = (event: ChangeEvent<HTMLInputElement>) => {
    setRollbackTarget(event.target.value);
  };
  const chooseLifecycleAction = (action: LifecycleAction) => () => {
    executeLifecycle(action);
  };
  const changeDraftEffectiveFrom = (event: ChangeEvent<HTMLInputElement>) => {
    setDraftEffectiveFrom(event.target.value);
  };
  const changeDraftReason = (event: ChangeEvent<HTMLInputElement>) => {
    setDraftReason(event.target.value);
  };

  return (
    <section className="content-surface attendance-section section-spaced">
      <div className="section-heading">
        <div>
          <h2>{t('attendanceSetup.policyLifecycle')}</h2>
          <p>{t('attendanceSetup.policyLifecycleDescription')}</p>
        </div>
        <AccessibleButton
          label={t('common.refresh')}
          icon={<IconRefresh aria-hidden="true" stroke={2} />}
          onClick={reload}
        >
          {t('common.refresh')}
        </AccessibleButton>
      </div>
      <AttendanceSetupNotice notice={notice} />
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
        ? <StatePanel state="empty" description={t('attendanceSetup.noPolicyVersions')} />
        : null}
      {versions.resource.status === 'ready' ? (
        <>
          <DataTable
            rows={versions.resource.data.items}
            rowKey={(version) => version.scopedVersionId}
            ariaLabel={t('attendanceSetup.policyLifecycle')}
            columns={[
              {
                key: 'version',
                title: t('attendanceSetup.version'),
                render: (version) => (
                  <AccessibleButton
                    label={`${t('attendanceSetup.version')} ${version.versionNumber}`}
                    type={version.scopedVersionId === effectiveVersionId ? 'primary' : 'link'}
                    onClick={selectVersion(version.scopedVersionId)}
                  >
                    {version.versionNumber}
                  </AccessibleButton>
                ),
              },
              { key: 'status', title: t('attendanceSetup.status'), render: (version) => <StatusBadge status={version.status} /> },
              { key: 'period', title: t('attendanceSetup.period'), render: (version) => `${version.effectiveFrom} → ${version.effectiveTo ?? t('attendanceSetup.longTerm')}` },
              {
                key: 'issues',
                title: t('attendanceSetup.validationIssues'),
                render: (version) => version.validation.issues.length,
              },
              { key: 'reason', title: t('attendanceSetup.reason'), render: (version) => version.changeReason },
            ]}
          />
          <ResourcePagination
            ariaLabel={t('attendanceSetup.policyVersionPagination')}
            page={versions.resource.data.page}
            pageSize={versions.resource.data.size}
            total={versions.resource.data.total}
            onChange={(page, pageSize) => {
              setVersionPage(page);
              setVersionPageSize(pageSize);
            }}
          />
        </>
      ) : null}
      {detail.resource.status === 'loading' || detail.resource.status === 'partial-loading'
        ? <StatePanel state={detail.resource.status} />
        : null}
      {'error' in detail.resource ? (
        <StatePanel
          state={detail.resource.status}
          description={detail.resource.error.message}
          onRetry={detail.reload}
        />
      ) : null}
      {selected ? (
        <div className="attendance-lifecycle-workbench">
          <header>
            <IconGitBranch aria-hidden="true" stroke={2} />
            <div>
              <strong>{t('attendanceSetup.version')} {selected.versionNumber}</strong>
              <span>{selected.scopedVersionId}</span>
            </div>
            <StatusBadge status={selected.status} />
          </header>
          <dl className="metric-list">
            <div><dt>{t('attendanceSetup.rowVersion')}</dt><dd>{selected.rowVersion}</dd></div>
            <div><dt>{t('attendanceSetup.legalEntityId')}</dt><dd>{selected.legalEntityId}</dd></div>
            <div><dt>{t('attendanceSetup.policyKind')}</dt><dd>{selected.policyKind}</dd></div>
            <div><dt>{t('attendanceSetup.scopeId')}</dt><dd>{selected.scopeId}</dd></div>
            <div><dt>{t('attendanceSetup.validation')}</dt><dd>{selected.validation.valid ? t('attendanceSetup.yes') : t('attendanceSetup.no')}</dd></div>
            <div><dt>{t('attendanceSetup.configurationDigest')}</dt><dd>{selected.snapshotDigest ?? t('common.none')}</dd></div>
            <div><dt>{t('attendanceSetup.rollbackTarget')}</dt><dd>{selected.rollbackOfScopedVersionId ?? t('common.none')}</dd></div>
            <div><dt>{t('attendanceSetup.deactivationEffectiveFrom')}</dt><dd>{selected.deactivationEffectiveFrom ?? t('common.none')}</dd></div>
          </dl>
          <section
            className="attendance-validation-issues"
            aria-label={t('attendanceSetup.validationIssues')}
          >
            {selected.validation.issues.length === 0 ? (
              <p>{t('attendanceSetup.noValidationIssues')}</p>
            ) : selected.validation.issues.map((issue) => (
              <Alert
                key={`${issue.code}-${issue.field}`}
                showIcon
                type="error"
                title={issue.message}
                description={`${issue.field} · ${issue.code}`}
              />
            ))}
          </section>
          {canManage ? (
            <div className="attendance-lifecycle-controls">
              {selected.status === 'DRAFT' || selected.status === 'VALIDATED' ? (
                <>
                  <div className="form-grid">
                    <label>
                      <span>{t('attendanceSetup.effectiveFrom')}</span>
                      <Input
                        type="date"
                        value={editEffectiveFrom}
                        onChange={changeEditEffectiveFrom}
                      />
                    </label>
                    <label>
                      <span>{t('attendanceSetup.effectiveTo')}</span>
                      <Input
                        type="date"
                        value={editEffectiveTo}
                        onChange={changeEditEffectiveTo}
                      />
                    </label>
                    {fields.map((field) => (
                      <label key={field.key}>
                        <span>
                          {field.label}
                          {!field.required ? ` · ${t('common.optional')}` : ''}
                        </span>
                        {field.valueType === 'BOOLEAN' ? (
                          <Select
                            value={parameterValues[field.key]}
                            options={[
                              { value: 'true', label: t('attendanceSetup.yes') },
                              { value: 'false', label: t('attendanceSetup.no') },
                            ]}
                            onChange={selectParameterValue(field.key)}
                          />
                        ) : (
                          <Input
                            value={parameterValues[field.key]}
                            onChange={changeParameterValue(field.key)}
                          />
                        )}
                      </label>
                    ))}
                  </div>
                  <AccessibleButton
                    label={t('attendanceSetup.savePolicyDraft')}
                    loading={processing}
                    onClick={saveDraft}
                  >
                    {t('attendanceSetup.savePolicyDraft')}
                  </AccessibleButton>
                </>
              ) : null}
              <label>
                <span>{t('attendanceSetup.reason')}</span>
                <Input.TextArea
                  rows={2}
                  value={actionReason}
                  onChange={changeActionReason}
                />
              </label>
              {selected.status === 'PUBLISHED' ? (
                <div className="form-grid">
                  <label>
                    <span>{t('attendanceSetup.actionEffectiveFrom')}</span>
                    <Input
                      type="date"
                      min={policyLifecycleMinimumDate(selected.effectiveFrom)}
                      max={selected.effectiveTo ?? undefined}
                      value={actionEffectiveFrom}
                      onChange={changeActionEffectiveFrom}
                    />
                  </label>
                  <label>
                    <span>{t('attendanceSetup.rollbackTarget')}</span>
                    <Input
                      value={rollbackTarget}
                      onChange={changeRollbackTarget}
                    />
                  </label>
                </div>
              ) : null}
              <div className="attendance-lifecycle-actions">
                {selected.status === 'DRAFT' || selected.status === 'VALIDATED' ? (
                  <>
                    <AccessibleButton
                      label={t('attendanceSetup.validatePolicy')}
                      icon={<IconPlayerPlay aria-hidden="true" stroke={2} />}
                      loading={processing}
                      onClick={chooseLifecycleAction('validate')}
                    >
                      {t('attendanceSetup.validatePolicy')}
                    </AccessibleButton>
                    <AccessibleButton
                      label={t('attendanceSetup.publish')}
                      type="primary"
                      loading={processing}
                      onClick={chooseLifecycleAction('publish')}
                    >
                      {t('attendanceSetup.publish')}
                    </AccessibleButton>
                  </>
                ) : null}
                {selected.status === 'PUBLISHED' ? (
                  <>
                    <AccessibleButton
                      label={t('attendanceSetup.deactivate')}
                      loading={processing}
                      onClick={chooseLifecycleAction('deactivate')}
                    >
                      {t('attendanceSetup.deactivate')}
                    </AccessibleButton>
                    <AccessibleButton
                      label={t('attendanceSetup.rollback')}
                      icon={<IconHistory aria-hidden="true" stroke={2} />}
                      loading={processing}
                      onClick={chooseLifecycleAction('rollback')}
                    >
                      {t('attendanceSetup.rollback')}
                    </AccessibleButton>
                  </>
                ) : null}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
      {canManage ? (
        <div className="attendance-draft-creator">
          <h3>{t('attendanceSetup.createPolicyDraft')}</h3>
          <div className="form-grid">
            <label>
              <span>{t('attendanceSetup.effectiveFrom')}</span>
              <Input
                type="date"
                value={draftEffectiveFrom}
                onChange={changeDraftEffectiveFrom}
              />
            </label>
            <label>
              <span>{t('attendanceSetup.reason')}</span>
              <Input
                value={draftReason}
                onChange={changeDraftReason}
              />
            </label>
          </div>
          <AccessibleButton
            label={t('attendanceSetup.createPolicyDraft')}
            type="primary"
            loading={processing}
            onClick={createDraft}
          >
            {t('attendanceSetup.createPolicyDraft')}
          </AccessibleButton>
        </div>
      ) : null}
    </section>
  );
}

function typedParameterValue(
  field: PolicyFieldDefinition,
  value: string,
): PolicyParameterValue['value'] {
  if (field.valueType === 'BOOLEAN') return value === 'true';
  if (field.valueType === 'INTEGER') return Number(value);
  if (field.valueType === 'ENUM_LIST') {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
  }
  return value;
}

export function policyParametersForSave(
  fields: PolicyFieldDefinition[],
  parameterValues: Record<string, string>,
): PolicyParameterValue[] {
  return fields.flatMap((field) => {
    const value = parameterValues[field.key] ?? '';
    if (!field.required && value.trim().length === 0) return [];
    return [{
      key: field.key,
      value: typedParameterValue(field, value),
    }];
  });
}

function displayParameterValue(value: unknown): string {
  return Array.isArray(value) ? value.join(', ') : String(value);
}

function futureDate(days: number): string {
  const value = new Date();
  value.setDate(value.getDate() + days);
  return [
    String(value.getFullYear()).padStart(4, '0'),
    String(value.getMonth() + 1).padStart(2, '0'),
    String(value.getDate()).padStart(2, '0'),
  ].join('-');
}

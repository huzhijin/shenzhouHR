import {
  IconActivityHeartbeat,
  IconEdit,
  IconFlask,
  IconLink,
} from '@tabler/icons-react';
import { Alert, Input, Pagination, Segmented } from 'antd';
import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  createPolicyBinding,
  getAttendancePolicyVersionContext,
  listPolicyBindings,
  listPolicyCatalog,
  previewPolicyImpact,
  simulateAttendancePolicy,
  updatePolicyBinding,
} from './attendanceSetupApi';
import { AttendancePolicyLifecyclePanel } from './AttendancePolicyLifecyclePanel';
import { AttendanceSetupNotice } from './AttendanceSetupNotice';
import {
  mutationFailureNotice,
  mutationSuccessNotice,
  type AttendanceSetupNotice as Notice,
} from './attendanceSetupFeedback';
import { PolicyBindingDialog } from './PolicyBindingDialog';
import { PolicySimulationPanel } from './PolicySimulationPanel';
import type {
  AttendancePolicyKind,
  AttendancePolicyVersionView,
  PolicyBindingInput,
  PolicyBindingPreviewInput,
  PolicyBindingView,
  PolicyImpactView,
  PolicySimulationInput,
  PolicySimulationBatchView,
} from './attendanceSetupTypes';

const defaultKind: AttendancePolicyKind = 'MEAL_DEDUCTION';

export function AttendancePolicyPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const { versionId = '' } = useParams();
  const navigate = useNavigate();
  const [policyKind, setPolicyKind] = useState<AttendancePolicyKind>(defaultKind);
  const [draftVersionId, setDraftVersionId] = useState(versionId);
  const [bindingOpen, setBindingOpen] = useState(false);
  const [editingBinding, setEditingBinding] = useState<PolicyBindingView>();
  const [processing, setProcessing] = useState(false);
  const [simulationProcessing, setSimulationProcessing] = useState(false);
  const [simulationError, setSimulationError] = useState<string>();
  const [notice, setNotice] = useState<Notice>();
  const [simulation, setSimulation] = useState<PolicySimulationBatchView>();
  const [impact, setImpact] = useState<PolicyImpactView>();
  const [impactBinding, setImpactBinding] = useState<PolicyBindingView>();
  const [validatingBindingRevisionId, setValidatingBindingRevisionId] = useState<string>();
  const [bindingPage, setBindingPage] = useState(0);
  const [bindingPageSize, setBindingPageSize] = useState(20);
  const simulationGeneration = useRef(0);
  const impactGeneration = useRef(0);
  const bindingMutationGeneration = useRef(0);
  const catalogAndBindingsLoader = useMemo(
    () => () => Promise.all([
      listPolicyCatalog(),
      listPolicyBindings(undefined, undefined, bindingPage, bindingPageSize),
    ]),
    [bindingPage, bindingPageSize],
  );
  const catalogAndBindings = useAsyncResource(
    catalogAndBindingsLoader,
    ([catalog, bindings]) => catalog.length === 0 && bindings.items.length === 0,
    [bindingPage, bindingPageSize],
  );
  const policyVersionContextLoader = useMemo(
    () => () => versionId
      ? getAttendancePolicyVersionContext(versionId)
      : Promise.resolve<AttendancePolicyVersionView | null>(null),
    [versionId],
  );
  const policyVersionContext = useAsyncResource(
    policyVersionContextLoader,
    () => false,
    [versionId],
  );
  const canManage = capabilities.includes('ATTENDANCE_SETUP:MANAGE_POLICY');
  const routeContext = policyVersionContext.resource.status === 'ready'
    && policyVersionContext.resource.data?.scopedVersionId === versionId
    ? policyVersionContext.resource.data
    : null;
  const routeContextReady = !versionId || routeContext !== null;
  const effectivePolicyKind = routeContext?.policyKind ?? policyKind;
  const bindingItems = catalogAndBindings.resource.status === 'ready'
    ? catalogAndBindings.resource.data[1].items
    : [];
  const selectedTemplate = catalogAndBindings.resource.status === 'ready'
    ? catalogAndBindings.resource.data[0].find((template) => routeContext
      ? template.templateId === routeContext.templateId
      : template.policyKind === effectivePolicyKind)
    : undefined;
  const lifecycleLegalEntityId = routeContext?.legalEntityId ?? '';
  const effectiveVersionId = routeContext?.scopedVersionId ?? '';
  const applyVersionId = () => {
    const trimmed = draftVersionId.trim();
    if (trimmed) navigate(`/rules/attendance-policy/${encodeURIComponent(trimmed)}`);
  };

  useEffect(() => {
    setDraftVersionId(versionId);
    simulationGeneration.current += 1;
    impactGeneration.current += 1;
    bindingMutationGeneration.current += 1;
    setSimulation(undefined);
    setSimulationError(undefined);
    setSimulationProcessing(false);
    setImpact(undefined);
    setImpactBinding(undefined);
    setNotice(undefined);
    setBindingOpen(false);
    setEditingBinding(undefined);
    setValidatingBindingRevisionId(undefined);
    setProcessing(false);
    return () => {
      simulationGeneration.current += 1;
      impactGeneration.current += 1;
      bindingMutationGeneration.current += 1;
    };
  }, [versionId]);

  useEffect(() => {
    if (!routeContext) return;
    setPolicyKind(routeContext.policyKind);
    setDraftVersionId(routeContext.scopedVersionId);
  }, [routeContext]);

  const saveBinding = async (input: PolicyBindingPreviewInput) => {
    const generation = ++bindingMutationGeneration.current;
    setProcessing(true);
    setNotice(undefined);
    try {
      const preview = await previewPolicyImpact(input);
      if (bindingMutationGeneration.current !== generation) return;
      const mutation: PolicyBindingInput = {
        ...input,
        impactToken: preview.impactToken,
      };
      const response = editingBinding
        ? await updatePolicyBinding(editingBinding, mutation)
        : await createPolicyBinding(mutation);
      if (bindingMutationGeneration.current !== generation) return;
      setImpact(preview);
      setImpactBinding(response);
      setBindingOpen(false);
      setEditingBinding(undefined);
      setBindingPage(0);
      catalogAndBindings.reload();
      setNotice(mutationSuccessNotice(
        response,
        editingBinding
          ? t('attendanceSetup.bindingUpdated')
          : t('attendanceSetup.bindingCreated'),
      ));
    } catch (caught: unknown) {
      if (bindingMutationGeneration.current === generation) {
        setNotice(mutationFailureNotice(caught));
      }
    } finally {
      if (bindingMutationGeneration.current === generation) {
        setProcessing(false);
      }
    }
  };

  const simulate = async (input: PolicySimulationInput) => {
    const generation = ++simulationGeneration.current;
    setSimulationProcessing(true);
    setSimulation(undefined);
    setSimulationError(undefined);
    try {
      const result = await simulateAttendancePolicy(input);
      if (simulationGeneration.current === generation) {
        setSimulation(result);
      }
    } catch (caught: unknown) {
      if (simulationGeneration.current === generation) {
        setSimulationError(mutationFailureNotice(caught).message);
      }
    } finally {
      if (simulationGeneration.current === generation) {
        setSimulationProcessing(false);
      }
    }
  };

  const previewImpact = async (binding: PolicyBindingView) => {
    const generation = ++impactGeneration.current;
    setImpactBinding(binding);
    setProcessing(true);
    setNotice(undefined);
    setImpact(undefined);
    try {
      const result = await previewPolicyImpact({
        policyKind: binding.policyKind,
        policyVersionId: binding.policyVersionId,
        groupId: binding.groupId,
        groupRevisionId: binding.groupRevisionId,
        effectiveFrom: binding.effectiveFrom,
        effectiveTo: binding.effectiveTo,
        reason: binding.changeReason,
      });
      if (impactGeneration.current === generation) {
        setImpact(result);
      }
    } catch (caught: unknown) {
      if (impactGeneration.current === generation) {
        setNotice(mutationFailureNotice(caught));
      }
    } finally {
      if (impactGeneration.current === generation) {
        setProcessing(false);
      }
    }
  };

  const openBindingCreator = () => {
    setEditingBinding(undefined);
    setBindingOpen(true);
  };
  const changePolicyKind = (value: AttendancePolicyKind) => {
    simulationGeneration.current += 1;
    impactGeneration.current += 1;
    bindingMutationGeneration.current += 1;
    if (versionId) {
      navigate('/rules/attendance-policy');
      setDraftVersionId('');
    }
    setPolicyKind(value);
    setSimulation(undefined);
    setSimulationError(undefined);
    setSimulationProcessing(false);
    setImpact(undefined);
    setImpactBinding(undefined);
    setNotice(undefined);
    setBindingOpen(false);
    setEditingBinding(undefined);
    setProcessing(false);
  };
  const changeDraftVersionId = (event: ChangeEvent<HTMLInputElement>) => {
    setDraftVersionId(event.target.value);
  };
  const openBindingEditor = (binding: PolicyBindingView) => async () => {
    const generation = ++bindingMutationGeneration.current;
    setValidatingBindingRevisionId(binding.bindingRevisionId);
    setNotice(undefined);
    try {
      const currentBindings = await listPolicyBindings(
        binding.groupId,
        undefined,
        0,
        100,
      );
      if (bindingMutationGeneration.current !== generation) return;
      const current = currentBindings.items.find(
        (candidate) => candidate.bindingId === binding.bindingId,
      );
      if (
        !current
        || current.status !== 'ACTIVE'
        || current.bindingRevisionId !== binding.bindingRevisionId
        || current.revisionNumber !== binding.revisionNumber
      ) {
        setNotice({
          kind: 'warning',
          message: t('attendanceSetup.stale'),
          state: 'stale',
        });
        catalogAndBindings.reload();
        return;
      }
      setEditingBinding(current);
      setBindingOpen(true);
    } catch (caught: unknown) {
      if (bindingMutationGeneration.current === generation) {
        setNotice(mutationFailureNotice(caught));
      }
    } finally {
      if (bindingMutationGeneration.current === generation) {
        setValidatingBindingRevisionId(undefined);
      }
    }
  };
  const selectPolicyVersion = (selectedVersionId: string) => {
    navigate(`/rules/attendance-policy/${encodeURIComponent(selectedVersionId)}`);
  };
  const requestSimulation = (input: PolicySimulationInput) => {
    void simulate(input);
  };
  const requestImpactPreview = (binding: PolicyBindingView) => () => {
    void previewImpact(binding);
  };
  const submitBinding = (input: PolicyBindingPreviewInput) => {
    void saveBinding(input);
  };
  const closeBindingDialog = () => {
    setBindingOpen(false);
    setEditingBinding(undefined);
  };
  const changeBindingPage = (nextPage: number, nextPageSize: number) => {
    impactGeneration.current += 1;
    setImpact(undefined);
    setImpactBinding(undefined);
    setProcessing(false);
    setBindingPage(nextPageSize === bindingPageSize ? nextPage - 1 : 0);
    setBindingPageSize(nextPageSize);
  };
  return (
    <>
      <PageHeader
        title={t('attendanceSetup.policy')}
        description={t('attendanceSetup.policyDescription')}
        breadcrumbs={[
          {
            label: t('attendanceSetup.home'),
            path: '/rules/attendance-groups',
          },
          { label: t('attendanceSetup.policy') },
        ]}
        actions={canManage ? (
          <AccessibleButton
            label={t('attendanceSetup.createBinding')}
            type="primary"
            icon={<IconLink aria-hidden="true" stroke={2} />}
            disabled={!effectiveVersionId}
            onClick={openBindingCreator}
          >
            {t('attendanceSetup.createBinding')}
          </AccessibleButton>
        ) : undefined}
      />
      <AttendanceSetupNotice notice={notice} />
      <Alert
        className="attendance-boundary-note"
        showIcon
        type="warning"
        title={t('attendanceSetup.frozenTitle')}
        description={t('attendanceSetup.frozenDescription')}
      />
      <section className="attendance-policy-selector" aria-label={t('attendanceSetup.policyCatalog')}>
        <Segmented<AttendancePolicyKind>
          block
          value={effectivePolicyKind}
          onChange={changePolicyKind}
          options={[
            { value: 'MEAL_DEDUCTION', label: t('attendanceSetup.policyMeal') },
            { value: 'LATE_GRACE', label: t('attendanceSetup.policyLateGrace') },
            { value: 'MONTHLY_LATE_EXEMPTION', label: t('attendanceSetup.policyMissingPunch') },
          ]}
        />
        <div className="attendance-version-input">
          <label htmlFor="attendance-policy-version">{t('attendanceSetup.policyVersionId')}</label>
          <Input
            id="attendance-policy-version"
            value={draftVersionId}
            onChange={changeDraftVersionId}
          />
          <AccessibleButton
            label={t('attendanceSetup.select')}
            onClick={applyVersionId}
          >
            {t('attendanceSetup.select')}
          </AccessibleButton>
        </div>
      </section>
      {catalogAndBindings.resource.status === 'loading'
        || catalogAndBindings.resource.status === 'partial-loading'
        ? <StatePanel state={catalogAndBindings.resource.status} />
        : null}
      {'error' in catalogAndBindings.resource ? (
        <StatePanel
          state={catalogAndBindings.resource.status}
          description={catalogAndBindings.resource.error.message}
          onRetry={catalogAndBindings.reload}
        />
      ) : null}
      {catalogAndBindings.resource.status === 'empty' ? <StatePanel state="empty" /> : null}
      {versionId && (policyVersionContext.resource.status === 'loading'
        || policyVersionContext.resource.status === 'partial-loading') ? (
          <StatePanel state={policyVersionContext.resource.status} />
        ) : null}
      {versionId && 'error' in policyVersionContext.resource ? (
        <StatePanel
          state={policyVersionContext.resource.status}
          description={policyVersionContext.resource.error.message}
          onRetry={policyVersionContext.reload}
        />
      ) : null}
      {catalogAndBindings.resource.status === 'ready' ? (
        <>
          <section className="content-surface attendance-section">
            <div className="section-heading">
              <div>
                <h2>{selectedTemplate?.name ?? t('attendanceSetup.policyCatalog')}</h2>
                <p>{t('attendanceSetup.policyDescription')}</p>
              </div>
            </div>
            <ul className="controlled-policy-fields">
              {(selectedTemplate?.fields ?? []).map((field) => (
                <li key={field.key}>
                  <strong>{field.label}</strong>
                  <span>{field.key}</span>
                  <code>{field.valueType}</code>
                  <small>
                    {t(field.required ? 'common.required' : 'common.optional')}
                  </small>
                </li>
              ))}
            </ul>
          </section>
          <section className="content-surface attendance-section section-spaced">
            <div className="section-heading">
              <div>
                <h2>{t('attendanceSetup.policyBinding')}</h2>
                <p>{t('attendanceSetup.policyDescription')}</p>
              </div>
              <AccessibleButton
                label={t('common.refresh')}
                onClick={catalogAndBindings.reload}
              >
                {t('common.refresh')}
              </AccessibleButton>
            </div>
            {catalogAndBindings.resource.data[1].items.length === 0
              ? <StatePanel state="empty" description={t('attendanceSetup.noBindings')} />
              : (
                <DataTable
                  rows={bindingItems}
                  rowKey={(binding) => binding.bindingRevisionId}
                  ariaLabel={t('attendanceSetup.policyBinding')}
                  columns={[
                    { key: 'kind', title: t('attendanceSetup.policyKind'), render: (binding) => binding.policyKind },
                    { key: 'revision', title: t('attendanceSetup.revision'), render: (binding) => binding.revisionNumber },
                    { key: 'policyVersion', title: t('attendanceSetup.policyVersionId'), render: (binding) => <code>{binding.policyVersionId}</code> },
                    { key: 'group', title: t('attendanceSetup.groupId'), render: (binding) => <code>{binding.groupId}</code> },
                    { key: 'groupRevision', title: t('attendanceSetup.groupRevisionId'), render: (binding) => <code>{binding.groupRevisionId}</code> },
                    { key: 'period', title: t('attendanceSetup.period'), render: (binding) => `${binding.effectiveFrom} → ${binding.effectiveTo ?? t('attendanceSetup.longTerm')}` },
                    { key: 'digest', title: t('attendanceSetup.snapshotDigest'), render: (binding) => <code className="attendance-digest">{binding.snapshotDigest}</code> },
                    { key: 'status', title: t('attendanceSetup.status'), render: (binding) => <StatusBadge status={binding.status} /> },
                    { key: 'rowVersion', title: t('attendanceSetup.rowVersion'), render: (binding) => binding.rowVersion },
                    { key: 'reason', title: t('attendanceSetup.reason'), render: (binding) => binding.changeReason },
                    ...(canManage && routeContextReady ? [{
                      key: 'actions',
                      title: t('common.actions'),
                      render: (binding: PolicyBindingView) => (
                        <div className="table-actions">
                          <AccessibleButton
                            label={t('attendanceSetup.updateBinding')}
                            type="text"
                            icon={<IconEdit aria-hidden="true" stroke={2} />}
                            disabled={binding.status !== 'ACTIVE'}
                            loading={
                              validatingBindingRevisionId === binding.bindingRevisionId
                            }
                            onClick={openBindingEditor(binding)}
                          >
                            {t('attendanceSetup.updateBinding')}
                          </AccessibleButton>
                          <AccessibleButton
                            label={t('attendanceSetup.runImpact')}
                            type="text"
                            icon={<IconActivityHeartbeat aria-hidden="true" stroke={2} />}
                            loading={processing
                              && impactBinding?.bindingRevisionId === binding.bindingRevisionId}
                            onClick={requestImpactPreview(binding)}
                          >
                            {t('attendanceSetup.runImpact')}
                          </AccessibleButton>
                        </div>
                      ),
                    }] : []),
                  ]}
                />
              )}
            {catalogAndBindings.resource.data[1].total > 0 ? (
              <nav
                className="attendance-pagination"
                aria-label={t('attendanceSetup.bindingPagination')}
              >
                <Pagination
                  current={catalogAndBindings.resource.data[1].page + 1}
                  pageSize={catalogAndBindings.resource.data[1].size}
                  total={catalogAndBindings.resource.data[1].total}
                  pageSizeOptions={['20', '50', '100']}
                  showSizeChanger
                  showTotal={(total) => t('attendanceSetup.bindingTotal', { total })}
                  onChange={changeBindingPage}
                />
              </nav>
            ) : null}
          </section>
          {routeContextReady && selectedTemplate && lifecycleLegalEntityId ? (
            <AttendancePolicyLifecyclePanel
              key={`${selectedTemplate.templateId}-${lifecycleLegalEntityId}-${effectiveVersionId}`}
              templateId={selectedTemplate.templateId}
              legalEntityId={lifecycleLegalEntityId}
              selectedVersionId={effectiveVersionId}
              fields={selectedTemplate.fields}
              canManage={canManage}
              onSelectVersion={selectPolicyVersion}
            />
          ) : null}
          {routeContextReady && canManage ? (
            <>
              <section className="content-surface attendance-section section-spaced">
                <div className="section-heading">
                  <div>
                    <h2>{t('attendanceSetup.simulation')}</h2>
                    <p>{t('attendanceSetup.authoritativeSimulationNotice')}</p>
                  </div>
                  <IconFlask aria-hidden="true" stroke={2} />
                </div>
                <PolicySimulationPanel
                  key={effectivePolicyKind}
                  policyKind={effectivePolicyKind}
                  policyVersionId={effectiveVersionId}
                  processing={simulationProcessing}
                  error={simulationError}
                  result={simulation}
                  onSimulate={requestSimulation}
                />
              </section>
              <section className="content-surface attendance-section section-spaced">
                <div className="section-heading">
                  <div>
                    <h2>{t('attendanceSetup.impactPreview')}</h2>
                    <p>
                      {impactBinding
                        ? `${impactBinding.policyVersionId} · ${impactBinding.groupId}`
                        : t('attendanceSetup.selectBindingForImpact')}
                    </p>
                  </div>
                </div>
                {impact ? (
                  <dl className="metric-list">
                    <div><dt>{t('attendanceSetup.groupCount')}</dt><dd>{impact.groupCount}</dd></div>
                    <div><dt>{t('attendanceSetup.assignmentCount')}</dt><dd>{impact.assignmentCount}</dd></div>
                    <div><dt>{t('attendanceSetup.countSource')}</dt><dd>{impact.countSource}</dd></div>
                  </dl>
                ) : <StatePanel state="empty" description={t('attendanceSetup.runImpact')} />}
              </section>
            </>
          ) : null}
        </>
      ) : null}
      {routeContextReady && canManage ? (
        <>
          <PolicyBindingDialog
            key={`${effectivePolicyKind}-${effectiveVersionId}-${editingBinding?.bindingId ?? 'create'}`}
            open={bindingOpen}
            processing={processing}
            catalog={catalogAndBindings.resource.status === 'ready'
              ? catalogAndBindings.resource.data[0]
              : []}
            policyKind={effectivePolicyKind}
            policyVersionId={effectiveVersionId}
            initialValues={editingBinding ? {
              policyKind: editingBinding.policyKind,
              policyVersionId: editingBinding.policyVersionId,
              groupId: editingBinding.groupId,
              groupRevisionId: editingBinding.groupRevisionId,
              effectiveFrom: editingBinding.effectiveFrom,
              effectiveTo: editingBinding.effectiveTo,
              reason: '',
            } : undefined}
            onSubmit={submitBinding}
            onCancel={closeBindingDialog}
          />
        </>
      ) : null}
    </>
  );
}

export default AttendancePolicyPage;

import {
  IconActivityHeartbeat,
  IconCircleCheck,
  IconFlask,
  IconPlayerPause,
  IconRotate,
  IconShieldCheck,
} from '@tabler/icons-react';
import { Alert, Button, Input, Segmented, Space } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { ApiRequestError } from '../../shared/api/apiClient';
import { ConfirmationDialog, OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  PolicyEditor,
  PolicyResultTabs,
  ScopeSelector,
} from './PolicyComponents';
import {
  checkPolicyConflicts,
  deactivatePolicy,
  getPolicyTemplate,
  getPolicyVersion,
  previewPolicyImpact,
  publishPolicy,
  rollbackPolicy,
  simulatePolicy,
  updateDraft,
  updateScopeBindings,
  validatePolicy,
  type PolicyConflictResult,
  type PolicyImpactPreview,
  type PolicySimulationResult,
  type PolicyValidationResult,
  type PolicyVersionDetail,
} from './policyApi';

type ResultTab = 'validation' | 'conflicts' | 'impact' | 'simulation';
type LifecycleOperation = 'publish' | 'deactivate' | 'rollback';

export function PolicyVersionPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const { templateId = '', versionId = '' } = useParams();
  const versionLoader = useMemo(() => () => getPolicyVersion(templateId, versionId), [templateId, versionId]);
  const templateLoader = useMemo(() => () => getPolicyTemplate(templateId), [templateId]);
  const versionResource = useAsyncResource(versionLoader, () => false, [templateId, versionId]);
  const templateResource = useAsyncResource(templateLoader, () => false, [templateId]);
  const [draft, setDraft] = useState<PolicyVersionDetail>();
  const [activeTab, setActiveTab] = useState<ResultTab>('validation');
  const [validation, setValidation] = useState<PolicyValidationResult>();
  const [conflicts, setConflicts] = useState<PolicyConflictResult>();
  const [impact, setImpact] = useState<PolicyImpactPreview>();
  const [simulation, setSimulation] = useState<PolicySimulationResult>();
  const [operation, setOperation] = useState<LifecycleOperation>();
  const [reason, setReason] = useState('');
  const [targetVersionId, setTargetVersionId] = useState('');
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [conflictFeedback, setConflictFeedback] = useState<string>();

  const currentVersion = draft ?? (versionResource.resource.status === 'ready' ? versionResource.resource.data : undefined);
  const runAction = async (tab: ResultTab) => {
    setProcessing(true);
    setActiveTab(tab);
    try {
      if (tab === 'validation') setValidation(await validatePolicy(templateId, versionId));
      if (tab === 'conflicts') setConflicts(await checkPolicyConflicts(templateId, versionId));
      if (tab === 'impact') setImpact(await previewPolicyImpact(templateId, versionId));
      if (tab === 'simulation') setSimulation(await simulatePolicy(
        templateId,
        versionId,
        t('policy.syntheticScenario'),
        { scopeResourceId: '9700000000000000002', occurredAt: '2026-08-01T08:00:00+08:00' },
      ));
    } finally {
      setProcessing(false);
    }
  };

  const save = async () => {
    if (!currentVersion) return;
    setProcessing(true);
    setConflictFeedback(undefined);
    try {
      const saved = await updateDraft(templateId, versionId, {
        effectiveFrom: currentVersion.effectiveFrom,
        effectiveTo: currentVersion.effectiveTo,
        changeReason: currentVersion.changeReason,
        parameters: currentVersion.parameters,
        expectedVersion: currentVersion.rowVersion,
      });
      const scoped = await updateScopeBindings(templateId, versionId, currentVersion.scopeBindings ?? [], saved.rowVersion);
      setDraft(scoped);
      setFeedback(t('policy.draftSaved'));
      versionResource.reload();
    } catch (caught: unknown) {
      if (caught instanceof ApiRequestError && (caught.status === 409 || caught.status === 412)) {
        setConflictFeedback(t('policy.optimisticConflict'));
      }
    } finally {
      setProcessing(false);
    }
  };

  const confirmLifecycle = async () => {
    if (!operation || !currentVersion) return;
    setProcessing(true);
    try {
      if (operation === 'publish') await publishPolicy(templateId, versionId, currentVersion.rowVersion, reason);
      if (operation === 'deactivate') await deactivatePolicy(templateId, versionId, currentVersion.rowVersion, reason);
      if (operation === 'rollback') {
        await rollbackPolicy(templateId, versionId, targetVersionId, currentVersion.rowVersion, reason);
      }
      setFeedback(t('policy.operationCompleted', { operation: operationLabel(operation, t) }));
      setOperation(undefined);
      setReason('');
      setTargetVersionId('');
      setDraft(undefined);
      versionResource.reload();
    } finally {
      setProcessing(false);
    }
  };

  if (versionResource.resource.status === 'loading' || templateResource.resource.status === 'loading') return <StatePanel state="loading" />;
  if ('error' in versionResource.resource) return <StatePanel state={versionResource.resource.status} description={versionResource.resource.error.message} onRetry={versionResource.reload} />;
  if ('error' in templateResource.resource) return <StatePanel state={templateResource.resource.status} description={templateResource.resource.error.message} onRetry={templateResource.reload} />;
  if (versionResource.resource.status !== 'ready' || templateResource.resource.status !== 'ready' || !currentVersion) return <StatePanel state="404" />;
  const template = templateResource.resource.data;
  const editable = currentVersion.status === 'DRAFT' && capabilities.includes('POLICY:EDIT');
  const handleSave = () => {
    void save();
  };
  const handleValidation = () => {
    void runAction('validation');
  };
  const handleConflictCheck = () => {
    void runAction('conflicts');
  };
  const handleImpactPreview = () => {
    void runAction('impact');
  };
  const handleSimulation = () => {
    void runAction('simulation');
  };
  const openPublish = () => setOperation('publish');
  const openDeactivate = () => setOperation('deactivate');
  const openRollback = () => setOperation('rollback');

  return (
    <>
      <PageHeader
        title={`${template.name} · V${currentVersion.versionNumber}`}
        description={currentVersion.changeReason}
        breadcrumbs={[{ label: t('rules.title'), path: '/rules' }, { label: t('rules.templates'), path: '/rules/templates' }, { label: template.code, path: `/rules/templates/${templateId}` }, { label: `V${currentVersion.versionNumber}` }]}
        actions={<StatusBadge status={currentVersion.status} />}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      {conflictFeedback ? <Alert showIcon type="error" title={t('policy.optimisticConflictTitle')} description={conflictFeedback} data-state="optimistic-lock-conflict" action={<Button onClick={versionResource.reload}>{t('policy.refreshData')}</Button>} /> : null}
      <section className="content-surface policy-workbench" data-state={currentVersion.status === 'ROLLED_BACK' ? 'rollback' : currentVersion.status.toLowerCase()}>
        <div className="section-heading">
          <div><h2>{t('policy.configuration')}</h2><p>{t('policy.configurationDescription')}</p></div>
          {editable ? <Button type="primary" loading={processing} onClick={handleSave}>{t('policy.saveDraft')}</Button> : null}
        </div>
        <PolicyEditor template={template} version={currentVersion} disabled={!editable} onChange={setDraft} />
        <ScopeSelector value={currentVersion.scopeBindings ?? []} disabled={!editable} onChange={(scopeBindings) => setDraft({ ...currentVersion, scopeBindings })} />
      </section>
      <section className="content-surface section-spaced">
        <div className="section-heading">
          <div><h2>{t('policy.prePublish')}</h2><p>{t('policy.prePublishDescription')}</p></div>
          <Space wrap>
            <Button icon={<IconCircleCheck stroke={2} />} disabled={!capabilities.includes('POLICY:VALIDATE')} loading={processing && activeTab === 'validation'} onClick={handleValidation}>{t('policy.validateAction')}</Button>
            <Button icon={<IconShieldCheck stroke={2} />} disabled={!capabilities.includes('POLICY:VALIDATE')} onClick={handleConflictCheck}>{t('policy.conflict')}</Button>
            <Button icon={<IconActivityHeartbeat stroke={2} />} disabled={!capabilities.includes('POLICY:SIMULATE')} onClick={handleImpactPreview}>{t('policy.impact')}</Button>
            <Button icon={<IconFlask stroke={2} />} disabled={!capabilities.includes('POLICY:SIMULATE')} onClick={handleSimulation}>{t('policy.simulation')}</Button>
          </Space>
        </div>
        <Segmented<ResultTab>
          value={activeTab}
          onChange={setActiveTab}
          options={[
            { label: t('policy.validateAction'), value: 'validation' },
            { label: t('policy.conflictAction'), value: 'conflicts' },
            { label: t('policy.impactAction'), value: 'impact' },
            { label: t('policy.simulationAction'), value: 'simulation' },
          ]}
        />
        <PolicyResultTabs active={activeTab} validation={validation} conflicts={conflicts} impact={impact} simulation={simulation} />
      </section>
      <section className="content-surface section-spaced">
        <h2>{t('policy.versionActions')}</h2>
        <Space wrap>
          {currentVersion.status === 'DRAFT' ? <Button type="primary" disabled={!capabilities.includes('POLICY:PUBLISH')} onClick={openPublish}>{t('policy.publish')}</Button> : null}
          {currentVersion.status === 'PUBLISHED' ? <Button danger icon={<IconPlayerPause stroke={2} />} disabled={!capabilities.includes('POLICY:DEACTIVATE')} onClick={openDeactivate}>{t('policy.deactivate')}</Button> : null}
          {['PUBLISHED', 'INACTIVE'].includes(currentVersion.status) ? <Button icon={<IconRotate stroke={2} />} disabled={!capabilities.includes('POLICY:ROLLBACK')} onClick={openRollback}>{t('policy.rollback')}</Button> : null}
        </Space>
        <Alert className="frozen-period-note" showIcon type="info" title={t('policy.freezeTitle')} description={t('policy.freezeDescription')} />
      </section>
      <ConfirmationDialog
        open={Boolean(operation)}
        title={t('policy.confirmOperation', { operation: operationLabel(operation, t) })}
        description={<>
          <p>{t('policy.operationHistoryNotice')}</p>
          {operation === 'rollback' ? <Input aria-label={t('policy.targetVersion')} value={targetVersionId} placeholder={t('policy.targetVersionPlaceholder')} onChange={(event) => setTargetVersionId(event.target.value)} /> : null}
          <Input.TextArea aria-label={t('policy.operationReason')} value={reason} placeholder={t('policy.operationReasonPlaceholder')} onChange={(event) => setReason(event.target.value)} />
        </>}
        confirmText={t('policy.confirmOperation', { operation: operationLabel(operation, t) })}
        danger={operation === 'deactivate' || operation === 'rollback'}
        processing={processing}
        onConfirm={() => void confirmLifecycle()}
        onCancel={() => setOperation(undefined)}
      />
    </>
  );
}

export default PolicyVersionPage;

function operationLabel(operation: LifecycleOperation | undefined, t: (key: string) => string): string {
  if (operation === 'publish') return t('policy.publish');
  if (operation === 'deactivate') return t('policy.deactivate');
  return t('policy.rollback');
}

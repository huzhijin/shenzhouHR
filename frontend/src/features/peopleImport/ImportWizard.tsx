import {
  IconAlertTriangle,
  IconCheck,
  IconDownload,
  IconFileSpreadsheet,
  IconPlayerPlay,
  IconTrash,
  IconUpload,
} from '@tabler/icons-react';
import {
  Alert,
  Form,
  Input,
  Select,
  Upload,
} from 'antd';
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from 'react';
import { useTranslation } from 'react-i18next';

import {
  ApiRequestError,
  createIdempotencyKey,
  saveDownloadedFile,
} from '../../shared/api/apiClient';
import {
  AccessibleButton,
  AccessibleNativeButton,
} from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { OperationFeedback } from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState } from '../people/PeopleCommon';
import { ImportActionDialog, type ImportDialogAction } from './ImportDialogs';
import { ImportResults } from './ImportResults';
import {
  createPeopleImportBatch,
  downloadPeopleImportTemplate,
  getPeopleImportBatch,
  precheckPeopleImport,
  publishPeopleImport,
  replacePeopleImportMapping,
  rollbackPeopleImport,
  uploadPeopleImportFile,
  voidPeopleImport,
} from './peopleImportApi';
import { validatePeopleImportFile } from './peopleImportFileValidation';
import type {
  PeopleImportBatchDetail,
  PeopleImportCreateRequest,
  PeopleImportMappingEntry,
  PeopleImportTemplateType,
  PeopleImportTemplateVersion,
} from './peopleImportTypes';

const steps = ['template', 'upload', 'mapping', 'precheck', 'diff', 'publish'] as const;
type StepKey = typeof steps[number];

export function ImportWizard({ templates, batch, capabilities, onBatchChange }: {
  templates: PeopleImportTemplateVersion[];
  batch?: PeopleImportBatchDetail;
  capabilities: string[];
  onBatchChange: (batch: PeopleImportBatchDetail) => void;
}) {
  const { t } = useTranslation();
  const [activeStep, setActiveStep] = useState<StepKey>(() => stepForBatch(batch));
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<ApiRequestError>();
  const [feedback, setFeedback] = useState<string>();
  const [selectedFile, setSelectedFile] = useState<File>();
  const [dialog, setDialog] = useState<ImportDialogAction>();
  const keys = useRef(new Map<string, string>());
  const stepTitle = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const next = stepForBatch(batch);
    setActiveStep(next);
  }, [batch?.batchId, batch?.status, batch?.rowVersion]);

  useEffect(() => {
    setSelectedFile(undefined);
    setDialog(undefined);
    setError(undefined);
    setFeedback(undefined);
    keys.current.clear();
  }, [batch?.batchId]);

  useEffect(() => {
    stepTitle.current?.focus();
  }, [activeStep]);

  const mutationKey = (scope: string) => {
    const existing = keys.current.get(scope);
    if (existing) return existing;
    const created = createIdempotencyKey(scope);
    keys.current.set(scope, created);
    return created;
  };
  const completed = (scope: string) => keys.current.delete(scope);

  const execute = async (
    scope: string,
    action: () => Promise<string | void>,
    success: string,
  ) => {
    setProcessing(true);
    setError(undefined);
    setFeedback(undefined);
    try {
      const actionFeedback = await action();
      completed(scope);
      setFeedback(actionFeedback ?? success);
    } catch (caught: unknown) {
      setError(caught instanceof ApiRequestError
        ? caught
        : new ApiRequestError(0, { code: 'PEOPLE_IMPORT_OPERATION_FAILED' }));
    } finally {
      setProcessing(false);
    }
  };

  const openStep = (step: StepKey) => {
    if (isStepReachable(step, batch)) setActiveStep(step);
  };

  const handleUpload = () => {
    if (!batch || !selectedFile) return;
    try {
      validatePeopleImportFile(selectedFile);
    } catch (caught: unknown) {
      setError(caught instanceof ApiRequestError
        ? caught
        : new ApiRequestError(415, { code: 'UNSUPPORTED_PEOPLE_IMPORT_FILE' }));
      return;
    }
    void execute(
      'upload',
      async () => {
        await uploadPeopleImportFile(
          batch.batchId,
          selectedFile,
          batch.rowVersion,
          mutationKey('upload'),
        );
        const refreshedBatch = await getPeopleImportBatch(batch.batchId);
        onBatchChange(refreshedBatch);
        setActiveStep('mapping');
      },
      t('peopleImport.uploadSuccess'),
    );
  };

  const handleDialog = (reason: string) => {
    if (!batch || !dialog) return;
    const currentAction = dialog;
    void execute(
      currentAction,
      async () => {
        if (isValue(currentAction, 'publish')) {
          const publication = await publishPeopleImport(
            batch,
            reason,
            mutationKey('publish'),
          );
          onBatchChange(await getPeopleImportBatch(batch.batchId));
          setDialog(undefined);
          return publication.deduplicated
            ? t('peopleImport.deduplicated')
            : t('peopleImport.publishSuccess');
        }
        if (isValue(currentAction, 'void')) {
          onBatchChange(await voidPeopleImport(
            batch.batchId,
            batch.rowVersion,
            reason,
            mutationKey('void'),
          ));
        }
        if (isValue(currentAction, 'rollback')) {
          await rollbackPeopleImport(batch, reason, mutationKey('rollback'));
          onBatchChange(await getPeopleImportBatch(batch.batchId));
          setDialog(undefined);
          return t('peopleImport.rollbackSuccess');
        }
        setDialog(undefined);
      },
      isValue(currentAction, 'void')
        ? t('peopleImport.voidSuccess')
        : t('peopleImport.operationSuccess'),
    );
  };
  const chooseStep = (step: StepKey) => () => openStep(step);
  const closeActionDialog = () => setDialog(undefined);

  return (
    <>
      <nav className="import-stepper" aria-label={t('peopleImport.stepNavigation')}>
        {Array.from(steps, (step, index) => {
          const reachable = isStepReachable(step, batch);
          const currentIndex = steps.indexOf(activeStep);
          const done = index < currentIndex || isServerStepComplete(step, batch);
          const hasError = isValue(step, 'precheck')
            && isValue(batch?.status, 'VALIDATION_FAILED');
          const stepNumber = String(index + 1).padStart(2, '0');
          const stepState = hasError
            ? t('peopleImport.stepFailed')
            : done
              ? t('peopleImport.stepComplete')
              : '';
          return (
            <AccessibleNativeButton
              label={`${stepNumber} ${t(`peopleImport.step.${step}`)} ${stepState}`.trim()}
              className="import-step"
              data-state={hasError
                ? 'error'
                : done
                  ? 'complete'
                  : isValue(activeStep, step)
                    ? 'current'
                    : 'unreachable'}
              type="button"
              key={step}
              disabled={!reachable}
              aria-current={isValue(activeStep, step) ? 'step' : undefined}
              onClick={chooseStep(step)}
            >
              <span className="import-step__number">{stepNumber}</span>
              {done && !hasError ? <IconCheck aria-hidden="true" stroke={2} /> : null}
              {hasError ? <IconAlertTriangle aria-hidden="true" stroke={2} /> : null}
              <strong>{t(`peopleImport.step.${step}`)}</strong>
              {hasError ? <span className="sr-only">{t('peopleImport.stepFailed')}</span> : null}
              {done && !hasError ? <span className="sr-only">{t('peopleImport.stepComplete')}</span> : null}
            </AccessibleNativeButton>
          );
        })}
      </nav>
      <section className="content-surface import-workspace" aria-labelledby="import-step-title">
        <header className="section-heading">
          <div>
            <h2 id="import-step-title" ref={stepTitle} tabIndex={-1}>
              {t(`peopleImport.step.${activeStep}`)}
            </h2>
            <p>{t(`peopleImport.stepDescription.${activeStep}`)}</p>
          </div>
          {batch ? <code>{batch.batchId}</code> : null}
        </header>
        {processing ? (
          <div aria-live="polite">
            <StatePanel state="processing" />
            <p className="processing-caption">{t('peopleImport.processingSafeLeave')}</p>
          </div>
        ) : null}
        {error && !dialog ? <ApiErrorState error={error} /> : null}
        {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
        {!processing ? (
          <StepContent
            step={activeStep}
            templates={templates}
            batch={batch}
            capabilities={capabilities}
            selectedFile={selectedFile}
            mutationKey={mutationKey}
            onFileChange={setSelectedFile}
            onBatchChange={onBatchChange}
            onStepChange={setActiveStep}
            onExecute={execute}
            onUpload={handleUpload}
            onDialog={setDialog}
          />
        ) : null}
      </section>
      {batch ? (
        <ImportActionDialog
          action={dialog}
          batch={batch}
          processing={processing}
          error={error}
          onCancel={closeActionDialog}
          onConfirm={handleDialog}
        />
      ) : null}
    </>
  );
}

function StepContent({
  step,
  templates,
  batch,
  capabilities,
  selectedFile,
  mutationKey,
  onFileChange,
  onBatchChange,
  onStepChange,
  onExecute,
  onUpload,
  onDialog,
}: {
  step: StepKey;
  templates: PeopleImportTemplateVersion[];
  batch?: PeopleImportBatchDetail;
  capabilities: string[];
  selectedFile?: File;
  mutationKey: (scope: string) => string;
  onFileChange: (file?: File) => void;
  onBatchChange: (batch: PeopleImportBatchDetail) => void;
  onStepChange: (step: StepKey) => void;
  onExecute: (scope: string, action: () => Promise<string | void>, success: string) => Promise<void>;
  onUpload: () => void;
  onDialog: (action: ImportDialogAction) => void;
}) {
  const { t } = useTranslation();
  const continueAfterBatchCreation = (created: PeopleImportBatchDetail) => {
    onBatchChange(created);
    onStepChange('upload');
  };
  const acceptSelectedFile = (file: File) => {
    onFileChange(file);
    return false;
  };
  const removeSelectedFile = () => {
    onFileChange(undefined);
    return true;
  };
  const saveMapping = async (
    entries: PeopleImportMappingEntry[],
    reason: string,
  ) => {
    if (!batch) return;
    await onExecute('mapping', async () => {
      const updated = await replacePeopleImportMapping(
        batch.batchId,
        { entries, reason },
        batch.rowVersion,
        mutationKey('mapping'),
      );
      onBatchChange(updated);
      onStepChange('precheck');
    }, t('peopleImport.mappingSaved'));
  };
  const runPrecheck = () => {
    if (!batch) return;
    void onExecute('precheck', async () => {
      const updated = await precheckPeopleImport(
        batch.batchId,
        batch.rowVersion,
        mutationKey('precheck'),
      );
      onBatchChange(updated);
      if (!isValue(updated.status, 'VALIDATING')) onStepChange('diff');
    }, t('peopleImport.precheckStarted'));
  };
  const continueToPublish = () => onStepChange('publish');
  const openVoidDialog = () => onDialog('void');
  const openPublishDialog = () => onDialog('publish');
  const openRollbackDialog = () => onDialog('rollback');

  if (isValue(step, 'template')) {
    return (
      <TemplateStep
        templates={templates}
        canCreate={capabilities.includes('PEOPLE_IMPORT:CREATE')}
        onBatchChange={continueAfterBatchCreation}
        onExecute={onExecute}
        mutationKey={mutationKey}
      />
    );
  }
  if (!batch) return <StatePanel state="empty" description={t('peopleImport.createBatchFirst')} />;
  if (isValue(step, 'upload')) {
    return (
      <div>
        <Upload.Dragger
          accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          maxCount={1}
          fileList={selectedFile ? [{
            uid: selectedFile.name,
            name: selectedFile.name,
            status: 'done',
            originFileObj: selectedFile as never,
          }] : []}
          beforeUpload={acceptSelectedFile}
          onRemove={removeSelectedFile}
        >
          <IconUpload aria-hidden="true" stroke={2} size="var(--size-icon-lg)" />
          <p className="upload-title">{t('peopleImport.chooseFile')}</p>
          <p>{t('peopleImport.fileHelp')}</p>
        </Upload.Dragger>
        {batch.file ? (
          <Alert
            className="section-spaced"
            showIcon
            type="success"
            title={t('peopleImport.serverFileSaved')}
            description={`${batch.file.originalFileName} · ${formatBytes(batch.file.sizeBytes)}`}
          />
        ) : null}
        <div className="wizard-actions">
          <AccessibleButton
            label={t('peopleImport.uploadFile')}
            type="primary"
            icon={<IconUpload aria-hidden="true" stroke={2} />}
            disabled={!selectedFile || !capabilities.includes('PEOPLE_IMPORT:UPLOAD')}
            onClick={onUpload}
          >
            {t('peopleImport.uploadFile')}
          </AccessibleButton>
        </div>
      </div>
    );
  }
  if (isValue(step, 'mapping')) {
    const template = templates.find((item) => (
      isValue(item.templateType, batch.templateType)
      && isValue(item.templateVersion, batch.templateVersion)
    ));
    return (
      <MappingStep
        key={batch.batchId}
        template={template}
        batch={batch}
        canMap={capabilities.includes('PEOPLE_IMPORT:MAP')}
        onSave={saveMapping}
      />
    );
  }
  if (isValue(step, 'precheck')) {
    const summary = batch.precheckSummary;
    return (
      <div>
        <Alert showIcon type="info" title={t('peopleImport.precheckNoWrite')} />
        {summary ? <PrecheckMetrics summary={summary} /> : (
          <StatePanel state="empty" description={t('peopleImport.precheckNotRun')} />
        )}
        {summary?.blockingIssueCount ? (
          <Alert
            showIcon
            type="error"
            title={t('peopleImport.BLOCKING_PRECHECK_ERRORS', { count: summary.blockingIssueCount })}
          />
        ) : null}
        <div className="wizard-actions">
          <AccessibleButton
            label={t('peopleImport.runPrecheck')}
            type="primary"
            icon={<IconPlayerPlay aria-hidden="true" stroke={2} />}
            disabled={!batch.file
              || isValue(batch.mapping.length, 0)
              || !capabilities.includes('PEOPLE_IMPORT:PRECHECK')}
            onClick={runPrecheck}
          >
            {t('peopleImport.runPrecheck')}
          </AccessibleButton>
        </div>
      </div>
    );
  }
  if (isValue(step, 'diff')) {
    return (
      <>
        <PrecheckMetrics summary={batch.precheckSummary ?? zeroSummary} />
        <ImportResults
          key={batch.batchId}
          batchId={batch.batchId}
          canDownloadReport={capabilities.includes('PEOPLE_IMPORT:ERROR_REPORT_DOWNLOAD')}
        />
        <div className="wizard-actions">
          <AccessibleButton
            label={t('peopleImport.continuePublish')}
            type="primary"
            onClick={continueToPublish}
          >
            {t('peopleImport.continuePublish')}
          </AccessibleButton>
        </div>
      </>
    );
  }
  const blocking = batch.precheckSummary?.blockingIssueCount ?? 0;
  const precheckVersionMissing = isValue(batch.precheckVersion, null);
  return (
    <div>
      {isValue(batch.status, 'PUBLISHED') && batch.publication ? (
        <OperationFeedback
          kind="success"
          message={batch.publication.deduplicated
            ? t('peopleImport.deduplicated')
            : t('peopleImport.publishSuccess')}
        />
      ) : (
        <Alert
          showIcon
          type={blocking > 0 || precheckVersionMissing ? 'error' : 'warning'}
          title={blocking > 0
            ? t('peopleImport.BLOCKING_PRECHECK_ERRORS', { count: blocking })
            : precheckVersionMissing
              ? t('peopleImport.precheckVersionRequired')
              : t('peopleImport.publishReady')}
          description={precheckVersionMissing
            ? t('peopleImport.precheckVersionAction')
            : t('peopleImport.publishBoundary')}
        />
      )}
      <div className="wizard-actions">
        {canVoidBatch(batch) || canPublishBatch(batch) ? (
          <>
            {canVoidBatch(batch) ? (
              <AccessibleButton
                label={t('peopleImport.voidDraft')}
                danger
                icon={<IconTrash aria-hidden="true" stroke={2} />}
                disabled={!capabilities.includes('PEOPLE_IMPORT:VOID')}
                onClick={openVoidDialog}
              >
                {t('peopleImport.voidDraft')}
              </AccessibleButton>
            ) : null}
            {canPublishBatch(batch) ? (
              <AccessibleButton
                label={t('peopleImport.publish')}
                type="primary"
                disabled={
                  blocking > 0
                  || precheckVersionMissing
                  || !capabilities.includes('PEOPLE_IMPORT:PUBLISH')
                }
                onClick={openPublishDialog}
              >
                {t('peopleImport.publish')}
              </AccessibleButton>
            ) : null}
          </>
        ) : null}
        {isValue(batch.status, 'PUBLISHED') ? (
          <>
            <AccessibleButton
              label={t('peopleImport.viewOrganization')}
              href="/people/organization"
            >
              {t('peopleImport.viewOrganization')}
            </AccessibleButton>
            <AccessibleButton
              label={t('peopleImport.controlledRollback')}
              danger
              disabled={!capabilities.includes('PEOPLE_IMPORT:ROLLBACK')}
              onClick={openRollbackDialog}
            >
              {t('peopleImport.controlledRollback')}
            </AccessibleButton>
          </>
        ) : null}
      </div>
    </div>
  );
}

function TemplateStep({ templates, canCreate, onBatchChange, onExecute, mutationKey }: {
  templates: PeopleImportTemplateVersion[];
  canCreate: boolean;
  onBatchChange: (batch: PeopleImportBatchDetail) => void;
  onExecute: (scope: string, action: () => Promise<string | void>, success: string) => Promise<void>;
  mutationKey: (scope: string) => string;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<PeopleImportCreateRequest>();
  const selectedType = Form.useWatch('templateType', form);
  const selectedVersion = Form.useWatch('templateVersion', form);
  const selectedTemplate = useMemo(() => templates.find((item) => (
    isValue(item.templateType, selectedType)
    && isValue(item.templateVersion, selectedVersion)
  )), [selectedType, selectedVersion, templates]);
  const changeTemplateType = (changed: Partial<PeopleImportCreateRequest>) => {
    const nextType = changed.templateType;
    if (!nextType) return;
    form.setFieldValue(
      'templateVersion',
      templates.find((item) => isValue(
        item.templateType,
        nextType,
      ))?.templateVersion,
    );
  };
  const createBatch = (values: PeopleImportCreateRequest) => {
    void onExecute('create', async () => {
      const created = await createPeopleImportBatch(values, mutationKey('create'));
      onBatchChange(created);
    }, t('peopleImport.batchCreated'));
  };
  const downloadTemplate = () => {
    if (!selectedTemplate) return;
    void onExecute('template-download', async () => {
      saveDownloadedFile(await downloadPeopleImportTemplate(
        selectedTemplate.templateType,
        selectedTemplate.templateVersion,
      ));
    }, t('peopleImport.templateDownloaded'));
  };

  return (
    <div>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          templateType: 'ORGANIZATION',
          templateVersion: templates.find((item) => isValue(
            item.templateType,
            'ORGANIZATION',
          ))?.templateVersion,
        }}
        onValuesChange={changeTemplateType}
        onFinish={createBatch}
      >
        <div className="form-grid">
          <Form.Item
            name="companyId"
            label={t('peopleImport.companyId')}
            rules={[{ required: true, message: t('peopleImport.companyRequired') }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="templateType"
            label={t('peopleImport.importType')}
            rules={[{ required: true }]}
          >
            <Select options={templateTypeOptions(t)} />
          </Form.Item>
          <Form.Item
            name="templateVersion"
            label={t('peopleImport.templateVersion')}
            rules={[{ required: true }]}
          >
            <Select
              options={Array.from(
                templates.filter((item) => isValue(item.templateType, selectedType)),
                (item) => ({ value: item.templateVersion, label: item.templateVersion }),
              )}
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label={t('people.reason')}
            rules={[{ required: true, min: 2, message: t('people.reasonRequired') }]}
          >
            <Input />
          </Form.Item>
        </div>
        {selectedTemplate ? (
          <section className="template-field-catalog" aria-labelledby="template-field-title">
            <div className="section-heading">
              <div>
                <h3 id="template-field-title">{selectedTemplate.fileName}</h3>
                <p>{t('peopleImport.fieldCatalog')}</p>
              </div>
              <AccessibleButton
                label={t('peopleImport.downloadTemplate')}
                icon={<IconDownload aria-hidden="true" stroke={2} />}
                onClick={downloadTemplate}
              >
                {t('peopleImport.downloadTemplate')}
              </AccessibleButton>
            </div>
            <DataTable
              rows={selectedTemplate.fields}
              rowKey={(row) => row.key}
              columns={[
                { key: 'label', title: t('peopleImport.field'), render: (row) => row.label },
                { key: 'key', title: t('peopleImport.systemField'), render: (row) => <code>{row.key}</code> },
                { key: 'required', title: t('common.required'), render: (row) => row.required ? t('people.yes') : t('people.no') },
                { key: 'match', title: t('peopleImport.matchKey'), render: (row) => row.matchKey ? t('people.yes') : t('people.no') },
                { key: 'type', title: t('peopleImport.valueType'), render: (row) => row.valueType },
              ]}
            />
          </section>
        ) : null}
        <Alert
          className="section-spaced"
          showIcon
          type="warning"
          title={t('peopleImport.matchBoundary')}
          description={t('peopleImport.matchBoundaryDescription')}
        />
        <div className="wizard-actions">
          <AccessibleButton
            label={t('peopleImport.createBatch')}
            type="primary"
            htmlType="submit"
            icon={<IconFileSpreadsheet aria-hidden="true" stroke={2} />}
            disabled={!canCreate}
          >
            {t('peopleImport.createBatch')}
          </AccessibleButton>
        </div>
      </Form>
    </div>
  );
}

function MappingStep({ template, batch, canMap, onSave }: {
  template?: PeopleImportTemplateVersion;
  batch: PeopleImportBatchDetail;
  canMap: boolean;
  onSave: (entries: PeopleImportMappingEntry[], reason: string) => Promise<void>;
}) {
  const { t } = useTranslation();
  const initialEntries = template
    ? Array.from(template.fields, (field) => ({
        sourceColumn: field.label,
        targetField: field.key,
      }))
    : batch.mapping;
  const [entries, setEntries] = useState(initialEntries);
  const [reason, setReason] = useState('');
  const changeSourceColumn = (targetField: string) => (
    event: ChangeEvent<HTMLInputElement>,
  ) => {
    setEntries((current) => Array.from(current, (entry) => (
      isValue(entry.targetField, targetField)
        ? { ...entry, sourceColumn: event.target.value }
        : entry
    )));
  };
  const changeReason = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setReason(event.target.value);
  };
  const save = () => {
    void onSave(entries, reason);
  };
  if (!template) return <StatePanel state="404" description={t('peopleImport.templateMissing')} />;
  return (
    <div>
      <DataTable
        rows={template.fields}
        rowKey={(row) => row.key}
        columns={[
          {
            key: 'source',
            title: t('peopleImport.sourceColumn'),
            render: (row) => (
              <Input
                aria-label={`${t('peopleImport.sourceColumn')} ${row.label}`}
                value={entries.find((entry) => isValue(
                  entry.targetField,
                  row.key,
                ))?.sourceColumn}
                onChange={changeSourceColumn(row.key)}
              />
            ),
          },
          { key: 'target', title: t('peopleImport.systemField'), render: (row) => <code>{row.key}</code> },
          { key: 'required', title: t('common.required'), render: (row) => row.required ? t('people.yes') : t('people.no') },
          { key: 'match', title: t('peopleImport.matchKey'), render: (row) => row.matchKey ? t('people.yes') : t('people.no') },
        ]}
      />
      <label className="field-label" htmlFor="mapping-reason">{t('people.reason')}</label>
      <Input.TextArea
        id="mapping-reason"
        rows={3}
        value={reason}
        onChange={changeReason}
        aria-describedby="mapping-reason-help"
      />
      <p id="mapping-reason-help" className="form-help">{t('peopleImport.mappingReasonHelp')}</p>
      <div className="wizard-actions">
        <AccessibleButton
          label={t('peopleImport.saveMapping')}
          type="primary"
          disabled={!canMap || reason.trim().length < 2 || entries.some((entry) => !entry.sourceColumn.trim())}
          onClick={save}
        >
          {t('peopleImport.saveMapping')}
        </AccessibleButton>
      </div>
    </div>
  );
}

function PrecheckMetrics({ summary }: {
  summary: NonNullable<PeopleImportBatchDetail['precheckSummary']>;
}) {
  const { t } = useTranslation();
  const values = [
    ['added', summary.added],
    ['updated', summary.updated],
    ['unchanged', summary.unchanged],
    ['conflict', summary.conflict],
    ['error', summary.error],
  ] as const;
  return (
    <dl className="people-metrics">
      {Array.from(values, ([key, value]) => (
        <div
          key={key}
          data-tone={isValue(key, 'error')
            ? 'danger'
            : isValue(key, 'conflict')
              ? 'warning'
              : 'neutral'}
        >
          <dt>{t(`peopleImport.metric.${key}`)}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

const zeroSummary = {
  added: 0,
  updated: 0,
  unchanged: 0,
  conflict: 0,
  error: 0,
  blockingIssueCount: 0,
};

export function stepForBatch(batch?: PeopleImportBatchDetail): StepKey {
  if (!batch) return 'template';
  if (isValue(batch.status, 'PUBLISHED')
      || isValue(batch.status, 'VOIDED')
      || isValue(batch.status, 'PUBLISHING')
      || isValue(batch.status, 'PUBLISH_FAILED')) {
    return 'publish';
  }
  if (isValue(batch.status, 'AWAITING_CONFIRMATION')
      || isValue(batch.status, 'VALIDATION_FAILED')) return 'diff';
  if (isValue(batch.status, 'VALIDATING')) return 'precheck';
  if (!batch.file) return 'upload';
  if (isValue(batch.mapping.length, 0)) return 'mapping';
  return 'precheck';
}

export function canVoidBatch(batch: PeopleImportBatchDetail): boolean {
  return ['DRAFT', 'VALIDATION_FAILED', 'AWAITING_CONFIRMATION'].includes(batch.status);
}

export function canPublishBatch(batch: PeopleImportBatchDetail): boolean {
  return isValue(batch.status, 'AWAITING_CONFIRMATION')
    || isValue(batch.status, 'PUBLISH_FAILED');
}

function isStepReachable(step: StepKey, batch?: PeopleImportBatchDetail): boolean {
  if (isValue(step, 'template')) return true;
  if (!batch) return false;
  if (isValue(step, 'upload')) {
    return !isValue(batch.status, 'PUBLISHED') && !isValue(batch.status, 'VOIDED');
  }
  if (isValue(step, 'mapping')) {
    return Boolean(batch.file)
      && !isValue(batch.status, 'PUBLISHED')
      && !isValue(batch.status, 'VOIDED');
  }
  if (isValue(step, 'precheck')) return Boolean(batch.file && batch.mapping.length);
  if (isValue(step, 'diff')) return Boolean(batch.precheckSummary);
  return Boolean(batch.precheckSummary);
}

function isServerStepComplete(step: StepKey, batch?: PeopleImportBatchDetail): boolean {
  if (!batch) return false;
  if (isValue(step, 'template')) return true;
  if (isValue(step, 'upload')) return Boolean(batch.file);
  if (isValue(step, 'mapping')) return batch.mapping.length > 0;
  if (isValue(step, 'precheck')) return Boolean(batch.precheckSummary);
  if (isValue(step, 'diff')) {
    return isValue(batch.status, 'AWAITING_CONFIRMATION')
      || isValue(batch.status, 'PUBLISHED');
  }
  return isValue(batch.status, 'PUBLISHED');
}

function templateTypeOptions(t: (key: string) => string) {
  const types: PeopleImportTemplateType[] = ['ORGANIZATION', 'EMPLOYEE', 'EMPLOYMENT', 'PRIOR_SERVICE'];
  return Array.from(types, (value) => ({ value, label: t(`peopleImport.type.${value}`) }));
}

function isValue<T, Expected extends T>(
  value: T,
  expected: Expected,
): value is Expected {
  return Object.is(value, expected);
}

function formatBytes(value: number): string {
  return new Intl.NumberFormat('zh-CN', {
    style: 'unit',
    unit: 'megabyte',
    maximumFractionDigits: 2,
  }).format(value / 1024 / 1024);
}

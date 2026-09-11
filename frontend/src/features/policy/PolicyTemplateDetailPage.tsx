import { Button, Form, Input, Modal } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';

import { OperationFeedback, ReadOnlyDetails, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { policyResultText, VersionTimeline } from './PolicyComponents';
import {
  createDraft,
  getPolicyTemplate,
  listPolicyVersions,
} from './policyApi';

export function PolicyTemplateDetailPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const { templateId = '' } = useParams();
  const navigate = useNavigate();
  const [draftOpen, setDraftOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [form] = Form.useForm();
  const templateLoader = useMemo(() => () => getPolicyTemplate(templateId), [templateId]);
  const versionsLoader = useMemo(() => () => listPolicyVersions(templateId), [templateId]);
  const templateResource = useAsyncResource(templateLoader, () => false, [templateId]);
  const versionsResource = useAsyncResource(versionsLoader, (page) => page.items.length === 0, [templateId]);

  const submitDraft = async (values: { effectiveFrom: string; effectiveTo?: string; changeReason: string }) => {
    setProcessing(true);
    try {
      const draft = await createDraft(templateId, values);
      setDraftOpen(false);
      setFeedback(t('policy.draftCreated'));
      navigate(`/rules/templates/${templateId}/versions/${draft.versionId}`);
    } finally {
      setProcessing(false);
    }
  };

  if (templateResource.resource.status === 'loading' || templateResource.resource.status === 'partial-loading') return <StatePanel state={templateResource.resource.status} />;
  if ('error' in templateResource.resource) return <StatePanel state={templateResource.resource.status} description={policyResultText(templateResource.resource.error.message)} onRetry={templateResource.reload} />;
  if (templateResource.resource.status !== 'ready') return <StatePanel state="404" />;
  const template = templateResource.resource.data;
  return (
    <>
      <PageHeader
        title={template.name}
        description={template.description}
        breadcrumbs={[{ label: t('rules.title'), path: '/rules' }, { label: t('rules.templates'), path: '/rules/templates' }, { label: template.code }]}
        actions={capabilities.includes('POLICY:CREATE') ? <Button type="primary" onClick={() => setDraftOpen(true)}>{t('policy.createDraft')}</Button> : undefined}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <div className="detail-grid">
        <section className="content-surface">
          <h2>{t('policy.definition')}</h2>
          <ReadOnlyDetails items={[
            { label: t('policy.templateCode'), value: <code>{template.code}</code> },
            { label: t('policy.status'), value: <StatusBadge status={template.status} /> },
            { label: t('policy.latestVersion'), value: `V${template.latestVersionNumber}` },
          ]} />
          <h3>{t('policy.controlledFields')}</h3>
          <ul className="definition-list">
            {template.fieldDefinitions?.map((field) => (
              <li key={field.key}>
                <strong>{field.label}</strong>
                <span>
                  {fieldTypeLabel(field.valueType)}
                  {' · '}
                  {field.required ? t('common.required') : t('common.optional')}
                </span>
              </li>
            ))}
          </ul>
        </section>
        <section className="content-surface">
          <h2>{t('policy.versionTimeline')}</h2>
          {versionsResource.resource.status === 'loading' || versionsResource.resource.status === 'partial-loading' ? <StatePanel state={versionsResource.resource.status} /> : null}
          {versionsResource.resource.status === 'empty' ? <StatePanel state="empty" description={t('policy.noVersions')} /> : null}
          {'error' in versionsResource.resource ? <StatePanel state={versionsResource.resource.status} description={policyResultText(versionsResource.resource.error.message)} onRetry={versionsResource.reload} /> : null}
          {versionsResource.resource.status === 'ready' ? <VersionTimeline versions={versionsResource.resource.data.items} onSelect={(version) => navigate(`/rules/templates/${templateId}/versions/${version.versionId}`)} /> : null}
        </section>
      </div>
      <Modal open={draftOpen} title={t('policy.createDraftTitle')} okText={t('policy.createDraft')} cancelText={t('common.cancel')} confirmLoading={processing} onOk={() => void form.submit()} onCancel={() => setDraftOpen(false)}>
        <Form form={form} layout="vertical" onFinish={(values) => void submitDraft(values)}>
          <Form.Item label={t('policy.effectiveFrom')} name="effectiveFrom" rules={[{ required: true, message: t('policy.effectiveFromRequired') }]}><Input type="date" /></Form.Item>
          <Form.Item label={t('policy.effectiveTo')} name="effectiveTo"><Input type="date" /></Form.Item>
          <Form.Item label={t('policy.changeReason')} name="changeReason" rules={[{ required: true, min: 4, message: t('policy.changeReasonRequired') }]}><Input.TextArea /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export default PolicyTemplateDetailPage;

function fieldTypeLabel(valueType: string): string {
  return ({
    ENUM: '选项',
    INTEGER: '整数',
    DECIMAL: '数值',
    BOOLEAN: '是/否',
    STRING: '文本',
  } as Readonly<Record<string, string>>)[valueType] ?? '业务参数';
}

import { Button, Form, Input, Modal } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { DataTable } from '../../shared/components/DataTable';
import { OperationFeedback, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, QueryFilterBar } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { policyResultText } from './PolicyComponents';
import { createPolicyTemplate, listPolicyTemplates } from './policyApi';

export function PolicyTemplatesPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [feedback, setFeedback] = useState<string>();
  const [form] = Form.useForm();
  const loader = useMemo(() => () => listPolicyTemplates({ q: query }), [query]);
  const { resource, reload } = useAsyncResource(loader, (page) => page.items.length === 0, [query]);

  const create = async (values: { code: string; name: string; description: string }) => {
    setProcessing(true);
    try {
      await createPolicyTemplate({
        ...values,
        fieldDefinitions: [{ key: 'mode', label: t('policy.judgementMode'), valueType: 'ENUM', required: true, enumValues: ['STRICT', 'BALANCED'] }],
      });
      setCreateOpen(false);
      form.resetFields();
      setFeedback(t('policy.templateCreated'));
      reload();
    } finally {
      setProcessing(false);
    }
  };

  return (
    <>
      <PageHeader title={t('rules.templates')} description={t('policy.templateDescription')} breadcrumbs={[{ label: t('rules.title'), path: '/rules' }, { label: t('rules.templates') }]} actions={capabilities.includes('POLICY:CREATE') ? <Button type="primary" onClick={() => setCreateOpen(true)}>{t('policy.createTemplate')}</Button> : undefined} />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      <section className="content-surface">
        <QueryFilterBar query={query} onQueryChange={setQuery} placeholder={t('policy.searchTemplates')}><Button onClick={reload}>{t('common.refresh')}</Button></QueryFilterBar>
        {resource.status === 'loading' || resource.status === 'partial-loading' ? <StatePanel state={resource.status} /> : null}
        {resource.status === 'empty' ? <StatePanel state="empty" description={t('policy.noTemplates')} /> : null}
        {'error' in resource ? <StatePanel state={resource.status} description={policyResultText(resource.error.message)} onRetry={reload} /> : null}
        {resource.status === 'ready' ? <DataTable rows={resource.data.items} rowKey={(row) => row.templateId} columns={[
          { key: 'code', title: t('policy.templateCode'), render: (row) => <Link to={`/rules/templates/${row.templateId}`}>{row.code}</Link> },
          { key: 'name', title: t('policy.templateName'), render: (row) => row.name },
          { key: 'status', title: t('policy.status'), render: (row) => <StatusBadge status={row.status} /> },
          { key: 'version', title: t('policy.latestVersion'), render: (row) => `V${row.latestVersionNumber}` },
        ]} /> : null}
      </section>
      <Modal open={createOpen} title={t('policy.createTemplateTitle')} okText={t('policy.create')} cancelText={t('common.cancel')} confirmLoading={processing} onOk={() => void form.submit()} onCancel={() => setCreateOpen(false)}>
        <Form form={form} layout="vertical" onFinish={(values) => void create(values)}>
          <Form.Item label={t('policy.templateCode')} name="code" rules={[{ required: true, pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: t('policy.templateCodeRule') }]}><Input /></Form.Item>
          <Form.Item label={t('policy.templateName')} name="name" rules={[{ required: true, message: t('policy.templateNameRequired') }]}><Input /></Form.Item>
          <Form.Item label={t('policy.templateDescriptionLabel')} name="description" rules={[{ required: true, message: t('policy.templateDescriptionRequired') }]}><Input.TextArea /></Form.Item>
        </Form>
      </Modal>
    </>
  );
}

export default PolicyTemplatesPage;

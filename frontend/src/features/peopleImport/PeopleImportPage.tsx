import { IconDownload, IconUpload } from '@tabler/icons-react';
import { Alert, Form, Input, Table, Upload } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiRequestError } from '../../shared/api/apiClient';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { OperationFeedback } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState } from '../people/PeopleCommon';
import {
  publishRosterImport,
  saveRosterTemplate,
  uploadRosterFile,
  type RosterImportBatch,
} from './rosterImportApi';

export function PeopleImportPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ reason: string }>();
  const [file, setFile] = useState<File>();
  const [batch, setBatch] = useState<RosterImportBatch>();
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<ApiRequestError>();
  const [feedback, setFeedback] = useState<string>();
  const canUpload = capabilities.includes('PEOPLE_IMPORT:UPLOAD');
  const canPublish = capabilities.includes('PEOPLE_IMPORT:PUBLISH');
  const canDownload = capabilities.includes('PEOPLE_IMPORT:TEMPLATE_DOWNLOAD');
  const blocked = Boolean(batch && (batch.summary.blocking > 0 || batch.summary.conflict > 0 || batch.summary.error > 0));

  const run = async (action: () => Promise<string>) => {
    setProcessing(true);
    setError(undefined);
    setFeedback(undefined);
    try {
      setFeedback(await action());
    } catch (caught: unknown) {
      setError(caught instanceof ApiRequestError
        ? caught
        : new ApiRequestError(0, { code: 'ROSTER_IMPORT_FAILED', retryable: true }));
    } finally {
      setProcessing(false);
    }
  };

  return (
    <section>
      <PageHeader
        title={t('peopleImport.title')}
        description={t('peopleImport.description')}
        breadcrumbs={[
          { label: t('people.section') },
          { label: t('peopleImport.title') },
        ]}
        actions={(
          <>
            {canDownload ? (
              <AccessibleButton
                label={t('peopleImport.downloadTemplate')}
                icon={<IconDownload aria-hidden="true" stroke={2} />}
                onClick={() => void run(async () => {
                  await saveRosterTemplate();
                  return t('peopleImport.templateDownloaded');
                })}
              >
                {t('peopleImport.downloadTemplate')}
              </AccessibleButton>
            ) : null}
          </>
        )}
      />
      {feedback ? <OperationFeedback kind="success" message={feedback} /> : null}
      {error ? <ApiErrorState error={error} /> : null}
      <Alert
        showIcon
        type="info"
        title={t('peopleImport.boundaryTitle')}
        description={t('peopleImport.rosterHelp')}
      />
      <Form form={form} layout="vertical" initialValues={{ reason: '花名册导入' }}>
        <Form.Item name="reason" label={t('people.reason')} rules={[{ required: true, min: 2 }, { max: 500 }]}>
          <Input.TextArea rows={2} />
        </Form.Item>
        {canUpload ? (
          <Upload.Dragger
            accept=".xlsx"
            maxCount={1}
            beforeUpload={(next) => {
              setFile(next);
              setBatch(undefined);
              return false;
            }}
            onRemove={() => {
              setFile(undefined);
              setBatch(undefined);
            }}
          >
            <p>{t('peopleImport.chooseFile')}</p>
            <p>{t('peopleImport.fileHelp')}</p>
          </Upload.Dragger>
        ) : null}
      </Form>
      <div className="page-actions">
        {canUpload ? (
          <AccessibleButton
            label={t('peopleImport.precheck')}
            icon={<IconUpload aria-hidden="true" stroke={2} />}
            disabled={!file || processing}
            onClick={() => void run(async () => {
              const reason = (await form.validateFields()).reason.trim();
              setBatch(await uploadRosterFile(file as File, reason));
              return t('peopleImport.precheckDone');
            })}
          >
            {t('peopleImport.precheck')}
          </AccessibleButton>
        ) : null}
        {canPublish && batch ? (
          <AccessibleButton
            label={t('peopleImport.publish')}
            disabled={processing || blocked || batch.status === 'PUBLISHED'}
            onClick={() => void run(async () => {
              const reason = (await form.validateFields()).reason.trim();
              setBatch(await publishRosterImport(batch.batchId, reason));
              return t('peopleImport.publishSuccess');
            })}
          >
            {t('peopleImport.publish')}
          </AccessibleButton>
        ) : null}
      </div>
      {processing ? <StatePanel state="loading" /> : null}
      {batch ? (
        <>
          <p>
            {t('peopleImport.precheckSummary', {
              added: batch.summary.added,
              updated: batch.summary.updated,
              unchanged: batch.summary.unchanged,
              conflict: batch.summary.conflict,
              error: batch.summary.error,
            })}
          </p>
          {blocked ? (
            <Alert showIcon type="warning" title={t('peopleImport.blockedTitle')} />
          ) : null}
          {batch.issues.length > 0 ? (
            <Table
              rowKey={(row) => `${row.rowNumber}-${row.code}`}
              pagination={false}
              dataSource={batch.issues}
              columns={[
                { title: t('peopleImport.rowNumber'), dataIndex: 'rowNumber', width: 80 },
                {
                  title: t('peopleImport.issueSeverity'),
                  dataIndex: 'severity',
                  width: 100,
                  render: (severity: string) => t(`peopleImport.severity.${severity}`, { defaultValue: severity }),
                },
                { title: t('peopleImport.issueField'), dataIndex: 'field', width: 120 },
                { title: t('peopleImport.issueMessage'), dataIndex: 'message' },
              ]}
            />
          ) : null}
          <Table
            rowKey={(row) => `${row.rowNumber}-${row.category}`}
            pagination={false}
            dataSource={batch.diffs}
            columns={[
              { title: t('peopleImport.rowNumber'), dataIndex: 'rowNumber', width: 80 },
              {
                title: t('peopleImport.diffCategory'),
                dataIndex: 'category',
                width: 120,
                render: (category: string) => t(`peopleImport.category.${category}`, { defaultValue: category }),
              },
              {
                title: t('employee.number'),
                render: (_, row) => String(row.sourceValues.employeeNumber ?? ''),
              },
              {
                title: t('employee.name'),
                render: (_, row) => String(row.sourceValues.displayName ?? ''),
              },
            ]}
          />
        </>
      ) : null}
    </section>
  );
}

export default PeopleImportPage;

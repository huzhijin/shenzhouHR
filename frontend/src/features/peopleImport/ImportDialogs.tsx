import { Alert, Descriptions, Form, Input, Modal } from 'antd';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

import type { ApiRequestError } from '../../shared/api/apiClient';
import { ApiErrorState } from '../people/PeopleCommon';
import type { PeopleImportBatchDetail } from './peopleImportTypes';

export type ImportDialogAction = 'publish' | 'void' | 'rollback';

export function ImportActionDialog({ action, batch, processing, error, onCancel, onConfirm }: {
  action?: ImportDialogAction;
  batch: PeopleImportBatchDetail;
  processing: boolean;
  error?: ApiRequestError;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ reason: string }>();

  useEffect(() => {
    if (action) form.resetFields();
  }, [action, form]);

  const summary = batch.precheckSummary;
  const danger = action === 'void' || action === 'rollback';

  return (
    <Modal
      open={action !== undefined}
      title={action ? t(`peopleImport.dialog.${action}.title`) : undefined}
      okText={action ? t(`peopleImport.dialog.${action}.confirm`) : undefined}
      cancelText={t('common.cancel')}
      okButtonProps={{ danger }}
      confirmLoading={processing}
      onCancel={onCancel}
      onOk={() => void form.submit()}
      destroyOnHidden
    >
      {error ? <ApiErrorState error={error} /> : null}
      {action === 'rollback' ? (
        <Alert
          showIcon
          type="warning"
          title={t('peopleImport.rollbackBoundary')}
          description={t('peopleImport.rollbackBoundaryDescription')}
        />
      ) : null}
      <Descriptions
        className="dialog-summary"
        column={2}
        items={[
          { key: 'batch', label: t('peopleImport.batchId'), children: <code>{batch.batchId}</code> },
          { key: 'type', label: t('peopleImport.importType'), children: t(`peopleImport.type.${batch.templateType}`) },
          { key: 'version', label: t('peopleImport.templateVersion'), children: batch.templateVersion },
          { key: 'state', label: t('peopleImport.batchStatus'), children: t(`peopleImport.status.${batch.status}`) },
          { key: 'added', label: t('peopleImport.metric.added'), children: summary?.added ?? 0 },
          { key: 'updated', label: t('peopleImport.metric.updated'), children: summary?.updated ?? 0 },
          { key: 'errors', label: t('peopleImport.metric.error'), children: summary?.error ?? 0 },
          { key: 'reversible', label: t('peopleImport.reversibility'), children: t('peopleImport.forwardCorrection') },
        ]}
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => onConfirm(values.reason)}
      >
        <Form.Item
          label={t('people.reason')}
          name="reason"
          rules={[
            { required: true, message: t('people.reasonRequired') },
            { min: 2, message: t('people.reasonLength') },
          ]}
        >
          <Input.TextArea
            autoFocus
            rows={3}
            aria-describedby="people-import-action-reason-help"
          />
        </Form.Item>
        <p className="form-help" id="people-import-action-reason-help">
          {action ? t(`peopleImport.dialog.${action}.description`) : ''}
        </p>
      </Form>
    </Modal>
  );
}

import { Form, Input, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import type {
  AttendancePolicyKind,
  PolicyBindingPreviewInput,
  PolicyTemplateDefinition,
} from './attendanceSetupTypes';

export function PolicyBindingDialog({
  open,
  processing,
  catalog,
  policyKind,
  policyVersionId,
  initialValues,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  processing: boolean;
  catalog: PolicyTemplateDefinition[];
  policyKind: AttendancePolicyKind;
  policyVersionId: string;
  initialValues?: Partial<PolicyBindingPreviewInput>;
  onSubmit: (values: PolicyBindingPreviewInput) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<PolicyBindingPreviewInput>();
  return (
    <Modal
      open={open}
      title={initialValues
        ? t('attendanceSetup.updateBinding')
        : t('attendanceSetup.createBinding')}
      okText={initialValues ? t('attendanceSetup.save') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<PolicyBindingPreviewInput>
        form={form}
        layout="vertical"
        initialValues={{
          policyKind,
          policyVersionId,
          ...initialValues,
        }}
        onFinish={onSubmit}
      >
        <Form.Item label={t('attendanceSetup.policyKind')} name="policyKind" rules={[required()]}>
          <Select options={catalog.map((template) => ({
            value: template.policyKind,
            label: template.name,
          }))} />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.policyVersionId')}
          name="policyVersionId"
          rules={[required()]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.groupId')} name="groupId" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.groupRevisionId')}
          name="groupRevisionId"
          rules={[required()]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <div className="form-grid">
          <Form.Item label={t('attendanceSetup.effectiveFrom')} name="effectiveFrom" rules={[required()]}>
            <Input type="date" />
          </Form.Item>
          <Form.Item label={t('attendanceSetup.effectiveTo')} name="effectiveTo">
            <Input type="date" />
          </Form.Item>
        </div>
        <Form.Item
          label={t('attendanceSetup.reason')}
          name="reason"
          rules={[{
            required: true,
            min: 2,
            max: 500,
            message: t('attendanceSetup.reasonRequired'),
          }]}
        >
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function required() {
  return { required: true };
}

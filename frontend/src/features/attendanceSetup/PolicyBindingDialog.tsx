import { Form, Input, Modal, Select } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { listAttendanceGroups } from './attendanceSetupApi';
import type {
  AttendanceGroupView,
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
  const [groups, setGroups] = useState<AttendanceGroupView[]>([]);
  useEffect(() => {
    let active = true;
    if (!open) return () => {
      active = false;
    };
    void listAttendanceGroups(undefined, 0, 500)
      .then((page) => {
        if (active) setGroups(page.items);
      })
      .catch(() => {
        if (active) setGroups([]);
      });
    return () => {
      active = false;
    };
  }, [open]);
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
        {/* The API needs immutable identifiers, but operators choose by business name. */}
        <Form.Item name="policyVersionId" hidden rules={[required()]}>
          <Input />
        </Form.Item>
        <Form.Item label="考勤组" name="groupId" rules={[required()]}>
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="请选择考勤组"
            options={groups.map((group) => ({
              value: group.groupId,
              label: `${group.name}（${group.code}）· 修订 ${group.revisionNumber}`,
            }))}
            onChange={(groupId: string) => {
              form.setFieldsValue({
                groupId,
                groupRevisionId: groups.find((group) => group.groupId === groupId)
                  ?.groupRevisionId,
              });
            }}
          />
        </Form.Item>
        <Form.Item name="groupRevisionId" hidden rules={[required()]}>
          <Input />
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

import { Form, Input, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import type {
  AssignmentInput,
  AttendanceGroupInput,
  LocationInput,
  LocationView,
} from './attendanceSetupTypes';

interface DialogProps<T> {
  open: boolean;
  processing: boolean;
  onSubmit: (values: T) => void;
  onCancel: () => void;
}

export function LocationDialog({
  open,
  processing,
  initialValues,
  onSubmit,
  onCancel,
}: DialogProps<LocationInput> & { initialValues?: LocationInput }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<LocationInput>();
  return (
    <Modal
      open={open}
      title={initialValues
        ? t('attendanceSetup.rolloverLocation')
        : t('attendanceSetup.createLocation')}
      okText={initialValues ? t('attendanceSetup.appendRevision') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<LocationInput>
        form={form}
        layout="vertical"
        initialValues={initialValues ?? { timeZone: 'Asia/Shanghai' }}
        onFinish={onSubmit}
      >
        <Form.Item
          label={t('attendanceSetup.legalEntityId')}
          name="legalEntityId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" disabled={initialValues !== undefined} />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.code')}
          name="code"
          rules={[requiredRule(t('attendanceSetup.required')), codeRule(t('attendanceSetup.codeRule'))]}
        >
          <Input autoComplete="off" disabled={initialValues !== undefined} />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.name')}
          name="name"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.timeZone')}
          name="timeZone"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Select
            options={[
              { value: 'Asia/Shanghai', label: 'Asia/Shanghai' },
              { value: 'Asia/Singapore', label: 'Asia/Singapore' },
            ]}
          />
        </Form.Item>
        <EffectivePeriodFields />
        <ReasonField />
      </Form>
    </Modal>
  );
}

export function AttendanceGroupDialog({
  open,
  processing,
  initialValues,
  locations,
  onSubmit,
  onCancel,
}: DialogProps<AttendanceGroupInput> & {
  initialValues?: AttendanceGroupInput;
  locations: LocationView[];
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<AttendanceGroupInput>();
  return (
    <Modal
      open={open}
      title={initialValues
        ? t('attendanceSetup.rolloverGroup')
        : t('attendanceSetup.createGroup')}
      okText={initialValues ? t('attendanceSetup.appendRevision') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<AttendanceGroupInput>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onSubmit}
      >
        <Form.Item
          label={t('attendanceSetup.legalEntityId')}
          name="legalEntityId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" disabled={initialValues !== undefined} />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.code')}
          name="code"
          rules={[requiredRule(t('attendanceSetup.required')), codeRule(t('attendanceSetup.codeRule'))]}
        >
          <Input autoComplete="off" disabled={initialValues !== undefined} />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.name')}
          name="name"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.locationName')}
          name="locationId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Select
            options={locations.map((location) => ({
              value: location.locationId,
              label: `${location.code} · ${location.name}`,
            }))}
          />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.calendarId')}
          name="calendarId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.shiftTemplateId')}
          name="shiftTemplateId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <EffectivePeriodFields />
        <ReasonField />
      </Form>
    </Modal>
  );
}

export function AssignmentDialog({
  open,
  processing,
  initialValues,
  onSubmit,
  onCancel,
}: DialogProps<AssignmentInput> & { initialValues?: AssignmentInput }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<AssignmentInput>();
  return (
    <Modal
      open={open}
      title={initialValues
        ? t('attendanceSetup.rolloverAssignment')
        : t('attendanceSetup.createAssignment')}
      okText={initialValues ? t('attendanceSetup.appendSuccessor') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<AssignmentInput>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onSubmit}
      >
        <Form.Item
          label={t('attendanceSetup.employeeId')}
          name="employeeId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input autoComplete="off" disabled={initialValues !== undefined} />
        </Form.Item>
        <EffectivePeriodFields />
        <ReasonField />
      </Form>
    </Modal>
  );
}

function EffectivePeriodFields() {
  const { t } = useTranslation();
  return (
    <div className="form-grid">
      <Form.Item
        label={t('attendanceSetup.effectiveFrom')}
        name="effectiveFrom"
        rules={[requiredRule(t('attendanceSetup.required'))]}
      >
        <Input type="date" />
      </Form.Item>
      <Form.Item label={t('attendanceSetup.effectiveTo')} name="effectiveTo">
        <Input type="date" />
      </Form.Item>
    </div>
  );
}

function ReasonField() {
  const { t } = useTranslation();
  return (
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
  );
}

function requiredRule(message: string) {
  return { required: true, whitespace: true, message };
}

function codeRule(message: string) {
  return {
    pattern: /^[A-Z][A-Z0-9_-]{1,63}$/,
    message,
  };
}

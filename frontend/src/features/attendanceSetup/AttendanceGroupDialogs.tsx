import { Form, Input, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import {
  CalendarSelect,
  CompanySelect,
  EmployeeSelect,
  ShiftTemplateSelect,
} from '../referenceData';
import type {
  AssignmentInput,
  AssignmentTransferInput,
  AssignmentView,
  AttendanceGroupInput,
  AttendanceGroupView,
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
}: DialogProps<LocationInput> & {
  initialValues?: LocationInput;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<LocationInput>();
  if (!initialValues) return null;
  return (
    <Modal
      open={open}
      title={t('attendanceSetup.rolloverLocation')}
      okText={t('attendanceSetup.appendRevision')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<LocationInput>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onSubmit}
      >
        <Form.Item name="companyId" hidden>
          <Input />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.code')}
          name="code"
          rules={[requiredRule(t('attendanceSetup.required')), codeRule(t('attendanceSetup.codeRule'))]}
        >
          <Input autoComplete="off" disabled />
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
          extra="未投入使用的地点可修订；已有考勤配置或历史引用的地点为保护历史不能直接修改时区，请联系系统管理员评估受控迁移。"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Select
            options={[
              { value: 'Asia/Shanghai', label: '中国标准时间（上海）' },
              { value: 'Asia/Singapore', label: '新加坡标准时间' },
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
  defaultCompanyId,
  locations,
  onSubmit,
  onCancel,
}: DialogProps<AttendanceGroupInput> & {
  initialValues?: AttendanceGroupInput;
  defaultCompanyId?: string;
  locations: LocationView[];
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<AttendanceGroupInput>();
  const companyId = Form.useWatch<string>('companyId', form);
  const locationId = Form.useWatch<string>('locationId', form);
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
        initialValues={initialValues ?? { companyId: defaultCompanyId }}
        onFinish={onSubmit}
      >
        <Form.Item
          label="公司"
          name="companyId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <CompanySelect disabled={initialValues !== undefined || Boolean(defaultCompanyId)} />
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
              value: location.companyLocationId,
              label: `${location.code} · ${location.name}`,
            }))}
          />
        </Form.Item>
        <Form.Item
          label="工作日历"
          name="calendarId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <CalendarSelect companyId={companyId} locationId={locationId} />
        </Form.Item>
        <Form.Item
          label="班次"
          name="shiftTemplateId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <ShiftTemplateSelect companyId={companyId} locationId={locationId} />
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
          label="员工"
          name="employeeId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <EmployeeSelect disabled={initialValues !== undefined} />
        </Form.Item>
        <EffectivePeriodFields />
        <ReasonField />
      </Form>
    </Modal>
  );
}

export function AssignmentTransferDialog({
  open,
  processing,
  assignment,
  employeeLabel,
  currentGroupLabel,
  targetGroups,
  initialEffectiveFrom,
  onSubmit,
  onCancel,
}: DialogProps<AssignmentTransferInput> & {
  assignment?: AssignmentView;
  employeeLabel: string;
  currentGroupLabel: string;
  targetGroups: AttendanceGroupView[];
  initialEffectiveFrom: string;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<AssignmentTransferInput>();
  return (
    <Modal
      open={open}
      title={t('attendanceSetup.transferAssignment')}
      okText={t('attendanceSetup.confirmTransfer')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <p className="form-help">{t('attendanceSetup.transferHint')}</p>
      <Form<AssignmentTransferInput>
        form={form}
        layout="vertical"
        initialValues={{ effectiveFrom: initialEffectiveFrom }}
        onFinish={onSubmit}
      >
        <Form.Item label="员工">
          <Input aria-label="员工" value={employeeLabel} disabled />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.currentGroup')}>
          <Input
            aria-label={t('attendanceSetup.currentGroup')}
            value={currentGroupLabel}
            disabled
          />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.targetGroup')}
          name="targetGroupId"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            options={targetGroups
              .filter((group) => group.groupId !== assignment?.groupId)
              .map((group) => ({
                value: group.groupId,
                label: `${group.code} · ${group.name}`,
              }))}
            placeholder={t('attendanceSetup.selectTargetGroup')}
          />
        </Form.Item>
        <Form.Item
          label={t('attendanceSetup.transferEffectiveFrom')}
          name="effectiveFrom"
          rules={[requiredRule(t('attendanceSetup.required'))]}
        >
          <Input type="date" />
        </Form.Item>
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

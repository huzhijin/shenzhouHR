import { IconPlus, IconTrash } from '@tabler/icons-react';
import { Form, Input, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import type {
  ShiftSegment,
  ShiftTemplateInput,
  ShiftVersionInput,
} from './attendanceSetupTypes';

interface DialogProps<T> {
  open: boolean;
  processing: boolean;
  onSubmit: (values: T) => void;
  onCancel: () => void;
}

export function ShiftTemplateDialog({
  open,
  processing,
  initialValues,
  onSubmit,
  onCancel,
}: DialogProps<ShiftTemplateInput> & { initialValues?: ShiftTemplateInput }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<ShiftTemplateInput>();
  return (
    <Modal
      open={open}
      title={initialValues ? t('attendanceSetup.updateShift') : t('attendanceSetup.createShift')}
      okText={initialValues ? t('attendanceSetup.save') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<ShiftTemplateInput>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onSubmit}
      >
        <Form.Item label={t('attendanceSetup.companyId')} name="companyId" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.locationId')} name="locationId" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.code')} name="code" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.name')} name="name" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <ReasonField />
      </Form>
    </Modal>
  );
}

export function ShiftVersionDialog({
  open,
  processing,
  initialValues,
  onSubmit,
  onCancel,
}: DialogProps<ShiftVersionInput> & { initialValues?: ShiftVersionInput }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<ShiftVersionInput>();
  return (
    <Modal
      className="attendance-wide-dialog"
      open={open}
      title={initialValues
        ? t('attendanceSetup.updateShiftVersion')
        : t('attendanceSetup.createShiftVersion')}
      okText={initialValues ? t('attendanceSetup.save') : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<ShiftVersionInput>
        form={form}
        layout="vertical"
        initialValues={initialValues ?? {
          segments: [
            { segmentType: 'WORK', startLocalTime: '20:00', startDayOffset: 0, endLocalTime: '01:00', endDayOffset: 1 },
            { segmentType: 'BREAK', startLocalTime: '01:00', startDayOffset: 1, endLocalTime: '01:15', endDayOffset: 1 },
            { segmentType: 'WORK', startLocalTime: '01:15', startDayOffset: 1, endLocalTime: '04:00', endDayOffset: 1 },
          ],
        }}
        onFinish={onSubmit}
      >
        <div className="form-grid">
          <Form.Item label={t('attendanceSetup.effectiveFrom')} name="effectiveFrom" rules={[required()]}>
            <Input type="date" />
          </Form.Item>
          <Form.Item label={t('attendanceSetup.effectiveTo')} name="effectiveTo">
            <Input type="date" />
          </Form.Item>
        </div>
        <Form.List
          name="segments"
          rules={[{
            validator: async (_rule, segments?: ShiftSegment[]) => {
              const issue = shiftSegmentIssue(segments, t);
              if (issue) throw new Error(issue);
            },
          }]}
        >
          {(fields, { add, remove }, { errors }) => (
            <fieldset className="attendance-fieldset">
              <legend>{t('attendanceSetup.segmentType')}</legend>
              <Form.ErrorList errors={errors} />
              {fields.map((field, index) => (
                <div className="attendance-segment-row" key={field.key}>
                  <Form.Item
                    label={t('attendanceSetup.segmentType')}
                    name={[field.name, 'segmentType']}
                    rules={[required()]}
                  >
                    <Select options={[
                      { value: 'WORK', label: '工作时段' },
                      { value: 'BREAK', label: '休息时段' },
                      { value: 'MEAL', label: '用餐时段' },
                    ]} />
                  </Form.Item>
                  <Form.Item
                    label={t('attendanceSetup.segmentStart')}
                    name={[field.name, 'startLocalTime']}
                    rules={[required()]}
                  >
                    <Input type="time" step={60} />
                  </Form.Item>
                  <Form.Item
                    label={t('attendanceSetup.startDayOffset')}
                    name={[field.name, 'startDayOffset']}
                    rules={[required()]}
                  >
                    <Select options={[
                      { value: 0, label: t('attendanceSetup.sameDay') },
                      { value: 1, label: t('attendanceSetup.nextDay') },
                    ]} />
                  </Form.Item>
                  <Form.Item
                    label={t('attendanceSetup.segmentEnd')}
                    name={[field.name, 'endLocalTime']}
                    rules={[required()]}
                  >
                    <Input type="time" step={60} />
                  </Form.Item>
                  <Form.Item
                    label={t('attendanceSetup.endDayOffset')}
                    name={[field.name, 'endDayOffset']}
                    rules={[required()]}
                  >
                    <Select options={[
                      { value: 0, label: t('attendanceSetup.sameDay') },
                      { value: 1, label: t('attendanceSetup.nextDay') },
                    ]} />
                  </Form.Item>
                  <AccessibleButton
                    label={`${t('attendanceSetup.removeSegment')} ${index + 1}`}
                    iconOnly
                    className="attendance-row-action"
                    type="text"
                    danger
                    icon={<IconTrash aria-hidden="true" stroke={2} />}
                    onClick={() => remove(field.name)}
                  />
                </div>
              ))}
              <AccessibleButton
                label={t('attendanceSetup.addSegment')}
                icon={<IconPlus aria-hidden="true" stroke={2} />}
                onClick={() => add({
                  segmentType: 'WORK',
                  startLocalTime: '08:00',
                  startDayOffset: 0,
                  endLocalTime: '17:00',
                  endDayOffset: 0,
                })}
              >
                {t('attendanceSetup.addSegment')}
              </AccessibleButton>
            </fieldset>
          )}
        </Form.List>
        <ReasonField />
      </Form>
    </Modal>
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

function required() {
  return { required: true };
}

function shiftSegmentIssue(
  segments: ShiftSegment[] | undefined,
  t: (key: string) => string,
): string | undefined {
  if (!segments || segments.length === 0) {
    return t('attendanceSetup.segmentRequired');
  }
  if (!segments.some((segment) => segment.segmentType === 'WORK')) {
    return t('attendanceSetup.workSegmentRequired');
  }
  let previousEnd = -1;
  for (const segment of segments) {
    const start = wallClockMinute(segment.startLocalTime, segment.startDayOffset);
    const end = wallClockMinute(segment.endLocalTime, segment.endDayOffset);
    if (start === undefined || end === undefined || end <= start) {
      return t('attendanceSetup.segmentTimeOrderInvalid');
    }
    if (previousEnd > start) {
      return t('attendanceSetup.segmentOverlapInvalid');
    }
    previousEnd = end;
  }
  return undefined;
}

function wallClockMinute(value: string, dayOffset: 0 | 1): number | undefined {
  const match = /^(\d{2}):(\d{2})(?::\d{2})?$/.exec(value);
  if (!match) return undefined;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return undefined;
  return dayOffset * 24 * 60 + hour * 60 + minute;
}

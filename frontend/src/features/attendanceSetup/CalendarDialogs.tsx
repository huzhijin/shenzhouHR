import { IconPlus, IconTrash } from '@tabler/icons-react';
import { Form, Input, InputNumber, Modal, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import {
  CompanySelect,
  LocationSelect,
  ShiftVersionSelect,
} from '../referenceData';
import type {
  CalendarDayInput,
  CalendarDayView,
  WorkCalendarInput,
} from './attendanceSetupTypes';

export function CalendarDialog({
  open,
  processing,
  initialValues,
  intent = initialValues ? 'revise-version' : 'create-family',
  calendarYear = new Date().getFullYear(),
  onSubmit,
  onCancel,
}: {
  open: boolean;
  processing: boolean;
  intent?: 'create-family' | 'update-family' | 'create-version' | 'revise-version';
  calendarYear?: number;
  initialValues?: WorkCalendarInput;
  onSubmit: (values: WorkCalendarInput) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<WorkCalendarInput>();
  const companyId = Form.useWatch<string>('companyId', form);
  return (
    <Modal
      open={open}
      title={intent === 'update-family'
        ? t('attendanceSetup.updateCalendar')
        : intent === 'create-version'
        ? t('attendanceSetup.createCalendarVersion')
        : intent === 'revise-version'
          ? t('attendanceSetup.updateCalendarVersion')
          : t('attendanceSetup.createCalendar')}
      okText={intent === 'update-family' || intent === 'revise-version'
        ? t('attendanceSetup.save')
        : t('attendanceSetup.create')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form<WorkCalendarInput>
        form={form}
        layout="vertical"
        initialValues={initialValues ?? {
          timeZone: 'Asia/Shanghai',
          calendarYear,
        }}
        onFinish={onSubmit}
      >
        <Form.Item label="公司" name="companyId" rules={[required()]}>
          <CompanySelect disabled={intent !== 'create-family'} />
        </Form.Item>
        <Form.Item label="地点" name="locationId" rules={[required()]}>
          <LocationSelect companyId={companyId} disabled={intent !== 'create-family'} />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.code')} name="code" rules={[required()]}>
          <Input autoComplete="off" disabled={intent !== 'create-family'} />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.name')} name="name" rules={[required()]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.calendarYear')} name="calendarYear" rules={[required()]}>
          <InputNumber min={2000} max={2100} />
        </Form.Item>
        <Form.Item label={t('attendanceSetup.timeZone')} name="timeZone" rules={[required()]}>
          <Select options={[
            { value: 'Asia/Shanghai', label: '中国标准时间（上海）' },
            { value: 'Asia/Singapore', label: '新加坡标准时间' },
          ]} />
        </Form.Item>
        <div className="form-grid">
          <Form.Item
            label={t('attendanceSetup.effectiveFrom')}
            name="effectiveFrom"
            rules={[required()]}
          >
            <Input type="date" />
          </Form.Item>
          <Form.Item
            label={t('attendanceSetup.effectiveTo')}
            name="effectiveTo"
            dependencies={['effectiveFrom']}
            rules={[
              required(),
              ({ getFieldValue }) => ({
                validator: async (_rule, value?: string) => {
                  const effectiveFrom = getFieldValue('effectiveFrom') as string | undefined;
                  if (!value || !effectiveFrom || value > effectiveFrom) return;
                  throw new Error(t('attendanceSetup.periodOrderInvalid'));
                },
              }),
            ]}
          >
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

export function CalendarDaysDialog({
  open,
  processing,
  days,
  onSubmit,
  onCancel,
}: {
  open: boolean;
  processing: boolean;
  days: CalendarDayView[];
  onSubmit: (days: CalendarDayInput[], reason: string) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<{ days: CalendarDayInput[]; reason: string }>();
  return (
    <Modal
      className="attendance-wide-dialog"
      open={open}
      title={t('attendanceSetup.saveDays')}
      okText={t('attendanceSetup.save')}
      cancelText={t('common.cancel')}
      confirmLoading={processing}
      destroyOnHidden
      onOk={() => void form.submit()}
      onCancel={onCancel}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          days: days.map(({ businessDate, dayType, shiftVersionOverrideId }) => ({
            businessDate,
            dayType,
            shiftVersionOverrideId,
          })),
        }}
        onFinish={(values) => onSubmit(values.days, values.reason)}
      >
        <Form.List
          name="days"
          rules={[{
            validator: async (_rule, values?: CalendarDayInput[]) => {
              if (!values || values.length === 0) {
                throw new Error(t('attendanceSetup.calendarDayRequired'));
              }
              const dates = values.map((value) => value.businessDate).filter(Boolean);
              if (new Set(dates).size !== dates.length) {
                throw new Error(t('attendanceSetup.calendarDayDuplicate'));
              }
            },
          }]}
        >
          {(fields, { add, remove }, { errors }) => (
            <fieldset className="attendance-fieldset">
              <legend>{t('attendanceSetup.dayType')}</legend>
              <p className="form-help" id="calendar-shift-override-help">
                通常沿用考勤组班次；只有节假日或临时调班时才选择其他班次。
              </p>
              <Form.ErrorList errors={errors} />
              {fields.map((field, index) => (
                <div className="attendance-calendar-row" key={field.key}>
                  <Form.Item
                    label={t('attendanceSetup.fromDate')}
                    name={[field.name, 'businessDate']}
                    rules={[required()]}
                  >
                    <Input type="date" />
                  </Form.Item>
                  <Form.Item
                    label={t('attendanceSetup.dayType')}
                    name={[field.name, 'dayType']}
                    rules={[required()]}
                  >
                    <Select options={[
                      { value: 'WORKDAY', label: t('attendanceSetup.workingDay') },
                      { value: 'SPECIAL_WORKDAY', label: t('attendanceSetup.specialWorkingDay') },
                      { value: 'WEEKEND', label: t('attendanceSetup.weekend') },
                      { value: 'PUBLIC_HOLIDAY', label: t('attendanceSetup.holiday') },
                    ]} />
                  </Form.Item>
                  <Form.Item
                    label="临时替换班次（可选）"
                    name={[field.name, 'shiftVersionOverrideId']}
                  >
                    <ShiftVersionSelect
                      aria-describedby="calendar-shift-override-help"
                    />
                  </Form.Item>
                  <AccessibleButton
                    label={`${t('attendanceSetup.removeDay')} ${index + 1}`}
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
                label={t('attendanceSetup.addDay')}
                icon={<IconPlus aria-hidden="true" stroke={2} />}
                onClick={() => add({ dayType: 'WORKDAY' })}
              >
                {t('attendanceSetup.addDay')}
              </AccessibleButton>
            </fieldset>
          )}
        </Form.List>
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

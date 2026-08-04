import { Form, Input as TextInput, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { ReadOnlyDetails } from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
import { policyResultText } from '../policy/PolicyComponents';
import { EmployeeSelect } from '../referenceData';
import type {
  AttendancePolicyKind,
  PolicySimulationBatchView,
  PolicySimulationInput,
} from './attendanceSetupTypes';

interface SimulationFormValues {
  employeeId: string;
  businessDate: string;
  correctionAsOf: string;
  punches: PolicySimulationInput['punches'];
}

export function PolicySimulationPanel({
  policyKind,
  processing,
  error,
  result,
  onSimulate,
}: {
  policyKind: AttendancePolicyKind;
  policyVersionId: string;
  processing: boolean;
  error?: string;
  result?: PolicySimulationBatchView;
  onSimulate: (input: PolicySimulationInput) => void;
}) {
  const { t } = useTranslation();
  const initialValues = simulationInitialValues();
  const submit = (values: SimulationFormValues) => {
    onSimulate({
      employeeId: values.employeeId,
      businessDate: values.businessDate,
      correctionAsOf: localInputToOffsetInstant(values.correctionAsOf),
      punches: values.punches.map((punch) => ({
        direction: punch.direction,
        instant: localInputToOffsetInstant(punch.instant),
      })),
    });
  };

  const selected = result?.results.find((item) => item.policyKind === policyKind);

  return (
    <div className="policy-simulation-workbench">
      <Form<SimulationFormValues>
        layout="vertical"
        initialValues={initialValues}
        onFinish={submit}
      >
        <div className="form-grid">
          <Form.Item label="员工" name="employeeId" rules={[{ required: true, message: '请选择员工' }]}>
            <EmployeeSelect />
          </Form.Item>
          <Form.Item label={t('attendanceSetup.fromDate')} name="businessDate" rules={[{ required: true }]}>
            <TextInput type="date" />
          </Form.Item>
          <Form.Item
            label="数据截至时间"
            name="correctionAsOf"
            rules={[{ required: true, message: '请选择数据截至时间' }]}
          >
            <TextInput type="datetime-local" step={1} />
          </Form.Item>
        </div>
        <Form.List name="punches">
          {(fields, { add, remove }) => (
            <div className="simulation-punch-list">
              {fields.map((field, index) => (
                <div className="simulation-punch-row" key={field.key}>
                  <Form.Item
                    label={`${t('attendanceSetup.punchDirection')} ${index + 1}`}
                    name={[field.name, 'direction']}
                    rules={[{ required: true }]}
                  >
                    <Select options={[
                      { value: 'ENTRY', label: '上班入场' },
                      { value: 'EXIT', label: '下班离场' },
                    ]} />
                  </Form.Item>
                  <Form.Item
                    label={`${t('attendanceSetup.punchInstant')} ${index + 1}`}
                    name={[field.name, 'instant']}
                    rules={[{ required: true, message: '请选择打卡时间' }]}
                  >
                    <TextInput type="datetime-local" step={1} />
                  </Form.Item>
                  <AccessibleButton
                    className="attendance-row-action"
                    label={`${t('attendanceSetup.removePunch')} ${index + 1}`}
                    onClick={() => remove(field.name)}
                  >
                    {t('attendanceSetup.removePunch')}
                  </AccessibleButton>
                </div>
              ))}
              <AccessibleButton
                label={t('attendanceSetup.addPunch')}
                onClick={() => add({ direction: 'ENTRY', instant: '' })}
              >
                {t('attendanceSetup.addPunch')}
              </AccessibleButton>
            </div>
          )}
        </Form.List>
        <p className="form-help">试算仅用于预览，不会修改正式考勤结果。</p>
        <AccessibleButton
          label={t('attendanceSetup.runSimulation')}
          type="primary"
          htmlType="submit"
          loading={processing}
        >
          {t('attendanceSetup.runSimulation')}
        </AccessibleButton>
      </Form>
      <section className="policy-simulation-result" aria-live="polite">
        {processing ? (
          <StatePanel state="processing" />
        ) : error ? (
          <StatePanel state="error" description={error} />
        ) : selected ? (
          <ReadOnlyDetails items={[
            {
              label: t('attendanceSetup.simulationStatus'),
              value: simulationStatusLabel(selected.status),
            },
            { label: t('attendanceSetup.lateMinutes'), value: selected.rawLateMinutes ?? t('common.none') },
            {
              label: t('attendanceSetup.deductionMinutes'),
              value: selected.deductionMinutes ?? t('common.none'),
            },
            {
              label: t('attendanceSetup.matchedMealWindows'),
              value: selected.matchedMealWindows?.length
                ? selected.matchedMealWindows.map((window) => (
                  `${window.mealType === 'LUNCH' ? '午餐' : '晚餐'} ${window.windowStart}–${window.windowEnd}`
                  + ` · ${window.deductionMinutes} ${t('attendanceSetup.minutes')}`
                )).join('；')
                : t('common.none'),
            },
            { label: t('attendanceSetup.consumesAllowance'), value: selected.predictedMonthlyConsumption },
            {
              label: t('attendanceSetup.explanation'),
              value: policyResultText(selected.explanation),
            },
            {
              label: '数据截至时间',
              value: formatSimulationTime(selected.usageKnowledgeTime),
            },
          ]} />
        ) : (
          <p>选择员工并填写打卡时间后，可以预览策略判定结果。</p>
        )}
      </section>
    </div>
  );
}

function simulationInitialValues(now = new Date()): SimulationFormValues {
  return {
    employeeId: '',
    businessDate: localDate(now),
    correctionAsOf: localDateTime(now),
    punches: [],
  };
}

function localDate(value: Date): string {
  return [
    String(value.getFullYear()).padStart(4, '0'),
    String(value.getMonth() + 1).padStart(2, '0'),
    String(value.getDate()).padStart(2, '0'),
  ].join('-');
}

function localDateTime(value: Date): string {
  return `${localDate(value)}T${String(value.getHours()).padStart(2, '0')}:${String(
    value.getMinutes(),
  ).padStart(2, '0')}:${String(value.getSeconds()).padStart(2, '0')}`;
}

function localInputToOffsetInstant(value: string): string {
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) return value;
  const offsetMinutes = -parsed.getTimezoneOffset();
  const offsetSign = offsetMinutes >= 0 ? '+' : '-';
  const absoluteOffset = Math.abs(offsetMinutes);
  const offset = `${offsetSign}${String(Math.floor(absoluteOffset / 60)).padStart(2, '0')}:${String(absoluteOffset % 60).padStart(2, '0')}`;
  return `${localDateTime(parsed)}${offset}`;
}

function simulationStatusLabel(value: string): string {
  return ({
    MATCHED: '已命中',
    NOT_MATCHED: '未命中',
    EXEMPTED: '已宽免',
    REJECTED: '不适用',
  } as Readonly<Record<string, string>>)[value] ?? '待确认';
}

function formatSimulationTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

import { Form, Input as TextInput, Select } from 'antd';
import { useTranslation } from 'react-i18next';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { ReadOnlyDetails } from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
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

const OFFSET_INSTANT_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;

function offsetInstantRule(message: string) {
  return {
    validator: (_rule: unknown, value?: string) => {
      if (typeof value === 'string'
          && OFFSET_INSTANT_PATTERN.test(value)
          && Number.isFinite(Date.parse(value))) {
        return Promise.resolve();
      }
      return Promise.reject(new Error(message));
    },
  };
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
      correctionAsOf: values.correctionAsOf,
      punches: values.punches,
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
          <Form.Item label={t('attendanceSetup.employeeId')} name="employeeId" rules={[{ required: true }]}>
            <TextInput autoComplete="off" />
          </Form.Item>
          <Form.Item label={t('attendanceSetup.fromDate')} name="businessDate" rules={[{ required: true }]}>
            <TextInput type="date" />
          </Form.Item>
          <Form.Item
            label={t('attendanceSetup.correctionAsOf')}
            name="correctionAsOf"
            rules={[{ required: true }, offsetInstantRule(t('attendanceSetup.offsetInstantInvalid'))]}
          >
            <TextInput autoComplete="off" />
          </Form.Item>
        </div>
        <Form.List name="punches">
          {(fields, { add, remove }) => (
            <div className="simulation-punch-list">
              {fields.map((field, index) => (
                <div className="form-grid" key={field.key}>
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
                    rules={[{ required: true }, offsetInstantRule(t('attendanceSetup.offsetInstantInvalid'))]}
                  >
                    <TextInput autoComplete="off" />
                  </Form.Item>
                  <Form.Item label={t('attendanceSetup.workSegmentId')} name={[field.name, 'workSegmentId']}>
                    <TextInput autoComplete="off" />
                  </Form.Item>
                  <Form.Item label={t('attendanceSetup.punchAssociation')} name={[field.name, 'association']}>
                    <TextInput autoComplete="off" />
                  </Form.Item>
                  <AccessibleButton
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
        <p className="form-help">{t('attendanceSetup.authoritativeSimulationNotice')}</p>
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
            { label: t('attendanceSetup.simulationStatus'), value: selected.status },
            { label: t('attendanceSetup.policyVersionId'), value: selected.policyVersionId },
            { label: t('attendanceSetup.configurationDigest'), value: result?.configurationDigest },
            { label: t('attendanceSetup.lateMinutes'), value: selected.rawLateMinutes ?? t('common.none') },
            {
              label: t('attendanceSetup.deductionMinutes'),
              value: selected.deductionMinutes ?? t('common.none'),
            },
            {
              label: t('attendanceSetup.matchedMealWindows'),
              value: selected.matchedMealWindows?.length
                ? selected.matchedMealWindows.map((window) => (
                  `${window.windowId} [${window.windowStart}, ${window.windowEnd})`
                  + ` · ${window.deductionMinutes} ${t('attendanceSetup.minutes')}`
                )).join('；')
                : t('common.none'),
            },
            { label: t('attendanceSetup.consumesAllowance'), value: selected.predictedMonthlyConsumption },
            { label: t('attendanceSetup.writesFormalResult'), value: selected.writesFormalResult ? t('attendanceSetup.yes') : t('attendanceSetup.no') },
            { label: t('attendanceSetup.explanation'), value: selected.explanation },
            { label: t('attendanceSetup.correctionAsOf'), value: selected.usageKnowledgeTime },
          ]} />
        ) : (
          <p>{t('attendanceSetup.authoritativeSimulationNotice')}</p>
        )}
      </section>
    </div>
  );
}

function simulationInitialValues(now = new Date()): SimulationFormValues {
  return {
    employeeId: '9200000000000000001',
    businessDate: localDate(now),
    correctionAsOf: localOffsetInstant(now),
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

function localOffsetInstant(value: Date): string {
  const offsetMinutes = -value.getTimezoneOffset();
  const offsetSign = offsetMinutes >= 0 ? '+' : '-';
  const absoluteOffset = Math.abs(offsetMinutes);
  const offset = `${offsetSign}${String(Math.floor(absoluteOffset / 60)).padStart(2, '0')}:${String(absoluteOffset % 60).padStart(2, '0')}`;
  return `${localDate(value)}T${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}:${String(value.getSeconds()).padStart(2, '0')}${offset}`;
}

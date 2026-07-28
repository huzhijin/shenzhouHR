import { Alert, DatePicker, Form, Input, InputNumber, Select, Timeline } from 'antd';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import {
  AccessibleButton,
  AccessibleNativeButton,
} from '../../shared/components/AccessibleButton';
import type {
  PolicyConflictResult,
  PolicyImpactPreview,
  PolicySimulationResult,
  PolicyTemplateDetail,
  PolicyValidationResult,
  PolicyVersionDetail,
  ScopeBinding,
  ScopeType,
} from './policyApi';

export function VersionTimeline({ versions, onSelect }: {
  versions: PolicyVersionDetail[];
  onSelect?: (version: PolicyVersionDetail) => void;
}) {
  const { t } = useTranslation();
  return (
    <Timeline
      className="version-timeline"
      items={Array.from(versions, (version) => ({
        color: version.status === 'PUBLISHED' ? 'green' : version.status === 'DRAFT' ? 'blue' : 'gray',
        content: (
          <AccessibleNativeButton
            label={t('policy.version', { number: version.versionNumber })}
            className="timeline-button"
            type="button"
            onClick={() => onSelect?.(version)}
          >
            <span>{t('policy.version', { number: version.versionNumber })}</span>
            <StatusBadge status={version.status} />
            <small>{version.effectiveFrom} · {version.changeReason}</small>
          </AccessibleNativeButton>
        ),
      }))}
    />
  );
}

export function ScopeSelector({ value, onChange, disabled = false }: {
  value: ScopeBinding[];
  onChange: (value: ScopeBinding[]) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const update = (index: number, patch: Partial<ScopeBinding>) => {
    onChange(Array.from(value, (binding, current) => current === index ? { ...binding, ...patch } : binding));
  };
  return (
    <section className="scope-selector" aria-labelledby="scope-selector-title">
      <div className="section-heading">
        <div><h3 id="scope-selector-title">{t('policy.scope')}</h3><p>{t('policy.scopeDescription')}</p></div>
        <AccessibleButton
          label={t('policy.addScope')}
          disabled={disabled}
          onClick={() => onChange([...value, {
            scopeType: 'COMPANY',
            scopeResourceId: '',
            priority: 100,
            effectiveFrom: new Date().toISOString().slice(0, 10),
            effectiveTo: null,
          }])}
        >
          {t('policy.addScope')}
        </AccessibleButton>
      </div>
      {Array.from(value, (binding, index) => (
        <div className="scope-row" key={`${binding.scopeType}-${index}`}>
          <Select<ScopeType>
            aria-label={t('policy.scopeType', { number: index + 1 })}
            disabled={disabled}
            value={binding.scopeType}
            options={[
              { value: 'COMPANY', label: t('policy.company') },
              { value: 'LOCATION', label: t('policy.location') },
              { value: 'ATTENDANCE_GROUP', label: t('policy.attendanceGroup') },
              { value: 'POLICY_GROUP', label: t('policy.policyGroup') },
            ]}
            onChange={(scopeType) => update(index, { scopeType })}
          />
          <Input disabled={disabled} aria-label={t('policy.scopeResource', { number: index + 1 })} value={binding.scopeResourceId} onChange={(event) => update(index, { scopeResourceId: event.target.value })} />
          <InputNumber disabled={disabled} aria-label={t('policy.priority', { number: index + 1 })} min={0} max={9999} value={binding.priority} onChange={(priority) => update(index, { priority: priority ?? 0 })} />
          <Input disabled={disabled} type="date" aria-label={t('policy.scopeEffectiveFrom', { number: index + 1 })} value={binding.effectiveFrom} onChange={(event) => update(index, { effectiveFrom: event.target.value })} />
          <Input disabled={disabled} type="date" aria-label={t('policy.scopeEffectiveTo', { number: index + 1 })} value={binding.effectiveTo ?? ''} onChange={(event) => update(index, { effectiveTo: event.target.value || null })} />
          <AccessibleButton
            label={`${t('policy.remove')} ${index + 1}`}
            danger
            disabled={disabled}
            onClick={() => onChange(value.filter((_item, current) => current !== index))}
          >
            {t('policy.remove')}
          </AccessibleButton>
        </div>
      ))}
      {value.length === 0 ? <Alert showIcon type="warning" title={t('policy.scopeMissing')} /> : null}
    </section>
  );
}

export function PolicyEditor({ template, version, onChange, disabled = false }: {
  template: PolicyTemplateDetail;
  version: PolicyVersionDetail;
  onChange: (version: PolicyVersionDetail) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const valueByKey = new Map(Array.from(version.parameters ?? [], (item) => [item.key, item.value] as const));
  const updateParameter = (key: string, value: unknown) => {
    const parameters = (version.parameters ?? []).filter((item) => item.key !== key);
    onChange({ ...version, parameters: [...parameters, { key, value }] });
  };
  return (
    <Form className="policy-editor" layout="vertical" disabled={disabled}>
      <div className="form-grid">
        {Array.from(template.fieldDefinitions ?? [], (field) => (
          <Form.Item key={field.key} label={field.label} required={field.required}>
            {field.valueType === 'ENUM' ? (
              <Select
                value={String(valueByKey.get(field.key) ?? '')}
                options={Array.from(field.enumValues ?? [], (value) => ({
                  value,
                  label: policyEnumLabel(value),
                }))}
                onChange={(value) => updateParameter(field.key, value)}
              />
            ) : (
              <InputNumber value={Number(valueByKey.get(field.key) ?? 0)} onChange={(value) => updateParameter(field.key, value)} />
            )}
          </Form.Item>
        ))}
        <Form.Item label={t('policy.effectiveFrom')} required><DatePicker value={undefined} placeholder={version.effectiveFrom} disabled={disabled} onChange={(_value, date) => onChange({ ...version, effectiveFrom: Array.isArray(date) ? date[0] ?? '' : date })} /></Form.Item>
        <Form.Item label={t('policy.effectiveTo')}><DatePicker value={undefined} placeholder={version.effectiveTo ?? t('policy.longTerm')} disabled={disabled} onChange={(_value, date) => onChange({ ...version, effectiveTo: Array.isArray(date) ? date[0] ?? null : date || null })} /></Form.Item>
      </div>
      <Form.Item label={t('policy.changeReason')} required><Input.TextArea value={version.changeReason} onChange={(event) => onChange({ ...version, changeReason: event.target.value })} /></Form.Item>
    </Form>
  );
}

export function ValidationPanel({ result }: { result?: PolicyValidationResult }) {
  const { t } = useTranslation();
  if (!result) return <ResultPlaceholder title={t('policy.validation')} description={t('policy.validationDescription')} />;
  return (
    <section className="result-panel" data-state={result.valid ? 'success' : 'invalid'} aria-live="polite">
      <Alert showIcon type={result.valid ? 'success' : 'error'} title={result.valid ? t('policy.validationPassed') : t('policy.validationFailed')} />
      {Array.from(result.issues, (issue) => <Alert key={`${issue.code}-${issue.field ?? ''}`} showIcon type={issue.severity === 'ERROR' ? 'error' : 'warning'} title={issue.message} description={issue.field ? t('policy.field', { field: issue.field }) : undefined} />)}
    </section>
  );
}

export function ConflictPanel({ result }: { result?: PolicyConflictResult }) {
  const { t } = useTranslation();
  if (!result) return <ResultPlaceholder title={t('policy.conflict')} description={t('policy.conflictDescription')} />;
  return (
    <section className="result-panel" data-state={result.hasConflicts ? 'scope-conflict' : 'success'} aria-live="polite">
      <Alert showIcon type={result.hasConflicts ? 'error' : 'success'} title={result.hasConflicts ? t('policy.conflictFound') : t('policy.conflictClear')} />
      {Array.from(result.conflicts, (conflict) => (
        <Alert
          key={`${conflict.conflictingVersionId}-${conflict.scopeResourceId}`}
          type="error"
          title={t('policy.conflictScopeOverlap')}
          description={t('policy.conflictVersion', {
            versionId: conflict.conflictingVersionId,
            scopeType: policyScopeTypeLabel(conflict.scopeType),
            scopeId: conflict.scopeResourceId,
          })}
        />
      ))}
    </section>
  );
}

export function ImpactPreviewPanel({ result }: { result?: PolicyImpactPreview }) {
  const { t } = useTranslation();
  if (!result) return <ResultPlaceholder title={t('policy.impact')} description={t('policy.impactDescription')} />;
  return (
    <section className="result-panel" data-state="impact-preview" aria-live="polite">
      <dl className="metric-list">
        <div><dt>{t('policy.scope')}</dt><dd>{result.scopeCount}</dd></div>
        <div><dt>{t('policy.affectedObjects')}</dt><dd>{result.affectedObjectCount}</dd></div>
        <div><dt>{t('policy.effectiveFrom')}</dt><dd>{result.effectiveFrom}</dd></div>
        <div><dt>{t('policy.freezeProtection')}</dt><dd>{result.frozenPeriodProtected ? t('policy.defaultDeny') : t('policy.notApplicable')}</dd></div>
      </dl>
      {Array.from(result.warnings, (warning) => <Alert key={warning} showIcon type="warning" title={warning} />)}
    </section>
  );
}

export function SimulationPanel({ result }: { result?: PolicySimulationResult }) {
  const { t } = useTranslation();
  if (!result) return <ResultPlaceholder title={t('policy.simulation')} description={t('policy.simulationDescription')} />;
  return (
    <section className="result-panel" data-state="success" aria-live="polite">
      <Alert showIcon type={result.matched ? 'success' : 'warning'} title={result.matched ? t('policy.simulationMatched') : t('policy.simulationUnmatched')} />
      <pre>{JSON.stringify(result.resolvedParameters, (_key, value) => (
        typeof value === 'string' ? policyEnumLabel(value) : value
      ), 2)}</pre>
      <ul>{Array.from(result.explanation, (line) => <li key={line}>{line}</li>)}</ul>
    </section>
  );
}

export function PolicyResultTabs({ validation, conflicts, impact, simulation, active }: {
  validation?: PolicyValidationResult;
  conflicts?: PolicyConflictResult;
  impact?: PolicyImpactPreview;
  simulation?: PolicySimulationResult;
  active: 'validation' | 'conflicts' | 'impact' | 'simulation';
}) {
  const panels: Record<typeof active, ReactNode> = {
    validation: <ValidationPanel result={validation} />,
    conflicts: <ConflictPanel result={conflicts} />,
    impact: <ImpactPreviewPanel result={impact} />,
    simulation: <SimulationPanel result={simulation} />,
  };
  return <div className="policy-result-tabs">{panels[active]}</div>;
}

function ResultPlaceholder({ title, description }: { title: string; description: string }) {
  return <section className="result-placeholder"><h3>{title}</h3><p>{description}</p></section>;
}

const policyEnumLabels: Readonly<Record<string, string>> = {
  STRICT: '严格模式',
  BALANCED: '均衡模式',
};

const policyScopeTypeLabels: Readonly<Record<string, string>> = {
  COMPANY: '公司',
  LOCATION: '地点',
  ATTENDANCE_GROUP: '考勤组',
  POLICY_GROUP: '政策组',
};

export function policyEnumLabel(value: string): string {
  return policyEnumLabels[value.trim().toUpperCase()] ?? value;
}

export function policyScopeTypeLabel(value: string): string {
  return policyScopeTypeLabels[value.trim().toUpperCase()] ?? value;
}

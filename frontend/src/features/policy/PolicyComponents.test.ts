import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  policyEnumLabel,
  policyFieldLabel,
  policyResultText,
  policyScopeTypeLabel,
} from './PolicyComponents';

describe('policy display labels', () => {
  it('translates controlled enum options without exposing unknown codes', () => {
    expect(policyEnumLabel('STRICT')).toBe('严格模式');
    expect(policyEnumLabel('BALANCED')).toBe('均衡模式');
    expect(policyEnumLabel('CUSTOM')).toBe('其他选项');
  });

  it('translates policy scope types', () => {
    expect(policyScopeTypeLabel('COMPANY')).toBe('公司');
    expect(policyScopeTypeLabel('ATTENDANCE_GROUP')).toBe('考勤组');
  });

  it('uses business field labels instead of backend keys', () => {
    expect(policyFieldLabel('threshold', [{
      key: 'threshold',
      label: '迟到阈值',
      valueType: 'INTEGER',
      required: true,
    }])).toBe('迟到阈值');
    expect(policyFieldLabel('unmappedInternalKey')).toBe('规则参数');
  });

  it('removes opaque identifiers from backend validation text', () => {
    expect(policyResultText(
      'scopeResourceId 550e8400-e29b-41d4-a716-446655440000 与 versionId 9600000000000000001 冲突',
    )).toBe('相关信息 相关记录 与 相关信息 相关记录 冲突');
  });

  it('replaces technical JSON, digests and machine states with business-safe copy', () => {
    expect(policyResultText(
      '{"policyVersionId":"550e8400-e29b-41d4-a716-446655440000","rowVersion":3}',
    )).toBe('当前结果请结合规则配置确认。');
    const cleaned = policyResultText(
      `configurationDigest ${'a'.repeat(64)} 的状态为 SOURCE_SYNC_STALE`,
    );
    expect(cleaned).toBe('相关信息 相关记录 的状态为 相关状态');
    expect(cleaned).not.toMatch(/configurationDigest|SOURCE_SYNC_STALE|a{24}/);
  });

  it('keeps concurrency tokens and backend field keys out of policy markup', () => {
    const root = resolve(process.cwd(), 'src/features/policy');
    const attendanceRoot = resolve(
      process.cwd(),
      'src/features/attendanceSetup',
    );
    const templates = readFileSync(
      resolve(root, 'PolicyTemplatesPage.tsx'),
      'utf8',
    );
    const detail = readFileSync(
      resolve(root, 'PolicyTemplateDetailPage.tsx'),
      'utf8',
    );
    const components = readFileSync(
      resolve(root, 'PolicyComponents.tsx'),
      'utf8',
    );
    const lifecycle = readFileSync(
      resolve(attendanceRoot, 'AttendancePolicyLifecyclePanel.tsx'),
      'utf8',
    );
    const simulation = readFileSync(
      resolve(attendanceRoot, 'PolicySimulationPanel.tsx'),
      'utf8',
    );

    expect(`${templates}\n${detail}`).not.toContain("t('policy.rowVersion')");
    expect(detail).not.toContain('<code>{field.key}</code>');
    expect(components).not.toContain('JSON.stringify(result.resolvedParameters');
    expect(components).not.toContain(
      'value={binding.scopeResourceId} onChange={(event)',
    );
    expect(components).toContain('<CompanySelect {...common} />');
    expect(components).toContain('<LocationSelect {...common} />');
    expect(components).toContain('<AttendanceGroupSelect {...common} />');
    expect(lifecycle).toContain('title={policyResultText(issue.message)}');
    expect(simulation).toContain('value: policyResultText(selected.explanation)');
  });
});

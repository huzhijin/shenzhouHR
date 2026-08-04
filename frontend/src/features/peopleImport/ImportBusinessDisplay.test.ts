import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { zhCNMessages } from '../../shared/i18n/messages';
import {
  formatPeopleImportValues,
  importTaskLabel,
  peopleImportFieldLabel,
  peopleImportIssueDescription,
} from './importDisplay';

describe('people import business display', () => {
  it('identifies tasks by source file and creation time instead of an internal reference', () => {
    const label = importTaskLabel('2026-07-30T08:30:00Z', '员工花名册.xlsx');

    expect(label).toContain('员工花名册.xlsx');
    expect(label).toContain('2026');
    expect(label).not.toContain('10000000-0000-0000-0000-000000000001');
    expect(zhCNMessages['peopleImport.batchId']).toBe('当前任务');
  });

  it('keeps internal template keys and value types out of field tables', () => {
    const source = read('ImportWizard.tsx');

    expect(source).toContain('<CompanySelect />');
    expect(source).not.toContain("{ key: 'key', title: t('peopleImport.systemField')");
    expect(source).not.toContain("{ key: 'type', title: t('peopleImport.valueType')");
    expect(source).toContain(
      "{ key: 'target', title: t('peopleImport.importField'), render: (row) => row.label }",
    );
    expect(source).toContain('targetField: field.key');
  });

  it('uses task creation details throughout page and confirmation UI', () => {
    const page = read('PeopleImportPage.tsx');
    const wizard = read('ImportWizard.tsx');
    const dialog = read('ImportDialogs.tsx');

    expect(page).toContain('importTaskLabel(state.batch.createdAt');
    expect(page).toContain('importTaskLabel(row.createdAt)');
    expect(wizard).toContain('importTaskLabel(batch.createdAt');
    expect(dialog).toContain('importTaskLabel(batch.createdAt');
    expect(`${page}\n${wizard}\n${dialog}`).not.toContain('importTaskNumber');
    expect(`${page}\n${wizard}\n${dialog}`).not.toContain('任务编号');
  });

  it('renders business field names and hides raw keys and error codes', () => {
    expect(peopleImportFieldLabel('employeeNumber')).toBe('工号');
    expect(peopleImportFieldLabel('unknownBackendField')).toBe('其他字段');
    expect(formatPeopleImportValues({
      employeeNumber: 'SZ001',
      displayName: '张三',
      rowVersion: 9,
    })).toBe('工号：SZ001；姓名：张三');
    expect(peopleImportIssueDescription(
      'EMPLOYMENT_PERIOD_OVERLAP',
      'employeeId=10000000-0000-0000-0000-000000000001',
      'startDate',
    )).toBe('任职日期与现有任职记录重叠，请调整日期。');

    const result = read('ImportResults.tsx');
    expect(result).toContain('peopleImportFieldLabel(row.field)');
    expect(result).toContain('peopleImportIssueDescription(row.code, row.message, row.field)');
    expect(result).not.toContain('<code>{row.code}</code>');
    expect(result).not.toContain('`${key}=${String(value)}`');
  });
});

function read(fileName: string): string {
  return readFileSync(resolve(process.cwd(), 'src/features/peopleImport', fileName), 'utf8');
}

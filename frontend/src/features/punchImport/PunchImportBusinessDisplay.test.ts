import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import type { AttendanceSourceView } from '../attendanceSources/attendanceSourceTypes';
import type { CompanyReference } from '../referenceData/referenceDataApi';
import {
  punchImportCreatedAtLabel,
  punchImportCompanyName,
  punchImportIssueDescription,
  punchImportSourceName,
  punchImportTaskLabel,
} from './punchImportDisplay';

describe('punch import business display', () => {
  it('resolves business names while keeping raw IDs out of fallback text', () => {
    const companies: CompanyReference[] = [{
      companyId: 'company-internal-id',
      companyName: '神州半导体',
      companyCode: 'SZSC',
    }];
    const sources: AttendanceSourceView[] = [{
      sourceId: 'source-internal-id',
      companyId: 'company-internal-id',
      sourceType: 'STANDARD_XLSX',
      code: 'XLSX',
      displayName: '标准考勤表',
      state: 'ACTIVE',
      timeZone: 'Asia/Shanghai',
      configurationRevision: 1,
      committedWatermark: null,
      lastSuccessfulSyncAt: null,
      lastFailedSyncAt: null,
      lastFailureReason: null,
      rowVersion: 1,
    }];

    expect(punchImportCompanyName('company-internal-id', companies)).toBe('神州半导体');
    expect(punchImportSourceName('source-internal-id', sources)).toBe('标准考勤表');
    expect(punchImportCompanyName('unknown-company-id', companies)).toBe('公司信息暂不可用');
    expect(punchImportSourceName('unknown-source-id', sources)).toBe('来源信息暂不可用');
  });

  it('identifies an import by its source file and creation time', () => {
    const label = punchImportTaskLabel(
      '考勤打卡记录.xlsx',
      '2026-07-30T08:30:00Z',
    );

    expect(label).toContain('考勤打卡记录.xlsx');
    expect(label).toContain('2026');
    expect(punchImportCreatedAtLabel('not-a-date')).toBe('创建时间暂不可用');
    expect(label).not.toContain('20000000-0000-0000-0000-000000000002');
  });

  it('rejects technical issue messages before they reach the table', () => {
    expect(punchImportIssueDescription('员工号在打卡时点没有唯一有效任职。'))
      .toBe('员工号在打卡时点没有唯一有效任职。');
    expect(punchImportIssueDescription(
      'employeeId=20000000-0000-0000-0000-000000000002',
    )).toBe('请根据问题原因检查该行内容。');
  });

  it('uses scoped business selectors and hides technical detail values', () => {
    const listPage = read('PunchImportsPage.tsx');
    const detailPage = read('PunchImportDetailPage.tsx');

    expect(listPage).toContain('<CompanySelect');
    expect(listPage).toContain('<AttendanceSourceSelect');
    expect(listPage).toContain('sourceTypes={spreadsheetSourceTypes}');
    expect(listPage).not.toContain('placeholder="请输入公司 ID"');
    expect(listPage).not.toContain('placeholder="请输入文件来源 ID"');
    expect(listPage).toContain("if (!Number.isFinite(timestamp)) return '—';");
    expect(detailPage).toContain('punchImportCompanyName(');
    expect(detailPage).toContain('punchImportSourceName(');
    expect(detailPage).not.toContain('children: String(batch.rowVersion)');
    expect(detailPage).not.toContain('children: batch.companyId');
    expect(detailPage).not.toContain('children: batch.sourceId');
    expect(detailPage).not.toContain('punchImportTaskNumber');
    expect(detailPage).not.toContain('任务编号');
    expect(detailPage).not.toContain("key: 'code', title:");
    expect(detailPage).toContain('punchImportIssueDescription(issue.safeMessage)');
  });
});

function read(fileName: string): string {
  return readFileSync(resolve(process.cwd(), 'src/features/punchImport', fileName), 'utf8');
}

import type { AttendanceSourceView } from '../attendanceSources/attendanceSourceTypes';
import type { CompanyReference } from '../referenceData/referenceDataApi';

const legacyCompanyNames: Readonly<Record<string, string>> = {
  'LEGAL-JIANGSU': '江苏神州半导体科技有限公司',
};

const legacySourceNames: Readonly<Record<string, string>> = {
  'SRC-XLS-OFFLINE-A': '离线考勤文件（一号厂区）',
};

export function punchImportCreatedAtLabel(createdAt: string): string {
  const timestamp = Date.parse(createdAt);
  if (!Number.isFinite(timestamp)) return '创建时间暂不可用';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

/**
 * Batch IDs remain route and persistence values only. A source filename plus
 * creation time gives operators a recognizable task label.
 */
export function punchImportTaskLabel(originalFilename: string, createdAt: string): string {
  const fileName = originalFilename.trim() || '考勤导入文件';
  return `${fileName} · ${punchImportCreatedAtLabel(createdAt)}`;
}

export function punchImportIssueDescription(message: string): string {
  const safe = message.trim();
  const containsTechnicalReference = /[0-9a-f]{8}-[0-9a-f-]{27}/i.test(safe)
    || /\b[A-Z][A-Z0-9_]{3,}\b/.test(safe)
    || /\b(?:batchId|rowId|sourceId|employeeId|rowVersion)\b/i.test(safe);
  if (
    !safe
    || safe.length > 180
    || containsTechnicalReference
    || !/[\u3400-\u9fff]/u.test(safe)
  ) {
    return '请根据问题原因检查该行内容。';
  }
  return safe;
}

export function punchImportCompanyName(
  companyId: string,
  companies: CompanyReference[],
): string {
  return companies.find((company) => company.companyId === companyId)?.companyName
    || legacyCompanyNames[companyId]
    || '公司信息暂不可用';
}

export function punchImportSourceName(
  sourceId: string,
  sources: AttendanceSourceView[],
): string {
  return sources.find((source) => source.sourceId === sourceId)?.displayName
    || legacySourceNames[sourceId]
    || '来源信息暂不可用';
}

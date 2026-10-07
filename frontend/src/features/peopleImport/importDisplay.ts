const fieldLabels: Readonly<Record<string, string>> = {
  organizationCode: '组织编码',
  name: '组织名称',
  parentOrganizationCode: '上级组织编码',
  organizationType: '组织类型',
  employeeNumber: '工号',
  externalEmployeeId: '外部员工编号',
  displayName: '姓名',
  effectiveFrom: '生效日期',
  startDate: '任职开始日期',
  terminationDate: '离职日期',
  amountDays: '变动天数',
  businessDate: '业务日期',
  reason: '原因',
  status: '状态',
};

const fieldValueLabels: Readonly<Record<string, Readonly<Record<string, string>>>> = {
  organizationType: {
    COMPANY: '公司',
    DEPARTMENT: '部门',
    TEAM: '团队',
  },
  status: {
    ACTIVE: '有效',
    INACTIVE: '停用',
    TERMINATED: '已离职',
  },
};

const issueDescriptions: Readonly<Record<string, string>> = {
  ORGANIZATION_PARENT_MISSING: '未找到对应的上级组织，请检查组织信息。',
  VERSION_EFFECTIVE_DATE_INVALID: '生效日期与现有记录冲突，请调整后重新预检。',
  ORGANIZATION_PARENT_CYCLE: '组织上下级关系形成循环，请调整上级组织。',
  ORGANIZATION_INACTIVE: '任职组织当前不可用，请选择有效组织。',
  EMPLOYMENT_PERIOD_OVERLAP: '任职日期与现有任职记录重叠，请调整日期。',
  EMPLOYEE_MATCH_KEY_REQUIRED: '请填写工号或可用的外部员工编号。',
  EMPLOYEE_MATCH_AMBIGUOUS: '员工信息匹配到多条记录，请检查工号或外部员工编号。',
  DUPLICATE_BUSINESS_KEY: '业务唯一信息重复，请检查后仅保留正确记录。',
  PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER: '工龄变动日期顺序不正确，请检查业务日期。',
  PRIOR_SERVICE_TOTAL_NEGATIVE: '工龄变动后累计天数不能为负数，请检查变动天数。',
};

/**
 * Import task IDs remain routing and persistence values only. The UI identifies a
 * task by its source file and creation time, both of which users can recognize.
 */
export function importTaskLabel(createdAt: string, originalFileName?: string | null): string {
  const timestamp = Date.parse(createdAt);
  const dateLabel = Number.isFinite(timestamp)
    ? new Intl.DateTimeFormat('zh-CN', {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: 'Asia/Shanghai',
    }).format(timestamp)
    : undefined;
  const fileName = originalFileName?.trim();
  if (fileName && dateLabel) return `${fileName} · ${dateLabel}`;
  if (fileName) return fileName;
  return dateLabel ? `创建于 ${dateLabel}` : '导入任务';
}

/**
 * Maps workbook keys to business-facing Chinese labels. Unknown keys deliberately
 * collapse to a neutral label so backend field names never leak into the UI.
 */
export function peopleImportFieldLabel(field?: string | null): string {
  if (!field) return '整行';
  return fieldLabels[field] ?? '其他字段';
}

export function formatPeopleImportValues(values?: Record<string, unknown>): string {
  if (!values) return '—';
  const visibleValues = Object.entries(values).flatMap(([field, value]) => {
    const label = fieldLabels[field];
    if (!label) return [];
    return [`${label}：${formatFieldValue(field, value)}`];
  });
  return visibleValues.length > 0 ? visibleValues.join('；') : '已记录业务变更';
}

/**
 * The server issue code is used only to choose a safe explanation. Raw codes,
 * UUIDs and backend field names are never rendered.
 */
export function peopleImportIssueDescription(
  code: string,
  message: string,
  field?: string | null,
): string {
  if (code === 'REQUIRED_FIELD_MISSING') {
    return field
      ? `${peopleImportFieldLabel(field)}不能为空，请补充后重新上传。`
      : '电子表格中没有可导入的数据，请补充后重新上传。';
  }
  const knownDescription = issueDescriptions[code];
  if (knownDescription) return knownDescription;

  const safeMessage = sanitizeIssueMessage(message);
  if (safeMessage) return safeMessage;
  const fieldLabel = peopleImportFieldLabel(field);
  return field
    ? `请检查${fieldLabel}的填写内容。`
    : '请检查该行内容后重新预检。';
}

function formatFieldValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  const mapped = fieldValueLabels[field]?.[String(value)];
  if (mapped) return mapped;
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  return '已填写';
}

function sanitizeIssueMessage(message: string): string | undefined {
  let safe = message.trim();
  if (!safe) return undefined;
  for (const [key, label] of Object.entries(fieldLabels)) {
    safe = safe.replaceAll(key, label);
  }
  safe = safe
    .replaceAll('外部精确员工 ID', '外部员工编号')
    .replaceAll('外部精确员工ID', '外部员工编号')
    .replaceAll('YYYY-MM-DD', '年-月-日格式')
    .replace(/\bCOMPANY\b/g, '公司')
    .replace(/\bDEPARTMENT\b/g, '部门')
    .replace(/\bTEAM\b/g, '团队');

  const containsOpaqueReference = /[0-9a-f]{8}-[0-9a-f-]{27}/i.test(safe)
    || /\b[A-Z][A-Z0-9_]{3,}\b/.test(safe)
    || /\b[a-z]+(?:[A-Z][a-z0-9]+)+\b/.test(safe)
    || /\b(?:rowVersion|versionId|resourceId|batchId)\b/i.test(safe);
  if (containsOpaqueReference || !/[\u3400-\u9fff]/u.test(safe)) return undefined;
  return safe.length <= 180 ? safe : undefined;
}

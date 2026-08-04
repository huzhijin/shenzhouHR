const auditActionLabels: Readonly<Record<string, string>> = {
  ACCOUNT_CREATED: '创建本地账号',
  ACCOUNT_STATUS_CHANGED: '变更账号状态',
  ACCOUNT_DISABLED: '停用账号',
  ACCOUNT_LOCKED: '锁定账号',
  ACCOUNT_AUTO_LOCKED: '自动锁定账号',
  ACCOUNT_UNLOCKED: '解锁账号',
  FIRST_PASSWORD_CHANGED: '完成首次密码修改',
  PASSWORD_CHANGED: '修改密码',
  PASSWORD_RESET: '重置密码',
  PASSWORD_RESET_REQUESTED: '申请密码重置',
  PASSWORD_RESET_GRANT_ISSUED: '签发密码重置授权',
  ROLE_ASSIGNMENTS_REPLACED: '更新角色授权',
  SESSION_REVOKED: '撤销登录会话',
  LOGIN_SUCCEEDED: '登录成功',
  LOGIN_FAILED: '登录失败',
  LOGIN_REJECTED_LOCKED: '因账号锁定拒绝登录',
  LOGOUT: '退出登录',
  POLICY_DRAFT_CREATED: '创建策略草稿',
  POLICY_DRAFT_UPDATED: '更新策略草稿',
  SCOPE_BINDINGS_UPDATED: '更新策略作用范围',
  POLICY_VALIDATED: '校验策略版本',
  POLICY_CONFLICT_CHECKED: '检查策略范围冲突',
  POLICY_IMPACT_PREVIEWED: '预览策略影响',
  POLICY_SIMULATED: '试算策略版本',
  POLICY_PUBLISHED: '发布策略版本',
  POLICY_DEACTIVATED: '停用策略版本',
  POLICY_ROLLED_BACK: '回滚策略版本',
  ORGANIZATION_VERSION_CREATED: '创建组织版本',
  EMPLOYEE_VERSION_CREATED: '创建员工档案版本',
  EMPLOYMENT_PERIOD_VERSION_CREATED: '创建任职周期版本',
  PRIOR_SERVICE_ADJUSTED: '调整累计工龄',
  PRIOR_SERVICE_RECALCULATED: '重新计算累计工龄',
  PEOPLE_IMPORT_DRAFT_CREATED: '创建期初导入草稿',
  PEOPLE_IMPORT_FILE_UPLOADED: '上传期初导入文件',
  PEOPLE_IMPORT_MAPPING_CHANGED: '调整期初导入字段映射',
  PEOPLE_IMPORT_PRECHECKED: '预检期初导入批次',
  PEOPLE_IMPORT_ERROR_REPORT_DOWNLOADED: '下载期初导入错误报告',
  PEOPLE_IMPORT_PUBLISHED: '发布期初导入批次',
  PEOPLE_IMPORT_DRAFT_VOIDED: '作废期初导入草稿',
  PEOPLE_IMPORT_ROLLBACK_REQUESTED: '申请回滚期初导入批次',
  ATTENDANCE_LOCATION_CREATED: '创建考勤地点',
  ATTENDANCE_LOCATION_UPDATED: '更新考勤地点',
  ATTENDANCE_LOCATION_ACTIVE: '启用考勤地点',
  ATTENDANCE_LOCATION_INACTIVE: '停用考勤地点',
  ATTENDANCE_GROUP_CREATED: '创建考勤组',
  ATTENDANCE_GROUP_UPDATED: '更新考勤组',
  ATTENDANCE_GROUP_ACTIVE: '启用考勤组',
  ATTENDANCE_GROUP_INACTIVE: '停用考勤组',
  ATTENDANCE_GROUP_ASSIGNMENT_CREATED: '创建考勤组人员归属',
  ATTENDANCE_GROUP_ASSIGNMENT_UPDATED: '更新考勤组人员归属',
  SHIFT_TEMPLATE_CREATED: '创建班次模板',
  SHIFT_TEMPLATE_UPDATED: '更新班次模板',
  SHIFT_VERSION_DRAFTED: '创建班次草稿版本',
  SHIFT_VERSION_DRAFT_UPDATED: '更新班次草稿版本',
  SHIFT_VERSION_PUBLISHED: '发布班次版本',
  SHIFT_VERSION_DEACTIVATED: '停用班次版本',
  WORK_CALENDAR_CREATED: '创建工作日历',
  WORK_CALENDAR_UPDATED: '更新工作日历',
  WORK_CALENDAR_VERSION_CREATED: '创建工作日历版本',
  WORK_CALENDAR_VERSION_UPDATED: '更新工作日历版本',
  WORK_CALENDAR_DAYS_REPLACED: '替换工作日历日期',
  WORK_CALENDAR_DAYS_UPSERTED: '维护工作日历日期',
  WORK_CALENDAR_VERSION_PUBLISHED: '发布工作日历版本',
  WORK_CALENDAR_VERSION_DEACTIVATED: '停用工作日历版本',
  ATTENDANCE_POLICY_DRAFT_CREATED: '创建考勤策略草稿',
  ATTENDANCE_POLICY_DRAFT_UPDATED: '更新考勤策略草稿',
  ATTENDANCE_POLICY_VALIDATED: '校验考勤策略版本',
  ATTENDANCE_POLICY_PUBLISHED: '发布考勤策略版本',
  ATTENDANCE_POLICY_DEACTIVATION_SCHEDULED: '计划停用考勤策略版本',
  ATTENDANCE_POLICY_ROLLED_BACK: '回滚考勤策略版本',
};

const auditResourceLabels: Readonly<Record<string, string>> = {
  LOCAL_ACCOUNT: '本地账号',
  USER_SESSION: '登录会话',
  ROLE_ASSIGNMENT: '角色授权',
  POLICY_TEMPLATE: '策略模板',
  POLICY_VERSION: '策略版本',
  POLICY_SCOPE: '策略作用范围',
  ORGANIZATION: '组织',
  EMPLOYEE: '员工档案',
  EMPLOYMENT_PERIOD: '任职周期',
  PRIOR_SERVICE: '累计工龄',
  PEOPLE_IMPORT: '组织与员工期初导入批次',
  ATTENDANCE_LOCATION: '考勤地点',
  ATTENDANCE_GROUP: '考勤组',
  ATTENDANCE_GROUP_ASSIGNMENT: '考勤组人员归属',
  SHIFT_TEMPLATE: '班次模板',
  SHIFT_VERSION: '班次版本',
  WORK_CALENDAR: '工作日历',
  WORK_CALENDAR_VERSION: '工作日历版本',
  ATTENDANCE_POLICY_SCOPED_VERSION: '考勤策略版本',
  ATTENDANCE_POLICY_BINDING: '考勤策略绑定',
  ATTENDANCE_PUNCH_IMPORT: '考勤打卡导入批次',
};

const auditActorLabels: Readonly<Record<string, string>> = {
  LOCAL_ACCOUNT: '本地账号操作人',
  SYSTEM: '系统服务',
  SERVICE_ACCOUNT: '服务账号',
  SCHEDULED_JOB: '定时任务',
  API_CLIENT: '接口客户端',
};

const auditFieldLabels: Readonly<Record<string, string>> = {
  status: '状态',
  roleAssignments: '角色授权',
  scopeBindings: '作用范围',
  parameters: '策略参数',
  effectiveFrom: '生效日期',
  effectiveTo: '失效日期',
  displayName: '显示名称',
  employeeNumber: '员工编号',
  organizationId: '所属组织',
  rowVersion: '数据版本',
};

const hiddenAuditFields = new Set([
  'actorId',
  'correlationId',
  'eventId',
  'requestId',
  'resourceId',
  'rowVersion',
]);

export function auditActionLabel(action: string): string {
  const normalized = action.trim().toUpperCase();
  if (!normalized) return '未记录动作';
  return auditActionLabels[normalized]
    ?? (isMachineCode(normalized) || containsOpaqueIdentifier(action)
      ? '其他审计操作'
      : action);
}

export function auditResourceLabel(resourceType: string): string {
  const normalized = resourceType.trim().toUpperCase();
  if (!normalized) return '未记录对象';
  return auditResourceLabels[normalized]
    ?? (isMachineCode(normalized) || containsOpaqueIdentifier(resourceType)
      ? '其他业务对象'
      : resourceType);
}

export function auditActorLabel(actor: string): string {
  const normalized = actor.trim().toUpperCase();
  if (!normalized) return '未记录操作人';
  return auditActorLabels[normalized]
    ?? (isMachineCode(normalized) || containsOpaqueIdentifier(actor)
      ? '系统操作主体'
      : actor);
}

export function auditFieldLabel(field: string): string {
  return auditFieldLabels[field]
    ?? (isMachineCode(field)
      || containsOpaqueIdentifier(field)
      || looksLikeIdentifierField(field)
      ? '其他变更字段'
      : field);
}

/**
 * 审计标识继续保留在 API 中用于筛选和路由。它们属于实现细节，
 * 只包含这些字段的变更不进入业务侧变更轨迹。
 */
export function isHiddenAuditField(field: string): boolean {
  return hiddenAuditFields.has(field);
}

export function shouldMaskAuditChange(
  field: string,
  before?: string,
  after?: string,
): boolean {
  return looksLikeIdentifierField(field)
    || [before, after].some((value) => (
      value !== undefined
      && (containsOpaqueIdentifier(value) || looksStructured(value))
    ));
}

export function auditTextLabel(value: string, maskedLabel: string): string {
  return containsOpaqueIdentifier(value) || looksStructured(value)
    ? maskedLabel
    : value;
}

function isMachineCode(value: string): boolean {
  return /^[A-Z][A-Z0-9_]*$/.test(value);
}

function containsOpaqueIdentifier(value: string): boolean {
  const trimmed = value.trim();
  return /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.test(trimmed)
    || /(?:^|\D)\d{16,}(?:\D|$)/.test(trimmed);
}

function looksLikeIdentifierField(field: string): boolean {
  return /(?:^|[_-])(?:id|ids)$/i.test(field)
    || /(?:Id|Ids)$/.test(field);
}

function looksStructured(value: string): boolean {
  const trimmed = value.trim();
  return (trimmed.startsWith('{') && trimmed.endsWith('}'))
    || (trimmed.startsWith('[') && trimmed.endsWith(']'));
}

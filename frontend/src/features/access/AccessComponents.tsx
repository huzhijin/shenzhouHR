import { Button, Checkbox, Descriptions, Tag } from 'antd';
import { useTranslation } from 'react-i18next';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import {
  CompanySelect,
  OrganizationSelect,
} from '../referenceData';
import type { AccountDetail, RoleAssignmentView, RoleView } from './accessApi';
import { allowedScopeTypes } from './roleScopePolicy';

const hiddenRoleCodes = new Set([
  'MANUFACTURING_SUPERVISOR',
  'MANUFACTURING_CENTER_SUPERVISOR',
  'MANUFACTURING_DIRECTOR',
  'MANUFACTURING_CENTER_DIRECTOR',
]);

const hiddenRoleNames = new Set([
  '制造中心主管',
  '制造中心主任',
  'manufacturing center supervisor',
  'manufacturing center director',
]);

const roleDescriptions: Readonly<Record<string, string>> = {
  SYSTEM_ADMIN: '维护系统配置、账号和权限。这是最高权限，只建议分配给系统负责人。',
  HR_ADMIN: '在所属公司内维护组织、员工和考勤设置，处理日常人事工作。',
  AUDITOR: '只读查看操作记录和相关数据，用于审计、核对和问题追溯。',
  DEPARTMENT_HEAD: '查看所负责部门的员工、考勤和请假信息，不管理系统设置。',
  DEPARTMENT_MANAGER: '查看所负责部门的员工、考勤和请假信息，不管理系统设置。',
  EXECUTIVE: '查看获授权公司或部门的汇总报表和经营概览。',
  EMPLOYEE_SELF: '只查看和处理本人的考勤、请假与反馈，不能查看他人数据。',
  EMPLOYEE: '只查看和处理本人的考勤、请假与反馈，不能查看他人数据。',
};

export function visibleAccessRoles(roles: RoleView[]): RoleView[] {
  return roles.filter((role) => (
    !hiddenRoleCodes.has(role.roleCode.trim().toUpperCase())
    && !hiddenRoleNames.has(role.roleName.trim().toLowerCase())
  ));
}

export function roleDescription(role: Pick<RoleView, 'roleCode' | 'roleName'>): string {
  return roleDescriptions[role.roleCode.trim().toUpperCase()]
    ?? `${role.roleName}的具体功能以下方权限数量和高级对照表为准。`;
}

export function RoleOverviewCards({ roles }: { roles: RoleView[] }) {
  const visibleRoles = visibleAccessRoles(roles);
  return (
    <section className="role-overview" aria-labelledby="role-overview-title">
      <div className="role-overview__heading">
        <div>
          <h2 id="role-overview-title">各角色能做什么</h2>
          <p>先看用途和数据范围；只有需要核对细项时，再展开下方高级权限对照。</p>
        </div>
        <Tag color="blue">共 {visibleRoles.length} 个角色</Tag>
      </div>
      <div className="role-card-grid" role="list">
        {visibleRoles.map((role) => {
          const capabilities = uniqueCapabilities(role.capabilities);
          const scopeTypes = allowedScopeTypes(role);
          const capabilityDomains = uniqueCapabilityDomains(capabilities);
          const shownDomains = capabilityDomains.slice(0, 5);
          const hiddenDomainCount = capabilityDomains.length - shownDomains.length;
          return (
            <article className="role-card" key={role.roleId} role="listitem">
              <header className="role-card__header">
                <div>
                  <span className="role-card__eyebrow">角色</span>
                  <h3>{role.roleName}</h3>
                </div>
                <Tag>{capabilities.length} 项权限</Tag>
              </header>
              <p className="role-card__description">{roleDescription(role)}</p>
              <div className="role-card__section">
                <strong>可授权范围</strong>
                <div className="role-card__tags">
                  {scopeTypes.length > 0
                    ? scopeTypes.map((scopeType) => <Tag color="geekblue" key={scopeType}>{roleScopeLabel(scopeType)}</Tag>)
                    : <Tag>未配置</Tag>}
                </div>
              </div>
              <div className="role-card__section">
                <strong>主要功能</strong>
                <div className="role-card__tags">
                  {shownDomains.length > 0
                    ? shownDomains.map((domain) => <Tag key={domain}>{domain}</Tag>)
                    : <Tag>暂无功能权限</Tag>}
                  {hiddenDomainCount > 0 ? <Tag>+{hiddenDomainCount} 类</Tag> : null}
                </div>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}

export function AccountStatusPanel({ account }: { account: AccountDetail }) {
  const { t } = useTranslation();
  const dataState = account.resetPending
    ? 'reset-pending'
    : account.firstPasswordChangeRequired
      ? 'first-change-required'
      : account.status.toLowerCase();
  return (
    <section className="detail-section" data-state={dataState} aria-labelledby="account-status-title">
      <h2 id="account-status-title">{t('access.accountStatus')}</h2>
      <Descriptions
        column={{ xs: 1, sm: 2 }}
        items={[
          { key: 'status', label: t('access.status'), children: <StatusBadge status={account.status} /> },
          { key: 'first', label: t('access.firstChange'), children: account.firstPasswordChangeRequired ? t('access.pending') : t('access.completed') },
          { key: 'reset', label: t('access.resetGrant'), children: account.resetPending ? t('access.pendingUse') : t('access.noResetGrant') },
          { key: 'locked', label: t('access.lockedUntil'), children: account.lockedUntil ? formatTime(account.lockedUntil) : t('common.none') },
          { key: 'login', label: t('access.lastLogin'), children: account.lastLoginAt ? formatTime(account.lastLoginAt) : t('access.neverLoggedIn') },
        ]}
      />
    </section>
  );
}

export function SessionStatusPanel({ sessions, onRevoke }: {
  sessions: AccountDetail['sessions'];
  onRevoke?: (sessionId: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <section className="detail-section" aria-labelledby="session-status-title">
      <h2 id="session-status-title">{t('access.sessionStatus')}</h2>
      {sessions.length === 0 ? (
        <p className="session-list__empty">{t('access.noSessions')}</p>
      ) : (
        <ul className="session-list">
          {Array.from(sessions, (session, index) => (
            <li
              className="session-list__item"
              key={session.sessionId}
              data-state={session.status === 'REVOKED' ? 'session-revoked' : session.status.toLowerCase()}
            >
              <div className="session-list__content">
                <div>
                  <strong>{t('access.sessionNumber', { number: index + 1 })}</strong>{' '}
                  <StatusBadge status={session.status} />
                </div>
                <p>{t('access.sessionDescription', { issued: formatTime(session.createdAt), idle: formatTime(session.idleExpiresAt), absolute: formatTime(session.absoluteExpiresAt) })}</p>
              </div>
              {session.status === 'ACTIVE' && onRevoke ? <Button danger size="small" onClick={() => onRevoke(session.sessionId)}>{t('access.revokeSession')}</Button> : null}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function PermissionMatrix({ roles, selectedRoleIds, readOnly = false, onChange }: {
  roles: RoleView[];
  selectedRoleIds: string[];
  readOnly?: boolean;
  onChange?: (ids: string[]) => void;
}) {
  const { t } = useTranslation();
  const capabilities = Array.from(new Set(roles.flatMap((role) => role.capabilities))).sort();
  return (
    <div
      className="permission-matrix data-table-scroll"
      role="region"
      aria-label={t('access.permissionMatrix')}
      tabIndex={0}
    >
      <table>
        <caption>{t('access.permissionMatrix')}</caption>
        <thead><tr><th scope="col">{t('access.capability')}</th>{Array.from(roles, (role) => <th scope="col" key={role.roleId}>{role.roleName}</th>)}</tr></thead>
        <tbody>
          {Array.from(capabilities, (capability) => (
            <tr key={capability}>
              <th scope="row">{capabilityLabel(capability)}</th>
              {Array.from(roles, (role) => (
                <td key={role.roleId}>
                  <Checkbox
                    aria-label={`${role.roleName} ${capabilityLabel(capability)}`}
                    disabled={readOnly || !role.capabilities.includes(capability)}
                    checked={role.capabilities.includes(capability) && selectedRoleIds.includes(role.roleId)}
                    onChange={(event) => {
                      if (!onChange) return;
                      const next = event.target.checked
                        ? [...selectedRoleIds, role.roleId]
                        : selectedRoleIds.filter((id) => id !== role.roleId);
                      onChange(Array.from(new Set(next)));
                    }}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function RoleScopeList({ assignments }: { assignments: RoleAssignmentView[] }) {
  const { t } = useTranslation();
  if (assignments.length === 0) {
    return <p className="session-list__empty">当前账号尚未分配角色。</p>;
  }
  return (
    <Descriptions
      column={{ xs: 1, md: 2 }}
      items={assignments.map((assignment) => ({
        key: assignment.assignmentId,
        label: assignment.roleName,
        children: (
          <div>
            <div>{roleScopeLabel(assignment.scopeType)}</div>
            {assignment.scopeType === 'COMPANY' && assignment.scopeResourceId ? (
              <CompanySelect
                aria-label={t('access.authorizedCompany')}
                disabled
                size="small"
                value={assignment.scopeResourceId}
              />
            ) : null}
            {assignment.scopeType === 'ORGANIZATION' && assignment.scopeResourceId ? (
              <OrganizationSelect
                aria-label={t('access.authorizedOrganization')}
                disabled
                size="small"
                value={assignment.scopeResourceId}
              />
            ) : null}
            {assignment.scopeType !== 'SELF' && !assignment.scopeResourceId ? (
              <div>{t('access.scopeTargetUnset')}</div>
            ) : null}
            <div>
              {`${formatDate(assignment.validFrom)} 至 ${
                assignment.validTo ? formatDate(assignment.validTo) : '长期有效'
              }`}
            </div>
          </div>
        ),
      }))}
    />
  );
}

const capabilityDomainLabels: Readonly<Record<string, string>> = {
  ACCOUNT: '账号',
  ROLE: '角色',
  AUDIT: '审计日志',
  OPERATIONS: '系统运维',
  MASTER_DATA: '主数据',
  ORGANIZATION: '组织',
  EMPLOYEE: '员工档案',
  EMPLOYMENT: '任职记录',
  PRIOR_SERVICE: '累计工龄',
  PEOPLE_IMPORT: '组织与员工导入',
  POLICY: '通用策略',
  ATTENDANCE_SETUP: '考勤设置',
  ATTENDANCE_LOCATION: '考勤地点',
  ATTENDANCE_SOURCE: '考勤数据源',
  ATTENDANCE_PUNCH_IMPORT: '考勤打卡导入',
  ATTENDANCE_DASHBOARD: '考勤工作台',
  ATTENDANCE_PERIOD: '考勤期间',
  ATTENDANCE_REPORT: '考勤报表',
  ATTENDANCE_SELF: '个人考勤',
  LEAVE_MANAGEMENT: '假期管理',
  LEAVE_ACCOUNT: '假期账户',
  LEAVE_POLICY: '假期规则',
  LEAVE_SELF: '个人假期',
  ATTENDANCE_FEEDBACK: '考勤反馈',
};

const capabilityActionLabels: Readonly<Record<string, string>> = {
  READ: '查看',
  CREATE: '新建',
  EDIT: '编辑',
  LOCK: '锁定',
  UNLOCK: '解锁',
  RESET_PASSWORD: '重置密码',
  ASSIGN: '分配授权',
  VALIDATE: '校验',
  SIMULATE: '试算',
  PUBLISH: '发布',
  PARTIAL_PUBLISH: '部分发布',
  DEACTIVATE: '停用',
  ROLLBACK: '回滚',
  TEMPLATE_DOWNLOAD: '下载模板',
  UPLOAD: '上传文件',
  MAP: '字段映射',
  PRECHECK: '预检',
  ERROR_REPORT_DOWNLOAD: '下载错误报告',
  VOID: '作废',
  VOID_OR_REVERSE: '作废或冲正',
  CONFIGURE: '配置',
  RUN: '执行同步',
  RETRY: '重试同步',
  QUARANTINE_READ: '查看隔离数据',
  RAW_FILE_READ: '查看原始文件',
  RAW_ROW_READ: '查看原始行',
  DUPLICATE_REVIEW: '复核重复记录',
  RECALCULATE: '触发重算',
  ADJUST: '调整',
  MANAGE: '管理',
  MATERIALIZE: '生成账户',
  EXPORT: '导出',
  MANAGE_GROUP: '管理考勤组',
  MANAGE_SHIFT: '管理班次',
  MANAGE_CALENDAR: '管理工作日历',
  MANAGE_POLICY: '管理考勤策略',
  EXPORT_CREATE: '创建导出任务',
  EXPORT_DOWNLOAD: '下载导出文件',
  REFRESH: '刷新数据',
  SYNC_PREVIEW: '预览同步',
  CLOSE: '月结',
  PRECLOSE: '月结预检',
  REOPEN: '重新打开',
  SCHEDULE_CLOSE: '定时月结',
};

const roleScopeLabels: Readonly<Record<string, string>> = {
  COMPANY: '公司范围',
  ORGANIZATION: '部门范围',
  SELF: '仅本人',
};

export function capabilityLabel(capability: string): string {
  const [domain, action] = capability.trim().toUpperCase().split(':');
  if (!domain || !action) return '未识别权限';
  return `${capabilityDomainLabels[domain] ?? '其他功能'} · ${capabilityActionLabels[action] ?? '其他操作'}`;
}

export function capabilityDomainLabel(capability: string): string {
  const [domain] = capability.trim().toUpperCase().split(':');
  if (!domain) return '其他功能';
  return capabilityDomainLabels[domain] ?? '其他功能';
}

export function roleScopeLabel(scopeType: string): string {
  return roleScopeLabels[scopeType.trim().toUpperCase()] ?? '其他授权范围';
}

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

function formatDate(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '未设置';
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

function uniqueCapabilities(capabilities: string[]): string[] {
  return Array.from(new Set(capabilities.map((capability) => capability.trim()).filter(Boolean)));
}

function uniqueCapabilityDomains(capabilities: string[]): string[] {
  return Array.from(new Set(capabilities.map(capabilityDomainLabel))).sort((left, right) => (
    left.localeCompare(right, 'zh-CN')
  ));
}

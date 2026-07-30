import { Button, Checkbox, Descriptions } from 'antd';
import { useTranslation } from 'react-i18next';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import type { AccountDetail, RoleAssignmentView, RoleView } from './accessApi';

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
          { key: 'version', label: t('policy.rowVersion'), children: account.rowVersion },
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
          {Array.from(sessions, (session) => (
            <li
              className="session-list__item"
              key={session.sessionId}
              data-state={session.status === 'REVOKED' ? 'session-revoked' : session.status.toLowerCase()}
            >
              <div className="session-list__content">
                <div><code>{session.sessionId}</code> <StatusBadge status={session.status} /></div>
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
          <span>
            {roleScopeLabel(assignment.scopeType)}
            {assignment.scopeType !== 'SELF' && assignment.scopeResourceId
              ? ` · 范围 ID ${assignment.scopeResourceId}`
              : ''}
            {` · ${formatDate(assignment.validFrom)} 至 ${assignment.validTo ? formatDate(assignment.validTo) : '长期有效'}`}
          </span>
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
  ATTENDANCE_SOURCE: '考勤数据源',
  ATTENDANCE_PUNCH_IMPORT: '考勤打卡导入',
  ATTENDANCE_DASHBOARD: '考勤工作台',
  ATTENDANCE_REPORT: '考勤报表',
  ATTENDANCE_SELF: '个人考勤',
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
  MANAGE_GROUP: '管理考勤组',
  MANAGE_SHIFT: '管理班次',
  MANAGE_CALENDAR: '管理工作日历',
  MANAGE_POLICY: '管理考勤策略',
  EXPORT_CREATE: '创建导出任务',
  EXPORT_DOWNLOAD: '下载导出文件',
};

const roleScopeLabels: Readonly<Record<string, string>> = {
  COMPANY: '公司范围',
  ORGANIZATION: '组织范围',
  SELF: '仅本人',
};

export function capabilityLabel(capability: string): string {
  const [domain, action] = capability.trim().toUpperCase().split(':');
  if (!domain || !action) return '未识别权限';
  return `${capabilityDomainLabels[domain] ?? '其他功能'} · ${capabilityActionLabels[action] ?? '其他操作'}`;
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

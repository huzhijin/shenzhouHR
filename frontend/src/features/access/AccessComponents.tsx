import { Button, Checkbox, Descriptions } from 'antd';
import { useTranslation } from 'react-i18next';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import type { AccountDetail, RoleView } from './accessApi';

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
              <th scope="row"><code>{capability}</code></th>
              {Array.from(roles, (role) => (
                <td key={role.roleId}>
                  <Checkbox
                    aria-label={`${role.roleName} ${capability}`}
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

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

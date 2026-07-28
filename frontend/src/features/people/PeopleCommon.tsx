import { IconClock, IconDatabase, IconHistory } from '@tabler/icons-react';
import { Tabs } from 'antd';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { listAuditEvents } from '../audit/auditApi';
import { DataTable } from '../../shared/components/DataTable';
import {
  CorrelationIdDisplay,
  StatusBadge,
} from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { ApiRequestError } from '../../shared/api/apiClient';

export function PeopleContextStrip({ items }: {
  items: Array<{ label: string; value: string; mono?: boolean }>;
}) {
  return (
    <dl className="people-context-strip">
      {items.map((item) => (
        <div key={item.label}>
          <dt>{item.label}</dt>
          <dd className={item.mono ? 'mono-value' : undefined}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function SourceAuthority({ authority }: { authority: 'INITIAL_EXCEL' | 'LOCAL' }) {
  const { t } = useTranslation();
  return (
    <span className={`source-authority source-authority--${authority.toLowerCase()}`}>
      <IconDatabase aria-hidden="true" stroke={2} size="var(--size-icon-md)" />
      {authority === 'LOCAL' ? t('people.source.local') : t('people.source.initialExcel')}
    </span>
  );
}

export function ApiErrorState({ error, onRetry }: {
  error: ApiRequestError;
  onRetry?: () => void;
}) {
  const state = error.status === 0
    ? 'network-error'
    : error.status === 401
      ? '401'
      : error.status === 403
        ? '403'
        : error.status === 404
          ? '404'
          : error.status === 409 || error.status === 412
            ? error.code === 'STALE_VERSION' ? 'stale' : 'conflict'
            : error.status === 422
              ? 'validation-error'
              : 'error';
  return (
    <div>
      <StatePanel
        state={state}
        description={error.message}
        onRetry={error.retryable ? onRetry : undefined}
      />
      <CorrelationIdDisplay correlationId={error.correlationId} />
    </div>
  );
}

interface VersionEntry {
  rowVersion: number;
  status?: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  changeReason?: string | null;
  createdBy: string;
  createdAt: string;
}

export function VersionAuditPanel({ versions, resourceType, resourceId, canReadAudit }: {
  versions: VersionEntry[];
  resourceType: string;
  resourceId: string;
  canReadAudit: boolean;
}) {
  const { t } = useTranslation();
  const auditLoader = useMemo(
    () => canReadAudit
      ? () => listAuditEvents({ resourceType, resourceId, page: 0, size: 20 })
      : () => Promise.reject(new ApiRequestError(403, {
        code: 'ACCESS_DENIED',
        retryable: false,
      })),
    [canReadAudit, resourceId, resourceType],
  );
  const audit = useAsyncResource(auditLoader, (page) => page.items.length === 0, [
    resourceId,
    resourceType,
    canReadAudit,
  ]);
  return (
    <Tabs
      className="people-history-tabs"
      items={[
        {
          key: 'versions',
          label: (
            <span className="tab-label">
              <IconHistory aria-hidden="true" stroke={2} size="var(--size-icon-md)" />
              {t('people.versions')}
            </span>
          ),
          children: versions.length === 0 ? (
            <StatePanel state="empty" description={t('people.noVersions')} />
          ) : (
            <DataTable
              rows={versions}
              rowKey={(row) => `${row.rowVersion}-${row.createdAt}`}
              columns={[
                { key: 'version', title: t('people.rowVersion'), render: (row) => <code>V{row.rowVersion}</code> },
                { key: 'status', title: t('people.status'), render: (row) => row.status ? <StatusBadge status={row.status} /> : t('common.none') },
                { key: 'effective', title: t('people.effectivePeriod'), render: (row) => `${formatDate(row.effectiveFrom)} — ${row.effectiveTo ? formatDate(row.effectiveTo) : t('people.longTerm')}` },
                { key: 'reason', title: t('people.reason'), render: (row) => row.changeReason ?? t('common.none') },
                { key: 'actor', title: t('people.actor'), render: (row) => <code>{row.createdBy}</code> },
                { key: 'time', title: t('people.changedAt'), render: (row) => formatDateTime(row.createdAt) },
              ]}
            />
          ),
        },
        {
          key: 'audit',
          label: (
            <span className="tab-label">
              <IconClock aria-hidden="true" stroke={2} size="var(--size-icon-md)" />
              {t('people.audit')}
            </span>
          ),
          children: (
            !canReadAudit ? <StatePanel state="403" /> : <>
              {audit.resource.status === 'loading' || audit.resource.status === 'partial-loading'
                ? <StatePanel state={audit.resource.status} /> : null}
              {audit.resource.status === 'empty'
                ? <StatePanel state="empty" description={t('people.noAudit')} /> : null}
              {'error' in audit.resource
                ? <ApiErrorState error={audit.resource.error} onRetry={audit.reload} /> : null}
              {audit.resource.status === 'ready' ? (
                <DataTable
                  rows={audit.resource.data.items}
                  rowKey={(row) => row.eventId}
                  columns={[
                    { key: 'time', title: t('people.changedAt'), render: (row) => formatDateTime(row.occurredAt) },
                    { key: 'action', title: t('people.action'), render: (row) => row.action },
                    { key: 'actor', title: t('people.actor'), render: (row) => row.actorDisplayName },
                    { key: 'result', title: t('people.result'), render: (row) => <StatusBadge status={row.result} /> },
                    { key: 'correlation', title: t('common.correlation'), render: (row) => <code>{row.correlationId}</code> },
                  ]}
                />
              ) : null}
            </>
          ),
        },
      ]}
    />
  );
}

export function formatDate(value: string): string {
  const timestamp = Date.parse(`${value.slice(0, 10)}T00:00:00Z`);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export function formatDateTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

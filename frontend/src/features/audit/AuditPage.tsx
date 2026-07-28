import { Button, Select } from 'antd';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { DataTable } from '../../shared/components/DataTable';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, QueryFilterBar } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { listAuditEvents } from './auditApi';
import {
  auditActionLabel,
  auditActorLabel,
  auditResourceLabel,
} from './auditDisplayLabels';

export function AuditPage() {
  const { t } = useTranslation();
  const [action, setAction] = useState('');
  const [result, setResult] = useState<string>();
  const loader = useMemo(() => () => listAuditEvents({ action, result }), [action, result]);
  const { resource, reload } = useAsyncResource(loader, (page) => page.items.length === 0, [action, result]);
  return (
    <>
      <PageHeader title={t('access.audit')} description={t('audit.description')} breadcrumbs={[{ label: t('access.section') }, { label: t('access.audit') }]} />
      <section className="content-surface">
        <QueryFilterBar query={action} onQueryChange={setAction} placeholder={t('audit.searchAction')}>
          <Select
            allowClear
            aria-label={t('audit.operationResult')}
            placeholder={t('audit.allResults')}
            value={result}
            onChange={setResult}
            options={[
              { value: 'SUCCESS', label: t('status.success') },
              { value: 'FAILURE', label: t('status.failed') },
              { value: 'DENIED', label: '已拒绝' },
            ]}
          />
          <Button onClick={reload}>{t('common.refresh')}</Button>
        </QueryFilterBar>
        {resource.status === 'loading' || resource.status === 'partial-loading' ? <StatePanel state={resource.status} /> : null}
        {resource.status === 'empty' ? <StatePanel state="empty" description={t('audit.noEvents')} /> : null}
        {'error' in resource ? <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} /> : null}
        {resource.status === 'ready' ? (
          <DataTable
            rows={resource.data.items}
            rowKey={(row) => row.eventId}
            columns={[
              { key: 'time', title: t('audit.operationTime'), render: (row) => formatTime(row.occurredAt) },
              { key: 'actor', title: t('audit.actor'), render: (row) => auditActorLabel(row.actorDisplayName) },
              { key: 'action', title: t('audit.action'), render: (row) => <Link to={`/access/audit/${row.eventId}`}>{auditActionLabel(row.action)}</Link> },
              { key: 'resource', title: t('audit.resource'), render: (row) => `${auditResourceLabel(row.resourceType)}${row.resourceId ? ` · ${row.resourceId}` : ''}` },
              { key: 'result', title: t('audit.result'), render: (row) => <StatusBadge status={row.result} /> },
              { key: 'correlation', title: t('common.correlation'), render: (row) => <code>{row.correlationId}</code> },
            ]}
          />
        ) : null}
      </section>
    </>
  );
}

export default AuditPage;

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

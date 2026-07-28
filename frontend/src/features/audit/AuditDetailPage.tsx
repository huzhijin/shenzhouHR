import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import { AuditTimeline, CorrelationIdDisplay, ReadOnlyDetails, StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { getAuditEvent } from './auditApi';

export function AuditDetailPage() {
  const { t } = useTranslation();
  const { auditEventId = '' } = useParams();
  const loader = useMemo(() => () => getAuditEvent(auditEventId), [auditEventId]);
  const { resource, reload } = useAsyncResource(loader, () => false, [auditEventId]);
  if (resource.status === 'loading' || resource.status === 'partial-loading') return <StatePanel state={resource.status} />;
  if ('error' in resource) return <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} />;
  if (resource.status !== 'ready') return <StatePanel state="404" />;
  const event = resource.data;
  return (
    <>
      <PageHeader title={event.action} description={t('audit.readOnlyDescription')} breadcrumbs={[{ label: t('access.section') }, { label: t('access.audit'), path: '/access/audit' }, { label: event.eventId }]} />
      <section className="content-surface">
        <ReadOnlyDetails items={[
          { label: t('audit.eventId'), value: <code>{event.eventId}</code> },
          { label: t('audit.operationTime'), value: formatTime(event.occurredAt) },
          { label: t('audit.actor'), value: event.actorDisplayName },
          { label: t('audit.result'), value: <StatusBadge status={event.result} /> },
          { label: t('audit.resource'), value: `${event.resourceType}${event.resourceId ? ` · ${event.resourceId}` : ''}` },
          { label: t('audit.requestId'), value: event.requestId ? <code>{event.requestId}</code> : t('common.none') },
          { label: t('audit.correlation'), value: <CorrelationIdDisplay correlationId={event.correlationId} /> },
          { label: t('audit.changeReason'), value: event.reason ?? t('common.none') },
        ]} />
      </section>
      {event.changes?.length ? (
        <section className="content-surface section-spaced">
          <h2>{t('audit.changeTrail')}</h2>
          <AuditTimeline entries={Array.from(event.changes, (change) => ({
            title: change.field,
            time: formatTime(event.occurredAt),
            result: event.result,
            detail: `${change.before ?? t('common.none')} → ${change.after ?? t('common.none')}`,
          }))} />
        </section>
      ) : null}
    </>
  );
}

export default AuditDetailPage;

function formatTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) {
    return '—';
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'long', timeStyle: 'medium', timeZone: 'Asia/Shanghai' }).format(timestamp);
}

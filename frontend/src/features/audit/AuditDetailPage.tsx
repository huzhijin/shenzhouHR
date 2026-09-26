import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';

import {
  AuditTimeline,
  ReadOnlyDetails,
  StatusBadge,
  statusLabel,
} from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { getAuditEvent } from './auditApi';
import {
  auditActionLabel,
  auditActorLabel,
  auditFieldLabel,
  auditResourceLabel,
  auditTextLabel,
  isHiddenAuditField,
  shouldMaskAuditChange,
} from './auditDisplayLabels';

export function AuditDetailPage() {
  const { t } = useTranslation();
  const { auditEventId = '' } = useParams();
  const loader = useMemo(() => () => getAuditEvent(auditEventId), [auditEventId]);
  const { resource, reload } = useAsyncResource(loader, () => false, [auditEventId]);
  if (resource.status === 'loading' || resource.status === 'partial-loading') return <StatePanel state={resource.status} />;
  if ('error' in resource) return <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} />;
  if (resource.status !== 'ready') return <StatePanel state="404" />;
  const event = resource.data;
  const visibleChanges = event.changes?.filter((change) => !isHiddenAuditField(change.field));
  return (
    <>
      <PageHeader
        title={auditActionLabel(event.action)}
        description="查看本次操作的结果和变更内容。"
        breadcrumbs={[
          { label: t('access.section') },
          { label: t('access.audit'), path: '/access/audit' },
          { label: '详情' },
        ]}
      />
      <section className="content-surface">
        <ReadOnlyDetails items={[
          { label: t('audit.operationTime'), value: formatTime(event.occurredAt) },
          { label: t('audit.actor'), value: auditActorLabel(event.actorDisplayName) },
          { label: t('audit.result'), value: <StatusBadge status={event.result} /> },
          { label: t('audit.resource'), value: auditResourceLabel(event.resourceType) },
          { label: t('audit.changeReason'), value: event.reason ? auditTextLabel(event.reason, '已记录') : t('common.none') },
        ]} />
      </section>
      {visibleChanges?.length ? (
        <section className="content-surface section-spaced">
          <h2>{t('audit.changeTrail')}</h2>
          <AuditTimeline entries={Array.from(visibleChanges, (change) => ({
            title: auditFieldLabel(change.field),
            time: formatTime(event.occurredAt),
            result: event.result,
            detail: shouldMaskAuditChange(change.field, change.before, change.after)
              ? '已调整'
              : `${change.before ? auditTextLabel(statusLabel(change.before), '已设置') : t('common.none')} → ${change.after ? auditTextLabel(statusLabel(change.after), '已设置') : t('common.none')}`,
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

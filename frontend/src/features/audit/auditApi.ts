import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';

export interface AuditEventSummary {
  eventId: string;
  occurredAt: string;
  actorDisplayName: string;
  action: string;
  resourceType: string;
  resourceId?: string | null;
  result: string;
  correlationId: string;
}

export interface AuditEventDetail extends AuditEventSummary {
  requestId?: string;
  reason?: string;
  changes?: Array<{ field: string; before?: string; after?: string }>;
  metadata?: Record<string, string>;
}

export interface AuditEventPage {
  items: AuditEventSummary[];
  total: number;
  page: number;
  size: number;
}

const demoEvents: AuditEventDetail[] = [
  {
    eventId: '9400000000000000001',
    occurredAt: '2026-07-24T08:30:14Z',
    actorDisplayName: '合成系统管理员',
    action: 'ACCOUNT_UNLOCKED',
    resourceType: 'LOCAL_ACCOUNT',
    resourceId: '9100000000000000002',
    result: 'SUCCESS',
    correlationId: 'synthetic-correlation-001',
    requestId: 'synthetic-request-001',
    reason: '完成本地联调验证',
    changes: [{ field: 'status', before: 'LOCKED', after: 'ACTIVE' }],
  },
  {
    eventId: '9400000000000000002',
    occurredAt: '2026-07-24T08:12:08Z',
    actorDisplayName: '合成规则管理员',
    action: 'POLICY_VALIDATED',
    resourceType: 'POLICY_VERSION',
    resourceId: '9600000000000000002',
    result: 'SUCCESS',
    correlationId: 'synthetic-correlation-002',
  },
];

export function listAuditEvents(filters: {
  action?: string;
  result?: string;
  resourceType?: string;
  resourceId?: string;
  page?: number;
  size?: number;
} = {}): Promise<AuditEventPage> {
  if (isDemoMode()) {
    const items = demoEvents.filter((event) => (
      (!filters.action || event.action.includes(filters.action))
      && (!filters.result || event.result === filters.result)
      && (!filters.resourceType || event.resourceType === filters.resourceType)
      && (!filters.resourceId || event.resourceId === filters.resourceId)
    ));
    return Promise.resolve({ items, total: items.length, page: 0, size: filters.size ?? 20 });
  }
  const params = new URLSearchParams();
  if (filters.action) params.set('action', filters.action);
  if (filters.result) params.set('result', filters.result);
  if (filters.resourceType) params.set('resourceType', filters.resourceType);
  if (filters.resourceId) params.set('resourceId', filters.resourceId);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 20));
  return requestJson<AuditEventPage>(`/api/v1/access/audit-events?${params}`);
}

export function getAuditEvent(eventId: string): Promise<AuditEventDetail> {
  if (isDemoMode()) {
    const event = demoEvents.find((item) => item.eventId === eventId) ?? demoEvents[0];
    if (!event) return Promise.reject(new Error('DEMO_AUDIT_EVENT_NOT_FOUND'));
    return Promise.resolve(event);
  }
  return requestJson<AuditEventDetail>(`/api/v1/access/audit-events/${encodeURIComponent(eventId)}`);
}

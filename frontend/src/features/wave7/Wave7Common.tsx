import { IconLock, IconSnowflake } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';

import type { ApiRequestError } from '../../shared/api/apiClient';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import type {
  DashboardMetricProjection,
  Wave7ProjectionMetadata,
} from './wave7Contracts';

export function Wave7AsyncBoundary<T>({
  loader,
  isEmpty,
  emptyTitle,
  emptyDescription = '当前授权范围内暂无可显示数据。',
  children,
}: {
  loader: () => Promise<T>;
  isEmpty: (projection: T) => boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  children: (projection: T) => ReactNode;
}) {
  const { resource, reload } = useAsyncResource(loader, isEmpty, [loader]);

  if (resource.status === 'loading' || resource.status === 'partial-loading') {
    return <StatePanel state={resource.status} />;
  }
  if (resource.status === 'empty') {
    return (
      <StatePanel
        state="empty"
        title={emptyTitle}
        description={emptyDescription}
      />
    );
  }
  if ('error' in resource) {
    return <Wave7ErrorState error={resource.error} onRetry={reload} />;
  }
  if (resource.status === 'ready') {
    return children(resource.data);
  }
  return null;
}

export function ProjectionMetadata({ metadata }: { metadata: Wave7ProjectionMetadata }) {
  return (
    <dl className="wave7-context" aria-label="数据概览">
      <div>
        <dt>范围</dt>
        <dd>{metadata.scope.label}</dd>
      </div>
      <div>
        <dt>期间</dt>
        <dd>{metadata.periodLabel}</dd>
      </div>
      <div>
        <dt>数据截至</dt>
        <dd><time dateTime={metadata.dataAsOf}>{formatDateTime(metadata.dataAsOf)}</time></dd>
      </div>
    </dl>
  );
}

export function FrozenHistoryNotice({ metadata }: { metadata: Wave7ProjectionMetadata }) {
  if (metadata.periodState !== 'FROZEN' && metadata.periodState !== 'CLOSED') {
    return null;
  }
  return (
    <section className="wave7-frozen" role="status" aria-labelledby="wave7-frozen-title">
      <IconSnowflake aria-hidden="true" stroke={2} />
      <div>
        <h2 id="wave7-frozen-title">
          {metadata.periodState === 'CLOSED' ? '本月已月结' : '本月数据已冻结'}
        </h2>
        <p>
          当前显示 {metadata.periodLabel} 的历史结果；如需调整，请按考勤复核流程处理。
        </p>
      </div>
    </section>
  );
}

export function isSyntheticMetadata(metadata: Wave7ProjectionMetadata): boolean {
  return metadata.projectionVersion.startsWith('DEMO-');
}

export function DashboardMetricGrid({
  metrics,
  projectionVersion,
  canDrillDown,
  onDrillDown,
}: {
  metrics: DashboardMetricProjection[];
  projectionVersion: string;
  canDrillDown: boolean;
  onDrillDown?: (reference: string, projectionVersion: string) => void;
}) {
  return (
    <dl className="wave7-metric-grid" aria-label="授权考勤指标">
      {metrics.map((metric) => (
        <div className="wave7-metric" key={metric.key}>
          <dt>{metric.label}</dt>
          <dd>
            {metric.suppressed
              ? <span className="wave7-suppressed">{metric.suppressionLabel ?? '已按小样本规则隐藏'}</span>
              : metric.displayValue}
          </dd>
          {metric.drillDownReference ? (
            <button
              className="wave7-link-button"
              type="button"
              disabled={!canDrillDown || !onDrillDown}
              aria-label={`下钻查看${metric.label}`}
              data-projection-version={projectionVersion}
              data-drill-down-reference={metric.drillDownReference}
              onClick={() => onDrillDown?.(metric.drillDownReference!, projectionVersion)}
            >
              查看明细
            </button>
          ) : null}
        </div>
      ))}
    </dl>
  );
}

export function SelfServiceNavigation({ capabilities }: { capabilities: readonly string[] }) {
  const items = [
    { path: '/me/today', label: '今日', capability: 'ATTENDANCE_SELF:READ' },
    { path: '/me/records', label: '记录', capability: 'ATTENDANCE_SELF:READ' },
    { path: '/me/leave', label: '假期', capability: 'LEAVE_SELF:READ' },
    { path: '/me/feedback', label: '反馈', capability: 'ATTENDANCE_FEEDBACK:READ' },
  ].filter((item) => capabilities.includes(item.capability));

  if (items.length === 0) return null;
  return (
    <nav className="wave7-self-navigation" aria-label="员工自助">
      {items.map((item) => (
        <NavLink
          key={item.path}
          to={item.path}
          className={({ isActive }) => isActive ? 'is-active' : undefined}
        >
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}

export function LockedActionReason({ children }: { children: ReactNode }) {
  return (
    <span className="wave7-locked-action" role="note">
      <IconLock aria-hidden="true" stroke={2} />
      {children}
    </span>
  );
}

export function formatHours(minutes: number): string {
  return `${(minutes / 60).toFixed(2)} 小时`;
}

export function formatDate(value: string): string {
  const timestamp = Date.parse(`${value.slice(0, 10)}T00:00:00+08:00`);
  if (!Number.isFinite(timestamp)) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export function formatDateTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

function Wave7ErrorState({ error, onRetry }: {
  error: ApiRequestError;
  onRetry: () => void;
}) {
  if (
    error.status === 409
    && error.code === 'ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY'
  ) {
    return (
      <StatePanel
        state="empty"
        title="今日异常考勤尚未生成"
        description="当前公司的今日考勤结果尚未生成。完成数据同步后，还需完成考勤计算并发布正式投影，再刷新查看。"
        onRetry={error.retryable ? onRetry : undefined}
      />
    );
  }
  if (
    error.status === 409
    && error.code === 'SELF_ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY'
  ) {
    return (
      <StatePanel
        state="empty"
        title="本人考勤工作台尚未生成"
        description="本人的当月考勤结果尚未生成或尚未发布。完成数据同步后，还需完成考勤计算并发布正式投影，再刷新查看。"
        onRetry={error.retryable ? onRetry : undefined}
      />
    );
  }
  if (error.status === 401) {
    return <StatePanel state="401" description="会话已失效，请重新登录。" />;
  }
  if (error.status === 403 || error.status === 404) {
    return <StatePanel state="403" description="当前账号无权访问该内容。" />;
  }
  // Correlation metadata remains on the gateway error for diagnostics, not end-user display.
  return (
    <StatePanel
      state={error.status === 0 ? 'network-error' : 'error'}
      description={error.message}
      onRetry={error.retryable ? onRetry : undefined}
    />
  );
}

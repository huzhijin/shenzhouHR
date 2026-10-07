import {
  IconAlertTriangle,
  IconCalendarStats,
  IconClockHour4,
  IconLock,
  IconShieldCheck,
} from '@tabler/icons-react';
import { DatePicker, Empty } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { defaultQueryPeriod } from '../reports/queryPeriod';
import { useCallback, useState, type ReactNode } from 'react';
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  SelfAttendanceDashboardProjection,
  SelfAttendanceDashboardTrendPoint,
  SelfAttendanceExceptionTypeDistribution,
  SelfAttendanceRecentException,
} from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  formatDate,
  formatDateTime,
  formatHours,
  Wave7AsyncBoundary,
} from './Wave7Common';
import './personalAttendanceDashboard.css';

export interface PersonalAttendanceDashboardProps {
  projection: SelfAttendanceDashboardProjection;
  windowKind?: 'DAY' | 'MONTH';
  period?: Dayjs;
  onWindowKindChange?: (windowKind: 'DAY' | 'MONTH') => void;
  onPeriodChange?: (period: Dayjs) => void;
  mode?: 'workbench' | 'attendance';
}

interface PersonalTrendPoint extends SelfAttendanceDashboardTrendPoint {
  scheduledHours: number;
  confirmedHours: number;
}

interface PersonalExceptionTypePoint {
  type: string;
  label: string;
  count: number;
}

const exceptionColors = [
  'var(--personal-dashboard-chart-1)',
  'var(--personal-dashboard-chart-2)',
  'var(--personal-dashboard-chart-3)',
  'var(--personal-dashboard-chart-4)',
  'var(--personal-dashboard-chart-5)',
  'var(--personal-dashboard-chart-6)',
] as const;

const exceptionTypeLabels: Record<string, string> = {
  ABSENCE: '缺勤',
  EARLY_LEAVE: '早退',
  EARLY_DEPARTURE: '早退',
  EVIDENCE_CONFLICT: '凭证冲突',
  LATE: '迟到',
  MISSING_ON_DUTY: '上班漏签',
  MISSING_OFF_DUTY: '下班漏签',
  MISSING_PUNCH: '缺卡',
  MISSING_PUNCH_OVERDUE: '缺卡逾期',
  FAKE_OVERTIME: '加班异常',
  OVERTIME_FORM_BEYOND_LAST_PUNCH: '加班结束晚于打卡',
  OVERTIME_UNCONFIRMED: '加班待确认',
  NEGATIVE_LEAVE_BALANCE: '假期余额为负',
  NEGATIVE_ANNUAL_LEAVE_BALANCE: '年假余额为负',
  NEGATIVE_TIME_OFF_BALANCE: '调休余额为负',
};

const exceptionStateLabels: Record<
  SelfAttendanceRecentException['state'],
  string
> = {
  OPEN: '待处理',
  PENDING_EVIDENCE: '待补充凭证',
  PENDING_REVIEW: '待复核',
};

const severityLabels: Record<
  SelfAttendanceRecentException['severity'],
  string
> = {
  ERROR: '异常',
  INFO: '提示',
  WARNING: '警告',
};

export function PersonalAttendanceDashboardRoute({
  gateway = wave7ProjectionGateway,
}: {
  gateway?: Wave7ProjectionGateway;
}) {
  const [windowKind, setWindowKind] = useState<'DAY' | 'MONTH'>('MONTH');
  const [period, setPeriod] = useState<Dayjs>(() => defaultQueryPeriod());
  const load = useCallback(
    () => gateway.loadSelfDashboard(period.format('YYYY-MM'), windowKind),
    [gateway, period, windowKind],
  );
  return (
    <Wave7AsyncBoundary
      key={`${windowKind}-${period.format('YYYY-MM')}`}
      loader={load}
      isEmpty={() => false}
    >
      {(projection) => (
        <PersonalAttendanceDashboard
          projection={projection}
          windowKind={windowKind}
          period={period}
          onWindowKindChange={setWindowKind}
          onPeriodChange={setPeriod}
          mode="workbench"
        />
      )}
    </Wave7AsyncBoundary>
  );
}

export function PersonalAttendanceDashboard({
  projection,
  period,
  onWindowKindChange,
  onPeriodChange,
  mode = 'attendance',
}: PersonalAttendanceDashboardProps) {
  if (!isSelfProjection(projection)) {
    return (
      <StatePanel
        state="403"
        title="无法显示个人工作台"
        description="个人工作台只接受当前登录账号的本人范围数据。"
      />
    );
  }

  const trend = projection.dailyTrend.map(toTrendPoint);
  const exceptionTypes = projection.exceptionTypeDistribution.map(
    toExceptionTypePoint,
  );
  const listedExceptions = mode === 'workbench'
    ? visibleSelfExceptions(projection)
    : projection.recentExceptions;

  return (
    <main
      className="personal-attendance-dashboard"
      aria-labelledby="personal-attendance-dashboard-title"
    >
      <header className="personal-attendance-dashboard__hero">
        <div>
          <p className="personal-attendance-dashboard__eyebrow">
            <IconLock aria-hidden="true" />
            个人专属 · 仅本人可见
          </p>
          <h1 id="personal-attendance-dashboard-title">
            {mode === 'workbench' ? '我的异常' : '我的考勤工作台'}
          </h1>
          <p className="personal-attendance-dashboard__hero-copy">
            {mode === 'workbench'
              ? '主看昨天完整异常（迟到、早退、漏签）。中午 12 点后才会列出今天的迟到和早上漏签。'
              : '查看本人每天的上下班打卡、工时和异常，不包含组织或其他员工数据。'}
          </p>
          {mode === 'attendance' && onWindowKindChange ? (
            <div style={{ display: 'flex', gap: 12, marginTop: 12, alignItems: 'center' }}>
              <DatePicker
                picker="month"
                value={period}
                allowClear={false}
                onChange={(value) => {
                  if (value) {
                    onWindowKindChange('MONTH');
                    onPeriodChange?.(value);
                  }
                }}
              />
            </div>
          ) : null}
        </div>
        <dl className="personal-attendance-dashboard__context">
          <div>
            <dt>数据范围</dt>
            <dd>{projection.metadata.scope.label}</dd>
          </div>
          <div>
            <dt>统计期间</dt>
            <dd>{projection.metadata.periodLabel}</dd>
          </div>
          <div>
            <dt>数据截至</dt>
            <dd>
              <time dateTime={projection.metadata.dataAsOf}>
                {formatDateTime(projection.metadata.dataAsOf)}
              </time>
            </dd>
          </div>
        </dl>
      </header>

      <PersonalPeriodNotice projection={projection} />

      <dl
        className="personal-attendance-dashboard__summary"
        aria-label="本人考勤关键指标"
      >
        <PersonalMetric
          icon={<IconCalendarStats aria-hidden="true" />}
          label="应出勤"
          tone="info"
          value={formatHours(projection.summary.scheduledMinutes)}
          note={`${projection.metadata.periodLabel} 本人结果`}
        />
        <PersonalMetric
          icon={<IconClockHour4 aria-hidden="true" />}
          label="确认工时"
          tone="success"
          value={formatHours(projection.summary.confirmedMinutes)}
          note="本月已确认考勤工时"
        />
        <PersonalMetric
          icon={<IconShieldCheck aria-hidden="true" />}
          label="认可加班"
          tone="info"
          value={formatHours(
            projection.summary.recognizedOvertimeMinutes,
          )}
          note="仅展示已认可时长"
        />
        <PersonalMetric
          icon={<IconCalendarStats aria-hidden="true" />}
          label="请假"
          tone="warning"
          value={formatHours(projection.summary.leaveMinutes)}
          note="本月已确认请假时长"
        />
        <PersonalMetric
          icon={<IconAlertTriangle aria-hidden="true" />}
          label="待处理异常"
          tone={projection.summary.unresolvedExceptionCount > 0
            ? 'warning'
            : 'success'}
          value={`${projection.summary.unresolvedExceptionCount} 条`}
          note="仅统计本人的未解决异常"
        />
      </dl>

      <section
        className="personal-attendance-dashboard__primary-grid"
        aria-label="本人考勤趋势与今日状态"
      >
        <PersonalPanel
          className="personal-attendance-dashboard__trend-panel"
          title="每日考勤趋势"
          subtitle={`${projection.metadata.periodLabel} · 应出勤与确认工时`}
          meta={`${trend.length} 天`}
        >
          <PersonalTrendChart data={trend} />
        </PersonalPanel>

        <PersonalPanel
          title="今日考勤"
          subtitle={formatDate(projection.businessDate)}
          meta={projection.metadata.periodState === 'OPEN'
            ? '当日暂算'
            : '已固化'}
        >
          <PersonalToday projection={projection} />
        </PersonalPanel>
      </section>

      <section
        className="personal-attendance-dashboard__secondary-grid"
        aria-label="本人异常分布与最近异常"
      >
        <PersonalPanel
          title="我的异常类型"
          subtitle="本人未解决异常按类型分组"
          meta={`${projection.summary.unresolvedExceptionCount} 条`}
        >
          <PersonalExceptionTypeChart data={exceptionTypes} />
        </PersonalPanel>

        <PersonalPanel
          className="personal-attendance-dashboard__attention-panel"
          title={mode === 'workbench' ? '昨日异常' : '我的最近异常'}
          subtitle={mode === 'workbench'
            ? '只显示本人昨天的异常；中午后附加今天迟到和早上漏签'
            : '仅显示与你相关的异常摘要'}
          meta={`${listedExceptions.length} 条`}
        >
          <PersonalExceptionList
            exceptions={listedExceptions}
          />
        </PersonalPanel>
      </section>
        {mode === 'attendance' ? (
          <section
            className="personal-attendance-dashboard__secondary-grid"
            aria-label="本人每日打卡"
          >
            <PersonalPanel
              title="每日打卡"
              subtitle="与考勤明细相同的上班、下班时间"
              meta={`${projection.dailyTrend.length} 天`}
            >
              <PersonalDailyPunchTable projection={projection} />
            </PersonalPanel>
          </section>
        ) : null}
    </main>
  );
}

function PersonalMetric({
  icon,
  label,
  value,
  note,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  note: string;
  tone: 'success' | 'warning' | 'info';
}) {
  return (
    <div
      className={`personal-attendance-dashboard__metric personal-attendance-dashboard__metric--${tone}`}
    >
      <dt>
        <span>{label}</span>
        {icon}
      </dt>
      <dd className="personal-attendance-dashboard__metric-value">
        {value}
      </dd>
      <dd className="personal-attendance-dashboard__metric-note">
        {note}
      </dd>
    </div>
  );
}

function PersonalPanel({
  title,
  subtitle,
  meta,
  className,
  children,
}: {
  title: string;
  subtitle: string;
  meta: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <article
      className={`personal-attendance-dashboard__panel${className ? ` ${className}` : ''}`}
    >
      <header className="personal-attendance-dashboard__panel-head">
        <div>
          <h2>{title}</h2>
          <p>{subtitle}</p>
        </div>
        <span>{meta}</span>
      </header>
      <div className="personal-attendance-dashboard__panel-body">
        {children}
      </div>
    </article>
  );
}

function PersonalPeriodNotice({
  projection,
}: {
  projection: SelfAttendanceDashboardProjection;
}) {
  if (
    projection.metadata.periodState !== 'FROZEN'
    && projection.metadata.periodState !== 'CLOSED'
  ) {
    return null;
  }
  return (
    <section
      className="personal-attendance-dashboard__period-notice"
      role="status"
    >
      <IconShieldCheck aria-hidden="true" />
      <div>
        <strong>
          {projection.metadata.periodState === 'CLOSED'
            ? '本月已月结'
            : '本月数据已冻结'}
        </strong>
        <p>
          当前展示 {projection.metadata.periodLabel} 的本人历史结果，数据不会被后续变化静默覆盖。
        </p>
      </div>
    </section>
  );
}

function PersonalTrendChart({ data }: { data: PersonalTrendPoint[] }) {
  if (data.length === 0) {
    return (
      <PersonalEmpty description="本期暂无本人考勤趋势" />
    );
  }
  return (
    <>
      <div
        className="personal-attendance-dashboard__chart"
        aria-hidden="true"
      >
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart
            data={data}
            margin={{ top: 10, right: 18, bottom: 4, left: -8 }}
          >
            <CartesianGrid
              stroke="var(--personal-dashboard-divider)"
              vertical={false}
            />
            <XAxis
              axisLine={false}
              dataKey="businessDate"
              tick={{
                fill: 'var(--personal-dashboard-muted)',
                fontSize: 11,
              }}
              tickFormatter={shortDate}
              tickLine={false}
            />
            <YAxis
              allowDecimals
              axisLine={false}
              tick={{
                fill: 'var(--personal-dashboard-muted)',
                fontSize: 11,
              }}
              tickLine={false}
              unit="h"
              width={42}
            />
            <Tooltip
              formatter={(value, name) => [
                `${Number(value).toFixed(2)} 小时`,
                String(name),
              ]}
              labelFormatter={(label) => formatDate(String(label))}
              contentStyle={{
                border: '1px solid var(--personal-dashboard-border)',
                borderRadius: 6,
              }}
            />
            <Area
              dataKey="confirmedHours"
              fill="var(--color-info-soft)"
              isAnimationActive={false}
              name="确认工时"
              stroke="var(--personal-dashboard-accent)"
              strokeWidth={3}
              type="monotone"
            />
            <Line
              dataKey="scheduledHours"
              dot={false}
              isAnimationActive={false}
              name="应出勤"
              stroke="var(--personal-dashboard-success)"
              strokeDasharray="5 4"
              strokeWidth={2}
              type="monotone"
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <table className="sr-only">
        <caption>本人每日考勤趋势明细</caption>
        <thead>
          <tr>
            <th scope="col">日期</th>
            <th scope="col">应出勤</th>
            <th scope="col">确认工时</th>
            <th scope="col">认可加班</th>
            <th scope="col">请假</th>
            <th scope="col">异常数</th>
          </tr>
        </thead>
        <tbody>
          {data.map((point) => (
            <tr key={point.businessDate}>
              <td>{point.businessDate}</td>
              <td>{point.scheduledHours.toFixed(2)} 小时</td>
              <td>{point.confirmedHours.toFixed(2)} 小时</td>
              <td>{formatHours(point.recognizedOvertimeMinutes)}</td>
              <td>{formatHours(point.leaveMinutes)}</td>
              <td>{point.issueCount} 条</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function PersonalToday({
  projection,
}: {
  projection: SelfAttendanceDashboardProjection;
}) {
  const today = projection.today;
  if (today === null) {
    return (
      <PersonalEmpty description="今天暂无排班或考勤结果" />
    );
  }
  return (
    <>
      <dl className="personal-attendance-dashboard__today-grid">
        <PersonalDetail label="班次" value={today.shiftLabel ?? '未排班'} />
        <PersonalDetail
          label="打卡时间"
          value={formatPunchRange(today.firstPunchAt, today.lastPunchAt)}
        />
        <PersonalDetail
          label="确认工时"
          value={formatHours(today.confirmedMinutes)}
        />
        <PersonalDetail
          label="状态"
          value={<StatusBadge status={today.statusLabel} />}
        />
      </dl>
      <div className="personal-attendance-dashboard__today-notice">
        <strong>今日提示</strong>
        <p>
          {today.issueLabels.map(exceptionTypeLabel).join('、')
            || '当前没有异常提示'}
        </p>
      </div>
    </>
  );
}

function PersonalExceptionTypeChart({
  data,
}: {
  data: PersonalExceptionTypePoint[];
}) {
  if (data.length === 0) {
    return (
      <PersonalEmpty description="当前没有未解决异常" />
    );
  }
  return (
    <>
      <div
        className="personal-attendance-dashboard__chart personal-attendance-dashboard__chart--compact"
        aria-hidden="true"
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart
            data={data}
            layout="vertical"
            margin={{ top: 4, right: 22, bottom: 4, left: 8 }}
          >
            <CartesianGrid
              horizontal={false}
              stroke="var(--personal-dashboard-divider)"
            />
            <XAxis
              allowDecimals={false}
              axisLine={false}
              tick={{
                fill: 'var(--personal-dashboard-muted)',
                fontSize: 11,
              }}
              tickLine={false}
              type="number"
            />
            <YAxis
              axisLine={false}
              dataKey="label"
              tick={{
                fill: 'var(--color-text-primary)',
                fontSize: 11,
              }}
              tickLine={false}
              type="category"
              width={88}
            />
            <Tooltip
              formatter={(value) => [`${String(value)} 条`, '异常数']}
              contentStyle={{
                border: '1px solid var(--personal-dashboard-border)',
                borderRadius: 6,
              }}
            />
            <Bar
              dataKey="count"
              fill="var(--personal-dashboard-accent)"
              isAnimationActive={false}
              maxBarSize={20}
              name="异常数"
              radius={[0, 3, 3, 0]}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ul
        className="personal-attendance-dashboard__status-list"
        aria-label="本人未解决异常类型分布"
      >
        {data.map((item, index) => (
          <li key={item.type}>
            <i
              aria-hidden="true"
              style={{
                background: exceptionColors[
                  index % exceptionColors.length
                ],
              }}
            />
            <span>{item.label}</span>
            <strong>{item.count} 条</strong>
          </li>
        ))}
      </ul>
    </>
  );
}

function PersonalExceptionList({
  exceptions,
}: {
  exceptions: SelfAttendanceRecentException[];
}) {
  if (exceptions.length === 0) {
    return (
      <PersonalEmpty description="当前没有需要处理的本人异常" />
    );
  }
  return (
    <ol className="personal-attendance-dashboard__attention-list">
      {exceptions.map((exception, index) => (
        <li
          key={`${exception.businessDate}-${exception.type}-${index}`}
        >
          <div>
            <span className="personal-attendance-dashboard__exception-heading">
              <time dateTime={exception.businessDate}>
                {formatDate(exception.businessDate)}
              </time>
              <strong>{exceptionTypeLabel(exception.type)}</strong>
            </span>
            <span className="personal-attendance-dashboard__exception-badges">
              <span
                className={`personal-attendance-dashboard__severity personal-attendance-dashboard__severity--${exception.severity.toLowerCase()}`}
              >
                {severityLabels[exception.severity]}
              </span>
              <StatusBadge
                status={exceptionStateLabels[exception.state]}
              />
            </span>
          </div>
          <p>{exception.safeEvidenceSummary}</p>
          <small>
            {exception.minutes > 0
              ? `影响 ${exception.minutes} 分钟`
              : '未统计影响分钟'}
          </small>
        </li>
      ))}
    </ol>
  );
}

function PersonalDetail({
  label,
  value,
}: {
  label: string;
  value: ReactNode;
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function PersonalEmpty({ description }: { description: string }) {
  return (
    <div className="personal-attendance-dashboard__empty">
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={description}
      />
    </div>
  );
}

function isSelfProjection(
  projection: SelfAttendanceDashboardProjection,
): boolean {
  const runtimeProjection = projection as {
    kind?: unknown;
    metadata?: {
      scope?: {
        type?: unknown;
        reference?: unknown;
        label?: unknown;
      };
    };
  };
  return runtimeProjection.kind === 'SELF_ATTENDANCE_DASHBOARD'
    && runtimeProjection.metadata?.scope?.type === 'SELF'
    && runtimeProjection.metadata.scope.reference === 'current-principal'
    && runtimeProjection.metadata.scope.label === '本人';
}

function toTrendPoint(
  point: SelfAttendanceDashboardTrendPoint,
): PersonalTrendPoint {
  return {
    ...point,
    scheduledHours: point.scheduledMinutes / 60,
    confirmedHours: point.confirmedMinutes / 60,
  };
}

function toExceptionTypePoint(
  point: SelfAttendanceExceptionTypeDistribution,
): PersonalExceptionTypePoint {
  return {
    ...point,
    label: exceptionTypeLabel(point.type),
  };
}

function visibleSelfExceptions(
  projection: SelfAttendanceDashboardProjection,
): SelfAttendanceRecentException[] {
  const today = shanghaiDate(new Date());
  const yesterday = shiftShanghaiDate(today, -1);
  const afterNoon = shanghaiHour(new Date()) >= 12;
  const preferred = projection.recentExceptions.filter((item) => {
    if (item.businessDate === yesterday) {
      return true;
    }
    return afterNoon
      && item.businessDate === today
      && (item.type === 'LATE'
        || item.type === 'MISSING_ON_DUTY'
        || item.type === 'MISSING_PUNCH'
        || item.type === 'MISSING_PUNCH_OVERDUE');
  });
  return preferred.length > 0 ? preferred : projection.recentExceptions;
}

function PersonalDailyPunchTable({
  projection,
}: {
  projection: SelfAttendanceDashboardProjection;
}) {
  if (projection.dailyTrend.length === 0) {
    return <PersonalEmpty description="本月还没有日考勤事实" />;
  }
  return (
    <table className="personal-attendance-dashboard__punch-table">
      <thead>
        <tr>
          <th>日期</th>
          <th>上班</th>
          <th>下班</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        {projection.dailyTrend.map((point) => {
          const isToday = point.businessDate === projection.businessDate;
          const first = isToday
            ? (projection.today?.firstPunchAt ?? point.firstPunchAt ?? null)
            : (point.firstPunchAt ?? null);
          const last = isToday
            ? (projection.today?.lastPunchAt ?? point.lastPunchAt ?? null)
            : (point.lastPunchAt ?? null);
          return (
            <tr key={point.businessDate}>
              <td>{formatDate(point.businessDate)}</td>
              <td>{formatPunchTime(first)}</td>
              <td>{formatPunchTime(last)}</td>
              <td>{point.issueCount > 0 ? `异常 ${point.issueCount} 项` : '正常'}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function shanghaiDate(value: Date): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(value);
}

function shanghaiHour(value: Date): number {
  return Number(new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    hour12: false,
  }).format(value));
}

function shiftShanghaiDate(isoDate: string, days: number): string {
  const parts = isoDate.split('-').map(Number);
  const year = parts[0] ?? 0;
  const month = parts[1] ?? 1;
  const day = parts[2] ?? 1;
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}

function exceptionTypeLabel(type: string): string {
  return exceptionTypeLabels[type] ?? '其他异常';
}

function formatPunchRange(
  firstPunchAt: string | null,
  lastPunchAt: string | null,
): string {
  if (firstPunchAt === null && lastPunchAt === null) return '暂无';
  return `${formatPunchTime(firstPunchAt)} – ${formatPunchTime(lastPunchAt)}`;
}

function formatPunchTime(value: string | null | undefined): string {
  if (value == null) return '—';
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

function shortDate(value: string): string {
  return value.slice(5);
}

export default PersonalAttendanceDashboard;

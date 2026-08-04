import {
  IconArrowRight,
  IconChartBar,
  IconPresentationAnalytics,
  IconRadar,
  IconZoomIn,
} from '@tabler/icons-react';
import { Button, Empty } from 'antd';
import {
  useCallback,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  Line,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import {
  DetailDrawer,
  ReadOnlyDetails,
} from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  DashboardAnomalyProjection,
  DashboardCompanyOption,
  DashboardCompanySelectionProjection,
  DashboardDailyTrendProjection,
  DashboardProjection,
  DashboardSeverityDistributionProjection,
  LiveDashboardProjection,
} from './wave7Contracts';
import { hasLiveDashboardProjection } from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  DashboardMetricGrid,
  formatDateTime,
  FrozenHistoryNotice,
  isSyntheticMetadata,
  ProjectionMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';
import './dashboardWorkbench.css';

interface DashboardReportContext {
  reportType: 'EXCEPTIONS';
  period: string;
  companyId: string;
}

type DashboardFilter =
  | { kind: 'severity'; value: DashboardAnomalyProjection['severity']; label: string }
  | { kind: 'exceptionType'; value: string; label: string }
  | { kind: 'organization'; value: string; label: string };

const severityColors: Record<
  DashboardAnomalyProjection['severity'],
  string
> = {
  INFO: 'var(--dashboard-info)',
  WARNING: 'var(--dashboard-warning)',
  ERROR: 'var(--dashboard-danger)',
};

const organizationColors = [
  'var(--dashboard-chart-1)',
  'var(--dashboard-chart-2)',
  'var(--dashboard-chart-3)',
  'var(--dashboard-chart-4)',
  'var(--dashboard-chart-5)',
];

export function DashboardRoute({
  gateway = wave7ProjectionGateway,
}: {
  gateway?: Wave7ProjectionGateway;
}) {
  const navigate = useNavigate();
  const [companyId, setCompanyId] = useState('');
  const loadDashboard = useCallback(
    () => gateway.loadDashboard(companyId || undefined),
    [companyId, gateway],
  );

  return (
    <Wave7AsyncBoundary
      loader={loadDashboard}
      isEmpty={() => false}
    >
      {(result) => result.kind === 'DASHBOARD_COMPANY_SELECTION'
        ? (
            <DashboardCompanySelectionView
              projection={result}
              onCompanyChange={setCompanyId}
            />
          )
        : (
            <DashboardView
              projection={result}
              onCompanyChange={setCompanyId}
              onDrillDown={() => navigate('/attendance/reports')}
              onOpenScreen={isSyntheticMetadata(result.metadata)
                ? () => navigate('/attendance/screen')
                : undefined}
              onOpenReports={(context) => {
                if (!context) {
                  navigate('/attendance/reports');
                  return;
                }
                const query = new URLSearchParams({
                  reportType: context.reportType,
                  period: context.period,
                  companyId: context.companyId,
                });
                navigate(`/attendance/reports?${query.toString()}`);
              }}
            />
          )}
    </Wave7AsyncBoundary>
  );
}

export function DashboardView({
  projection,
  onCompanyChange,
  onDrillDown,
  onOpenScreen,
  onOpenReports,
}: {
  projection: DashboardProjection;
  onCompanyChange?: (companyId: string) => void;
  onDrillDown?: (reference: string, projectionVersion: string) => void;
  onOpenScreen?: () => void;
  onOpenReports?: (context?: DashboardReportContext) => void;
}) {
  const [activeFilter, setActiveFilter] = useState<DashboardFilter>();
  const [selectedException, setSelectedException] =
    useState<DashboardAnomalyProjection>();
  const liveProjection = hasLiveDashboardProjection(projection)
    ? projection
    : undefined;
  const canOpenReports = projection.metadata.allowedActions
    .includes('DASHBOARD_DRILL_DOWN');
  const reportContext = liveProjection
    ? {
        reportType: 'EXCEPTIONS' as const,
        period: liveProjection.businessDate.slice(0, 7),
        companyId: liveProjection.selectedCompanyId,
      }
    : undefined;

  return (
    <main className="attendance-dashboard">
      <DashboardHero
        projection={projection}
        liveProjection={liveProjection}
        canOpenReports={canOpenReports}
        onCompanyChange={onCompanyChange}
        onOpenScreen={onOpenScreen}
        onOpenReports={onOpenReports}
        reportContext={reportContext}
      />
      {liveProjection ? null : (
        <ProjectionMetadata metadata={projection.metadata} />
      )}
      <FrozenHistoryNotice metadata={projection.metadata} />
      {liveProjection ? (
        <>
          <DashboardAnomalySummary projection={liveProjection} />
          <DashboardVisualizations
            projection={liveProjection}
            activeFilter={activeFilter}
            onFilter={setActiveFilter}
          />
          <DashboardAnomalyList
            businessDate={liveProjection.businessDate}
            exceptions={liveProjection.exceptions}
            activeFilter={activeFilter}
            onClearFilter={() => setActiveFilter(undefined)}
            onSelectException={setSelectedException}
            onOpenReports={canOpenReports && onOpenReports
              ? () => onOpenReports(reportContext)
              : undefined}
          />
        </>
      ) : null}
      {projection.metrics.length > 0 ? (
        <section
          className="attendance-dashboard__panel attendance-dashboard__legacy"
          aria-labelledby="wave7-dashboard-heading"
        >
          <header className="attendance-dashboard__panel-head">
            <div>
              <h2 id="wave7-dashboard-heading">授权汇总</h2>
              <p>查看当前账号有权访问的考勤汇总。</p>
            </div>
          </header>
          <DashboardMetricGrid
            metrics={projection.metrics}
            projectionVersion={projection.metadata.projectionVersion}
            canDrillDown={canOpenReports}
            onDrillDown={onDrillDown}
          />
        </section>
      ) : null}
      <DashboardExceptionDrawer
        exception={selectedException}
        projection={projection}
        onClose={() => setSelectedException(undefined)}
        onOpenReports={canOpenReports && onOpenReports
          ? () => onOpenReports(reportContext)
          : undefined}
      />
    </main>
  );
}

function DashboardHero({
  projection,
  liveProjection,
  canOpenReports,
  onCompanyChange,
  onOpenScreen,
  onOpenReports,
  reportContext,
}: {
  projection: DashboardProjection;
  liveProjection?: LiveDashboardProjection;
  canOpenReports: boolean;
  onCompanyChange?: (companyId: string) => void;
  onOpenScreen?: () => void;
  onOpenReports?: (context?: DashboardReportContext) => void;
  reportContext?: DashboardReportContext;
}) {
  return (
    <header className="attendance-dashboard__hero">
      <div>
        <p className="attendance-dashboard__eyebrow">
          <IconRadar aria-hidden="true" />
          ATTENDANCE OPERATIONS · 考勤运行指挥台
        </p>
        <h1>{projection.title}</h1>
        <p className="attendance-dashboard__hero-copy">
          {liveProjection
            ? `${liveProjection.businessDate} · 聚焦今日异常、近 7 日趋势与组织风险，所有数据均受当前账号授权范围约束。`
            : '查看当前账号有权访问的考勤指标。'}
        </p>
        <div className="attendance-dashboard__hero-status">
          <span>
            <i className="attendance-dashboard__live-dot" aria-hidden="true" />
            数据已更新
          </span>
          <span>
            数据截至 {formatDateTime(projection.metadata.dataAsOf)}
          </span>
          <span>{projection.metadata.scope.label}</span>
        </div>
      </div>
      <div className="attendance-dashboard__hero-controls">
        {liveProjection && liveProjection.companies.length > 1 ? (
          <DashboardCompanySelector
            companies={liveProjection.companies}
            selectedCompanyId={liveProjection.selectedCompanyId}
            onCompanyChange={onCompanyChange}
          />
        ) : null}
        <div className="attendance-dashboard__hero-actions">
          {onOpenScreen ? (
            <Button
              icon={<IconPresentationAnalytics aria-hidden="true" />}
              onClick={onOpenScreen}
            >
              打开考勤大屏
            </Button>
          ) : null}
          {onOpenReports && canOpenReports ? (
            <Button
              type="primary"
              icon={<IconChartBar aria-hidden="true" />}
              onClick={() => onOpenReports(reportContext)}
            >
              查看异常报表
            </Button>
          ) : null}
        </div>
      </div>
    </header>
  );
}

function DashboardCompanySelectionView({
  projection,
  onCompanyChange,
}: {
  projection: DashboardCompanySelectionProjection;
  onCompanyChange: (companyId: string) => void;
}) {
  return (
    <>
      <PageHeader
        title={projection.title}
        description={`${projection.businessDate} · ${projection.message}`}
      />
      <section
        className="content-surface wave7-report-filters"
        aria-labelledby="wave7-dashboard-company-heading"
      >
        <h2 id="wave7-dashboard-company-heading">公司范围</h2>
        <DashboardCompanySelector
          companies={projection.companies}
          selectedCompanyId=""
          onCompanyChange={onCompanyChange}
        />
        <p>选择公司后查看对应范围的考勤数据。</p>
      </section>
    </>
  );
}

function DashboardCompanySelector({
  companies,
  selectedCompanyId,
  onCompanyChange,
}: {
  companies: DashboardCompanyOption[];
  selectedCompanyId: string;
  onCompanyChange?: (companyId: string) => void;
}) {
  return (
    <label className="attendance-dashboard__company-field">
      <span>公司范围</span>
      <select
        aria-label="控制台公司"
        value={selectedCompanyId}
        onChange={(event) => onCompanyChange?.(event.target.value)}
      >
        {selectedCompanyId === '' ? (
          <option value="">请选择公司</option>
        ) : null}
        {companies.map((company) => (
          <option key={company.companyId} value={company.companyId}>
            {company.companyName}
          </option>
        ))}
      </select>
    </label>
  );
}

function DashboardAnomalySummary({
  projection,
}: {
  projection: LiveDashboardProjection;
}) {
  const cards = [
    {
      label: '未处理异常',
      value: projection.summary.unresolvedCount,
      suffix: '条',
      note: `${projection.businessDate} 当前待处理`,
      tone: 'warning',
    },
    {
      label: '影响员工',
      value: projection.summary.affectedEmployeeCount,
      suffix: '人',
      note: '今日按员工去重',
      tone: 'success',
    },
    {
      label: '阻断异常',
      value: projection.summary.blockingCount,
      suffix: '条',
      note: '错误级别，建议优先处理',
      tone: 'danger',
    },
  ] as const;
  return (
    <dl
      className="attendance-dashboard__summary"
      aria-label="今日异常汇总指标"
    >
      {cards.map((card) => (
        <div
          className={`attendance-dashboard__metric attendance-dashboard__metric--${card.tone}`}
          key={card.label}
        >
          <dt>{card.label}</dt>
          <dd>
            {card.value}
            <small>{card.suffix}</small>
          </dd>
          <p>{card.note}</p>
        </div>
      ))}
    </dl>
  );
}

function DashboardVisualizations({
  projection,
  activeFilter,
  onFilter,
}: {
  projection: LiveDashboardProjection;
  activeFilter?: DashboardFilter;
  onFilter: (filter?: DashboardFilter) => void;
}) {
  const typeData = projection.analytics.typeDistribution.map((item) => ({
    ...item,
    label: exceptionTypeLabel(item.exceptionType),
  }));
  const organizationData = projection.analytics.organizationRanking.map(
    (item) => ({
      ...item,
      label: item.organizationName,
    }),
  );
  const trendMaximum = Math.max(
    1,
    ...projection.analytics.dailyTrend.map((item) => item.exceptionCount),
  );

  return (
    <>
      <section
        className="attendance-dashboard__visual-grid"
        aria-label="异常趋势与严重程度"
      >
        <DashboardPanel
          title="近 7 日异常趋势"
          subtitle="查看异常总数、影响员工和需要优先处理的异常变化"
          meta={`峰值 ${trendMaximum} 条`}
        >
          <DashboardTrendChart data={projection.analytics.dailyTrend} />
        </DashboardPanel>
        <DashboardPanel
          title="严重程度分布"
          subtitle="点击下方级别筛选今日优先异常"
          meta={`${projection.summary.unresolvedCount} 条待处理`}
        >
          <DashboardSeverityChart
            data={projection.analytics.severityDistribution}
          />
          <div className="attendance-dashboard__legend">
            {projection.analytics.severityDistribution.map((item) => {
              const label = severityLabel(item.severity);
              const selected = activeFilter?.kind === 'severity'
                && activeFilter.value === item.severity;
              return (
                <button
                  className={`attendance-dashboard__filter-button${selected ? ' is-active' : ''}`}
                  type="button"
                  aria-pressed={selected}
                  aria-label={`筛选${label}级别异常 ${item.count} 条`}
                  key={item.severity}
                  onClick={() => onFilter(selected
                    ? undefined
                    : {
                        kind: 'severity',
                        value: item.severity,
                        label: `${label}级别`,
                      })}
                >
                  <i
                    className="attendance-dashboard__legend-swatch"
                    style={{
                      '--legend-color': severityColors[item.severity],
                    } as CSSProperties}
                    aria-hidden="true"
                  />
                  {label}
                  <strong>{item.count}</strong>
                </button>
              );
            })}
          </div>
        </DashboardPanel>
      </section>
      <section
        className="attendance-dashboard__structure-grid"
        aria-label="异常类型与组织排行"
      >
        <DashboardPanel
          title="异常类型分布"
          subtitle="按待处理异常数量排序，最多展示 10 类"
          meta="今日"
        >
          <DashboardTypeChart data={typeData} />
          <div className="attendance-dashboard__legend">
            {typeData.slice(0, 6).map((item) => {
              const selected = activeFilter?.kind === 'exceptionType'
                && activeFilter.value === item.exceptionType;
              return (
                <button
                  className={`attendance-dashboard__filter-button${selected ? ' is-active' : ''}`}
                  type="button"
                  aria-pressed={selected}
                  aria-label={`筛选${item.label}异常 ${item.count} 条`}
                  key={item.exceptionType}
                  onClick={() => onFilter(selected
                    ? undefined
                    : {
                        kind: 'exceptionType',
                        value: item.exceptionType,
                        label: item.label,
                      })}
                >
                  {item.label}
                  <strong>{item.count}</strong>
                </button>
              );
            })}
          </div>
        </DashboardPanel>
        <DashboardPanel
          title="组织异常排行"
          subtitle="按异常总数排序，并标记其中阻断数量"
          meta="Top 5"
        >
          <DashboardOrganizationChart data={organizationData} />
          <div className="attendance-dashboard__legend">
            {organizationData.map((item) => {
              const selected = activeFilter?.kind === 'organization'
                && activeFilter.value === item.organizationName;
              return (
                <button
                  className={`attendance-dashboard__filter-button${selected ? ' is-active' : ''}`}
                  type="button"
                  aria-pressed={selected}
                  aria-label={`筛选${item.organizationName}异常 ${item.exceptionCount} 条`}
                  key={item.organizationName}
                  onClick={() => onFilter(selected
                    ? undefined
                    : {
                        kind: 'organization',
                        value: item.organizationName,
                        label: item.organizationName,
                      })}
                >
                  {item.organizationName}
                  <strong>{item.exceptionCount}</strong>
                </button>
              );
            })}
          </div>
        </DashboardPanel>
      </section>
    </>
  );
}

function DashboardTrendChart({
  data,
}: {
  data: DashboardDailyTrendProjection[];
}) {
  return (
    <div
      className="attendance-dashboard__chart"
      role="img"
      aria-label={trendAriaLabel(data)}
    >
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart
          data={data}
          margin={{ top: 8, right: 14, bottom: 4, left: -12 }}
        >
          <CartesianGrid stroke="var(--dashboard-divider)" vertical={false} />
          <XAxis
            axisLine={false}
            dataKey="businessDate"
            tickFormatter={shortDate}
            tick={{ fill: 'var(--dashboard-muted)', fontSize: 11 }}
            tickLine={false}
          />
          <YAxis
            allowDecimals={false}
            axisLine={false}
            domain={[0, 'dataMax + 1']}
            tick={{ fill: 'var(--dashboard-muted)', fontSize: 11 }}
            tickLine={false}
            width={36}
          />
          <Tooltip
            labelFormatter={(label) => `${String(label)}（上海时区）`}
            contentStyle={{
              border: '1px solid var(--dashboard-border)',
              borderRadius: 6,
              boxShadow: 'var(--dashboard-tooltip-shadow)',
            }}
          />
          <Area
            dataKey="exceptionCount"
            fill="var(--dashboard-info-soft)"
            isAnimationActive={false}
            name="异常条数"
            stroke="var(--dashboard-accent)"
            strokeWidth={3}
            type="monotone"
          />
          <Line
            dataKey="affectedEmployeeCount"
            dot={{
              fill: 'var(--dashboard-surface)',
              r: 3,
              stroke: 'var(--dashboard-success)',
              strokeWidth: 2,
            }}
            isAnimationActive={false}
            name="影响员工"
            stroke="var(--dashboard-success)"
            strokeWidth={2}
            type="monotone"
          />
          <Line
            dataKey="blockingCount"
            dot={{
              fill: 'var(--dashboard-surface)',
              r: 3,
              stroke: 'var(--dashboard-danger)',
              strokeWidth: 2,
            }}
            isAnimationActive={false}
            name="阻断异常"
            stroke="var(--dashboard-danger)"
            strokeDasharray="4 4"
            strokeWidth={2}
            type="monotone"
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

function DashboardSeverityChart({
  data,
}: {
  data: DashboardSeverityDistributionProjection[];
}) {
  const visibleData = data.filter((item) => item.count > 0);
  if (visibleData.length === 0) {
    return (
      <div className="attendance-dashboard__chart-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今日没有异常" />
      </div>
    );
  }
  return (
    <div
      className="attendance-dashboard__chart attendance-dashboard__chart--compact"
      role="img"
      aria-label={visibleData
        .map((item) => `${severityLabel(item.severity)} ${item.count} 条`)
        .join('，')}
    >
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={visibleData}
            dataKey="count"
            innerRadius="56%"
            isAnimationActive={false}
            nameKey="severity"
            outerRadius="82%"
            paddingAngle={2}
            stroke="var(--dashboard-surface)"
            strokeWidth={2}
          >
            {visibleData.map((item) => (
              <Cell
                fill={severityColors[item.severity]}
                key={item.severity}
              />
            ))}
          </Pie>
          <Tooltip
            formatter={(value, name) => [
              `${String(value)} 条`,
              severityLabel(String(name) as DashboardAnomalyProjection['severity']),
            ]}
            contentStyle={{
              border: '1px solid var(--dashboard-border)',
              borderRadius: 6,
            }}
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}

function DashboardTypeChart({
  data,
}: {
  data: Array<{ label: string; count: number }>;
}) {
  if (data.length === 0) {
    return (
      <div className="attendance-dashboard__chart-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今日没有异常类型" />
      </div>
    );
  }
  return (
    <div
      className="attendance-dashboard__chart attendance-dashboard__chart--compact"
      role="img"
      aria-label={data
        .map((item) => `${item.label} ${item.count} 条`)
        .join('，')}
    >
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={data.slice(0, 6)}
          layout="vertical"
          margin={{ top: 4, right: 22, bottom: 4, left: 16 }}
        >
          <CartesianGrid
            horizontal={false}
            stroke="var(--dashboard-divider)"
          />
          <XAxis
            allowDecimals={false}
            axisLine={false}
            tick={{ fill: 'var(--dashboard-muted)', fontSize: 11 }}
            tickLine={false}
            type="number"
          />
          <YAxis
            axisLine={false}
            dataKey="label"
            tick={{ fill: 'var(--dashboard-axis-text)', fontSize: 11 }}
            tickLine={false}
            type="category"
            width={112}
          />
          <Tooltip
            formatter={(value) => [`${String(value)} 条`, '异常数量']}
            contentStyle={{
              border: '1px solid var(--dashboard-border)',
              borderRadius: 6,
            }}
          />
          <Bar
            dataKey="count"
            fill="var(--dashboard-accent)"
            isAnimationActive={false}
            maxBarSize={18}
            name="异常数量"
            radius={[0, 3, 3, 0]}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

function DashboardOrganizationChart({
  data,
}: {
  data: Array<{
    label: string;
    organizationName: string;
    exceptionCount: number;
    blockingCount: number;
  }>;
}) {
  if (data.length === 0) {
    return (
      <div className="attendance-dashboard__chart-empty">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="今日没有组织异常" />
      </div>
    );
  }
  return (
    <div
      className="attendance-dashboard__chart attendance-dashboard__chart--compact"
      role="img"
      aria-label={data
        .map((item) => (
          `${item.organizationName} ${item.exceptionCount} 条，其中阻断 ${item.blockingCount} 条`
        ))
        .join('；')}
    >
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={data}
          margin={{ top: 6, right: 14, bottom: 4, left: -8 }}
        >
          <CartesianGrid stroke="var(--dashboard-divider)" vertical={false} />
          <XAxis
            axisLine={false}
            dataKey="label"
            interval={0}
            tick={{ fill: 'var(--dashboard-muted)', fontSize: 10 }}
            tickLine={false}
          />
          <YAxis
            allowDecimals={false}
            axisLine={false}
            tick={{ fill: 'var(--dashboard-muted)', fontSize: 11 }}
            tickLine={false}
            width={34}
          />
          <Tooltip
            contentStyle={{
              border: '1px solid var(--dashboard-border)',
              borderRadius: 6,
            }}
          />
          <Bar
            dataKey="exceptionCount"
            isAnimationActive={false}
            maxBarSize={34}
            name="异常数量"
            radius={[3, 3, 0, 0]}
          >
            {data.map((item, index) => (
              <Cell
                fill={organizationColors[index % organizationColors.length]}
                key={item.organizationName}
              />
            ))}
          </Bar>
          <Bar
            dataKey="blockingCount"
            fill="var(--dashboard-danger)"
            isAnimationActive={false}
            maxBarSize={16}
            name="阻断数量"
            radius={[3, 3, 0, 0]}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

function DashboardPanel({
  title,
  subtitle,
  meta,
  children,
}: {
  title: string;
  subtitle: string;
  meta: string;
  children: ReactNode;
}) {
  return (
    <article className="attendance-dashboard__panel">
      <header className="attendance-dashboard__panel-head">
        <div>
          <h2>{title}</h2>
          <p>{subtitle}</p>
        </div>
        <span className="attendance-dashboard__panel-meta">{meta}</span>
      </header>
      {children}
    </article>
  );
}

function DashboardAnomalyList({
  businessDate,
  exceptions,
  activeFilter,
  onClearFilter,
  onSelectException,
  onOpenReports,
}: {
  businessDate: string;
  exceptions: DashboardAnomalyProjection[];
  activeFilter?: DashboardFilter;
  onClearFilter: () => void;
  onSelectException: (exception: DashboardAnomalyProjection) => void;
  onOpenReports?: () => void;
}) {
  const visibleExceptions = useMemo(
    () => exceptions.filter((item) => matchesDashboardFilter(item, activeFilter)),
    [activeFilter, exceptions],
  );
  const columns = useMemo(
    () => createDashboardAnomalyColumns(onSelectException),
    [onSelectException],
  );

  return (
    <section
      className="attendance-dashboard__list"
      aria-labelledby="wave7-dashboard-anomaly-heading"
    >
      <div className="attendance-dashboard__list-head">
        <div>
          <h2 id="wave7-dashboard-anomaly-heading">今日异常考勤</h2>
          <p>
            {businessDate} · 按处理优先级显示前 10 条，点击“查看详情”查看考勤依据
          </p>
        </div>
        {activeFilter ? (
          <div className="attendance-dashboard__active-filter" role="status">
            当前筛选：{activeFilter.label}
            <Button size="small" onClick={onClearFilter}>清除筛选</Button>
          </div>
        ) : onOpenReports ? (
          <Button
            icon={<IconArrowRight aria-hidden="true" />}
            onClick={onOpenReports}
          >
            查看全部异常
          </Button>
        ) : null}
      </div>
      <div className="attendance-dashboard__list-body">
        {exceptions.length === 0 ? (
          <Empty description="今日没有未处理的异常考勤" />
        ) : visibleExceptions.length === 0 ? (
          <Empty
            description={(
              <span>
                当前优先列表中没有“{activeFilter?.label}”异常；
                {onOpenReports ? (
                  <Button type="link" onClick={onOpenReports}>
                    进入异常报表查看完整结果
                  </Button>
                ) : null}
              </span>
            )}
          />
        ) : (
          <DataTable
            ariaLabel="今日异常考勤列表"
            rows={visibleExceptions}
            rowKey={(item) => item.exceptionReference}
            columns={columns}
          />
        )}
      </div>
    </section>
  );
}

function DashboardExceptionDrawer({
  exception,
  projection,
  onClose,
  onOpenReports,
}: {
  exception?: DashboardAnomalyProjection;
  projection: DashboardProjection;
  onClose: () => void;
  onOpenReports?: () => void;
}) {
  return (
    <DetailDrawer
      open={exception !== undefined}
      title="异常考勤详情"
      onClose={onClose}
    >
      {exception ? (
        <div className="attendance-dashboard__drawer">
          <div className="attendance-dashboard__drawer-lead">
            <span>{exception.organizationName}</span>
            <strong>{exception.employeeName}</strong>
            <span>{exception.employeeNumber} · {exception.businessDate}</span>
          </div>
          <ReadOnlyDetails
            items={[
              {
                label: '异常类型',
                value: exceptionTypeLabel(exception.exceptionType),
              },
              {
                label: '异常时长',
                value: exception.exceptionMinutes > 0
                  ? `${exception.exceptionMinutes} 分钟`
                  : '—',
              },
              {
                label: '严重程度',
                value: (
                  <DashboardStatus
                    tone={severityTone(exception.severity)}
                    label={severityLabel(exception.severity)}
                  />
                ),
              },
              {
                label: '处理状态',
                value: (
                  <DashboardStatus
                    tone="warning"
                    label={anomalyStateLabel(exception.state)}
                  />
                ),
              },
              {
                label: '证据摘要',
                value: exception.evidenceSummary,
              },
              {
                label: '数据截至',
                value: formatDateTime(projection.metadata.dataAsOf),
              },
            ]}
          />
          <p className="attendance-dashboard__drawer-note">
            如需查看完整处理记录和更多明细，请进入异常报表。
          </p>
          {onOpenReports ? (
            <div className="attendance-dashboard__drawer-actions">
              <Button
                type="primary"
                icon={<IconArrowRight aria-hidden="true" />}
                onClick={onOpenReports}
              >
                进入异常报表
              </Button>
            </div>
          ) : null}
        </div>
      ) : null}
    </DetailDrawer>
  );
}

function createDashboardAnomalyColumns(
  onSelectException: (exception: DashboardAnomalyProjection) => void,
): Array<DataColumn<DashboardAnomalyProjection>> {
  return [
    {
      key: 'employee',
      title: '员工',
      render: (item) => (
        <span>
          <strong>{item.employeeName}</strong>
          <br />
          <span className="mono-value">{item.employeeNumber}</span>
        </span>
      ),
    },
    {
      key: 'organization',
      title: '组织',
      render: (item) => item.organizationName,
    },
    {
      key: 'exceptionType',
      title: '异常类型',
      render: (item) => exceptionTypeLabel(item.exceptionType),
    },
    {
      key: 'exceptionMinutes',
      title: '异常时长',
      render: (item) => item.exceptionMinutes > 0
        ? `${item.exceptionMinutes} 分钟`
        : '—',
    },
    {
      key: 'severity',
      title: '级别',
      render: (item) => (
        <DashboardStatus
          tone={severityTone(item.severity)}
          label={severityLabel(item.severity)}
        />
      ),
    },
    {
      key: 'state',
      title: '处理状态',
      render: (item) => (
        <DashboardStatus
          tone="warning"
          label={anomalyStateLabel(item.state)}
        />
      ),
    },
    {
      key: 'action',
      title: '操作',
      render: (item) => (
        <Button
          className="attendance-dashboard__detail-button"
          type="link"
          icon={<IconZoomIn aria-hidden="true" />}
          aria-label={`查看${item.employeeName}的异常详情`}
          onClick={() => onSelectException(item)}
        >
          查看详情
        </Button>
      ),
    },
  ];
}

function DashboardStatus({
  tone,
  label,
}: {
  tone: 'success' | 'warning' | 'danger' | 'info';
  label: ReactNode;
}) {
  return (
    <span className={`data-status data-status--${tone}`}>
      {label}
    </span>
  );
}

function matchesDashboardFilter(
  exception: DashboardAnomalyProjection,
  filter?: DashboardFilter,
): boolean {
  if (!filter) return true;
  if (filter.kind === 'severity') {
    return exception.severity === filter.value;
  }
  if (filter.kind === 'exceptionType') {
    return exception.exceptionType === filter.value;
  }
  return exception.organizationName === filter.value;
}

function severityTone(
  severity: DashboardAnomalyProjection['severity'],
): 'danger' | 'warning' | 'info' {
  if (severity === 'ERROR') return 'danger';
  if (severity === 'WARNING') return 'warning';
  return 'info';
}

function severityLabel(
  severity: DashboardAnomalyProjection['severity'],
): string {
  return ({
    INFO: '提示',
    WARNING: '警告',
    ERROR: '错误',
  })[severity];
}

function anomalyStateLabel(
  state: DashboardAnomalyProjection['state'],
): string {
  return ({
    OPEN: '待处理',
    PENDING_EVIDENCE: '待补充证据',
    PENDING_REVIEW: '待复核',
  })[state];
}

function trendAriaLabel(data: DashboardDailyTrendProjection[]): string {
  return data.map((item) => (
    `${item.businessDate}：异常 ${item.exceptionCount} 条，影响员工 ${item.affectedEmployeeCount} 人，阻断 ${item.blockingCount} 条`
  )).join('；');
}

function shortDate(value: string): string {
  return value.length >= 10 ? value.slice(5) : value;
}

export function exceptionTypeLabel(type: string): string {
  return ({
    LATE: '迟到',
    EARLY_DEPARTURE: '早退',
    MISSING_PUNCH_PENDING: '缺卡待补正',
    MISSING_PUNCH_OVERDUE: '缺卡逾期',
    ABSENCE: '旷工',
    EVIDENCE_CONFLICT: '证据冲突',
    LEAVE_PUNCH_CONFLICT: '请假与打卡冲突',
    OUTING_OR_TRIP_INCOMPLETE: '外出或出差信息不完整',
    OA_APPROVAL_STATUS_UNKNOWN: 'OA 审批状态未知',
    OA_PERSON_REFERENCE_INVALID: 'OA 人员未匹配',
    EMPLOYEE_UNMATCHED: '人员未匹配',
    DUPLICATE_SOURCE_RECORD: '来源记录重复',
    SOURCE_SCHEMA_CHANGED: '来源结构变化',
    SOURCE_SYNC_STALE: '来源同步超时',
    NO_ATTENDANCE_GROUP: '未配置考勤组',
    NO_SHIFT_OR_CALENDAR: '未配置班次或日历',
    AMBIGUOUS_PUNCH_MATCH: '打卡匹配不唯一',
    CROSS_MIDNIGHT_REVIEW_REQUIRED: '跨日考勤待复核',
    OVERTIME_DOCUMENT_MISSING_OR_LATE: '加班单缺失或迟到',
    EARLY_RETURN_CANDIDATE: '提前返岗待确认',
    POST_CLOSE_SOURCE_CHANGE: '月结后来源变化',
    INPUT_INTEGRITY_ERROR: '输入完整性错误',
  } as Record<string, string>)[type] ?? '其他异常';
}

export default DashboardRoute;

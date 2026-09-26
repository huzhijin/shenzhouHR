import {
  IconArrowRight,
  IconZoomIn,
} from '@tabler/icons-react';
import { Button, Empty, Pagination } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Area,
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import { departmentPathNodes, visibleDepartmentPath } from '../reports/departmentPath';
import { defaultQueryPeriod } from '../reports/queryPeriod';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import {
  DetailDrawer,
  ReadOnlyDetails,
} from '../../shared/components/FeedbackComponents';
import { StatePanel } from '../../shared/components/StatePanel';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  DashboardAnomalyProjection,
  DashboardDailyTrendProjection,
  DashboardProjection,
  LiveDashboardProjection,
} from './wave7Contracts';
import { hasLiveDashboardProjection } from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  formatDateTime,
  isSyntheticMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';
import './dashboardWorkbench.css';

interface DashboardReportContext {
  reportType: 'EXCEPTIONS';
  period: string;
  companyId: string;
  projectionVersion: string;
  employeeNumber?: string;
  fromDate?: string;
}

export function DashboardRoute({
  gateway = wave7ProjectionGateway,
}: {
  gateway?: Wave7ProjectionGateway;
}) {
  const navigate = useNavigate();
  const [windowKind, setWindowKind] = useState<'DAY' | 'MONTH'>('MONTH');
  const [period, setPeriod] = useState<Dayjs>(() => defaultQueryPeriod());
  const loadDashboard = useCallback(
    () => gateway.loadDashboard(undefined, period.format('YYYY-MM'), windowKind),
    [gateway, period, windowKind],
  );

  return (
    <Wave7AsyncBoundary
      key={`${windowKind}-${period.format('YYYY-MM')}`}
      loader={loadDashboard}
      isEmpty={() => false}
      loadingTitle="正在加载考勤工作台"
      loadingDescription="正在汇总所选期间的打卡和异常，请稍候。"
    >
      {(result) => result.kind === 'DASHBOARD_COMPANY_SELECTION'
        ? (
            <StatePanel
              state="empty"
              title="按授权范围展示"
              description="工作台按当前授权范围内的花名册和今日打卡汇总。组织调整后按最新部门取数，不再先选公司。"
            />
          )
        : (
            <DashboardView
              projection={result}
              windowKind={windowKind}
              period={period}
              onWindowKindChange={setWindowKind}
              onPeriodChange={setPeriod}
              onDrillDown={() => {
                if (!hasLiveDashboardProjection(result)) {
                  navigate('/attendance/queries/exceptions');
                  return;
                }
                navigate(attendanceExceptionOverviewPath({
                  period: result.businessDate.slice(0, 7),
                  companyId: result.selectedCompanyId,
                }));
              }}
              onOpenScreen={isSyntheticMetadata(result.metadata)
                ? () => navigate('/attendance/screen')
                : undefined}
              onOpenReports={(context) => {
                navigate(attendanceReportsPath(context));
              }}
            />
          )}
    </Wave7AsyncBoundary>
  );
}

type ExceptionGroup =
  | 'missed'
  | 'late'
  | 'early'
  | 'leave'
  | 'balance'
  | 'other';

const exceptionGroups: Array<{
  key: ExceptionGroup;
  label: string;
  types: string[];
}> = [
  {
    key: 'missed',
    label: '漏刷',
    types: [
      'MISSING_ON_DUTY',
      'MISSING_OFF_DUTY',
      'MISSING_PUNCH_PENDING',
      'MISSING_PUNCH_OVERDUE',
    ],
  },
  { key: 'late', label: '迟到', types: ['LATE'] },
  { key: 'early', label: '早退', types: ['EARLY_DEPARTURE'] },
  {
    key: 'leave',
    label: '请假与旷工',
    types: [
      'ABSENCE',
      'LEAVE_PUNCH_CONFLICT',
      'OUTING_OR_TRIP_INCOMPLETE',
    ],
  },
  {
    key: 'balance',
    label: '假期余额',
    types: [
      'NEGATIVE_ANNUAL_LEAVE_BALANCE',
      'NEGATIVE_TIME_OFF_BALANCE',
      'NEGATIVE_LEAVE_BALANCE',
    ],
  },
  { key: 'other', label: '其他异常', types: [] },
];

const groupedTypeSet = new Set(
  exceptionGroups.flatMap((group) => group.types),
);

export function DashboardView({
  projection,
  onOpenReports,
}: {
  projection: DashboardProjection;
  windowKind?: 'DAY' | 'MONTH';
  period?: Dayjs;
  onWindowKindChange?: (windowKind: 'DAY' | 'MONTH') => void;
  onPeriodChange?: (period: Dayjs) => void;
  onDrillDown?: (reference: string, projectionVersion: string) => void;
  onOpenScreen?: () => void;
  onOpenReports?: (context?: DashboardReportContext) => void;
}) {
  const [activeGroup, setActiveGroup] = useState<ExceptionGroup>();
  const [selectedException, setSelectedException] =
    useState<DashboardAnomalyProjection>();
  const liveProjection = hasLiveDashboardProjection(projection)
    ? projection
    : undefined;
  const canOpenReports = projection.metadata.allowedActions
    .includes('DASHBOARD_DRILL_DOWN');
  const overviewContext = liveProjection
    ? {
        reportType: 'EXCEPTIONS' as const,
        period: liveProjection.metadata.periodLabel,
        companyId: liveProjection.selectedCompanyId,
        projectionVersion: projection.metadata.projectionVersion,
      }
    : undefined;
  const reportContext = liveProjection
    ? {
        ...overviewContext!,
        employeeNumber: selectedException?.employeeNumber,
        fromDate: selectedException?.businessDate,
      }
    : undefined;
  const visibleExceptions = useMemo(() => {
    if (!liveProjection) return [];
    if (!activeGroup) return liveProjection.exceptions;
    return liveProjection.exceptions.filter((item) =>
      exceptionGroupOf(item.exceptionType) === activeGroup);
  }, [activeGroup, liveProjection]);

  return (
    <main className="attendance-dashboard">
      {liveProjection ? (
        <>
          <DashboardExceptionGraphics
            projection={liveProjection}
            activeGroup={activeGroup}
            onSelectGroup={(group) => setActiveGroup((current) => (
              current === group ? undefined : group
            ))}
            onOpenOverview={canOpenReports && onOpenReports && overviewContext
              ? () => onOpenReports(overviewContext)
              : undefined}
          />
          <DashboardAnomalyList
            periodLabel={liveProjection.metadata.periodLabel}
            totalCount={liveProjection.summary.unresolvedCount}
            listedCount={liveProjection.exceptions.length}
            activeGroup={activeGroup}
            exceptions={visibleExceptions}
            canViewExceptionDetails={canOpenReports}
            onSelectException={setSelectedException}
          />
        </>
      ) : (
        <section className="attendance-dashboard__list">
          <div className="attendance-dashboard__list-head">
            <div>
              <h1>异常人员</h1>
              <p>当前授权范围内暂无异常人员。</p>
            </div>
          </div>
        </section>
      )}
      {canOpenReports ? (
        <DashboardExceptionDrawer
          exception={selectedException}
          projection={projection}
          onClose={() => setSelectedException(undefined)}
          onOpenReports={onOpenReports
            ? () => onOpenReports(reportContext)
            : undefined}
        />
      ) : null}
    </main>
  );
}

function attendanceReportsPath(
  context?: DashboardReportContext,
): string {
  if (context === undefined) {
    return '/attendance/queries/exceptions';
  }
  return attendanceExceptionOverviewPath({
    period: context.period,
    companyId: context.companyId,
    employeeNumber: context.employeeNumber,
    fromDate: context.fromDate,
  });
}

function attendanceExceptionOverviewPath(context: {
  period: string;
  companyId: string;
  employeeNumber?: string;
  fromDate?: string;
}): string {
  const query = new URLSearchParams({
    period: context.period,
    companyId: context.companyId,
  });
  if (context.employeeNumber) {
    query.set('employeeNumber', context.employeeNumber);
  }
  if (context.fromDate) {
    query.set('fromDate', context.fromDate);
    query.set('toDate', context.fromDate);
  }
  return `/attendance/queries/exceptions?${query.toString()}`;
}

function exceptionGroupOf(type: string): ExceptionGroup {
  const matched = exceptionGroups.find((group) =>
    group.key !== 'other' && group.types.includes(type));
  return matched?.key ?? 'other';
}

function DashboardExceptionGraphics({
  projection,
  activeGroup,
  onSelectGroup,
  onOpenOverview,
}: {
  projection: LiveDashboardProjection;
  activeGroup?: ExceptionGroup;
  onSelectGroup: (group: ExceptionGroup) => void;
  onOpenOverview?: () => void;
}) {
  const typeCounts = useMemo(() => {
    const counts = new Map<string, number>();
    for (const item of projection.analytics.typeDistribution) {
      counts.set(item.exceptionType, item.count);
    }
    return counts;
  }, [projection.analytics.typeDistribution]);
  const groupCounts = useMemo(
    () => exceptionGroups.map((group) => {
      const count = group.key === 'other'
        ? [...typeCounts.entries()].reduce((total, [type, value]) => (
          groupedTypeSet.has(type) ? total : total + value
        ), 0)
        : group.types.reduce((total, type) => total + (typeCounts.get(type) ?? 0), 0);
      return { ...group, count };
    }),
    [typeCounts],
  );
  const typeChartData = projection.analytics.typeDistribution.map((item) => ({
    ...item,
    label: exceptionTypeLabel(item.exceptionType),
  }));

  return (
    <section className="attendance-dashboard__graphics" aria-label="异常图形汇总">
      <header className="attendance-dashboard__list-head">
        <div>
          <h1>异常工作台</h1>
          <p>
            {projection.metadata.periodLabel}
            {' · '}
            本月待处理 {projection.summary.unresolvedCount} 条，影响
            {' '}
            {projection.summary.affectedEmployeeCount} 人
          </p>
        </div>
        {onOpenOverview ? (
          <Button
            type="primary"
            icon={<IconArrowRight aria-hidden="true" />}
            onClick={onOpenOverview}
          >
            异常总览
          </Button>
        ) : null}
      </header>
      <div className="attendance-dashboard__categories" role="toolbar" aria-label="异常分类">
        {groupCounts.map((group) => {
          const selected = activeGroup === group.key;
          return (
            <button
              key={group.key}
              type="button"
              className={`attendance-dashboard__category${selected ? ' is-active' : ''}`}
              aria-pressed={selected}
              onClick={() => onSelectGroup(group.key)}
            >
              <span>{group.label}</span>
              <strong>{group.count}</strong>
            </button>
          );
        })}
      </div>
      <div className="attendance-dashboard__structure-grid">
        <article className="attendance-dashboard__panel">
          <header className="attendance-dashboard__panel-head">
            <div>
              <h2>异常类型</h2>
              <p>按本月待处理条数排序，点击分类可筛选下方名单</p>
            </div>
          </header>
          <DashboardTypeChart data={typeChartData} />
        </article>
        <article className="attendance-dashboard__panel">
          <header className="attendance-dashboard__panel-head">
            <div>
              <h2>近 7 日异常</h2>
              <p>看最近几天待处理异常的变化</p>
            </div>
          </header>
          <DashboardTrendChart data={projection.analytics.dailyTrend} />
        </article>
      </div>
    </section>
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
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="本月没有异常类型" />
      </div>
    );
  }
  return (
    <div
      className="attendance-dashboard__chart attendance-dashboard__chart--compact"
      role="img"
      aria-label={data.map((item) => `${item.label} ${item.count} 条`).join('，')}
    >
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={data.slice(0, 8)}
          layout="vertical"
          margin={{ top: 8, right: 22, bottom: 8, left: 16 }}
        >
          <CartesianGrid horizontal={false} stroke="var(--dashboard-divider)" />
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
            width={88}
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

function DashboardTrendChart({
  data,
}: {
  data: DashboardDailyTrendProjection[];
}) {
  return (
    <div
      className="attendance-dashboard__chart attendance-dashboard__chart--compact"
      role="img"
      aria-label={data.map((item) => (
        `${item.businessDate}：${item.exceptionCount} 条`
      )).join('；')}
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
            tickFormatter={(value: string) => (
              value.length >= 10 ? value.slice(5) : value
            )}
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
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

function DashboardAnomalyList({
  periodLabel,
  totalCount,
  listedCount,
  activeGroup,
  exceptions,
  canViewExceptionDetails,
  onSelectException,
}: {
  periodLabel: string;
  totalCount: number;
  listedCount: number;
  activeGroup?: ExceptionGroup;
  exceptions: DashboardAnomalyProjection[];
  canViewExceptionDetails: boolean;
  onSelectException: (exception: DashboardAnomalyProjection) => void;
}) {
  const [page, setPage] = useState(1);
  const pageSize = 20;
  useEffect(() => {
    setPage(1);
  }, [activeGroup, exceptions]);
  const columns = useMemo(
    () => createDashboardAnomalyColumns(onSelectException),
    [onSelectException],
  );
  const pagedExceptions = exceptions.slice(
    (page - 1) * pageSize,
    page * pageSize,
  );
  const groupLabel = exceptionGroups.find((group) => group.key === activeGroup)
    ?.label;
  const listHint = listedCount < totalCount
    ? `${periodLabel} · 名单显示 ${listedCount} / ${totalCount} 条，完整结果请打开异常总览`
    : `${periodLabel} · 本月待处理 ${totalCount} 条`;

  return (
    <section
      className="attendance-dashboard__list"
      aria-labelledby="wave7-dashboard-anomaly-heading"
    >
      <div className="attendance-dashboard__list-head">
        <div>
          <h2 id="wave7-dashboard-anomaly-heading">异常人员</h2>
          <p>
            {canViewExceptionDetails
              ? (groupLabel ? `当前筛选：${groupLabel} · ${exceptions.length} 条` : listHint)
              : `${periodLabel} · 员工异常明细已按当前账号权限隐藏`}
          </p>
        </div>
      </div>
      <div className="attendance-dashboard__list-body">
        {!canViewExceptionDetails ? (
          <Empty
            description="当前账号仅可查看汇总指标，无权查看员工异常明细"
          />
        ) : exceptions.length === 0 ? (
          <Empty description={groupLabel
            ? `当前没有“${groupLabel}”异常`
            : '本月没有未处理的异常考勤'}
          />
        ) : (
          <>
            <DataTable
              ariaLabel="异常人员列表"
              rows={pagedExceptions}
              rowKey={(item) => item.exceptionReference}
              columns={columns}
            />
            {exceptions.length > pageSize ? (
              <div className="attendance-dashboard__pager">
                <Pagination
                  current={page}
                  pageSize={pageSize}
                  total={exceptions.length}
                  onChange={setPage}
                  showSizeChanger={false}
                  showTotal={(total) => `共 ${total} 条`}
                />
              </div>
            ) : null}
          </>
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
            <span title={visibleDepartmentPath(exception.organizationName)}>
              {departmentPathNodes(exception.organizationName)}
            </span>
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
            如需查看完整处理记录和更多明细，请打开异常总览。
          </p>
          {onOpenReports ? (
            <div className="attendance-dashboard__drawer-actions">
              <Button
                type="primary"
                icon={<IconArrowRight aria-hidden="true" />}
                onClick={onOpenReports}
              >
                打开异常详情
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
      render: (item) => (
        <span title={visibleDepartmentPath(item.organizationName)}>
          {departmentPathNodes(item.organizationName)}
        </span>
      ),
    },
    {
      key: 'businessDate',
      title: '日期',
      render: (item) => item.businessDate,
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

export function exceptionTypeLabel(type: string): string {
  return ({
    LATE: '迟到',
    EARLY_DEPARTURE: '早退',
    MISSING_ON_DUTY: '上班漏签',
    MISSING_OFF_DUTY: '下班漏签',
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
    FAKE_OVERTIME: '加班异常',
    OVERTIME_FORM_BEYOND_LAST_PUNCH: '加班结束晚于打卡',
    EARLY_RETURN_CANDIDATE: '提前返岗待确认',
    POST_CLOSE_SOURCE_CHANGE: '月结后来源变化',
    INPUT_INTEGRITY_ERROR: '输入完整性错误',
    NEGATIVE_LEAVE_BALANCE: '假期余额为负',
    NEGATIVE_ANNUAL_LEAVE_BALANCE: '年假余额为负',
    NEGATIVE_TIME_OFF_BALANCE: '调休余额为负',
  } as Record<string, string>)[type] ?? '其他异常';
}

export default DashboardRoute;

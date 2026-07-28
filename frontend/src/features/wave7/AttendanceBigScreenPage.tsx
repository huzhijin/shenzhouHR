import {
  IconArrowsMaximize,
  IconDatabase,
  IconFileSpreadsheet,
  IconInfoCircle,
  IconLayoutDashboard,
  IconRefresh,
  IconTable,
  IconX,
} from '@tabler/icons-react';
import {
  useEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { Link } from 'react-router-dom';

import { BrandLogo } from '../../shared/components/BrandLogo';
import './attendanceBigScreen.css';

type ScopeKey = 'company' | 'manufacturing' | 'rd' | 'hr';

interface ScopeData {
  label: string;
  headcount: number;
  departmentLabel: string;
  attendance: number;
  delta: string;
  exceptions: [number, number, number, number];
  overtime: [number, number, number, number];
  close: number;
  pending: number;
  trend: [number, number, number, number, number, number, number];
}

const scopes: Record<ScopeKey, ScopeData> = {
  company: {
    label: '江苏公司',
    headcount: 81,
    departmentLabel: '3 个部门',
    attendance: 96.8,
    delta: '+0.6 个百分点',
    exceptions: [3, 2, 1, 1],
    overtime: [38, 44, 51, 53],
    close: 72,
    pending: 1,
    trend: [96.1, 96.4, 96.0, 97.2, 96.5, 97.0, 96.8],
  },
  manufacturing: {
    label: '制造一部',
    headcount: 42,
    departmentLabel: '制造一部',
    attendance: 95.8,
    delta: '+0.3 个百分点',
    exceptions: [2, 1, 1, 0],
    overtime: [22, 24, 27, 25],
    close: 70,
    pending: 1,
    trend: [95.2, 95.7, 95.1, 96.4, 95.6, 96.0, 95.8],
  },
  rd: {
    label: '研发一部',
    headcount: 31,
    departmentLabel: '研发一部',
    attendance: 97.4,
    delta: '+0.8 个百分点',
    exceptions: [1, 1, 0, 0],
    overtime: [12, 16, 18, 20],
    close: 78,
    pending: 0,
    trend: [96.8, 97.1, 96.9, 97.8, 97.2, 97.6, 97.4],
  },
  hr: {
    label: '人力资源部',
    headcount: 8,
    departmentLabel: '人力资源部',
    attendance: 98.6,
    delta: '+0.2 个百分点',
    exceptions: [0, 0, 0, 1],
    overtime: [4, 4, 6, 8],
    close: 88,
    pending: 0,
    trend: [98.1, 98.7, 98.4, 99.0, 98.6, 98.9, 98.6],
  },
};

const scopeOptions: Array<{ key: ScopeKey; label: string }> = [
  { key: 'company', label: '公司总览' },
  { key: 'manufacturing', label: '制造一部' },
  { key: 'rd', label: '研发一部' },
  { key: 'hr', label: '人力资源部' },
];

const departmentRates = [
  { key: 'manufacturing', label: '制造一部', value: 95.8 },
  { key: 'rd', label: '研发一部', value: 97.4 },
  { key: 'hr', label: '人力资源部', value: 98.6 },
] as const;

const metricDefinitions = [
  ['出勤率', '正常出勤计划工作段数 ÷ 应出勤计划工作段数。待补正、冻结保护和无有效排班记录分别处理，不按自然日粗算。'],
  ['异常结构', '按员工、日期、计划工作段去重后的待处理异常。大屏只展示类型和数量，不展示人员、位置或原因。'],
  ['确认加班', '仅统计已确认且落入授权范围的加班时长；待审批、撤销或冲突记录不计入。'],
  ['月结进度', '已通过检查项 ÷ 全部月结检查项。未发布导入、未解决异常和未确认重算均形成阻断。'],
  ['数据新鲜度', '分别展示源系统水位和大屏刷新时间。刷新展示不会改变源系统水位，也不会补推缺失事实。'],
  ['离线地点最近成功导入', '最近一个已发布离线原始打卡批次的发布时间；待预检或待发布批次单独列示。'],
] as const;

export function AttendanceBigScreenPage() {
  const [scope, setScope] = useState<ScopeKey>('company');
  const [scale, setScale] = useState(1);
  const [methodOpen, setMethodOpen] = useState(false);
  const [toastVisible, setToastVisible] = useState(false);
  const [refreshStatus, setRefreshStatus] = useState('展示已刷新');
  const data = scopes[scope];

  useEffect(() => {
    const fit = () => setScale(Math.min(window.innerWidth / 1920, window.innerHeight / 1080));
    fit();
    window.addEventListener('resize', fit);
    return () => window.removeEventListener('resize', fit);
  }, []);

  useEffect(() => {
    if (!methodOpen) return undefined;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMethodOpen(false);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [methodOpen]);

  const exceptionTotal = data.exceptions.reduce((sum, value) => sum + value, 0);
  const overtimeTotal = data.overtime.reduce((sum, value) => sum + value, 0);
  const trendAverage = data.trend.reduce((sum, value) => sum + value, 0) / data.trend.length;
  const trendPoints = useMemo(() => chartPoints(data.trend), [data.trend]);
  const state = typeof window === 'undefined'
    ? 'normal'
    : new URLSearchParams(window.location.search).get('state') ?? 'normal';

  const refresh = () => {
    setRefreshStatus('刚刚完成展示刷新');
    setToastVisible(true);
    window.setTimeout(() => setToastVisible(false), 2600);
  };

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else {
        await document.documentElement.requestFullscreen?.();
      }
    } catch {
      setToastVisible(true);
    }
  };

  return (
    <div className="od15-viewport">
      <main
        className="od15-stage"
        data-od-id="attendance-live-screen"
        aria-label="神州 HR 独立考勤数据大屏"
        style={{ '--od15-scale': scale } as CSSProperties}
      >
        <div className="od15-screen">
          <header className="od15-topbar" data-od-id="screen-header">
            <div className="od15-brand" data-od-id="screen-brand">
              <BrandLogo />
            </div>
            <div className="od15-title-group">
              <h1>考勤运行总览</h1>
              <p>{data.label} · 2026 年 7 月 · 只读授权聚合</p>
            </div>
            <div className="od15-top-spacer" />
            <div className="od15-scope-switch" role="radiogroup" aria-label="授权范围" data-od-id="scope-switcher">
              {scopeOptions.map((option) => (
                <button
                  className={scope === option.key ? 'active' : ''}
                  type="button"
                  role="radio"
                  aria-checked={scope === option.key}
                  key={option.key}
                  onClick={() => setScope(option.key)}
                >
                  {option.label}
                </button>
              ))}
            </div>
            <div className="od15-top-status">
              <strong><span className="od15-live-dot" />{refreshStatus}</strong>
              <span>数据截至 2026-07-22 14:20</span>
            </div>
            <TopLink to="/workbench" label="返回工作台"><IconLayoutDashboard /></TopLink>
            <TopLink to="/attendance/reports" label="查看报表"><IconTable /></TopLink>
            <TopButton label="刷新展示" onClick={refresh}><IconRefresh /></TopButton>
            <TopButton label="查看指标口径" onClick={() => setMethodOpen(true)}><IconInfoCircle /></TopButton>
            <TopButton label="进入全屏" onClick={() => void toggleFullscreen()}><IconArrowsMaximize /></TopButton>
          </header>

          <section className="od15-kpis" data-od-id="headline-kpis" aria-label="核心指标">
            <Kpi
              tone="success"
              label="出勤率"
              value={data.attendance.toFixed(1)}
              suffix="%"
              meta="应出勤计划工作段"
              end={<span className="od15-delta">{data.delta}</span>}
            />
            <Kpi
              label="授权范围人数"
              value={String(data.headcount)}
              suffix=" 人"
              meta={data.departmentLabel}
              end="合成数据"
            />
            <Kpi
              tone="warning"
              label="待处理异常"
              value={String(exceptionTotal)}
              suffix=" 条"
              meta="按员工-日期-工作段去重"
              end={<span className="od15-delta od15-delta--warning">缺卡 {data.exceptions[0]}</span>}
            />
            <Kpi
              label="本月确认加班"
              value={String(overtimeTotal)}
              suffix=" 小时"
              meta="已确认业务单据"
              end="截至 7 月 22 日"
            />
            <Kpi
              tone="warning"
              label="月结准备"
              value={String(data.close)}
              suffix="%"
              meta={`阻断 ${data.pending ? 3 : 2} 项`}
              end="2026-07"
            />
            <Kpi
              tone="danger"
              label="待处理导入批次"
              value={String(data.pending)}
              suffix=" 个"
              meta={data.pending ? '离线一号厂区' : '当前范围'}
              end={data.pending ? '待发布' : '无待处理'}
            />
          </section>

          <section className="od15-main-grid" aria-label="趋势与结构">
            <Panel
              title="近 7 日出勤率"
              subtitle="正常出勤计划工作段 / 应出勤计划工作段"
              meta={`均值 ${trendAverage.toFixed(1)}%`}
              odId="attendance-trend-panel"
            >
              <div className="od15-trend-wrap">
                <div className="od15-trend-chart">
                  <div className="od15-y-labels"><span>100%</span><span>98%</span><span>96%</span><span>94%</span></div>
                  <svg viewBox="0 0 700 250" role="img" aria-labelledby="attendance-chart-title attendance-chart-desc">
                    <title id="attendance-chart-title">近七日出勤率趋势</title>
                    <desc id="attendance-chart-desc">
                      7 月 16 日至 22 日，{data.label}出勤率在 {Math.min(...data.trend).toFixed(1)}% 至 {Math.max(...data.trend).toFixed(1)}% 之间。
                    </desc>
                    {[12, 88, 164, 240].map((y) => (
                      <line className="od15-grid-line" x1="0" y1={y} x2="700" y2={y} key={y} />
                    ))}
                    <polygon className="od15-trend-area" points={`${trendPoints} 700,240 0,240`} />
                    <polyline className="od15-trend-line" points={trendPoints} />
                    {trendPoints.split(' ').map((point) => {
                      const [x, y] = point.split(',');
                      return <circle className="od15-trend-point" cx={x} cy={y} r="5" key={point} />;
                    })}
                  </svg>
                </div>
                <div className="od15-x-labels">
                  {['07-16', '07-17', '07-18', '07-19', '07-20', '07-21', '07-22'].map((label) => <span key={label}>{label}</span>)}
                </div>
              </div>
            </Panel>

            <Panel
              title="异常结构"
              subtitle="只展示聚合类型，不展示个人或原因"
              meta="2026-07"
              odId="exception-structure-panel"
            >
              <div className="od15-anomaly-total">
                <div><strong>{exceptionTotal}</strong><span> 待处理</span></div>
                <span>{data.label}</span>
              </div>
              <div className="od15-stack-bar" aria-label={`异常结构，共 ${exceptionTotal} 条`}>
                {[
                  ['missing', data.exceptions[0]],
                  ['late', data.exceptions[1]],
                  ['meal', data.exceptions[2]],
                  ['other', data.exceptions[3]],
                ].map(([key, value]) => (
                  <span
                    className={`od15-segment od15-segment--${key}`}
                    key={key}
                    style={{ width: `${exceptionTotal ? (Number(value) / exceptionTotal) * 100 : 0}%` }}
                  />
                ))}
              </div>
              <div className="od15-legend-list">
                <Legend tone="missing" label="单边缺卡" value={data.exceptions[0]} />
                <Legend tone="late" label="迟到" value={data.exceptions[1]} />
                <Legend tone="meal" label="晚餐扣除复核" value={data.exceptions[2]} />
                <Legend tone="other" label="其他复核" value={data.exceptions[3]} />
              </div>
            </Panel>

            <Panel
              title="部门出勤率对比"
              subtitle="仅限当前授权范围"
              meta="目标 ≥ 96.0%"
              odId="department-comparison-panel"
            >
              <div className="od15-dept-list">
                {departmentRates.map((department) => (
                  <div
                    className={`od15-dept-row${scope === department.key ? ' selected' : ''}`}
                    key={department.key}
                  >
                    <span className="od15-dept-name">{department.label}</span>
                    <div className="od15-bar-track"><div className="od15-bar-fill" style={{ width: `${department.value}%` }} /></div>
                    <strong className="od15-dept-value">{department.value}%</strong>
                  </div>
                ))}
              </div>
              <div className="od15-dept-foot"><span>最低：制造一部 95.8%</span><span>最大差：2.8 个百分点</span></div>
            </Panel>
          </section>

          <section className="od15-bottom-grid" aria-label="加班、月结与数据新鲜度">
            <Panel
              title="近 4 周确认加班趋势"
              subtitle="仅统计已确认时长，不含待审批申请"
              meta={`周均 ${(overtimeTotal / 4).toFixed(1)} 小时`}
              odId="overtime-trend-panel"
            >
              <div className="od15-overtime-chart">
                {data.overtime.map((value, index) => (
                  <div className="od15-ot-col" key={`week-${index + 1}`}>
                    <strong>{value} 小时</strong>
                    <div className="od15-ot-bar-wrap">
                      <div
                        className="od15-ot-bar"
                        style={{ height: `${Math.max(8, (value / Math.max(...data.overtime)) * 84)}%` }}
                      />
                    </div>
                    <span>第 {index + 1} 周</span>
                  </div>
                ))}
              </div>
            </Panel>

            <Panel
              title="月结进度"
              subtitle="完成检查后冻结 2026-07 结果"
              meta={<Status tone="warning">存在阻断</Status>}
              odId="month-close-panel"
            >
              <div className="od15-close-layout">
                <div className="od15-close-top"><strong>{data.close}%</strong><span>已通过 {data.close}% 检查项</span></div>
                <div className="od15-progress" aria-label={`月结进度 ${data.close}%`}><span style={{ width: `${data.close}%` }} /></div>
                <div className="od15-blockers">
                  {data.pending ? <Blocker label="离线批次待发布" value="1 个" /> : null}
                  {exceptionTotal ? <Blocker label="待处理异常" value={`${exceptionTotal} 条`} /> : null}
                  <Blocker label="重算差异未确认" value="待执行" />
                </div>
              </div>
            </Panel>

            <Panel
              title="数据新鲜度与离线接入"
              subtitle="源系统水位与展示刷新时间分开"
              meta={<Status tone="success">3 类来源</Status>}
              odId="data-freshness-panel"
            >
              <div className="od15-source-list">
                <Source icon={<IconDatabase />} name="在线考勤机 · 演示设备 01" detail="源数据截至 2026-07-22 14:18" status="正常" />
                <Source icon={<IconFileSpreadsheet />} name="办公系统考勤业务单据 · 只读" detail="源数据截至 2026-07-22 14:12" status="正常" />
                <Source
                  icon={<IconFileSpreadsheet />}
                  name="离线一号厂区 · 电子表格"
                  detail="最近成功 2026-07-21 23:42"
                  status={data.pending ? '待发布 1 批' : '无待发布'}
                  warning={Boolean(data.pending)}
                />
                <Source
                  icon={<IconFileSpreadsheet />}
                  name="演示导入批次 001"
                  detail={data.pending ? '428 行 · 412 可发布 · 重复 8 · 疑似 4' : '当前授权范围无待处理离线批次'}
                  status={data.pending ? '待确认' : '无待处理'}
                  warning={Boolean(data.pending)}
                />
              </div>
            </Panel>
          </section>

          <footer className="od15-footer" data-od-id="screen-footnote">
            <span><strong>口径摘要：</strong>出勤率按计划工作段统计；异常按员工-日期-工作段去重；加班仅含已确认时长。</span>
            <span className="od15-footer-right">开放设计大屏 · 项目内置 · 合成演示数据</span>
          </footer>
        </div>

        <div className={`od15-scope-banner${state === 'stale' || state === 'partial' ? ' show' : ''}`} role="status">
          <span><strong>部分来源延迟：</strong>当前展示保留最近成功水位，不使用缺失来源推算个人结果。</span>
          <span>刷新时间 14:20</span>
        </div>
        <div className={`od15-toast${toastVisible ? ' show' : ''}`} role="status" aria-live="polite">
          展示已刷新；源系统水位保持不变。
        </div>

        {methodOpen ? (
          <section className="od15-method-layer" onMouseDown={(event) => {
            if (event.currentTarget === event.target) setMethodOpen(false);
          }}>
            <div className="od15-method-panel" role="dialog" aria-modal="true" aria-labelledby="od15-method-title">
              <header className="od15-method-head">
                <div>
                  <h2 id="od15-method-title">大屏指标口径</h2>
                  <p>所有指标为授权范围聚合，不下钻到个人敏感明细。</p>
                </div>
                <button type="button" aria-label="关闭指标口径" onClick={() => setMethodOpen(false)}><IconX /></button>
              </header>
              <div className="od15-method-body">
                {metricDefinitions.map(([title, description]) => (
                  <article className="od15-definition" key={title}><strong>{title}</strong><p>{description}</p></article>
                ))}
              </div>
              <footer className="od15-method-foot">
                工程边界：当前演示使用合成数据，仅证明只读展示、口径和状态表达。
              </footer>
            </div>
          </section>
        ) : null}

        <StateOverlay state={state} />
      </main>
    </div>
  );
}

function TopLink({ to, label, children }: { to: string; label: string; children: ReactNode }) {
  return <Link className="od15-icon-button" to={to} aria-label={label} title={label}>{children}</Link>;
}

function TopButton({ label, children, onClick }: { label: string; children: ReactNode; onClick: () => void }) {
  return <button className="od15-icon-button" type="button" aria-label={label} title={label} onClick={onClick}>{children}</button>;
}

function Kpi({
  label,
  value,
  suffix,
  meta,
  end,
  tone = 'default',
}: {
  label: string;
  value: string;
  suffix: string;
  meta: string;
  end: ReactNode;
  tone?: 'default' | 'success' | 'warning' | 'danger';
}) {
  return (
    <article className={`od15-kpi od15-kpi--${tone}`}>
      <div className="od15-kpi-label"><span>{label}</span><span className="od15-status-dot" /></div>
      <strong className="od15-kpi-value">{value}<small>{suffix}</small></strong>
      <div className="od15-kpi-meta"><span>{meta}</span>{end}</div>
    </article>
  );
}

function Panel({
  title,
  subtitle,
  meta,
  odId,
  children,
}: {
  title: string;
  subtitle: string;
  meta: ReactNode;
  odId: string;
  children: ReactNode;
}) {
  return (
    <article className="od15-panel" data-od-id={odId}>
      <header className="od15-panel-head">
        <div className="od15-panel-title"><h2>{title}</h2><p>{subtitle}</p></div>
        <span className="od15-panel-meta">{meta}</span>
      </header>
      <div className="od15-panel-body">{children}</div>
    </article>
  );
}

function Legend({ tone, label, value }: { tone: string; label: string; value: number }) {
  return (
    <div className="od15-legend-row">
      <span className={`od15-legend-swatch od15-legend-swatch--${tone}`} />
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Status({ tone, children }: { tone: 'success' | 'warning' | 'info'; children: ReactNode }) {
  return <span className={`od15-status od15-status--${tone}`}>{children}</span>;
}

function Blocker({ label, value }: { label: string; value: string }) {
  return (
    <div className="od15-blocker">
      <span className="od15-blocker-dot" />
      <strong>{label}</strong>
      <span>{value}</span>
    </div>
  );
}

function Source({
  icon,
  name,
  detail,
  status,
  warning = false,
}: {
  icon: ReactNode;
  name: string;
  detail: string;
  status: string;
  warning?: boolean;
}) {
  return (
    <div className="od15-source-row">
      <span className="od15-source-icon">{icon}</span>
      <div className="od15-source-main"><strong>{name}</strong><span>{detail}</span></div>
      <Status tone={warning ? 'warning' : 'success'}>{status}</Status>
    </div>
  );
}

function StateOverlay({ state }: { state: string }) {
  const content: Record<string, [string, string, string]> = {
    loading: ['加载中', '正在加载授权范围数据', '不会显示上一个授权范围的数据。'],
    empty: ['暂无数据', '当前授权范围暂无可展示数据', '请确认期间、组织范围与来源数据时间。'],
    failure: ['来源失败', '数据加载失败', '已保留最近成功数据；不会使用缺失来源推算结果。'],
    forbidden: ['无权访问', '无权查看该大屏', '系统不会泄露授权范围外的数量、字段或对象是否存在。'],
  };
  const selected = content[state];
  if (!selected) return null;
  return (
    <section className="od15-overlay">
      <div className="od15-state-card">
        {state === 'loading' ? <div className="od15-spinner" /> : null}
        <span className="od15-state-code">{selected[0]}</span>
        <h2>{selected[1]}</h2>
        <p>{selected[2]}</p>
      </div>
    </section>
  );
}

function chartPoints(values: readonly number[]): string {
  const width = 700;
  const height = 228;
  const min = 94;
  const max = 100;
  return values.map((value, index) => {
    const x = Math.round(index * width / (values.length - 1));
    const y = Math.round(12 + (max - value) / (max - min) * height);
    return `${x},${y}`;
  }).join(' ');
}

export default AttendanceBigScreenPage;

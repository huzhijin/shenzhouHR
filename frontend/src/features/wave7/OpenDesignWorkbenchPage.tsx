import {
  IconAdjustmentsHorizontal,
  IconChartBar,
  IconClock,
  IconDatabase,
  IconFileSpreadsheet,
  IconHome2,
  IconLogout,
  IconPresentationAnalytics,
  IconSearch,
  IconUsers,
} from '@tabler/icons-react';
import { useState, type ComponentType, type SVGProps } from 'react';
import { Link } from 'react-router-dom';

import { BrandLogo } from '../../shared/components/BrandLogo';
import './openDesignWorkbench.css';

type WorkbenchTab = 'overview' | 'department' | 'personal';

type TablerIcon = ComponentType<SVGProps<SVGSVGElement>>;

interface NavItem {
  icon: TablerIcon;
  label: string;
  path: string;
}

interface WorkbenchMetric {
  label: string;
  value: string;
  meta: string;
  path: string;
}

interface WorkbenchTask {
  title: string;
  scope: string;
  status: string;
  updatedAt: string;
  path: string;
}

export interface OpenDesignWorkbenchPageProps {
  onLogout?: () => void;
}

const navGroups: Array<{ label: string; items: NavItem[] }> = [
  {
    label: '核心工作',
    items: [
      { icon: IconHome2, label: '工作台', path: '/workbench' },
    ],
  },
  {
    label: '组织与规则',
    items: [
      { icon: IconUsers, label: '组织与人员', path: '/people/organization' },
      { icon: IconAdjustmentsHorizontal, label: '规则与排班', path: '/rules' },
    ],
  },
  {
    label: '数据与考勤',
    items: [
      { icon: IconDatabase, label: '数据接入', path: '/sources/online' },
      { icon: IconClock, label: '考勤管理', path: '/attendance/reports' },
    ],
  },
];

const overviewMetrics: WorkbenchMetric[] = [
  {
    label: '待发布离线批次',
    value: '1',
    meta: '412 行可发布',
    path: '/sources/attendance-excel',
  },
  {
    label: '待补正异常',
    value: '3',
    meta: '含 1 条单边缺卡',
    path: '/attendance/reports',
  },
  {
    label: '月结进度',
    value: '72%',
    meta: '仍有 2 项阻断',
    path: '/attendance/reports',
  },
  {
    label: '当前规则',
    value: '第三版草稿',
    meta: '2026-08-01 拟生效',
    path: '/rules/attendance-policy',
  },
];

const tasks: WorkbenchTask[] = [
  {
    title: '处理离线打卡批次',
    scope: '离线一号厂区',
    status: '待确认',
    updatedAt: '14:06',
    path: '/sources/attendance-excel',
  },
  {
    title: '处理单边缺卡',
    scope: '制造一部 · 演示员工 001',
    status: '7 月 25 日截止',
    updatedAt: '13:58',
    path: '/attendance/reports',
  },
  {
    title: '解除 2026-07 月结阻断',
    scope: '江苏公司',
    status: '阻断 2 项',
    updatedAt: '13:44',
    path: '/attendance/reports',
  },
  {
    title: '复核考勤规则第三版',
    scope: '制造一部',
    status: '待发布',
    updatedAt: '13:32',
    path: '/rules/attendance-policy',
  },
];

const searchItems = [
  { label: '考勤工作台', description: '查看优先事项与数据新鲜度', path: '/workbench' },
  { label: '在线考勤来源', description: '查看设备、办公系统与最近同步时间', path: '/sources/online' },
  { label: '考勤电子表格导入', description: '上传、预检并发布离线打卡', path: '/sources/attendance-excel' },
  { label: '考勤报表中心', description: '查询并导出九张独立报表', path: '/attendance/reports' },
  { label: '考勤运行大屏', description: '查看公司与部门聚合指标', path: '/attendance/screen' },
  { label: '员工反馈中心', description: '查看并提交本人考勤反馈', path: '/me/feedback' },
] as const;

function MetricCard({ metric }: { metric: WorkbenchMetric }) {
  return (
    <article className="odw-card odw-metric-card">
      <span className="odw-metric-label">{metric.label}</span>
      <strong className="odw-metric-value">{metric.value}</strong>
      <span className="odw-metric-meta">{metric.meta}</span>
      <Link
        className="odw-metric-link"
        to={metric.path}
        aria-label={`${metric.label}：${metric.value}`}
      />
    </article>
  );
}

function WorkbenchSidebar() {
  return (
    <aside className="odw-sidebar" aria-label="主导航">
      {navGroups.map((group) => (
        <section className="odw-nav-group" key={group.label}>
          <h2 className="odw-nav-group-label">{group.label}</h2>
          {group.items.map((item) => {
            const Icon = item.icon;
            const isActive = item.path === '/workbench';
            return (
              <Link
                className={`odw-nav-link${isActive ? ' is-active' : ''}`}
                to={item.path}
                aria-current={isActive ? 'page' : undefined}
                key={item.path}
              >
                <Icon aria-hidden="true" />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </section>
      ))}
      <div className="odw-sidebar-note">
        <strong>考勤工作台预览</strong>
        <span>当前账号的数据权限已生效。</span>
      </div>
    </aside>
  );
}

function OverviewPanel() {
  return (
    <section
      className="odw-tab-panel"
      id="workbench-overview-panel"
      role="tabpanel"
      aria-labelledby="workbench-overview-tab"
    >
      <div className="odw-metric-grid">
        {overviewMetrics.map((metric) => <MetricCard key={metric.label} metric={metric} />)}
      </div>

      <div className="odw-split">
        <article className="odw-card">
          <header className="odw-card-head">
            <h2>优先处理</h2>
            <span>按阻断程度排序</span>
          </header>
          <div className="odw-card-body">
            <div className="odw-list">
              {tasks.map((task) => (
                <div className="odw-list-row" key={task.title}>
                  <div className="odw-list-row-main">
                    <strong>{task.title}</strong>
                    <span>{task.scope} · {task.status}</span>
                  </div>
                  <Link className="odw-link-button" to={task.path}>处理</Link>
                </div>
              ))}
            </div>
          </div>
        </article>

        <article className="odw-card">
          <header className="odw-card-head">
            <h2>数据新鲜度</h2>
            <span className="odw-status odw-status--success">正常</span>
          </header>
          <div className="odw-card-body">
            <div className="odw-list">
              <div className="odw-list-row">
                <div className="odw-list-row-main">
                  <strong>在线考勤机</strong>
                  <span className="odw-mono">演示考勤设备 01</span>
                </div>
                <span className="odw-list-time">14:18</span>
              </div>
              <div className="odw-list-row">
                <div className="odw-list-row-main">
                  <strong>办公系统考勤业务单据</strong>
                  <span>只读来源</span>
                </div>
                <span className="odw-list-time">14:12</span>
              </div>
              <div className="odw-list-row">
                <div className="odw-list-row-main">
                  <strong>离线一号厂区</strong>
                  <span className="odw-mono">演示导入批次 001</span>
                </div>
                <Link className="odw-link-button" to="/sources/attendance-excel">查看批次</Link>
              </div>
            </div>
          </div>
        </article>
      </div>

      <article className="odw-table-card">
        <div className="odw-table-tools">
          <strong>跨模块待办</strong>
          <span className="odw-mono">2026-07 · 保留筛选上下文</span>
        </div>
        <div className="odw-table-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">任务</th>
                <th scope="col">范围</th>
                <th scope="col">状态</th>
                <th scope="col">更新时间</th>
                <th scope="col">下一步</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((task) => (
                <tr key={task.title}>
                  <td data-label="任务"><strong>{task.title}</strong></td>
                  <td data-label="范围">{task.scope}</td>
                  <td data-label="状态">
                    <span className="odw-status odw-status--warning">{task.status}</span>
                  </td>
                  <td data-label="更新时间" className="odw-mono">{task.updatedAt}</td>
                  <td data-label="下一步"><Link className="odw-link-button" to={task.path}>打开</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </article>
    </section>
  );
}

function DepartmentPanel() {
  const metrics: WorkbenchMetric[] = [
    {
      label: '制造一部 · 需处理异常',
      value: '5',
      meta: '缺卡 3 · 迟到 1 · 其他 1',
      path: '/attendance/reports',
    },
    {
      label: '研发一部 · 需处理异常',
      value: '2',
      meta: '迟到 1 · 餐扣复核 1',
      path: '/attendance/reports',
    },
    {
      label: '人力资源部 · 需处理异常',
      value: '0',
      meta: '当前授权范围内无阻断项',
      path: '/attendance/reports',
    },
  ];
  return (
    <section
      className="odw-tab-panel"
      id="workbench-department-panel"
      role="tabpanel"
      aria-labelledby="workbench-department-tab"
    >
      <div className="odw-department-grid">
        {metrics.map((metric) => <MetricCard key={metric.label} metric={metric} />)}
      </div>
    </section>
  );
}

function PersonalPanel() {
  return (
    <section
      className="odw-tab-panel"
      id="workbench-personal-panel"
      role="tabpanel"
      aria-labelledby="workbench-personal-tab"
    >
      <div className="odw-notice">
        <strong>个人口径仅展示当前员工本人</strong>
        <p>切换角色或员工时，页面会在新范围确认后再展示数据。</p>
      </div>
      <div className="odw-personal-grid">
        <article className="odw-card odw-personal-card">
          <span className="odw-metric-label">演示员工甲 · 本月宽限</span>
          <strong className="odw-metric-value odw-metric-value--compact">已使用 1 / 1</strong>
          <Link className="odw-link-button" to="/me/records">查看为什么</Link>
        </article>
        <article className="odw-card odw-personal-card">
          <span className="odw-metric-label">待补正</span>
          <strong className="odw-metric-value odw-metric-value--compact">1 个工作段</strong>
          <Link className="odw-link-button" to="/me/today">查看证据链</Link>
        </article>
      </div>
    </section>
  );
}

export function OpenDesignWorkbenchPage({ onLogout }: OpenDesignWorkbenchPageProps) {
  const [activeTab, setActiveTab] = useState<WorkbenchTab>('overview');
  const [searchQuery, setSearchQuery] = useState('');
  const tabs: Array<{ id: WorkbenchTab; label: string }> = [
    { id: 'overview', label: '授权总览' },
    { id: 'department', label: '部门口径' },
    { id: 'personal', label: '个人口径' },
  ];
  const normalizedSearch = searchQuery.trim().toLocaleLowerCase('zh-CN');
  const searchResults = normalizedSearch
    ? searchItems.filter((item) => (
      `${item.label}${item.description}`.toLocaleLowerCase('zh-CN').includes(normalizedSearch)
    ))
    : [];

  return (
    <div className="odw-shell">
      <header className="odw-topbar">
        <Link className="odw-brand" to="/workbench" aria-label="神州半导体科技工作台">
          <BrandLogo />
        </Link>
        <label className="odw-search">
          <span className="odw-visually-hidden">搜索页面、制度或帮助</span>
          <IconSearch aria-hidden="true" />
          <input
            type="search"
            placeholder="搜索页面、制度或帮助"
            value={searchQuery}
            aria-controls="workbench-search-results"
            aria-expanded={normalizedSearch.length > 0}
            onChange={(event) => setSearchQuery(event.target.value)}
          />
          {normalizedSearch ? (
            <div
              className="odw-search-results"
              id="workbench-search-results"
              role="listbox"
              aria-label="工作台搜索结果"
            >
              {searchResults.length ? searchResults.map((item) => (
                <Link
                  to={item.path}
                  role="option"
                  aria-selected="false"
                  key={item.path}
                  onClick={() => setSearchQuery('')}
                >
                  <strong>{item.label}</strong>
                  <span>{item.description}</span>
                </Link>
              )) : <p>没有匹配的页面，请尝试“报表”或“导入”。</p>}
            </div>
          ) : null}
        </label>
        <div className="odw-topbar-spacer" />
        <div className="odw-top-context">
          <span className="odw-fresh-dot" aria-hidden="true" />
          数据截至 14:18
        </div>
        <div className="odw-top-context odw-top-context--scope">江苏公司 · 考勤管理员</div>
        {onLogout ? (
          <button className="odw-avatar" type="button" onClick={onLogout} aria-label="退出演示">
            <IconLogout aria-hidden="true" />
          </button>
        ) : (
          <span className="odw-avatar" aria-label="当前用户：考勤管理员">考</span>
        )}
      </header>

      <WorkbenchSidebar />

      <main className="odw-workspace">
        <header className="odw-page-head">
          <div>
            <nav className="odw-breadcrumb" aria-label="面包屑">
              <Link to="/workbench">神州 HR</Link>
              <span aria-hidden="true">/</span>
              <span>工作台</span>
            </nav>
            <h1>考勤管理员工作台</h1>
            <p>聚焦数据接入、异常处理与 2026-07 月结准备。所有数字均为合成演示数据。</p>
          </div>
          <div className="odw-head-actions">
            <Link className="odw-button" to="/sources/online">
              <IconDatabase aria-hidden="true" />
              数据接入
            </Link>
            <Link className="odw-button" to="/attendance/reports">
              <IconChartBar aria-hidden="true" />
              查看统计报表
            </Link>
            <Link className="odw-button odw-button--primary" to="/attendance/screen">
              <IconPresentationAnalytics aria-hidden="true" />
              进入考勤大屏
            </Link>
          </div>
        </header>

        <div className="odw-context-strip" aria-label="当前工作台上下文">
          <span className="odw-prototype-label">示例数据</span>
          <span>角色：<strong>考勤管理员</strong></span>
          <span>范围：<strong>江苏公司 · 授权考勤组</strong></span>
          <span>期间：<strong>2026-07</strong></span>
          <span>数据截至：<strong>2026-07-22 14:18</strong></span>
        </div>

        <div className="odw-tabs" role="tablist" aria-label="看板口径">
          {tabs.map((tab) => (
            <button
              className={`odw-tab${activeTab === tab.id ? ' is-active' : ''}`}
              id={`workbench-${tab.id}-tab`}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              aria-controls={`workbench-${tab.id}-panel`}
              tabIndex={activeTab === tab.id ? 0 : -1}
              onClick={() => setActiveTab(tab.id)}
              key={tab.id}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {activeTab === 'overview' ? <OverviewPanel /> : null}
        {activeTab === 'department' ? <DepartmentPanel /> : null}
        {activeTab === 'personal' ? <PersonalPanel /> : null}
      </main>

      <nav className="odw-mobile-nav" aria-label="移动端快捷导航">
        <Link to="/workbench" aria-current="page"><IconHome2 aria-hidden="true" /><span>工作台</span></Link>
        <Link to="/sources/attendance-excel"><IconFileSpreadsheet aria-hidden="true" /><span>导入</span></Link>
        <Link to="/attendance/reports"><IconChartBar aria-hidden="true" /><span>报表</span></Link>
        <Link to="/attendance/screen"><IconPresentationAnalytics aria-hidden="true" /><span>大屏</span></Link>
      </nav>
    </div>
  );
}

export default OpenDesignWorkbenchPage;

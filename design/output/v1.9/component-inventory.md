# 神州 HR V1.9 · 公共组件清单

更新日期：2026-07-22  
设计规范模式：`PROJECT_EMBEDDED`  
实现基线：`V19-OD-00`（唯一 `ACTIVE_BASELINE`）

## 共享基础

| 层级 | 组件 / 令牌 | 原型来源 | React 实施建议 | 状态 |
|---|---|---|---|---|
| 品牌 | 横版 Logo、主品牌蓝、深品牌蓝、支持蓝、危险色 | `assets/shenzhou-logo.svg`、`design-system/tokens.css` | 建立只读 `BrandLogo`，固定 `object-fit: contain`，保留文本回退 | READY_FOR_IMPLEMENTATION |
| 排版 | UI 字体、等宽数字、400/500/600 字重 | `design-system/tokens.css` | 通过全局 token 和数字工具类提供，禁止页面私建字体比例 | READY_FOR_IMPLEMENTATION |
| 空间 | 8px 节奏、控件 6px、卡片 8px | `design-system/tokens.css` | 映射到主题对象和 CSS variables | READY_FOR_IMPLEMENTATION |
| 焦点 | `:focus-visible`、2px 偏移、44px 触控目标 | `assets/v19-prototype.css` | 作为 P0 基础样式；不可由业务页覆盖移除 | READY_FOR_IMPLEMENTATION |
| 动效 | 100–150ms 状态反馈、减少动态效果 | `design-system/tokens.css`、共享 CSS | 使用统一 motion token，尊重 `prefers-reduced-motion` | READY_FOR_IMPLEMENTATION |

## 壳层与导航

| 组件 | 关键能力 | 主要使用 artifact | 稳定 `data-od-id` / 契约 |
|---|---|---|---|
| `AppShell` | PC 顶栏与左导航、平板抽屉、移动四项底栏 | V19-OD-01～14 | `shared-global-topbar`、`shared-primary-navigation`、`shared-mobile-navigation` |
| `RoleScopeGuard` | 角色、数据范围、403、不展示上一范围数据 | V19-OD-01～14 | `data-allowed-roles`；服务端必须再次鉴权 |
| `Breadcrumbs` | 返回上层、来源页和上下文保留 | V19-OD-02～14 | 页面内 `.breadcrumb` 与 `context-return-button` |
| `RouteLink` | 传递角色、月份、部门、员工、批次、筛选和返回地址 | V19-OD-02～14 | `data-route` 与 `route-to-artifact.json` |
| `MobileBottomNav` | 今日、记录、反馈、我的 | V19-OD-01、13 | 仅移动员工任务；不缩放管理表格 |
| `DisplayShell` | 16:9 只读大屏、授权范围、刷新与全屏 | V19-OD-15 | `attendance-display-canvas`；不进入产品导航 |

## 数据展示与任务组件

| 组件 | 关键能力 | 使用范围 | 实施约束 |
|---|---|---|---|
| `MetricCard` | 指标、口径、更新时间、预设筛选跳转 | 工作台、报表、大屏 | 数字用等宽字体；状态不可只靠颜色 |
| `DenseDataTable` | 固定关键列、字段标签、筛选摘要、分页接口位 | OD03、04、08～12、14 | 720px 以下改为字段标签卡片；排序和分页为服务端能力 |
| `ContextStrip` | 角色、范围、期间、版本、数据截至 | OD02～14 | 切换上下文时先清空旧范围数据 |
| `StatusBadge` | 正常、告警、危险、信息 | 全局 | 同时使用文字和形状；颜色不是唯一编码 |
| `SourceTag` | API、Excel、OA/人工证据来源 | OD07、10A、10B、11 | 原始证据保留；来源标签不可替代可追溯主键 |
| `EvidenceTimeline` | 输入事实、规则版本、计算过程、结果、下一步 | OD08、11、13 | “为什么”入口必须保留完整五段解释 |
| `AuditTimeline` | 操作人、时间、原因、版本、批次 | OD03～10A、12、14 | 时间来自服务端；不可由客户端伪造 |
| `VersionTimeline` | 草稿、校验、发布、停用、回滚 | OD05～08 | 冻结/已月结期间不得静默重写 |

## 表单、向导与高风险操作

| 组件 | 关键能力 | 主要使用 artifact | 实施约束 |
|---|---|---|---|
| `ControlledField` | 文本、数字、日期、时段、枚举、开关 | OD04～09、14 | 错误信息与字段关联；禁止任意脚本编辑器 |
| `SixStepImportWizard` | 模板、上传、匹配、预校验、确认、发布 | OD03、09、10A | 三类导入共享骨架但 route、模板、字段、权限和发布规则独立 |
| `MappingTable` | 字段、设备人员、员工、厂商方案映射 | OD03、09、10A | 显示样例、匹配状态、未匹配原因和保存方案范围 |
| `PrecheckSummary` | 有效、错误、重复、疑似重复、冻结、可发布 | OD03、09、10A | 预校验不写入正式数据 |
| `HighRiskDialog` | 作用范围、人数/记录数、期间、影响、可逆性、确认 | OD03、07、09、10A、12、14 | 上传、预检、发布、作废、重算、月结、反月结、导出分别鉴权 |
| `Drawer` / `Dialog` | 详情、解释、确认、恢复 | OD02～14 | 焦点陷阱、Escape 关闭、关闭后恢复触发控件焦点 |
| `ToastRegion` | 成功或错误的文字反馈 | OD02～14 | `role=status`、`aria-live=polite`；不可替代持久错误提示 |

## 页面状态组件

`PageStateLayer` 覆盖 `LOADING`、`EMPTY`、`REQUEST_FAILED`、`403`、`CONFLICT`、`PERIOD_FROZEN`、`PROCESSING`、`PARTIAL_SUCCESS` 和 `SUCCESS`；正常态由业务页面本身承担。V19-OD-15 使用独立的大屏状态层，登录与会话状态由 V19-OD-01 独立处理。

## 原型专用组件

- `PROTOTYPE_ONLY · 演示场景`、本地角色切换、状态切换与重置，不进入生产前台。
- `localStorage` 中的 `shenzhou-hr-v19-demo-state` 仅用于跨 artifact 演示，不是生产状态管理方案。
- `data-prototype-unwired` 明确标记非主链路的工程待接入动作，不得在生产中保留为假按钮。

## 禁止复用

- 任何 SSO/IdP 待定、组织持续同步、通用导入混流或旧年假口径组件。
- 任何薪资、工资条、相关占位、导航、搜索、通知、报表或员工入口。
- 任何把客户端角色切换器当作真实鉴权、把合成数据当作真实接口返回的实现。


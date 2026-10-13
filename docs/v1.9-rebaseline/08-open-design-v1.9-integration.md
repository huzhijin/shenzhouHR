# Open Design V1.9 纠偏与 React 接入规范

## Visual direction

`product`。用户进入系统是为了完成组织、导入、规则、考勤、月结和自助任务，界面应优先熟悉、紧凑、清晰和可解释；不使用营销页式大标题、装饰渐变或页面加载编舞。

## 1. 事实优先级纠偏

Open Design V1.9 只在 PRD 和本轮已确认规格之后提供视觉、布局、交互和响应式参考。`design/DESIGN-HANDOFF.md` 中的 “production source of truth / pixel-first” 不具有业务事实效力。

路径事实：

- `design/open-design/v1.9` 是指向 `design/output/v1.9` 的符号链接。
- 顶层 manifest 使用逻辑路径 `open-design/v1.9/...`，可通过该符号链接访问。
- `IMPORT.md` 已声明禁止把静态 HTML/JS 整体复制或拼接进 React。

## 2. route-to-artifact 接入

| Artifact | 原型 route | 生产纠偏 |
|---|---|---|
| OD01 | `/login` | 保留；接真实本地认证 |
| OD02 | `/workbench` | 按服务端 capability 和 scope 生成 |
| OD03 | `/people/import` | 组织/员工期初导入 |
| OD04 | `/people/organization`、`/people/employees`、demo 员工详情 | 详情改 `/people/employees/:employeeId` |
| OD05 | `/rules` | 统一规则中心 |
| OD06 | `/rules/attendance-groups`、`/rules/shifts`、`/rules/calendars` | 保留语义 |
| OD07 | demo 策略 route | 改 `/rules/attendance-policies/:policyVersionId` |
| OD08 | `/rules/leave`、`/rules/annual-leave` | 保留；员工上下文由授权参数/route 解析 |
| OD09 | `/rules/time-accounts`、`/rules/time-accounts/import` | 保留 |
| OD10A | `/sources/attendance-excel`、demo 批次 | 批次改 `/sources/attendance-excel/:batchId` |
| OD10B | `/sources/online`、`/sources/oa`、`/sources/jobs` | 明确组织同步不在此处 |
| OD11 | `/attendance/daily`、demo event | 事件改 `/attendance/evidence/:eventId` |
| OD12 | `/attendance/exceptions`、`/attendance/recalc`、`/attendance/close/2026-07`、`/attendance/reports` | 月结期改参数路由 `/attendance/close/:period`；生产重算 route 统一 `/attendance/recalculations` |
| OD13 | `/me/today`、`/me/records`、`/me/leave`、`/me/feedback` | 仅 employee；本人 ID 来自 session |
| OD14 | `/access/accounts`、`/access/roles`、`/access/audit`、`/ops/health` | 角色、capability 与数据范围分别鉴权 |
| OD15 | `/display/attendance` | 独立 16:9 只读面，不进普通导航；入口原型角色只作参考，生产以服务端只读 capability 为准 |
| OD16 | `/qa/handoff` | 设计交付页，不注册生产 route |

原型 route 中的 `role`、`source`、`return`、`context` 和 demo 标识只用于设计跳转。生产端角色字符串不得作为授权依据，统一改为服务端 capability + scope；`return` 只允许站内 allowlist route，禁止信任任意 query 或外部 URL；来源与上下文必须由受控枚举、授权资源和服务端返回值解析。

## 3. Token 裁决

待人工确认的 canonical candidate：

- `docs/docs-confirm-v1.9/design-tokens.json`
- `docs/docs-confirm-v1.9/design-tokens.css`

两份文件是同一候选合同：JSON 记录设计语义和引用，CSS 记录其运行时变量与兼容 alias；人工 `docs_confirm` 后才生效。它们以现有前端已验证使用的 token 为基础，保留全部在用的尺寸、状态、字体和 skip-link 变量，并吸收 Open Design 的 product register、Tabler 2px、`font-mono` 和语义 alias，不直接覆盖：

- 导入 JSON 的 `font.size.xl=1.25rem` 与 CSS/现有前端 `1.3125rem` 冲突，采用 `1.3125rem`。
- 原型 CSS 的 sidebar/topbar `248/64px` 与候选基线 `240/56px` 冲突，采用候选基线。
- 原型 CSS 的 1180/900/720 断点仅作布局证据；生产按内容压力和确认视口实现。
- 原型 HTML 的 raw hex/rgba 和 SVG sprite 不进入组件；组件只用语义 token 和 Tabler Icons。
- 每个 surface/status token 必须有 `on-*` 前景；后续 `preview_confirm` 实测正文 4.5:1、UI/大字 3:1。

## 4. 公共组件映射

保留/新增的 React 语义组件：

- 壳层：AppShell、RoleScopeGuard、Breadcrumbs、MobileEmployeeNav、DisplayShell。
- 数据：DenseDataTable、MetricCard、ContextStrip、StatusBadge、SourceTag。
- 解释：EvidenceTimeline、AuditTimeline、VersionTimeline、CalculationExplanation。
- 表单：ControlledField、MappingTable、PolicyEditor、ScopePicker。
- 流程：SixStepImportWizard、PrecheckSummary、ImpactPreview、HighRiskDialog。
- 状态：PageStateLayer、InlineIssueSummary、ConflictPanel、FrozenPeriodPanel。

禁止把原型的通用卡片网格、localStorage 状态管理、QA chrome 或 `PROTOTYPE_ONLY` 控制条复制进生产。

## 5. 页面状态合同

每个异步页：normal、loading、empty、error、403；创建/发布类页面增加 conflict、processing、success。

| 场景 | 额外状态 |
|---|---|
| 认证/会话/系统 | 首次改密、密码过期、失败锁定、账号恢复、会话过期、退出、404、计划维护、断网、数据过期；恢复前不得闪现旧 capability/scope 数据 |
| Excel 导入 | mapping required、validation failed、duplicate、partial success、frozen blocked |
| 规则配置 | draft、invalid、scope conflict、impact preview、published、inactive、rollback |
| 考勤日结果 | stale source、pending correction、evidence conflict、recalculated |
| 月结 | pre-close failed、frozen、closed、reopened、difference |
| 大屏 | stale、partial、no authorized data |

原型中的状态只定义客户端体验；服务端错误码、任务状态、幂等、权限和审计必须由 OpenAPI 锁定。

## 6. 响应式合同

后续浏览器验证视口：

`360×800`、`390×844`、`430×932`、`600×960`、`768×1024`、`820×1180`、`1024×768`、`1366×768`、`1440×900`、`1920×1080`；OD15 另验 `3840×2160`。

- 手机无页面级横向滚动；表格转换为字段卡或受控局部滚动。
- 管理端手机不自动注入员工底栏；员工移动导航只对 employee。
- Drawer/Dialog 在窄屏转底部 sheet 时保持焦点、标题和危险操作。
- 44px 触控目标、`focus-visible`、Escape、focus trap/restore 和 reduced motion 必须可测。

## 7. 五条演示链路的工程化

1. 期初建档 → 本地组织 → 任职/工龄 → 周年年假；
2. 离线打卡导入 → 预检/发布 → 证据链；
3. 规则中心 → 考勤组/班次 → 类型化策略发布；
4. 异常 → 证据/补正 → 重算 → 月结/报表；
5. 员工今日 → 为什么 → 假期余额 → 反馈。

`demo-flow.md` 当前全部是 `PROTOTYPE_SIMULATION`。工程验收必须替换为真实路由、API、数据库状态、权限和合成测试数据；原型点击连续不等于功能完成。

## 8. PRD / 设计冲突处理

- 原设计交接曾排除 PRD DOCX；本轮已经实际读取 PRD V1.9，任何差异以 PRD/新确认规格为准。
- 班组长不能在未确认时静默合并为部门负责人；权限模型保留独立 scope 能力。
- 设计包内 “NONE missing/blocked” 只表示 artifact 文件齐全，不表示后端、API 或生产验收完整。
- V19 产品页未发现薪资入口，但未来 React 接入仍须执行 route/menu/search/export 发现性扫描。

## 9. 真实性与交付门

以下标识不得冒充实现：

- `PROTOTYPE_SIMULATION`
- `ENGINEERING_ASSUMPTION`
- `NOT_VERIFIED`
- `VISUAL_CAPTURE_NOT_AVAILABLE`

当前仍是 `VISUAL_CAPTURE_NOT_AVAILABLE`。后续 `preview_confirm` 必须在真实 React 构建和真实开发 API 上完成多断点截图、键盘、焦点、状态、对比度和无敏感数据检查。在此之前不得写“像素级验收通过”。

# 神州 HR V1.9 · Design Handoff

## 采用模式

- 设计规范模式：`PROJECT_EMBEDDED`
- 平台 Active Design System：`NOT_ACTIVE`
- 采用依据：已成功读取 `design-system/DESIGN.md`、`design-system/tokens.css`、`design-tokens.json`、`brand-spec.md` 与 `manifest.json`。
- 事实声明：本项目直接绑定上述项目内规范；未声称、也不依赖平台级激活。

## 基线与增量交付

- 唯一 `ACTIVE_BASELINE`：`v19-od-00-project-brief.html`
- 被替代尝试：`V19-OD-00-PRECHECK`，状态为 `SUPERSEDED`；原文件保留不修改。
- 已完成增量 artifact：V19-OD-01～V19-OD-16；其中 V19-OD-15 为独立 Live artifact，V19-OD-16 为全项目 QA 与设计交接。
- 配套文档：`artifact-index.md`、`design-handoff.md`、`assumptions.md`、`qa.md`、`route-to-artifact.json`、`demo-data-registry.json`、`demo-flow.md`、`component-inventory.md`、`page-state-matrix.md`、`responsive-screenshot-checklist.md`、`react-implementation-notes.md`、`missing-blocked-not-verified.md`
- 品牌资产副本：`assets/shenzhou-logo.svg`
- 当前缺失或阻塞的 V1.9 设计交付：`NONE`。

### V19-OD-01 实施交接

- 对应范围：本系统独立账号密码、忘记密码/联系管理员、首次改密、密码过期、失败锁定、会话过期与退出。
- 壳层范围：PC 左导航与顶栏、平板抽屉、手机“今日 / 记录 / 反馈 / 我的”四项底栏。
- 系统状态：403、404、计划维护、断网只读、数据过期与恢复动作。
- 原型入口：右下角“仅用于演示 · 场景”用于评审状态和角色；明确不代表真实权限由前端决定。
- 工程约束：真实账号策略、锁定阈值、密码历史、会话 TTL、CSRF、防暴力破解和服务端权限均需由安全与后端实现确认；原型未验证这些能力。
- 状态恢复：退出与会话过期返回登录；403/404 返回授权首页；断网与数据过期提供显式恢复，不显示上一权限范围的数据。
- 合成数据：仅使用“演示管理员”、江苏公司和虚构 request_id；无真实个人信息。

## 设计系统绑定

| 语义 | 采用值 |
|---|---|
| 主品牌蓝 | `#25449A` |
| 深品牌蓝 | `#00265F` |
| 支持蓝 | `#003C95` |
| 危险色 | `#E60012`，仅危险操作与关键告警 |
| 页面背景 | `#F6F9FD` |
| 表面 | `#FFFFFF` |
| 正文 | `#1F2937` |
| 边框 | `#CBD5E1` |
| 字体 | Inter 优先的系统无衬线栈；编号、日期和版本使用等宽字体 |
| 空间与圆角 | 8px 基础节奏；控件 6px；卡片 8px |
| 动效 | 100–150ms，仅用于操作确认和状态变化；支持减少动态效果 |
| 可访问性 | WCAG 2.2 AA；44px 触控目标；状态不只依赖颜色 |

Logo 使用本地 `assets/shenzhou-logo.svg`，保持约 4.42:1 比例并使用 `contain`；不可拉伸、裁切、拆分、变色或重绘。加载失败时显示公司全称文本回退。

## 资料读取与裁决

| 资料 | 状态 | 用途 / 裁决 |
|---|---|---|
| 当前最高优先级指令与已粘贴的 V1.9 完整总提示词 | READ | 本次重启的自包含正式设计事实包 |
| 归档 PRD V1.9 DOCX | EXCLUDED / NOT_USED | 按重启指令排除，不作为本轮启动依赖或设计证据 |
| `umadev-v19-master-prompt.md` | OPTIONAL_REFERENCE | 本轮不重新读取；以已粘贴事实包为准 |
| 离线考勤 Excel 导入专题 | OPTIONAL_REFERENCE | 本轮不重新读取；范围由自包含事实包锁定 |
| `docs/docs-confirm-v1.9/` | MISSING / NOT_READ | 不可直接访问，不推测其内容，也不阻断设计 |
| 已确认 UI tokens 与现有前端 tokens | READ | 与项目嵌入式设计系统比对 |
| 现有 AppShell、路由和公共组件 | READ | 仅作实现证据，不等同于已完成 OD artifact |
| V1.7 Open Design 文件 | MISSING / UNKNOWN | 无可读原 artifact；仅建立 LEGACY-V17 历史引用关系 |

冲突裁决：

1. 旧开放问题中的“SSO 待定”被 V1.9 明确要求覆盖；采用本系统独立账号密码。
2. 旧“组织持续同步”被 V1.9 取消；只保留组织/员工期初 Excel 导入和发布后的本地维护。
3. OA 请假、加班、外出、出差、补卡仍是只读考勤数据来源，取消组织同步不影响这些业务单据接入。
4. 旧薪资后台与工资条仅 `FUTURE_RESERVED`；当前前端完全不可发现。
5. PRD 中出现的“班组长”未列入最新七角色清单；本轮暂并入部门负责人授权团队视角，不新增第八个前端角色。

## 可复用与不可复用

可重新核验后复用：

- 品牌 Logo、色彩、间距、圆角、字体、焦点和状态令牌。
- 现有 AppShell 的结构思路、StatePanel 的状态表达、表格/筛选/抽屉基础形态。
- 导入向导、版本状态、审计时间线、证据抽屉等跨模块组件骨架。

不可沿用：

- SSO/IdP 待定登录流程。
- 致远组织持续、定时、手动或双向同步的信息架构与入口。
- 将组织、余额、离线打卡混为“通用导入”的业务结构。
- 把单边缺卡解释为整日无卡或默认整日旷工。
- 将年假资格、累计工龄档位和发放周期混成单一规则。
- 任何会静默改写已月结/冻结历史的交互。
- 任何薪资或工资条的当前前端入口、占位或暗示。

## 工程边界

- 本原型只验证客户端信息架构、交互、状态和文案。
- 服务端权限、数据库隔离、接口真实性、性能与安全均未由原型验证。
- 页面路由属于设计交接建议；实现前由 React 前端与后端契约共同确认。
- 不修改只读参考仓库中的 `frontend/src`。
- UmaDev 后续只能从 `V19-OD-00` 继续；不得以 PRECHECK、错误文本或任何 V1.7 资产作为当前基线。

## V19-OD-02～14 批次交付

- 共享 AppShell、角色导航、页面状态、对话框焦点管理、跨页上下文和演示状态由 `assets/v19-prototype.css` 与 `assets/v19-prototype.js` 提供。
- `route-to-artifact.json` 是页面、入口角色、来源页、返回页和上下文参数的唯一稳定路由注册表。
- `demo-data-registry.json` 是跨 artifact 合成主键与初始状态的唯一登记；员工、部门、月份、规则版本和批次编号不得在实现中另造冲突值。
- V19-OD-01 仅补充登录成功后跳转到 `/workbench` 的关系；其视觉、认证状态与壳层未重新设计。
- V19-OD-02～14 每个文件均可独立打开；复杂表格在 720px 以下重排为字段标签卡片，平板使用抽屉导航，PC 保留高密度表格与筛选结构。
- 五条客户演示主链路的起点、步骤、前置状态、变化、返回路径和重置方式见 `demo-flow.md`。

## PROTOTYPE_SIMULATION

- 演示操作状态存储于浏览器 `localStorage`，键为 `shenzhou-hr-v19-demo-state`；状态在 OD02～14 间共享。
- 已模拟：组织批次发布、时间账户批次发布、离线打卡预检/发布、缺卡补正、重算、月结、规则发布与反馈状态。
- 任一 OD02～14 页面右下角 `PROTOTYPE_ONLY · 演示场景` 可切换演示角色、检查页面状态并重置演示数据。
- 该入口仅用于原型评审，UmaDev 默认不得实施到生产导航、账号、权限或客户端状态管理中。
- 角色切换先隐藏旧范围内容，再按查询上下文进入目标工作台；这只证明客户端防闪现意图，不能代替服务端鉴权与数据隔离。

## Demo Flow 状态

| 链路 | 入口 | 主要 artifacts | 状态 |
|---|---|---|---|
| FLOW-01 HR 期初建档到周年年假 | V19-OD-01 选择 HR 管理员并登录 | OD02 → OD03 → OD04 → OD08 | CLICKABLE / PROTOTYPE_SIMULATION |
| FLOW-02 离线打卡导入到证据链 | OD02 考勤管理员工作台“待发布离线批次” | OD10A → OD11 | CLICKABLE / PROTOTYPE_SIMULATION |
| FLOW-03 规则组合到发布 | OD05 统一规则中心 | OD06 → OD07 | CLICKABLE / PROTOTYPE_SIMULATION |
| FLOW-04 异常处理到月结报表 | OD12 异常队列 | OD11 → OD10A → OD12 | CLICKABLE / PROTOTYPE_SIMULATION |
| FLOW-05 员工移动端解释与反馈 | OD13“我的今日” | OD11 → OD13 | CLICKABLE / PROTOTYPE_SIMULATION |

## ENGINEERING_ASSUMPTION / NOT_VERIFIED

- 本地账号认证、密码策略、会话安全、服务端菜单/字段/动作权限与数据范围隔离。
- XLSX 文件解析、模板真实下载、恶意文件扫描、对象存储、幂等、批次事务和错误报告生成。
- 得力设备 API、OA 业务单据接口、水位、重试、隔离队列和跨来源规范事件合并。
- 规则引擎、作用范围冲突求解、样例试算、冻结期间保护、版本回滚和历史结果重放。
- 人工调整审批、重算队列、月结/反月结数据库事务、受控导出与不可抵赖审计。
- 真实性能、并发、数据库隔离、备份恢复、监控、安全扫描与消息通知。

## V19-OD-16 · 全项目 QA 与设计交接

- 文件：`v19-od-16-qa-handoff.html`；设计交接 route 为 `/qa/handoff`，不进入生产产品导航。
- V19-OD-00 仍为唯一 `ACTIVE_BASELINE`；V19-OD-01～15 全部为 `COMPLETE`。
- 36 条 route 均指向存在文件，来源与返回 route 均已登记；共享 ROUTES 与注册表一致，未发现未说明死链。
- 五条客户演示主链路全部连续可点击；共享月份、部门、员工、批次、规则版本、异常和月结状态与 `demo-data-registry.json` 一致。
- 产品 artifact 扫描未发现薪资、工资条、SSO/IdP 待定、组织持续/定时/双向同步口径或 route。
- 设计层修正：共享对话框关闭后恢复触发控件焦点，并为缺少可访问名称的“×”关闭按钮补充“关闭”。
- 响应式结论来自断点、结构、尺寸、溢出风险和对比度静态检查；宿主未提供可用的 Open Design HTML 导出命令，截图状态如实记录为 `VISUAL_CAPTURE_NOT_AVAILABLE`。
- 完整组件、状态、截图、React 与风险清单分别见 `component-inventory.md`、`page-state-matrix.md`、`responsive-screenshot-checklist.md`、`react-implementation-notes.md`、`missing-blocked-not-verified.md`。

## V19-OD-15 · 独立考勤数据大屏

- 文件：`v19-od-15-attendance-screen.html`
- 表面类型：自由形式 Live artifact；逻辑画布固定为 1920×1080，并等比缩放至 3840×2160。
- 路由：`/display/attendance`；不进入现有产品导航、移动端导航或员工入口。
- 入口角色：公司管理层、考勤管理员和审计员的授权只读范围；真实服务端鉴权仍为 `NOT_VERIFIED`。
- 设计规范：`PROJECT_EMBEDDED`，复用 shenzhou-hr 品牌色、完整横版 Logo、8px 节奏、6/8px 圆角与 WCAG 2.2 AA 状态表达。
- 默认数据：2026-07 合成聚合；公司 81 人、出勤率 96.8%、待处理异常 7 条、确认加班 186 小时、月结准备 72%、待处理离线批次 1 个。
- 数据锚点：`ATT-XLS-DEMO-001`、`DEVICE-DEMO-01`、离线厂区 A；若浏览器存在 V19 演示状态，则批次发布和月结结果会只读同步。
- 信息范围：出勤率、异常结构、加班趋势、部门对比、月结进度、离线地点最近成功导入、待处理批次和来源新鲜度。
- 禁止范围：不展示个人、工号、精确位置、假别原因、敏感单据内容或任何被排除模块信息。
- 交互：授权范围切换、展示刷新、全屏、指标口径层；均为只读，不提供下钻到个人或高风险操作。
- 状态 URL：`?state=loading`、`empty`、`failure`、`forbidden`、`stale`、`partial`；默认 `normal`。状态切换不作为生产 UI 控件展示。
- 工程边界：Live artifact 只证明视觉、口径、只读交互和状态表达；实时流、缓存、服务端聚合、权限、性能与安全均为 `ENGINEERING_ASSUMPTION / NOT_VERIFIED`。

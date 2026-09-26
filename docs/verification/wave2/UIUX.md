# 神州 HR V1.9 WAVE-2 UIUX 锁定

## Visual direction

**Register**: product

WAVE-2 延续“精密制造控制台”：设计服务于高密度、低歧义、可追溯的组织与人员作业。用户进入页面是为了完成导入、核对差异、维护版本和追查历史，不是为了浏览品牌叙事。

- 视觉记忆点是持续可见的来源、版本、生效期、数据截至时间和审计入口，不使用装饰性渐变、插画或页面入场动画。
- 使用现有批准品牌蓝承载当前选择、焦点和唯一主操作；同一屏幕最多一个实心主按钮。
- 危险红只用于草稿作废、受控撤销等危险动作；警告、冲突、成功均使用浅色状态表面、配对文字、图标和明确文案。
- 使用 `Tabler Icons`，统一 `stroke=2`；不得混用图标库、emoji 或手绘功能图标。
- 字体、颜色、间距、圆角、阴影、时长和控件尺寸只使用 `frontend/src/styles/design-tokens.css` 中的语义 token。
- 普通模块以边框、分区标题和紧凑留白分层；阴影只用于真实悬浮层、抽屉和弹窗，不把每个区块做成卡片。
- 不出现 PAYROLL、工资、年假、考勤组、班次、餐扣、宽限、缺卡、外部考勤联调或员工自助入口。
- 不出现组织定时同步、手动持续同步、双向同步、同步按钮、同步 route 或同步 job；组织发布后明确标记为“本地权威来源”。

## Authority and implementation boundary

以下文件用于理解信息层级、任务顺序和状态覆盖：

- `design/open-design/v1.9/v19-od-03-people-initial-import.html`
- `design/open-design/v1.9/v19-od-04-organization-employment.html`
- `design/open-design/v1.9/route-to-artifact.json`
- `design/open-design/v1.9/page-state-matrix.md`
- `design/open-design/v1.9/design-system/DESIGN.md`
- `design/open-design/v1.9/react-implementation-notes.md`

它们是历史 Open Design 参考，不是可复制的实现：

- React 只能重构语义、流程和状态，不得复制或嵌入 prototype HTML、CSS、JavaScript。
- 不得使用 `iframe`、`dangerouslySetInnerHTML`、原型状态控制器、`PROTOTYPE_SIMULATION` 或原型内的静态业务数据。
- 原型中的“周年年假试算”超出 WAVE-2 范围，不进入生产页面。
- 正式 dev 模式只使用真实 8080 API；demo 模式使用明显标记的合成数据，并在 API 层短路，不能请求后端或写入 MySQL。
- 页面隐藏不是权限控制。服务端 capability、data scope、版本、幂等和审计结果是所有写交互的事实来源。

## Global page grammar

四个路由共享以下层级：

1. Breadcrumb 和页面标题。
2. 唯一主操作及必要的次操作。
3. 上下文条：授权范围、对象或批次 ID、版本、状态、来源、数据截至时间。
4. 页面专用警示或边界说明。
5. 主工作区。
6. 版本历史和审计入口，或操作成功后的审计关联。

通用规则：

- ID、版本、批次号、时间使用 `--font-mono`；表格正文使用 `--text-sm`。
- 所有字段有常驻 label；placeholder 仅作为输入示例，不能替代 label。
- 所有异步切换先清空旧范围数据，再显示 loading，避免上一组织、员工或批次数据闪现。
- 所有错误只显示当前授权范围内的安全上下文；403 统一为 `ACCESS_DENIED`，不得披露对象是否存在、数量或字段。
- 所有写操作在处理期间保持按钮宽度稳定、禁止重复提交，并在完成后按受影响资源精确刷新。
- 状态不能只靠颜色；必须同时出现文字，并在需要时使用 Tabler 图标或形状。
- 禁用操作旁必须有可读原因，例如“存在 2 条阻断错误，不能发布”。

## Route information architecture

### `/people/import`

目标：完成组织、员工、任职和入职前累计工龄的一次性期初导入，并追踪批次全生命周期。

页头操作：

- “查看历史批次”打开批次列表或抽屉，支持按状态和时间筛选。
- 有未完成批次时显示“继续批次”；没有草稿时不制造空入口。
- 草稿支持“作废草稿”；已发布批次只提供受控撤销或前向更正入口，不提供删除。

六步向导：

1. 下载模板：显示当前模板版本、适用数据范围、四类工作表和字段说明；模板和错误报告均使用真实文件下载。
2. 上传文件：创建批次后支持拖放或选择 `.xlsx`，显示文件名、大小、模板识别、上传进度、取消和重试；浏览器不解析并发布正式数据。
3. 字段匹配：按工作表展示源字段、系统字段、必填性和样例。员工唯一匹配只能使用受控唯一键；姓名和部门不能成为唯一匹配键，多人匹配必须进入错误报告。
4. 预检：显示预检处理中和完成时间，明确“预检不会写入正式数据”；摘要固定覆盖新增、修改、不变、冲突、错误。
5. 差异确认：支持按五类结果筛选、服务端分页查看行级差异和错误码，并下载错误行报告；阻断问题只能通过修正文件或字段映射解决，客户端不得猜测匹配对象。
6. 发布结果：发布前确认范围、组织/员工/任职/工龄记录数、业务日期、原因和可逆性；发布成功显示批次 ID、各类实际数量、时间、审计入口和“查看本地组织”。

批次专用交互：

- Stepper 显示 `complete/current/error/unreachable`，使用文字、图标和 `aria-current="step"`，不能只靠颜色。
- 上传、预检、发布和受控撤销分别是独立 mutation 和 capability。
- 同一文件 hash 或幂等键命中时显示“已存在批次/已发布”的非破坏性结果，并导航到原批次，不重新创建数据。
- 预检有阻断错误时发布按钮禁用并说明原因。
- processing 状态允许安全离开；返回后从服务端恢复批次和任务进度。
- 草稿作废显示批次、文件和影响范围；已发布批次不得出现物理删除。
- 受控撤销先展示下游引用和后续版本检查。409 时仅提供刷新、恢复上一快照的新版本或前向更正，不提供强制覆盖。

### `/people/organization`

目标：将发布后的本地组织作为权威来源进行查询、版本化维护和审计。

页面结构：

- 上下文条显示组织范围、当前节点、数据截至、当前版本和“本地权威来源”。
- 期初批次未发布时显示专用 empty/warning 状态和“继续期初导入”，不显示虚构的可维护组织树。
- 桌面使用组织树与组织详情分栏。树节点显示名称、编码、人数和启停状态；详情显示名称、编码、类型、父级、生效期、状态、版本、来源批次和发布快照引用。
- 具备写 capability 时提供新增、编辑和停用；每次写入要求原因和当前版本，成功后产生新版本与审计。
- 版本历史和审计时间线为真实服务端查询；来源文件及发布快照保持只读。
- 停用不等于删除。被引用或存在后续版本时按服务端 409 显示冲突范围和下一步。

### `/people/employees`

目标：在服务端数据范围内检索员工并进入单个员工档案。

页面结构：

- 搜索员工唯一编号或姓名；筛选组织、任职状态；使用服务端分页和排序。
- 姓名允许作为检索条件，但页面文案不得暗示姓名或部门是唯一匹配键。
- 列表展示员工编号和姓名、当前组织/岗位、最新任职周期、累计工龄、状态、版本和详情操作。
- 筛选结果为空与组织范围无权访问是不同状态。
- 点击详情进入动态路由，并通过 query 或 `return` 上下文保留列表筛选、页码和组织范围。

### `/people/employees/:employeeId`

目标：维护单个员工的本地档案、任职周期、累计工龄、版本和审计。

页面结构：

- 页头持续显示员工唯一编号、状态、当前版本、数据截至和返回列表。
- 概览：本地员工字段、当前组织/岗位、来源批次和发布快照；编辑保存必须创建新版本。
- 任职历史：按日期倒序展示当前、历史和离职空档，统一使用 `[start_date,end_exclusive)`。
- 二次入职：创建新任职周期，不覆盖历史。表单收集开始日、组织、岗位和原因；周期重叠时显示字段级错误或 409。
- 离职：用户输入“业务离职日”，界面明确保存后 `end_exclusive = 业务离职日 + 1`，因此业务离职日仍属于任职周期。
- 空档：任职周期外不显示或猜测员工组织归属。
- 累计工龄：展示可追溯发生额和调整记录，包括增减量、原因、操作者、时间、来源批次或审计关联；调整和重算是两个独立操作。
- 版本与审计：显示版本号、变更原因、操作者、时间和字段变化；历史版本只读。

## Complete state matrix

| 状态 | `/people/import` | `/people/organization` | `/people/employees` | `/people/employees/:employeeId` | 恢复动作 |
|---|---|---|---|---|---|
| normal | 当前批次和可达步骤 | 树、选中节点与详情 | 筛选、列表与分页 | 档案、时间线、工龄、版本 | 继续当前任务 |
| loading | 模板、批次或步骤首次加载 | 授权范围内组织首次加载 | 授权范围内员工首次加载 | 员工档案首次加载 | 无旧范围数据闪现 |
| partial-loading | 保留批次壳层，步骤区刷新 | 保留节点选择，详情清空刷新 | 保留筛选，列表以骨架刷新 | 保留页头，当前 section 刷新 | 不显示上一范围数据 |
| empty | 无草稿或历史批次 | 期初未发布或当前范围无组织 | 当前筛选无员工 | 当前 section 无历史/调整 | 清除筛选、继续导入或返回 |
| error | 模板、上传、预检、下载或发布失败 | 组织查询/写入失败 | 列表查询失败 | 详情/写入/重算失败 | 重试；显示安全 correlation ID |
| network-error | 网络断开但保留安全批次 ID | 不显示缓存的其他范围 | 不显示缓存的其他范围 | 不显示旧员工档案 | 重新连接并重试 |
| 401 | 会话失效 | 会话失效 | 会话失效 | 会话失效 | 返回登录，成功后恢复安全 return |
| 403 | 不泄露批次或文件存在性 | 不泄露节点或人数 | 不泄露员工数量 | 不泄露员工存在性 | 返回首个授权页面 |
| 404 | 模板版本或批次不存在 | 组织节点不存在 | 不适用筛选结果；route 仍存在 | 员工不存在或已不可见 | 返回安全列表或批次历史 |
| 409/conflict | 重复发布、撤销引用、版本冲突 | 乐观锁或引用冲突 | 筛选快照过期时刷新 | 重叠任职、版本或重算冲突 | 刷新后重新核对，禁止覆盖 |
| stale | 批次状态已变化 | 节点版本已变化 | 列表数据截至过旧 | 档案版本已变化 | 明确刷新，旧提交不可继续 |
| processing | 上传、预检、发布、作废或撤销 | 保存或停用中 | 查询中使用 loading，不制造写状态 | 保存、二次入职、调整或重算中 | 可安全离开或禁止重复提交 |
| partial-success | 预检含有效行和错误行 | 批量影响只按服务端结果展示 | 不适用 | 重算有被拒记录时逐项说明 | 显示成功/失败数量及错误报告 |
| success | 发布/作废/幂等命中完成 | 新版本保存并出现审计入口 | 查询结果稳定显示 | 新周期/调整/重算完成 | 显示对象、范围、时间和可逆性 |
| frozen/blocked | 阻断错误或引用禁止发布/撤销 | 被引用版本禁止停用 | 不适用 | 周期重叠或下游引用禁止变更 | 显示阻断原因和允许的下一步 |

页面专用状态必须另外可辨识：

- 导入：文件级失败、模板版本不匹配、重复批次、幂等命中、草稿恢复、预检阻断、发布确认、已发布、已作废、撤销冲突。
- 组织：期初未发布、本地权威来源、组织已停用、版本过期。
- 员工：无筛选结果、员工已停用、版本过期。
- 员工详情：当前任职、历史任职、离职空档、二次入职、周期重叠、业务离职日转换、工龄调整已审计、重算完成。

## Responsive lock

验收视口至少覆盖 `1366×768`、`1440×900`、`1920×1080`、`1024×768`、`768×1024` 和 `390×844`；同时保证 360–430px 不出现页面级横向滚动。

### Desktop

- 左侧栏展开 `--size-sidebar-expanded`，收起 `--size-sidebar-collapsed`；顶栏使用 `--size-topbar`。
- 导入 Stepper 六列；组织页为树与详情分栏；员工列表为高密表格。
- 1366px 下标题、筛选和操作不得重叠；表格仅允许组件内部受控滚动。

### Tablet

- 1024px 及以下使用现有导航 Drawer。
- 组织树与详情改为单列主从结构；复杂筛选进入筛选面板或 Drawer。
- 导入 Stepper 可折为三列两行，但完成、当前和错误语义不丢失。
- 所有触控目标至少 `--size-touch-target`。

### Phone `390×844`

- 页面内边距使用现有 spacing token；支持安全区。
- HR 管理页面继续使用顶栏导航 Drawer，不引入仅适用于员工自助的四项底栏。
- 页面标题和操作纵向排列；唯一主操作占可用宽度。
- 六步 Stepper 改为单列或“当前步骤 + 可访问步骤列表”，不做横向滚动条。
- 组织树先选择节点，再显示全宽详情；不把桌面分栏等比缩小。
- 员工、差异和错误行表格改为字段标签卡片；操作与状态在卡片中保持明确顺序。
- Modal 变为底部或全屏受控层，Drawer 占满宽度；软键盘不得遮住输入和提交操作。
- 断言 `document.documentElement.scrollWidth <= document.documentElement.clientWidth`。

## Keyboard and focus lock

- 页面保留 skip link，目标为 `#main-content`。
- Tab 顺序遵循视觉顺序：breadcrumb → 页头操作 → 上下文 → 主工作区 → 次级历史/审计。
- Stepper 使用原生 button；不可达步骤 `disabled`，当前步骤 `aria-current="step"`，错误步骤有可读名称。
- 拖放区必须包含可聚焦的文件 input 或关联 label；按 Enter/Space 能选择文件，拖放不是唯一入口。
- 步骤切换后焦点移动到新步骤标题；异步完成通过 `aria-live="polite"` 播报，阻断错误通过与字段关联的错误文本表达。
- 字段错误使用 `aria-invalid` 和 `aria-describedby`；错误摘要可跳转到首个错误字段。
- Modal/Drawer 打开后焦点进入容器并形成焦点陷阱，Escape 关闭，关闭后恢复到触发控件。
- 危险确认的默认焦点不能落在危险按钮；发布原因、调整原因和版本冲突刷新均有明确 label。
- 自定义交互必须覆盖 hover、focus-visible、active 和 disabled；焦点使用 `--color-focus`、`--border-width-focus` 和 `--focus-offset`。
- 遵循 `prefers-reduced-motion`；状态变化只使用 `--duration-fast` 或 `--duration-state` 的 opacity/transform，不使用页面加载编排。

## Contrast and token evidence

对比度使用 WCAG 2.x 相对亮度公式从当前 token 十六进制值重新计算。正文要求至少 `4.5:1`，大字和 UI 图形至少 `3:1`。

| Surface token | Paired foreground | 实测对比度 | 结论 |
|---|---|---:|---|
| `--color-canvas` `#F6F9FD` | `--color-on-canvas` `#111827` | `16.80:1` | AA/AAA 正文通过 |
| `--color-surface` `#FFFFFF` | `--color-on-surface` `#1F2937` | `14.68:1` | AA/AAA 正文通过 |
| `--color-surface-subtle` `#EEF2F7` | `--color-on-surface-subtle` `#334155` | `9.21:1` | AA/AAA 正文通过 |
| `--color-surface-inverse` `#1F2937` | `--color-on-surface-inverse` `#FFFFFF` | `14.68:1` | AA/AAA 正文通过 |
| `--color-brand-primary` `#25449A` | `--color-on-brand-primary` `#FFFFFF` | `8.85:1` | AA/AAA 正文通过 |
| `--color-brand-deep` `#00265F` | `--color-on-brand-deep` `#FFFFFF` | `14.56:1` | AA/AAA 正文通过 |
| `--color-brand-support` `#003C95` | `--color-on-brand-support` `#FFFFFF` | `10.09:1` | AA/AAA 正文通过 |
| `--color-danger` `#E60012` | `--color-on-danger` `#FFFFFF` | `4.80:1` | AA 正文通过 |
| `--color-info-soft` `#EAF0FB` | `--color-on-info-soft` `#16346F` | `10.46:1` | AA/AAA 正文通过 |
| `--color-success-soft` `#E8F5EE` | `--color-on-success-soft` `#14532D` | `8.12:1` | AA/AAA 正文通过 |
| `--color-warning-soft` `#FFF4E5` | `--color-on-warning-soft` `#713F12` | `7.98:1` | AA/AAA 正文通过 |
| `--color-danger-soft` `#FDEBEC` | `--color-on-danger-soft` `#8A0010` | `8.75:1` | AA/AAA 正文通过 |
| `--color-focus` `#003C95` | `--color-on-focus` `#FFFFFF` | `10.09:1` | AA UI 与正文通过 |

使用规则：

- 新组件只能选择表中已配对的 surface/foreground，不能在组件内交换前景色。
- 边框不承载正文含义；状态必须有配对文字和图标。
- `--color-text-disabled` 只用于明确禁用控件，并通过控件形态和说明文字补足语义。
- token 审计结论：现有颜色、状态、间距、圆角、时长、触控、抽屉、内容宽度和焦点 token 已覆盖 WAVE-2，本次无需修改 `frontend/src/styles/design-tokens.json` 或 `frontend/src/styles/design-tokens.css`。

## Acceptance checklist

- 四个生产路由均可直接加载并由服务端 capability/data scope 控制。
- 普通 dev 模式所有操作走真实 API；demo 模式的所有 people API 调用均在 API 层短路且 `fetch` 调用数为零。
- 导入完整覆盖六步、五类预检结果、错误报告、阻断发布、幂等命中、草稿作废和受控撤销 409。
- 组织和员工每次本地修改产生新版本与审计，不覆盖导入文件或发布快照。
- 二次入职新增任职周期；离职日属于半开周期；重叠周期被拒；空档不猜测归属。
- 累计工龄每次调整保留原因、操作者、时间和审计关联，并可真实重算。
- loading、empty、error、network-error、401、403、404、409、processing、partial-success、success、stale 和页面专用状态均由真实响应驱动。
- 390×844 无页面级横向溢出，桌面表格转换为字段标签卡片，键盘可完成全部核心任务。
- focus trap、焦点恢复、字段错误关联、live region、44px 触控目标、reduced motion 和上述对比度全部通过验证。
- 用户可见源码、route、菜单、搜索、通知和空状态中没有 PAYROLL 或组织同步发现性。
- React 源码中没有复制的 Open Design HTML、prototype 控制器、mock-only 主流程、placeholder、Lorem 或 TODO。

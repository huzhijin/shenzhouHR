## Context

现有「考勤报表」是单页九 tab，对应导出 workbook 的九个 sheet。查询走 `RealtimeAttendanceReportSnapshotService`：有 pin 时仍把该公司月日/OA/异常/账户事实全部装进内存，再在 Java 里拼表、内存分页；前端最多连翻 50 页。现场 560 人、已钉住的 2026-08：矩阵约 25 秒（目录与报表各打一遍 `month-matrix`），异常 3115 条约 70 秒。加载中表格为全 0 和「暂无记录」，「查询」只做浏览器过滤。

整月核算引擎（`FullCalculationEngineOrchestrator`）不能用 SQL 统计替代，否则与官方 Excel 口径分叉。pin 方案曾规定同步不自动重算、第一次 GET 可物化；现场因此出现「来源已更新」却仍要人点按钮，以及打开页面可能同步等最多 900 秒。

得力与 OA 定时同步均为上海时区 00:00、12:00，两个 Job 独立，SYSTEM 主体。手动 `POST /attendance-reports/recalculate` 需要 `ATTENDANCE_REPORT:REFRESH`。菜单由 `AuthenticationController.menu` 按能力拼出，角色权限可分配能力。

## Goals / Non-Goals

**Goals:**

- **查询秒级**：已 pin 时查询页第一屏 p95 ≤ 1 秒。现场数据量（约 560 人/月）不得再成为慢的理由。
- 新菜单组挂齐九张独立查询页，全部显示；能力可在角色权限中分配。
- 每页细筛全部保留并下推 SQL（部门含子部门、工号、日期、假别、异常类型/级别/状态、加班性质、迟到档、缺卡时段、在职状态、年假余额等）；单据页日期可跨月。
- 无打卡/无 pin 的期间友好提示，不抛错；菜单能力与公司/组织/本人数据范围同时生效。
- 组织工作台与「我的考勤」均默认当月，可选当天或指定月份。
- 得力与 OA 该班次定时同步都成功后，延迟再后台整月核算；查询线程不核算。
- 保留现有考勤报表中心、导出合同、手动「重新计算」。

**Non-Goals:**

- 不按人/日增量补丁替换整月引擎。
- 不删除或拆掉现有 `/attendance/reports` 九 tab。
- 不改得力/OA 的 00:00、12:00 cron 本身。
- 不把关账月纳入自动重算。
- 不按单张报表拆九个能力（一张能力看到全部九页）。
- 不在手工「同步记录」触发的同步后自动重算。
- 不把「我的考勤」做成可查他人的报表；期间切换后仍只显示本人。

## Decisions

### Decision 1: New menu group shows all nine query pages

菜单增加一组「查询报表」，九项全部列出：

| 菜单 | 路径 | 对应 sheet |
|------|------|------------|
| 考勤明细 | `/attendance/queries/matrix` | 月度考勤明细矩阵 |
| 请假统计 | `/attendance/queries/leave` | 请假 |
| 加班统计 | `/attendance/queries/overtime` | 加班 |
| 月度工时 | `/attendance/queries/work-hours` | 个人月度工时 |
| 异常总览 | `/attendance/queries/exceptions` | 考勤异常总览 |
| 迟到统计 | `/attendance/queries/late` | 迟到 |
| 忘打卡 | `/attendance/queries/missed-punch` | 缺卡 |
| 出勤率 | `/attendance/queries/attendance-rate` | 出勤率 |
| 年休假 | `/attendance/queries/annual-leave` | 年假 |

有查询能力则九项都出现，不能只开放其中几张。现有「考勤报表」仍由 `ATTENDANCE_REPORT:READ` 控制。默认入口建议异常或请假，不要默认进矩阵。

备选：九张仍挤在旧中心 tab 里。拒绝，因为无法按页配筛选，且默认矩阵会继续把日常查询拖到 25 秒以上。

### Decision 2: One assignable capability for the whole query menu

新增 `ATTENDANCE_REPORT_QUERY:READ`。角色权限 UI 可授予/收回。获得该能力即看到全部九页并调用对应查询 API。数据范围仍用该角色现有 company/org/self scope，与官方报表相同，不把授权写入 pin。

种子：当前已有 `ATTENDANCE_REPORT:READ` 的角色默认同时获得 `ATTENDANCE_REPORT_QUERY:READ`，避免上线后谁都进不去；之后可在角色权限里单独拿掉查询菜单而保留旧中心，或反过来。

重新计算仍只认 `ATTENDANCE_REPORT:REFRESH`。查询页导出复用 `ATTENDANCE_REPORT:EXPORT_CREATE` / `EXPORT_DOWNLOAD`。

备选：九个 `…:EXCEPTIONS` 能力。拒绝，与「所有报表都需要显示」冲突。备选：复用 `ATTENDANCE_REPORT:READ`。可分配性不足，无法把「只开查询页、不开整本中心」交给现场。

### Decision 3: Query reads pin facts with SQL filters and paging

每个查询页有独立 GET，参数含公司、分页、**公共细筛**和**该页专属细筛**。公共细筛必须始终可提交：公司、部门（含子部门）、工号/员工。部门与员工是筛选条件，不能去掉；只是选项列表改从人员/组织目录加载。

实现：

1. 校验 `ATTENDANCE_REPORT_QUERY:READ` 与公司授权。
2. 读涉及月份的最新合格 pin（当前公式版本）。无 pin → 可重试错误「核算尚未完成」，HTTP 不跑引擎。单据跨月则按期间覆盖的每个月各取该月 pin。
3. 解析当前授权员工/组织集合一次（禁止每行 `EXISTS` 整棵权限树）。
4. 只查该页需要的事实表，`WHERE` 带筛选，`ORDER BY` 稳定键，`LIMIT/OFFSET` 或 keyset。默认页大小 50，上限 200。
5. 前端只请求当前页，禁止为了画表把最多 50 页拉完。

事实表映射：

- 异常 → `attendance_report_exception_fact`
- 请假/加班（单据型）→ `attendance_report_oa_fact`
- 迟到/缺卡/工时/出勤率 → `attendance_report_daily_fact`（工时/出勤率可按员工聚合后分页）
- 年假 → `attendance_report_time_account_fact`
- 矩阵 → 日事实 + 必要 OA/异常，**按员工分页**，不要一次返回 560×31

部门筛选继续含子部门（现有 closure），但在授权集合内做，而不是对每一行跑递归 CTE。人员目录用员工/组织读模型，禁止 `collectMatrixPages`。

专属筛选**全部进 SQL，不得减档、不得改回浏览器过滤**。最低清单：

| 页面 | 必须可筛 |
|------|----------|
| 公共 | 部门（含子部门）、工号 |
| 明细矩阵 | 考勤状态、日期（当月内） |
| 请假 | 起止期间（可跨月）、假别、审批状态 |
| 加班 | 起止期间（可跨月）、加班性质（计薪/调休/义务）、发生日 |
| 工时 | 在职 / 本月入职 / 本月离职 |
| 异常 | 起止期间（可跨月）、异常类型、级别、状态 |
| 迟到 | 次数档、分钟档 |
| 忘打卡 | 上班缺卡 / 下班缺卡 |
| 出勤率 | 出勤类型或低于某比例 |
| 年假 | 余额档、一级/二级部门 |

单据页（请假、加班、异常）期间预设：本月、上月、近三个月、自定义；自定义最长 12 个月。跨月读取各月 pin 的对应事实行，仍然 `WHERE` + `LIMIT`，不把三个月日事实整表装进内存。

每页热查询不得再装载无关事实表。矩阵允许读日+OA+异常，但仍按员工页切片。

### Decision 7: Second-level reads use SQL techniques, not "less data"

560 人一个月不是容量问题。秒级靠读路径，不靠砍筛选：

1. 一次请求只碰该页事实表（年假 13 行不得再 join 全月日事实）。
2. 授权员工/组织集合先解析成半连接，禁止每行 `EXISTS` 角色树和递归 CTE。
3. 复合索引：`(projection_id, organization_id, employee_id, business_date)`，单据再加 `document_type` / `leave_type_code` / `exception_type` / `state`。
4. `WHERE` 消化全部细筛 + `ORDER BY` 稳定键 + `LIMIT/OFFSET`（或 keyset）；`COUNT(*)` 可同条件另查，3115 行也是毫秒级。
5. 响应只含本页列；矩阵禁止一次 2MB/200 人格子。
6. 前端只请求当前页；查询按钮发 API，禁用连点。
7. 验收：请假/异常/迟到带细筛的第一页，在已 pin 的现场库上墙钟 ≤ 1 秒。

备选：为了快而减少筛选项。拒绝。细筛下推后更少行，只会更快。

备选：独立页仍走现在的 `calculator.calculate(reportType, fullSnapshot)`。拒绝，现场已证明异常 16 页 × 4.4 秒。

### Decision 4: Missing source data is a hint, not an error; pin-not-ready is a different hint

现场打卡目前主要是 2026-07 与 2026-08。查询 6 月、9 月或「近三个月」里没有来源的月份时：

| 情况 | 行为 |
|------|------|
| 该月没有已提交得力/OA 水位，也没有 pin | **HTTP 成功**，0 行，提示「该期间暂无打卡或核算数据」 |
| 跨月期间部分月有 pin、部分月没有来源 | 返回有数的月份；列出未计入的月份「2026-06 暂无数据」 |
| 该月已有同步水位但还没有合格 pin | 提示「该月核算尚未完成」，有 `REFRESH` 的人可手动重算；**不 500、不空转 900 秒** |
| 有 pin，细筛后 0 行 | 提示「当前筛选条件下没有记录」（查询完成之后才说，不是加载中） |
| 公司/组织不在授权范围 | 与现网一致：无该公司选项或 403，不透露其他公司有没有数 |

缺数据不是异常。核算未完成也不是 500。只有权限不足、参数非法、引擎失败才走错误信封。

### Decision 4b: GET never calculates; waiting UI must not look empty

官方报表 GET、月矩阵、看板、新查询页：有 pin 则读；无 pin 不在请求线程 `assemble`/`publish`。仅当该月**已有已提交来源水位**但还没有合格 pin 时返回 `ATTENDANCE_REPORT_PIN_NOT_READY`。没有任何来源的月份走 Decision 4 的空结果提示，不走该错误码。

加载中：禁用「查询」「刷新数据」；不把表格改成「暂无记录」和指标全 0；有上一屏则保留。查询按钮必须发请求，不得只滤内存空数组。

这是对 pin 方案「第一次 GET 物化」的刻意反转，避免 900 秒同步等待和 stampedee。

### Decision 5: Auto-recalculate after both scheduled syncs succeed, then a delay

新增协调器，不塞进 `DeliAutoSyncJob` / `OaAutoSyncJob` 的尾部（避免同步日志时长含核算、一边先结束就算半截数）。

```
同一班次（00:00 或 12:00）
  得力定时同步成功  AND  OA 定时同步成功
        │
        ▼
  等待 delay（默认 30 分钟，配置
  shenzhouhr.report.auto-recalculate-delay）
        │
        ▼
  对每个有已提交来源的公司：
    当月 OPEN；若本地日期为 1–2 号则加上个月 OPEN
    水位不新于 pin → 跳过
    关账/冻结 → 跳过
    否则 SYSTEM 调用与手动按钮同一条整月核算，追加 pin
```

任一侧该班次失败或未跑：不算。核算失败：旧 pin 不动，查询仍可读旧数。核算期间 GET 继续返回旧 pin，算完后默认查询切到新版本；带旧 token 的翻页仍 `SNAPSHOT_CHANGED`。

开关 `shenzhouhr.report.auto-recalculate-after-sync`，本客户部署默认 true。手工 `ATTENDANCE_SOURCE:RUN` 不触发。手动「重新计算」保留且同步等待（操作者知情）。

自动任务与手动共享公司月 inflight/公司锁，避免双算。线程池仍有界（现为 2 条 calc 线程），排队公司月，不在查询池里跑。

备选：得力一结束就算、OA 再算。拒绝，一次窗口两次分钟级核算。备选：查询时算。拒绝，用户已明确。

### Decision 6: Keep official report center behavior compatible except GET materialize

旧中心路由、sheet 列、导出编码器保留。旧中心 GET 同样不再请求线程物化（与 Decision 4b 一致），避免两个入口一个快一个卡 15 分钟。旧中心热路径本轮至少：目录与矩阵解绑、加载态不再显示假空表；整页 SQL 化优先保证新查询 API，旧中心可继续走「读 pin 后按类型组装」但必须服务端真分页、前端不连翻全量。若工时允许，旧中心热路径与新查询共用 Mapper。

### Decision 8: Data scope is enforced on every query, not only the menu

`ATTENDANCE_REPORT_QUERY:READ` 只控制菜单和路由。行级数据仍每次解析 `auth_data_scope`：

- COMPANY：该公司授权员工
- ORGANIZATION：该组织及（配置了 include_descendants 时）下级
- SELF：仅本人

部门树、工号下拉、SQL `WHERE` 都必须是「当前范围 ∩ 用户筛选」。选范围外部门或工号视为空结果或非法筛选，不得查出别的组织。多公司只列出 `listAuthorizedCompanies`。工作台继续用 `ATTENDANCE_DASHBOARD:READ` 加同一套数据范围，不能因为开了查询菜单就看到未授权公司。

### Decision 9: Workbench defaults to the current month and lets the user pick the window

`AttendanceDashboardService` 现在用时钟的「今天」过滤异常、近 7 日趋势和今日打卡。改为：

- 默认期间 = 上海时区当月 1 号至当天（或月末）
- 页面可选：当天 / 本月 / 指定月份（至少能选到有 pin 的 7 月、8 月）
- 卡片、趋势、类型分布、组织排行、异常列表都跟所选期间走
- 读 pin 上的 exception/daily 事实做 SQL 聚合，不要为看板再跑整月引擎
- 无数据月份与查询页同一套提示
- 「今日打卡」区块在选「当天」时保留；选本月时可改为当月出勤/异常摘要，或仍附一条「今日打卡」而不挡主数字

「我的考勤」（`ATTENDANCE_SELF:READ`）同样默认本月、可选当天/本月/指定月份。趋势、异常、工时摘要跟所选期间走；选「当天」或当前月时仍可保留「今日班次/打卡」区块。只读本人，不得因选月而看到同事。无数据月份与查询页同一套提示。不跑整月引擎。

## Risks / Trade-offs

- [无 pin 时查询空白直到凌晨任务] → 部署后对当前 OPEN 月跑一次后台核算或由 HR 点按钮；页面写清尚未完成，不转圈到超时。
- [12:30 数字替换，午餐对账被打断] → 核算中仍显示旧 pin；完成后版本变化可提示「已按 xx:xx 同步更新」。delay 可加大。
- [两边同步成功但延迟窗口内又来手工同步] → 只认定时班次汇合；手工同步不重置 delay，避免抖动。
- [SQL 分页与 Java 组装行列不一致] → 查询页与官方 sheet 对同一 pin 做契约测试（抽样行/合计）。
- [授权 IN 列表过大] → 授权组织用闭包表，员工用半连接/临时范围，禁止相关子查询相关每一行。
- [矩阵仍重] → 按员工分页；日常菜单仍展示但不当登录后第一落点。
- [角色未赋新能力] → 迁移默认复制自 `ATTENDANCE_REPORT:READ`。
- [近三个月撞上无打卡的 6 月被当成核算失败] → 无水位且无 pin 记为缺数提示，不触发 PIN_NOT_READY。
- [工作台改当月后变慢] → 与查询页共用 pin 聚合 SQL，禁止再装整月矩阵。

## Migration Plan

1. 发布能力种子、查询 API、九个页面、目录接口、等待态；旧中心仍可开。
2. 打开 auto-recalculate 开关与 delay；不改同步 cron。
3. 当前 OPEN 月若无合格 pin，发布后由任务或 HR 手动补一次，避免查询空窗。
4. 回退：关自动重算开关；隐藏查询菜单（收回能力）；旧中心仍在。已写入的新 pin 不删。

## Open Questions

无阻塞项。delay 默认 30 分钟；若现场要只在 00:00 班次自动算、12:00 仍手动，用配置限制 slot，默认两个班次都算。

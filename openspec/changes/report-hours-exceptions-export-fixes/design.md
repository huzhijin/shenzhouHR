## Context

查询报表（`independent-report-queries-and-auto-recalc`）已经按 pin 做秒级 SQL 分页，但现场五类数字和一类页面合同同时坏掉：

1. OA 单据历史上允许任意分钟。请假小时走 `LeaveHoursRecognizer`（区间 ∩ 班段），加班小时走 `recognizedScheduledMinutes` 的**同一套班段交集**。休息日没有 WORK 班段，加班 `recognized_minutes` 恒为 0；开始/结束在查询页变成 `—`。
2. 计算器加班认定本身是「打卡落在加班单区间里的班外在岗」，但 OA 事实写入报表时没有用这个结果。
3. `AttendanceReportFactProjector` 从 `ResultItem`、rule hit、metric fallback 各写一遍缺卡/迟到/旷工；请假盖住的时段也不抑制。查询页不过 `actionableException`。工作台类型分布按事实行计数，所以「忘记打开 24 次」其实是重复行。
4. 忘打卡查询把 `exception_type` 精确设成 `MISSING_PUNCH`，真实类型是 `MISSING_PUNCH_PENDING` / `OVERDUE` / 上下班侧。
5. 查询页导出只 POST `{reportType, period, companyId}`，后端要完整绑定；导出服务还额外 `require ATTENDANCE_REPORT:READ`。
6. `QueryReportsPage` 详情抽屉对行 JSON `Object.keys` 兜底，`extraFieldTitle` 缺映射就显示英文；`note`/`details` 列被渲染成第二颗「查看详情」。工时备注取 `MAX(shift_label)`，休息日的「无班次」污染整月。

已 pin 月份必须重算后新口径才生效。查询页与官方报表继续共用同一 pin。

## Goals / Non-Goals

**Goals:**

- OA 区间进入计算和报表前按 30 分钟网格向下取整。
- 加班小时 = 取整后加班单上的认可加班，休息日不为 0。
- 异常一人一日一展示类型；假单覆盖时段不再出缺卡/迟到/早退/旷工。
- 忘打卡和工作台按上下班侧去重计次。
- 查询页导出走完整合同，查询角色能导出。
- 查询页表格和抽屉只展示中文业务列，单入口详情。

**Non-Goals:**

- 不改 OA 插件、不在致远表单侧限制只能填整点/半点。
- 不改得力/OA 同步 cron，不改补卡配额和工资。
- 不按人日增量补丁替换整月引擎。
- 不删除旧「考勤报表」中心。
- 不手工 DELETE 生产异常表；以发布新 pin 替换。
- 不把九张查询页拆成九个能力。

## Decisions

### Decision 1: Snap OA endpoints in one helper, then feed both evidence and OA facts

新增纯函数（建议 `OaIntervalGrid.snap(instant)`）：把瞬时换到 `Asia/Shanghai`，分钟 `0–29 → :00`，`30–59 → :30`，秒和纳秒清零；已是 `:00`/`:30` 不变。对 `IntervalEvidence` 和 `projectOaReportFacts` 的 start/end **都**先 snap 再算小时。

备选：只改报表展示、计算仍用原始分钟。拒绝，因为用户要「统计与计算」同一套。

备选：四舍五入到最近 30 分钟。拒绝，现场规则是向下取整。

Snap 后 `start >= end` 时认可分钟为 0，单据行仍保留，时刻显示取整后的钟点。

### Decision 2: Overtime recognized minutes reuse the calculator result, not shift overlap

`FullCalculationEngineOrchestrator.recognizedScheduledMinutes` 对 `OVERTIME` 不再走「∩ WORK 班段」。加班单的 `recognized_minutes` 改为该员工、该加班证据在当月日结果里 `RECOGNIZED_OVERTIME` / 分类加班分钟之和（按加班类型计薪/调休/义务）。查询页 开始/结束用 snap 后的 OA 区间；小时用这份认可分钟。

日事实里的 `paidOvertimeMinutes` 等仍由 `DeterministicAttendanceCalculator` 写入；计算器入参的加班证据区间改为 snap 后的区间，这样休息日加班只要打卡落在单内就会 > 0。周末午餐 12:00–13:00、晚餐门槛保持现有规则。

备选：报表层把 OA 区间长度直接当加班小时、不看打卡。拒绝，虚假加班和未打卡加班会被算进去。

### Decision 3: Exception uniqueness at projection time; leave suppresses punch slots

在 `AttendanceReportFactProjector.exceptionFacts`：

- 先按展示类型折叠（`MISSING_PUNCH_*` → 看首末卡分成 `MISSING_ON_DUTY` / `MISSING_OFF_DUTY`，同一侧只留一行）。
- 有计罚迟到才留 `LATE`；有早退才留 `EARLY_DEPARTURE`；`ABSENCE` 不与双侧缺卡并存。
- 当天 snap 后的有效请假/调休/外出/出差/免打卡若盖住上午窗，丢掉上班缺卡和迟到；盖住下午窗，丢掉下班缺卡和早退；盖住全天，丢掉旷工和双侧缺卡。
- 去掉会再造一行缺卡的 metric fallback，或让 fallback 与已有 `MISSING_*` 前缀互斥且不再额外插入。

查询页异常列表不再原样倾倒非 actionable 类型。忘打卡筛选改为 `exception_type IN` 缺卡家族或已投影的上下班侧，结果再按 `(employeeId, date, side)` 去重。

工作台类型分布按去重后的事实计数，不再 `COUNT(*)` 原始行。

### Decision 4: Cleanup is republish, not a SQL delete script

异常重复在旧 pin 里。修复投影后，现有「重新计算」和同步后自动重算会写新 pin。查询只读最新合格 pin，旧重复行不再出现。不提供单独的 `DELETE FROM attendance_report_exception_fact` 运维脚本。

### Decision 5: Query export reuses the official encoder with query binding

前端 `QueryReportsPage` 用最近一次查询响应里的 `projectionVersion`、`dataAsOf` 对应的 fingerprint（查询接口补齐 `queryFingerprint` 与 `scopeReference`）、当前筛选和中文 `purpose` 调用现有 `POST /api/v1/attendance-reports/exports`，再轮询下载。失败留在页内提示。

后端 `AttendanceReportExportService.createAuthorized`：持有 `ATTENDANCE_REPORT_QUERY:READ` + `EXPORT_CREATE` 且正在导出查询页可见的 sheet 时，不再强制 `ATTENDANCE_REPORT:READ`。数据范围仍按主体解析。下载仍要 `EXPORT_DOWNLOAD`。

备选：查询页另做一套 CSV。拒绝，和官方 xlsx 会分叉。

### Decision 6: Query UI is a field whitelist

`sheetColumns` 即抽屉白名单。`detailEntries` 只遍历这些列，删除 `Object.keys(row)` 兜底。`renderCell` 只有 `action` 列渲染按钮；`note`/`details` 当文本。

月度工时列补上中文「年假小时」「调休小时」；备注改为入职/离职（或空），SQL 不再 `MAX(shift_label) AS employmentNote`。迟到列补「迟到分钟」「计罚迟到分钟」，去掉空的「说明」或固定为 `—`。

### Decision 7: Work-hours leave split

`leaveHours`（请假小时）= 事假+病假+其他，不含年假和调休。年假、调休单独列。与官方工时公式 `应出勤 + 计薪加班 − (事假+病假+其他) − 年假 + 加班换调休 − 实际调休` 一致。查询页 `actualHours` 改为同一公式，不再只是 `SUM(actual_work_minutes)`。

## Risks / Trade-offs

[Risk] Snap 后短单变成 0 小时，现场可能觉得「单子丢了」。→ 行仍在，时刻显示取整后的整点/半点，小时为 0；不在 OA 侧拦单。

[Risk] 加班改用打卡∩单据后，只交单不打卡的休息日加班仍为 0。→ 与现行「认可加班看在岗」一致；在加班详情里可看到单据时刻但核算为 0。若现场要「有单就算」，另开变更。

[Risk] 异常去重改变工作台数字，业务会觉得「次数变少了」。→ 这正是要修的虚高；发布说明写明按人日侧计次。

[Risk] 导出不再要 `ATTENDANCE_REPORT:READ`，查询角色能出 xlsx。→ 仍要 `EXPORT_CREATE`/`DOWNLOAD` 和数据范围；与已签字的查询页设计一致。

[Risk] 旧 pin 在重算前仍是重复异常和 0 加班。→ 部署后对 OPEN 月跑一次重新计算；关账月需按现有重开规则。

## Migration Plan

1. 部署代码（计算、投影、查询、导出、前端）。
2. 对当前 OPEN 的公司月执行「重新计算」（或等同步后自动重算）。
3. 抽查：休息日加班小时、一人一日异常不重复、忘打卡次数、查询页导出 xlsx、工时抽屉无英文。
4. 回滚：回退制品后旧 pin 仍可读；新 pin 与旧公式版本并存时查询继续按当前 catalog 取最新合格 pin。

## Open Questions

- 现场若坚持「只交加班单、休息日不打卡也要算加班小时」，本设计不算，需要产品确认后再改 Decision 2。
- 关账月是否在本变更内强制重开重算，默认否，由 HR 按现有封账规则处理。

## Context

查询报表 `QueryReportsPage` 的「期间」同时有月份选择器和起止日期；请求仍带 `period=YYYY-MM` 与 `fromDate`/`toDate`。后端 `dateWindow()` 已优先用起止日期，最长 12 个月。年假统计 / 调休统计用自然年。考勤明细前端禁止跨月，抽屉按单月铺日历。

婚假在 `LeaveType.includesWeekendHours()` 里与产假等一起含周末。`LeaveHoursRecognizer` 对休息日套工作日班段，请假统计小时比 OA 工作日天数大。矩阵休息日也会涂成婚假。该口径写在已完成变更 `attendance-report-overnight-hours` 的 `leave-hours-by-type` 里，本次是明确反转婚假一条。

查询导出 `GET /attendance-report-queries/{sheet}/export` 一律 `page=0, size=50000`。画面分页写死 50、不能改页大小。考勤明细导出已是签到/签退日历表。考勤报表中心 `/attendance/reports` 走另一套导出任务，本次不得改。

## Goals / Non-Goals

**Goals:**

- 查询报表期间只保留起止日期；年假/调休统计保留年份。
- 考勤明细继续禁止跨月，筛选不再依赖「月份」控件。
- 婚假按考勤日历去掉周末与节假日小时，休息日格子不涂婚假。
- 查询报表可调页大小，导出可选全部或当前页；明细导出保持日历表。

**Non-Goals:**

- 不改考勤报表中心「导出当前报表」的按钮、任务、xlsx 与列。
- 不放开考勤明细跨月查询或抽屉多月 Tab（只保证筛选文案以后能加 Tab）。
- 不改产假、陪产假、丧假、病假、计生假等假别的周末口径。
- 不自动重开关账月；不在查询 GET 里重算。
- 不把抽屉、改打卡、详情列写入 Excel。

## Decisions

### 1. 查询期间：起止日期是唯一控件，月份只是派生值

普通查询表（含考勤明细、月度工时、出勤率）去掉 `DatePicker picker="month"`。只保留 RangePicker，预设本月 / 上月 / 近三个月。`defaultQueryPeriod` 的 1–7 日默认上月逻辑保留，写成默认 `fromDate`/`toDate`。

请求仍可带 `period`（取开始日所在月）给 directory 与旧客户端，但 UI 不再展示月份。年假统计 / 调休统计继续年份选择，`sheetQueryRange` 仍是该年 1 月 1 日～12 月 31 日。

考勤明细：起止日期必须落在同一自然月，否则 Modal「暂不支持跨月」，文案不出现「请先选月份」。抽屉 `MatrixMonthView` 的月份取筛选区间的那一个月（`fromDate` 的 `YYYY-MM`）。

Alternative: 连 `period` 查询参数也删掉。Rejected for this change; directory 仍按月，保留派生 `period` 避免目录接口并行改造。

### 2. 婚假加入「不含周末」集合，展示与小时同一判断

`LeaveType.includesWeekendHours()` 对 `MARRIAGE` 返回 false（与 `ANNUAL`、`COMPENSATORY`、`PERSONAL`、`BREASTFEEDING` 相同）。休息日判定继续用考勤日历 `SATURDAY` / `SUNDAY` / `PUBLIC_HOLIDAY` / `WEEKEND`；`ADJUSTED_WORKDAY` 计婚假。

日事实投影或矩阵组装：当假别 `!includesWeekendHours()` 且当天是休息日/节假日时，不把该天标成该假别（婚假周末显示休息日/节假日，不涂婚假色）。小时已由 `LeaveHoursRecognizer` 跳过。

其它日历假（产假等）仍含周末。已有测试 `marriageLeaveFridayThroughMondayIncludesWeekend` 改为排除周末。

Alternative: 只改请假统计展示、不改识别分钟。Rejected; OA 天数、请假汇总、月度工时、日报必须同一数字。

### 3. 查询导出范围用显式参数，默认全部

`GET .../{sheet}/export` 增加 `exportScope=ALL|PAGE`，缺省 `ALL`（与现网全量一致）。

- `ALL`：`page=0, size=MAX_EXPORT_ROWS`（50000）
- `PAGE`：使用请求的 `page` 与 `size`（与列表相同，50/100/200）

Excel 列与当前表格可见列对齐，去掉 `action` / `adjust`。考勤明细、每日加班继续走现有 `writeMatrix` / `writeFinanceOvertime`（日历表 / 宽表），范围只限制**人/行**，不改格子形态。

前端导出改成下拉：导出全部 / 导出当前页。分页 `showSizeChanger`，选项 50、100、200；查询带上所选 `size`。

不得把查询导出改接到报表中心的 `AttendanceReportExportService`。

Alternative: 前端截 `result.rows` 当当前页。Rejected; 演示模式才是当前页，生产必须服务端按 page/size 取数，否则筛选与授权会漂。

### 4. 报表中心导出隔离

`CustomerReportCenterPage.handleExport`、`AttendanceReportExportController`、`XlsxAttendanceReportExportEncoder`、导出 worker 本变更零 diff。婚假重算后中心页数字会变，那是计算层，不是导出逻辑。

## Risks / Trade-offs

- [Risk] 已 pin 月份婚假小时仍含周末 → Mitigation: 任务写明 OPEN 月须手动或既有自动重算；查询 GET 不重算。
- [Risk] 矩阵日事实仍带周末婚假 leaveType → Mitigation: 组装/投影与 `includesWeekendHours` 同一判断，休息日回退日类型。
- [Risk] 导出当前页被理解成「当前抽屉日历」→ Mitigation: 文案与 spec 写明是列表当前页的人；每人仍出日历行。
- [Risk] 去掉月份后 directory 月份与跨月请假区间不一致 → Mitigation: directory 用开始日所在月；请假跨月本来就按 from/to 读多个 pin。
- [Risk] 误改报表中心导出 → Mitigation: 验收包含中心页导出回归；禁止改那条链路的文件。

## Migration Plan

1. 先合查询 UI 与导出参数（行为对旧客户端默认 ALL 兼容）。
2. 再合婚假规则；对仍 OPEN 的公司月执行既有「重新计算」。
3. 关账月保持旧 pin；需要新数字时由有权限的人重开并重算。
4. 回滚：恢复 `includesWeekendHours` 与查询页月份控件；导出缺省 ALL 可单独回滚 PAGE 分支。

## Open Questions

- 无。跨月明细、其它假别、报表中心导出均已排除。

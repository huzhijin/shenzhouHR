## Context

现场 2026-08 报表已经能出数，但人事清单里的八项对不上代码行为：报表中心导出走 Wave7 绑定下载且校验过严；月矩阵用正午切下午卡、POINT 补签被 `endExclusive == null` 丢掉；查询菜单白名单没跟上后端新 sheet；异常总览只按 pin 里已批准请假隐藏。哺乳假按整段 OA 区间交班段，跨日单会变成全天假。

约束：保留 pin + 重算模型；不改得力/OA cron；关账月不自动重开；Wave7 旧 `ReportsPage` 不改成客户样表。

## Goals / Non-Goals

**Goals:**

- 报表中心每个可见 tab 能导出当前筛选 xlsx。
- 补签只出补签标志；普通 8:30 仍迟到。
- 纸质加班单退出菜单。
- 每日加班出现在查询菜单，加班费/转调休列与日历分色与报表中心对齐。
- 哺乳假按开始时刻+1h、每个工作日切片。
- 调休日历表与每日加班同构。
- 首末卡不同则末卡为下班（含 12:00 前）。
- 异常总览按「已入库且能盖住该类异常的 OA 单」隐藏；批准后重算删除事实。
- 上海缺卡与 OA 请假缺口走现有导入/对账。

**Non-Goals:**

- 不改工资计算、补卡配额、OA 表单填写。
- 不把审批中的请假算进工时/出勤（只从异常总览隐藏）。
- 不把义务加班写入加班费或转调休列。
- 不自动重开关账月。
- 不删除纸质加班 API。

## Decisions

### 1. 报表中心导出走「服务端优先、屏幕回退」

`exportCustomerReport` 继续 POST `/attendance-reports/exports`。下载侧 `blob.type` 只要求 **starts with** xlsx media type（忽略 charset）。`isSafeXlsxFileName` / ZIP `PK` 保留。

若创建/轮询/下载失败，且页面已有 `report`，调用现有 `downloadCustomerReportWorkbook`。反馈文案区分「LIVE 快照」与「当前屏幕」。

备选：查询页 GET `/attendance-report-queries/{sheet}/export` 统一所有 tab。否决，月矩阵与查询 sheet 列不完全同构；先修现有合同。

### 2. 补签 POINT 进入矩阵，迟到让路

`AttendanceMonthMatrixAssembler` 对 `PUNCH_CORRECTION` 不再要求 `endExclusive != null`。用 `start` 落在上午/下午（`!start.isAfter(MORNING_END)` → 上午）。`composeSlot`：该侧 `correction == true` 时禁止走 `迟到` 分支。`markLateFromWorkWindows` 在该侧已补签时跳过。

普通打卡 `>=` 班次开始仍迟到（沿用 `matrix-late-ot-hours-and-export`）。

备选：只改前端文案。否决，迟到统计和徽章仍会把补签 8:30 当迟到。

### 3. 菜单：白名单补齐，纸质加班从登录菜单拿掉

`routeAuthorization.capabilitiesByPath` 增加：

- `/attendance/queries/leave-summary`
- `/attendance/queries/overtime-daily`
- `/attendance/queries/finance-overtime`
- `/attendance/queries/daily-journal`
- `/attendance/queries/makeup`
- `/attendance/queries/time-off-daily`（新）

`AuthenticationController` 不再 `menu.add` 纸质加班单；前端白名单可保留以免直链被 `authorizedMenu` 误伤书签（直链走路由权限，不靠菜单）。

### 4. 加班费 / 转调休用日事实已有分钟

`attendance_report_daily_fact.paid_overtime_minutes` / `compensatory_overtime_minutes` 已按 OA 加班类别写入。查询 `overtimeDailyRow`、`finance-overtime` 汇总与报表中心每日加班在「节假日加班」后加两列小时。日历 `dayType` 之外增加 treatment（paid / compensatory / voluntary）决定 fill。多种类同一天：主色取分钟最多的一类，hover 列出全部。

筛选 `overtimeTreatment` 接到这两列，而不是只扫 OA 单据 `leave_type_code`。

### 5. 哺乳假在转换层按日切片

`OaDocumentConverter`（或紧挨着的 leave expander）：`BREASTFEEDING_TIME` 不产出跨日大区间。对 `[startDate, endDate]` 每个工作日（日历 SATURDAY/SUNDAY/PUBLIC_HOLIDAY 跳过）产出 `[clock, clock+1h]`，`clock` 为单据 `start` 的上海墙上时刻。`LeaveHoursRecognizer` 仍做「切片 ∩ WORK 段」。矩阵用切片而不是原始跨日 `start/endExclusive` 涂格。

产检 (`PRENATAL_EXAM_TIME`) 保持真实区间。`PRENATAL_NURSING.includesWeekendHours()` 不用于哺乳假切片。

### 6. 调休日报新 sheet，不改额度页

新 `time-off-daily`：从日事实 `leave_type in (TIME_OFF, COMPENSATORY)` 或 OA `TIME_OFF` 按日小时列出。菜单与 `QueryReportsPage.sheets` 增加一项。额度页 `time-off` 不动。

### 7. 显示层废止「下午卡必须过正午」

`DayAccumulator.afternoonPunchAt`：若 `first != last`，返回 `last`，**不要** `morningInstant(last) → null`。`composeSlot` 删除「有加班单且下午卡 null → 漏刷」在 last 存在时的路径。日报 `journalRemark` 的 `samePunch` 仍表示真的只有一张卡。

工作日正午切只保留在 `selectDayClocks` 的工作日分支（避免午休中间卡当下班）。休息日已是 min/max，显示层必须跟。

### 8. 异常总览两层：查询隐藏 + 重算清事实

**查询层（立即）：** `hideApprovedOaCoveredExceptions` 改为 JOIN `oa_attendance_document`（或投影 OA 事实扩展状态），状态 ∈ `{UNKNOWN, DRAFT, APPROVED, MODIFIED, SUPPLEMENTED}`，类型按覆盖表。补签按 POINT 落日 + 上下午侧。加班单仅抑制休息日 `MISSING_OFF_*`。

**核算层（批准后）：** 现有 `effectiveCandidate` 仍只对已生效单。`AttendanceReportFactProjector.collapseExceptions` / slot coverage 把补签侧、请假/调休/外出/出差/免打卡覆盖进去。批准后自动重算（已有 OA success → slot）写出不含这些异常的新 pin。

**同步层：** 请假/补签/外出等在 `UNKNOWN` 时已经入库（`effective=false`）。确认补签/外出同样写入；若 overtime 审批中未入库，补抓或接受加班单要到批准才藏。

审批中**不**计入请假小时、出勤、哺乳假切片。只从待办清单消失。

### 9. 上海与 OA 缺口是运维任务，不是新同步协议

用 `/sources/attendance-excel` 导上海月报；用 `outputs/check-oa-szoa-vs-hr.sh` 对请假。未映射 `showvalue` 进隔离日志，补目录后再 sync。导入/对账后对该公司 OPEN 月重算。

## Risks / Trade-offs

- [导出回退与 LIVE 不一致] → 文案标明「当前屏幕」；主路径仍修服务端绑定。
- [审批中请假藏异常但工时仍漏刷] → 有意：总览是待办，结果表仍等批准。矩阵漏刷可能仍在，直到批准重算。
- [哺乳假切片与 OA 填写的结束时刻不一致] → 结束时刻只用来定结束日；每天时长固定 1h。
- [多种加班同一天主色武断] → hover 列全部分钟；列合计仍按 paid/compensatory 分开。
- [上海无文件则无法凭空出卡] → 对账先证明缺的是 Deli 绑定还是从未导入。

## Migration Plan

1. 发前端菜单/导出/显示与后端矩阵/查询隐藏（无需迁库）。
2. 哺乳假切片与异常核算变更后，对 OPEN 月点「重新计算」。
3. 上海：导入 → 重算；OA 请假：对账 → sync → 重算。
4. 回滚：回退应用即可；pin 保留旧事实，可用旧包再算。

## Open Questions

- 同一天同时有加班费和转调休时，日历主色取分钟较多的一类（已定，见决策 4）。
- 审批中加班单若从未入库，总览在批准前仍可能显示休息日下班漏刷。接受，除非同步层补抓审批中加班。

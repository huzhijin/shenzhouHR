## Context

月矩阵已经按上下两段组装（`AttendanceMonthMatrixAssembler` + 前端 slot）。现场仍错在计算比较符、休息日取卡、加班色条件和悬停小时窗口；Excel 仍一人一行整格取下午色，迟到+加班会互相盖掉。客户样表 `工作簿11111.xlsx` 是一人两行（签到/签退），半天两格不同色。上一轮 `month-matrix-excel-cell-display` 明确不改成两行物理行；本次只把**导出**改成样表两行，页面保持一格两段。

## Goals / Non-Goals

**Goals:**

- 到达时刻 `>=` 班次开始即迟到；`08:29:59` 准时，`08:30:00` 迟到。格子看迟到事件，不看计罚分钟。
- 休息日 `firstPunchAt`/`lastPunchAt` 为当天窗口最早/最晚，半天加班两头都在格子上。
- 生效 OA 加班单即加班色；按段上色，迟到段不被加班绿覆盖。
- 悬停半天小时 = 已发布 WORK ∩ OA，主表不写小时数字。
- 考勤明细 Excel 每人签到/签退两行，半格各一色。

**Non-Goals:**

- 不改页面为每人两行，不加签到/签退列。
- 不把 `年假4.5` 写进主表或导出格子。
- 不改得力同步、补卡配额、工资、OA 插件、Wave7 `ReportsPage`。
- 不改查询页其它 sheet 的导出合同。
- 不自动重开关账月。

## Decisions

### 1. 迟到比较改为「不早于开始」

`DeterministicAttendanceCalculator.addLateWithoutAbsence` / `addLateOrConvertToAbsence` 今天用 `isAfter(start)`，`08:30:00` 整点不会进迟到。改为 `!isBefore(start)`（即 `>=`）。

恰好准点：raw 分钟为 0，不消费宽限，仍产出迟到事件（rule hit 或日事实标记），供矩阵和迟到统计使用。矩阵 `DayAccumulator.late` 改为看 raw 迟到事件（`lateMinutes > 0` **或** 上班卡 `>=` 班次开始），禁止只认 `penalizedLateMinutes > 0`。

备选：只改展示层把 `08:30` 涂迟到。否决，迟到统计、异常总览会继续把整点当准时。

### 2. 休息日取卡与工作日正午切分开

`selectDayClocks` 今天：午前最早 + 午后最晚。下午 15:00–18:00 只剩 18:00。

休息日（`DayType` 为 SATURDAY/SUNDAY/PUBLIC_HOLIDAY）：对该日证据窗口内全部卡取 min/max，写入 `firstPunchAt`/`lastPunchAt`。组装器两段分别放这两张卡，即使两张都在下午。

工作日保持正午切，避免午休中间卡变成下班卡。

多于两张休息日卡：格子首尾，中间只进悬停。

备选：休息日也按 12:00 切但下午取最早+最晚（四段）。否决，格子仍只有两行。

### 3. 加班色只看生效加班单，并且按段

`overtimeTone()`：`overtimeDocument == true` 即可，休息日与工作日相同。取消 `recognizedOvertimeMinutes > 0`。无单的晚走仍不绿。

`composeSlot` 优先级不变：假别 > 漏刷 > 补签 > 迟到/早退 > 时间。只有落到「时间」那一档且当天 `overtimeDocument` 时才给 `RECOGNIZED_OVERTIME` tone。上午已是 `LATE` 则保持迟到色。

`formulaVersion` 升到 `ATTENDANCE_MONTH_MATRIX_V3`（取卡和 tone 规则变了）。

备选：继续要求认可分钟。否决，现场有单仍白，与「有加班就显示」不符。

### 4. 悬停小时走班次工作段

组装器不要再用硬编码 `13:00–18:00` 算 `afternoonLeaveMinutes`。把该员工当天已发布 WORK 段（与 `LeaveHoursRecognizer` 同一来源：日事实/快照里的班次，或组装时能拿到的 segment 列表）与 OA 区间相交。没有 WORK 段时悬停不编造 4.5。

主表 `text` 仍只是假名。覆盖哪一段仍可用现有 08:30–12:00 / 13:30–18:00 窗口判断上午还是下午，但**小时**必须按真实段长。

组装器今天只有 `ReportSourceSnapshot`，没有 shift segment 列表。实现时二选一，优先 1：

1. 快照增加当天 WORK 段起止（最小字段），组装器相交。
2. 日事实已有 `leaveOrTimeOffMinutes` 时，半天悬停用 OA∩默认窗口但按 snapshot 里该员工的 shift label 不够准。否决 2。

若快照扩段成本高：在 `RealtimeAttendanceReportSnapshotService` / source repository 把每人每天 morning/afternoon WORK 分钟或起止带进 snapshot。不把地点时钟写死在组装器。

### 5. Excel 签到/签退两行，页面不动

`customerReportWorkbook` 考勤明细：

```
姓名 | 部门 | 职位 | 签到 | 01/三 | …
                 | 签退 |      | …
```

身份列竖向合并。每个日期两格：`primary`/`primaryStatus` 在签到行，`secondary`/`secondaryStatus` 在签退行，各自 `fill`。全天年假两格都写「年假」。禁止 `secondaryStatus ?? primaryStatus` 整格一色。

页面 `AttendanceMatrixRow` 仍一 `td` 两段。

导出列：现有工号/姓名/部门即可；职位列若当前 sheet 没有，不新造，对齐现有明细列后再加签到/签退列。

### 6. 迟到统计

迟到查询对 `lateMinutes == 0` 但迟到事件为真的日子计数。需要日事实能表达「迟到事件」。最小改法：`lateMinutes` 在整点迟到时仍为 0，另用已有 exception LATE 或新增 `lateEvents` 已在月度汇总里。日事实若只有 minutes，则投影 `lateMinutes = max(raw, arrival>=start ? 0 : 0)` 不够。

采用：DailyFact 继续存 raw `lateMinutes`；投影增加「上班卡不早于班次开始」时 `lateMinutes` 至少记 0 但 exception LATE 仍发出。更干净：assembler 用 `firstPunchAt >= morning WORK start` 判断格子迟到，计算器仍发 LATE rule hit（included=0）。迟到统计：`lateMinutes > 0` **或** 存在 LATE 异常/rule。若现网迟到统计只 sum minutes，则整点迟到次数 +1、分钟 0。

## Risks / Trade-offs

[Risk] 整点迟到 raw=0，旧投影 `penalizedLateMinutes==0` 会把 LATE 异常压掉。
→ Mitigation: 矩阵迟到改看 raw 事件或打卡相对班次开始；补测试 `08:30:00`。

[Risk] 工作日若误走休息日 min/max，午休卡会当下班卡。
→ Mitigation: 仅 `DayType` 休息日走 min/max；工作日正午切回归测试保留。

[Risk] 快照无 WORK 段时悬停又回到 5 小时。
→ Mitigation: 组装器拿不到段则不写小时数字，禁止回退到 13:00–18:00。

[Risk] Excel 行数翻倍，大月导出变慢。
→ Mitigation: 只改考勤明细 sheet；保持流式写。样表本身就是两行。

[Risk] OPEN 月不重算则取卡和迟到事件仍旧。
→ Mitigation: 发布说明要求重新计算；关账月不动。

## Migration Plan

1. 先改计算比较符、休息日取卡、组装器 tone/小时、测试。
2. 再改 Excel 两行导出和前端测试。
3. 部署后 HR 对 OPEN 月点重新计算。
4. 回滚：恢复比较符与导出一行；已重算月份再算一次。

## Open Questions

无。主表不写年假小时已确认。职位列：当前明细没有则不加。

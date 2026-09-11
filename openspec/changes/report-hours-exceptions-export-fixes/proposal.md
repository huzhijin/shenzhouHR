## Why

现场查询报表和官方报表已经能出数，但口径和页面合同对不上现场单据。OA 请假/加班单历史上允许任意分钟，核算却按班段整点窗口去交，导致加班时长经常是 0、工时和请假小时偏掉；异常事实按计算项堆叠，忘打卡次数被重复行放大；查询页导出请求不完整，详情抽屉把内部英文字段和重复的「查看详情」摊给业务。需要一次把单据取整、加班认定、异常去重、导出和查询页展示收口，否则现场只能靠手工对账。

## What Changes

- OA 请假、加班、调休、外出、出差等区间单据：开始和结束时刻分别落到 30 分钟网格后再参与统计与计算。分钟 `< 30` 落到整点，`30 ≤ 分钟 < 60` 落到半点；已经是 `:00` 或 `:30` 的保持现有逻辑。
- 加班报表的「加班时长 / 核算加班」按取整后的 OA 加班区间认定（扣午餐/晚餐规则仍按现有加班逻辑），禁止再用「加班区间 ∩ 当天上班班段」把休息日加班算成 0。
- 月度工时的请假、加班、实际工时与上述取整后的单据和加班认定对齐；备注不再用班次标签 `MAX(shift_label)` 冒充。
- 异常总览按人+日+类型（缺卡再分上下班）去重；请假已盖住的时段不再同时出缺卡/迟到/早退/旷工。已 pin 月份通过重算换新事实，不再保留重复行。
- 忘打卡次数与工作台「忘记打开」类计数改为按人日、按上下班侧计次：全天无卡且无替代单据计 2，单边计 1；被请假/免打卡盖住的一侧不计。
- 查询报表「导出」按当前 pin、当前页筛选走完整导出合同，有 `ATTENDANCE_REPORT:EXPORT_CREATE` 即可，不再因缺官方报表 `READ` 或请求体不完整变成「当前功能暂不可用」。
- 查询页表格只留一列操作入口；详情抽屉只展示该页中文业务列，禁止把 `annualLeaveHours`、`lateMinutes` 这类内部字段漏到页面。

## Capabilities

### New Capabilities

- `oa-interval-half-hour-grid`: OA 单据起止时刻按 30 分钟网格取整后再进入计算和报表小时。
- `overtime-hours-from-oa-interval`: 加班报表与工时中的加班小时来自取整后的加班单区间（含休息日），不是与上班班段的交集。
- `exception-dedupe-and-leave-suppression`: 异常事实一人一日一类型；假单覆盖时段抑制缺卡/迟到/早退/旷工；重算清理重复行。
- `missed-punch-count`: 忘打卡与工作台缺卡次数按上下班侧、去重后计次。
- `query-report-export`: 查询页导出绑定当前 pin 与筛选，合同完整且权限与查询角色匹配。
- `query-report-display`: 查询页列与详情抽屉的中文展示合同，去掉重复操作列和英文残留字段。

### Modified Capabilities

- （主规格库 `openspec/specs/` 尚无已归档能力。本变更以 delta 覆盖查询页、加班认定和异常投影的现场行为。）

## Impact

- 计算：`OaDocumentConverter` / `LeaveHoursRecognizer` / `FullCalculationEngineOrchestrator.recognizedScheduledMinutes`、`DeterministicAttendanceCalculator` 加班认定入参使用取整后的 OA 区间。
- 投影：`AttendanceReportFactProjector` 异常去重与假单抑制；日事实加班分钟、OA `recognized_minutes`、`shift_label` 不再写入工时备注。
- 查询：`AttendanceReportQueryPageService`、加班/异常/忘打卡/工时 Mapper 与筛选；忘打卡不再精确匹配 `MISSING_PUNCH` 而丢掉 `MISSING_PUNCH_*`。
- 导出：查询页前端补齐导出绑定；后端允许查询能力 + `EXPORT_CREATE` 创建导出，不必同时持有 `ATTENDANCE_REPORT:READ`。
- 前端：`QueryReportsPage` 列定义、单元格按钮、详情抽屉字段白名单。
- 数据：已 pin 月份需 HR「重新计算」后新口径生效；异常重复行随新 pin 替换，不另做手工清表。
- 不改得力同步 cron、OA 表单填写限制、补卡配额、工资计算。

## Why

现网考勤报表中心导出仍失败或看起来像演示文件；次日 07:xx 早卡被当成前一天下班，两天格子互相吃卡；每日加班的加班费/转调休筛选不改变可见列；OA 加班结束晚于末卡仍按填单出满小时且无异常；李尹同一加班区间出现两条；班后 1 分钟也出「未报加班」；上海两家多数人只有漏刷。人事已确认：两天各自显示自己的卡、18:30 后无单才出未报加班、单超出打卡要异常并截到末卡、跨夜小时跟开始日（含周末节假日）、加班单据按人+时段去重。

## What Changes

- 报表中心「导出当前报表」必须下载当前可见报表的 xlsx。成功文案不得再写「演示文件已导出」。服务端失败时回退到当前屏幕工作簿，失败原因要可见。月矩阵按矩阵导出，不得绑到旧的 `ATTENDANCE_DETAIL` 投影白名单上导致整表失败。
- 过夜离开与次日早卡拆开，**两天都显示**：`[次日 00:00, 06:00)` 的卡记前一天末卡（`次日 HH:mm`）；`[06:00, 次日上班)` 的卡记次日上班。前一天若晚上没卡就显示下班漏刷，不得把次日 07:xx 填进去。金玉亮类 `次日 00:14` 仍归前一天。
- 每日加班（报表中心与查询报表）筛选「加班费」只显示加班费列、隐藏转调休；「转调休」相反；不选则两列都在。选项文案与匹配值对齐为「加班费 / 转调休」。
- OA 加班结束时刻晚于当天实际末卡：出异常，并且认可/计薪/转调休小时截到末卡（取整网格仍先做，再与末卡取早）。
- 同一员工同一加班开始–结束区间只保留一条有效加班事实；李尹 8/8 `09:30–20:00` 不得出两行、不得加两遍小时。
- 「未报加班」仅当末卡晚于 **18:30**（上海日历）且没有盖住该段的有效加班单时出现。18:00–18:30 的晚走不再出该异常。
- 人看的表和财务加班都把跨夜（含跨到周末/节假日）小时记在加班开始日，不再按日历零点切开。
- 上海昇州/晟州：核对接在花名册工号上的有效打卡事件；绑定在、事件不在的人补回放后再重算。周末不得在无班次日历时整格涂漏刷（休息日保持休息日）。不改得力 cron。

## Capabilities

### New Capabilities

- `morning-vs-overnight-punch-split`: 06:00 切开过夜离开与次日早卡，两天各自显示
- `overtime-form-capped-to-last-punch`: OA 结束晚于末卡 → 异常，小时截到末卡
- `overtime-document-interval-dedupe`: 同一人同一加班区间只留一条
- `daily-overtime-treatment-columns`: 加班费/转调休筛选控制列显隐

### Modified Capabilities

- `customer-report-center-export`: 真实下载、禁止演示文案、矩阵导出合同
- `overnight-clock-display`: 过夜切点改回 06:00 分界，早卡不再归前一天
- `overnight-overtime-start-day`: 财务也按开始日，周末/节假日跨夜不切零点
- `undeclared-off-schedule-overtime`: 阈值改为 18:30
- `overtime-hours-from-snapped-oa-form`: 填单小时与末卡取早
- `attendance-source-gap-close`: 上海有效打卡事件与休息日格子

## Impact

- 前端：`CustomerReportCenterPage` 导出文案与矩阵导出；`customerReportApi` 不再用空白名单的 `ATTENDANCE_DETAIL` 挡矩阵；`QueryReportsPage` / 每日加班表头按筛选藏列。
- 后端：`selectDayClocks` / `overnightCut`；`OffScheduleAttendanceExceptions` 18:30；`OvertimeMealDeductions` / `OvernightOvertimeFold` / 财务加班查询改为开始日；加班投影去重；OA 结束 vs 末卡异常与截断。
- 数据：OPEN 月须重新计算。关账月不自动重开。上海需有效打卡事件对齐后再重算晟州/昇州。
- 验收：吴根银「次日 07:56」类早卡、金玉亮 `次日 00:14`、李尹 8/8 去重且 8/8 末卡 18:16 截小时、导出可下载、未报加班 18:30 阈值、上海晟州有卡的人不再全月漏刷。

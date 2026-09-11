## Why

工作日加班单常把班次开始时刻带进 OA/纸质区间（例如周旋 8/4 `08:30–21:00`），当前小时=整段时长−餐扣，得到 12h。财务要的是扣掉当天上班和固定晚餐后的 2.5h，并按人×日出工作日/周末/节假日加班和考勤日记账。不做薪资管理。

## What Changes

- 工作日加班小时：取整区间 − 当天已发布 WORK 段 − 午餐空隙 − 固定晚餐 30 分钟（剩余加班穿过晚餐窗才扣；`18:30–21:00` 晚间单仍 2.5h）。
- 过夜单按日历日切开后，工作日那天同样扣上班。
- 周末/节假日不扣 WORK，保持现有午休/晚餐覆盖扣减。
- 纸质加班与 OA 同一函数。不退回打卡相交，不改 OA 插件。
- 查询报表新增「加班日报」（人×日，工作日/周末/节假日三列）和「考勤日报」（图1 扁表，页面与导出同布局）。
- 查询报表新增「请假汇总」（人×假别加总）；请假统计一单一行保留。
- 休息日有加班单且无下班卡：格子漏刷，异常总览下班缺卡。
- 人事可改打卡、改当天加班小时、取消迟到/早退/缺卡/旷工，追加裁定并重算，留原因。
- 年假/调休可按 OA 已休与加班转调休重算台账，再刷新报表 pin。
- 现有加班单据明细保留。公式目录升到 V6，OPEN 月须重算。
- **不做** 薪资发放表、基本工资、个税、社保、未批准 OA 从异常消失、改 OA 插件。

## Capabilities

### New Capabilities

- `finance-overtime-by-calendar-day`: 人×日加班日报，三列小时。
- `attendance-daily-journal`: 人×日考勤日报扁表与同布局导出。
- `leave-type-person-summary`: 请假人×假别加总。
- `rest-day-overtime-missing-off-punch`: 周末加班无下班卡出漏刷。
- `hr-attendance-adjustment`: 改打卡、改小时、取消异常，留痕后重算。
- `leave-account-oa-recalculate`: 按 OA 重算年假已休与调休额度。

### Modified Capabilities

- `overtime-hours-from-snapped-oa-form`: 工作日扣 WORK 与固定晚餐，不再把上班时间算进加班。

## Impact

- `OvertimeMealDeductions`、`FullCalculationEngineOrchestrator` 公式版本。
- 查询报表 SQL/API/Excel、菜单、考勤报表中心页签。
- OPEN 月重新计算后新小时进 pin。

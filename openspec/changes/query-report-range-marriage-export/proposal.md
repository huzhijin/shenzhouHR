## Why

查询报表的「期间」同时摆了月份和起止日期，人事只要一段开始/结束；婚假按旧规格把周末和节假日算进小时，和 OA 工作日天数对不上；分页固定 50 条，导出只能全量，细节抽屉也不该进 Excel。考勤报表中心的导出已经可用，这次不能动。

## What Changes

- 查询报表去掉「月份」控件，只留开始日期和结束日期（预设本月 / 上月 / 近三个月）。默认范围仍是月初 1–7 日看上月、其后看当月，用起止日期表达，不再单独显示月份。
- 年假统计表、调休统计表保留「自然年」年份选择，查整年、12 列已休不变。
- 考勤明细现在仍禁止跨月；拒绝文案不提「请先选月份」。抽屉继续一张月历。以后若放开，筛选不用改，抽屉加月 Tab，导出走同一张日历表把日期列拉长。本次不实现跨月。
- **只改婚假**：周末和节假日（考勤日历的休息日/节假日，含调休上班为工作日）不计入婚假小时；这些天的矩阵格子显示休息日/节假日，不涂婚假。产假、陪产假、丧假、病假、计生假等假别不变。已钉住月份须重算后数字才变。
- 查询报表每张表都能导出。导出列等于当前表格可见列（去掉详情、改打卡等操作列），样式与屏幕一致。抽屉内容不导出。
- 查询报表导出提供 **导出全部** 和 **导出当前页**。分页可调每页 50 / 100 / 200。考勤明细两种范围都导出日历表（签到/签退 × 筛选日期）；「当前页」= 这一页的人。
- **不改** `/attendance/reports` 考勤报表中心的「导出当前报表」按钮、任务、xlsx 生成与列合同。

## Capabilities

### New Capabilities

- `query-report-export-scope`: 查询报表导出全部/当前页、页大小、可见列与日历表明细导出；明确排除报表中心导出。

### Modified Capabilities

- `report-query-pages`: 查询条件去掉月份、只留起止日期；年假/调休统计保留自然年；考勤明细继续禁止跨月。
- `leave-hours-by-type`: 婚假改为不含周末与节假日；矩阵休息日不涂婚假。其它假别周末口径不变。

## Impact

- 前端：仅 `QueryReportsPage`、`queryPeriod` 与查询页测试。不改 `CustomerReportCenterPage` 导出。
- 后端：`LeaveType.includesWeekendHours`、`LeaveHoursRecognizer` 及测试；查询导出 `AttendanceReportQueryPageController` / `AttendanceReportQueryPageService.export` 增加全部/当前页；`QueryPageExcelEncoder` 考勤明细仍为日历表。不改 `AttendanceReportExportController`、`XlsxAttendanceReportExportEncoder`、报表中心导出 worker。
- 数据：婚假规则变更后 OPEN 月须重新计算。关账月不自动重开。
- OpenAPI：查询导出增加范围参数（全部 / 当前页 + page/size）。

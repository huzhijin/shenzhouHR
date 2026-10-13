## 1. Query period without a month picker

- [x] 1.1 `QueryReportsPage` 普通表去掉「月份」`DatePicker`，期间只留起止日期和本月/上月/近三个月预设；默认范围仍用 `defaultQueryPeriod` 写成 from/to
- [x] 1.2 年假统计表、调休统计表继续只显示「自然年」年份选择，`sheetQueryRange` 仍为该年整年
- [x] 1.3 考勤明细跨月继续拒绝；弹窗「暂不支持跨月」，文案不出现「请先选月份」；抽屉月份取筛选起止所在月
- [x] 1.4 查询/目录请求可继续派生 `period=YYYY-MM`（开始日所在月），UI 不再展示月份；更新 `QueryReportsPage` 测试

## 2. Marriage leave excludes rest days

- [x] 2.1 `LeaveType.includesWeekendHours()` 对 `MARRIAGE` 返回 false；注释与其它假别口径分开
- [x] 2.2 `LeaveHoursRecognizerTest`：周五至周一婚假只计周五和周一；节假日婚假 0 小时；调休上班日仍计
- [x] 2.3 日事实投影或矩阵组装：`!includesWeekendHours()` 且当天为休息日/节假日时不涂该假别；婚假周末格子为休息日/节假日
- [x] 2.4 请假统计/汇总小时走识别分钟，与矩阵一致；产假等日历假回归测试仍含周末

## 3. Query export all vs current page

- [x] 3.1 OpenAPI 与 `AttendanceReportQueryPageController` 增加 `exportScope=ALL|PAGE`（缺省 ALL）；PAGE 使用请求的 page/size
- [x] 3.2 `AttendanceReportQueryPageService.export`：ALL 仍 0 + 50000；PAGE 按当前分页查询再交给现有 `QueryPageExcelEncoder`
- [x] 3.3 考勤明细导出继续 `writeMatrix` 日历表，每日加班继续宽表；PAGE 只限制人/行，不改格子
- [x] 3.4 `QueryReportsPage` 导出下拉「导出全部 / 导出当前页」；分页 `showSizeChanger` 50/100/200，查询与 PAGE 导出带同一 size
- [x] 3.5 导出列对齐表格可见列，去掉详情/改打卡；抽屉不导出；**禁止修改** `CustomerReportCenterPage`、`AttendanceReportExportController`、`XlsxAttendanceReportExportEncoder`

## 4. Verification

- [x] 4.1 查询报表：请假/工时/明细无月份控件；年假统计能改年；明细跨月被拒且话术不含「月份」
- [x] 4.2 婚假跨周末：请假统计小时不含六日；矩阵六日为休息日；重算 OPEN 月后数字变化
- [x] 4.3 导出全部 vs 当前页人数/行数正确；页大小 200 生效；明细 xlsx 仍是签到/签退日历
- [x] 4.4 考勤报表中心「导出当前报表」回归：按钮、文件、列与改前一致

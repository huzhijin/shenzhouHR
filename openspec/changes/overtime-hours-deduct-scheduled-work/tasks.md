## 1. Weekday overtime hours

- [x] 1.1 `OvertimeMealDeductions`: weekday subtract WORK fragments, lunch gap, dinner 30 on remaining intersect; rest/holiday keep coverage meals
- [x] 1.2 Tests: `08:30–21:00` summer weekday + two WORK segments → 150; `18:30–21:00` → 150; Saturday `08:30–17:00` → 450; overnight weekday deducts that day's WORK
- [x] 1.3 Orchestrator: 周旋-style weekday form with published WORK is 2.5h on document and daily fact
- [x] 1.4 Bump formula catalog to `FULL_CALCULATION_OA_FORM_HOURS_V6` in orchestrator, mappers, auto-recalc, tests

## 2. Finance overtime daily sheet

- [x] 2.1 Query SQL: person-date rows with weekday/weekend/holiday hours from `day_type` + recognized overtime
- [x] 2.2 Query page + Excel columns; keep document overtime sheet unchanged
- [x] 2.3 Menu, demo session, 考勤报表 tab

## 2b. 财务加班矩阵（独立新表，不改加班日报/加班统计）

- [x] 2b.1 Query SQL：有加班的人分页 + 其人日加班格子（`day_type` + recognized minutes）
- [x] 2b.2 查询页「财务加班」一人一行：部门/工号/加班人/平时/周末/节假日 + 每日小时；加班日报与加班统计列不动
- [x] 2b.3 Excel 双表头（`M月D日` + 星期 1–7）与总计行，与页面同布局
- [x] 2b.4 菜单、demo、考勤报表页签
- [x] 2b.5 单测：一人多日聚一行；节假日周六进节假日列；加班日报仍是人×日两行

## 3. Daily journal

- [x] 3.1 Query SQL: person-date punch/late/early/absence/leave/overtime/remark
- [x] 3.2 Page and export share 序号 部门 工号 姓名 日期 班次 上班 下班 迟到 早退 旷工 请假 加班 备注
- [x] 3.3 Menu, demo session, 考勤报表 tab

## 4. Regression

- [x] 4.1 Existing evening/weekend overtime tests stay green
- [x] 4.2 Document overtime export still has no 平时加班/周末加班 columns

## 5. 请假汇总

- [x] 5.1 Query SQL：人×假别 SUM(recognized_minutes)
- [x] 5.2 查询页「请假汇总」+ Excel；请假统计一单一行保留
- [x] 5.3 菜单

## 6. 周末加班下班漏刷

- [x] 6.1 有加班单的休息日无下班卡：下午格「漏刷」
- [x] 6.2 异常总览出 MISSING_OFF_DUTY；无加班单的休息日仍不出漏刷
- [x] 6.3 矩阵/投影单测

## 7. 人事改打卡

- [x] 7.1 表 `attendance_hr_punch_adjustment` + 能力 `ATTENDANCE_ADJUST:MANAGE`
- [x] 7.2 核算注入上班/下班时刻（含休息日）后重算当月
- [x] 7.3 考勤日报「改打卡」对话框

## 8. 人事改当天加班小时、取消迟到/早退/缺卡/旷工

- [x] 8.1 裁定表可写加班分钟覆盖、取消的异常类型、原因、操作者
- [x] 8.2 重算写入 pin 时套用覆盖：日报/加班日报小时、矩阵异常色、异常总览不再列出已取消类型
- [x] 8.3 考勤日报改打卡对话框增加加班小时和取消异常勾选
- [x] 8.4 单测：覆盖 2.5h；取消 LATE 后异常页无该行

## 9. 年假/调休按 OA 重算

- [x] 9.1 按公司+年：OA 年假已休小时写入年假台账 USE，与台账已用对齐
- [x] 9.2 OA 调休已休 + 加班转调休写入 TIME_OFF 台账
- [x] 9.3 查询页年休假/调休额度「按 OA 重算」按钮，HR 可点；完成后刷新报表 pin
- [x] 9.4 单测：OA 年假 8h 则 usedHours=8

## 10. 暂缓（TODO）

- [ ] TODO: 考勤明细格子改成 Excel 那种单元格直接改（难度大，本次跳过）
- [ ] TODO: 考勤明细跨月展示列（当前仅本月内日期筛选，选跨月弹窗提示）
- [ ] 不做：请假/OA 定时自动重算（HR 手动点「按 OA 重算」即可，不另做定时任务）

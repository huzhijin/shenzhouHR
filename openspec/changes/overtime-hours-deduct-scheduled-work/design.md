## Context

加班小时当前是 snap(OA 区间) − 餐扣覆盖，工作日把 08:30 上班算进去。财务要人×日加班三列和考勤日记账，不要工资模块。日事实已有 `day_type` 与加班分钟。

## Goals / Non-Goals

**Goals:** 工作日扣 WORK + 午餐空隙 + 固定晚餐 30；周末/节假日维持餐扣；两张财务报表从 pin 的日事实分页查询并同布局导出。

**Non-Goals:** 薪资发放、基本工资/个税/社保、改 OA 插件、打卡 ∩ 单据、手工改数（后续 change）。

## Decisions

### 1. Deduct published WORK fragments, not clamp start to shift-off

班前加班保留。午餐空隙从剩余片段减去，避免 12:00–13:00 变成加班。

备选：把开始卡到下班+晚餐。拒绝，会丢掉早上班前加班。

### 2. Weekday dinner is 30 minutes if remaining intersects the dinner window

`18:30–21:00` 与 `18:00–18:30` 不相交，不扣。`08:30–21:00` 扣完 WORK 后剩 18:00–21:00，相交则 −30。

休息日仍用「完全盖住」餐窗，与现网周末口径一致。

### 3. Formula catalog V6

旧 pin 仍是虚高小时。查询只读 V6 pin，OPEN 月重算后替换。

### 4. New query sheets, keep document overtime sheet

`overtime-daily` / `daily-journal` 走已有查询分页 SQL。加班单据页不动。菜单与考勤报表页签都挂这两张。

### 5. No new ReportType enum values

查询页指纹映射到现有 `OVERTIME` / `ATTENDANCE_DETAIL`，避免计算器 exhaustive switch 扩散。

## Risks / Trade-offs

[Risk] 未发布班段的工作日扣不到上班 → 以已发布 WORK 为准；晚间单无 WORK 重叠仍 2.5h。
[Risk] 单段 WORK 含午餐 → 扣 WORK 已去掉午餐，再减 12:00–13:00 无剩余，结果仍对。
[Risk] 旧 pin 继续被读 → V6 公式版本强制重算。

## Migration Plan

1. 部署后端 + 前端。
2. OPEN 月点「重新计算」或等自动近 3 天窗口（当月全量才齐）。
3. 抽查周旋 8/4 → 2.5h；加班日报三列；考勤日报导出列。
4. 回滚制品后读旧 V5 pin。

## Open Questions

无。薪资发放明确不做。

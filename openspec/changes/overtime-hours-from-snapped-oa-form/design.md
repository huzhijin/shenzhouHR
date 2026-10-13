## Context

上一轮 `report-hours-exceptions-export-fixes` 已经把 OA 起止收到 30 分钟网格，并把加班 `recognized_minutes` 改成计算器里「打卡配对 ∩ 取整单据 − 餐扣」。现场 2026-08 江苏神州 4282 条加班单里约 1/3 小时为 0（含有卡留到 21:00 的工作日晚上单、周六全天有卡的单），另有 1.33 / 2.67 这类打卡尾巴。业务已确认：小时看 OA 填的起止，先取整再算，餐扣看区间是否盖住餐窗；有单无卡也要出时长。

当前写小时的路径：

```
OaIntervalGrid.snap → 计算器 pairPresence ∩ 单据 → overtimeBySourceKey
    → projectOaReportFacts.recognizedMinutes
    → 查询页 minutes/60.0
```

`pairPresence` 要求当天 AUTO 卡为偶数，奇数卡整天加班为 0。餐扣 `requireFullCoverage` 作用在打卡在岗段上，不在单据上。

## Goals / Non-Goals

**Goals:**

- OA 加班 `recognized_minutes` = 取整区间分钟 − 盖住餐窗的午休/晚餐。
- 查询加班统计、官方加班报表、月度工时三类加班列同一套分钟。
- 小时为 0.5 的倍数（含 0）；开始/结束为取整钟点。
- 虚假加班异常仍可报，但不把单据小时打成 0。

**Non-Goals:**

- 不改 OA 插件填单（仍允许历史任意分钟，由 HR 侧取整）。
- 不改得力同步、补卡配额、工资引擎。
- 不按打卡是否在岗决定加班小时（打卡仍可用于矩阵格子、虚假加班）。
- 不强制重开关账月。
- 不在本变更改请假小时算法（请假仍走班段交集；取整网格保持现状）。

## Decisions

### Decision 1: Document hours ignore punches

`FullCalculationEngineOrchestrator.recognizedMinutes` 对 `OVERTIME` 不再读 `overtimeBySourceKey`。改为：

1. `OaIntervalGrid.snapInterval(start, end)`；失败则 0。
2. 毛分钟 = `Duration.between(snappedStart, snappedEnd).toMinutes()`。
3. 按单据发生日（取整后开始的上海日历日，过夜单按现有切日把分钟分到各出勤日）套用餐扣。
4. `recognized = max(0, 毛分钟 − 餐扣)`。

过夜单（取整后跨日）按日历日切开后分别扣该日餐窗，再求和。不要把 18:00–次日 07:00 的整天毛时长只扣一次晚餐。

备选：继续打卡 ∩ 单据再把结果收到 0.5。拒绝，因为有卡仍为 0 的根因是配对，取整救不了。

### Decision 2: Meal deduction is interval coverage, not punch coverage

新建纯函数（建议 `OvertimeMealDeductions`，与 `OaIntervalGrid` 同包）：

- 休息日（SATURDAY / SUNDAY / PUBLIC_HOLIDAY）：若取整区间完全盖住当天 `12:00–13:00`，扣 60。
- 晚餐：夏令盖住当天 `18:00–18:30` 扣 30；冬令盖住 `[shiftOff, shiftOff+30min]` 扣 30。无班次下班时刻时冬令 `shiftOff = 17:30`。
- 「完全盖住」= 区间 start ≤ 餐窗 start 且区间 end ≥ 餐窗 end（与现有 `covers` 一致）。
- 夏令/冬令跟当天已发布班次季节，与现有班次版本一致，不新造日期表。

大连考勤组「晚餐触发 120 分钟打卡」不作用于本单据时长路径。

备选：周末一律扣 1 小时午休。已否决（口径 A）。

### Decision 3: Daily overtime facts follow the same documents

人日 `paid/compensatory/voluntary_overtime_minutes` 改为按该日摊到的单据分钟（同一套取整+餐扣）汇总，避免加班统计 2.5、工时仍是 0。计算器 `RECOGNIZED_OVERTIME` 打卡路径可保留给解释链/虚假加班，但投影写日事实时以单据分钟为准。

### Decision 4: Display

查询页 `minutesToHours` 对加班小时用 `minutes / 60.0` 即可，因为结果必是 0.5 的倍数；前端不要 `String(1.333…)`。若仍出现非 0.5 倍数，视为计算回归，测试锁死。

## Risks / Trade-offs

[Risk] 只交单不打卡的加班会进计薪/调休小时。→ 这是已确认口径；虚假加班异常仍留下给 HR 审。

[Risk] 过夜单按日切餐窗算错（只扣一天或扣两次午餐）。→ 设计要求按上海日历日切开后分别覆盖判断；加 18:00–次日 07:00 的测试。

[Risk] 旧 pin 仍是 0 和 1.33。→ 部署后对 OPEN 月重新计算。

[Risk] 工时公式分子变大（休息日加班不再是 0）。→ 与业务要的「有单算出时长」一致；发布说明写明。

## Migration Plan

1. 部署后端（识别分钟 + 餐扣 + 日事实对齐）和前端（若有小时格式化）。
2. 对当前 OPEN 公司月点「重新计算」（或等自动重算）。
3. 抽查：张珍珍类「有卡仍为 0」的工作日晚上单变为 2.0/2.5；周六白天单扣 1 小时午休；周六 `18:00–21:00` 不扣午休、夏令扣晚餐得 2.5；`18:10–21:00` 先收到 `18:00–21:00` 再算。
4. 回滚：回退制品后读旧 pin。

## Open Questions

- 无。夏令晚餐窗、周末午休覆盖才扣、0 小时改为单据时长，均已确认。

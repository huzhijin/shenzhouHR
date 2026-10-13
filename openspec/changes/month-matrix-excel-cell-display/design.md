## Context

客户样表 `工作簿11111.xlsx` 按签到/签退两行写时间和假别，半天两格不同色，全天两行都写同一假别。报表中心月矩阵已经是「一人一行、格内两行时间」，但正式映射 `toAttendanceDetailRows` 只填打卡时间，整格用 `primaryAttendanceStatus` 抢一个底色。OA 请假在组装器里按自然日整段盖章，`leaveBadge` 只认出年假/事假/病假/调休，陪产假等变成 `OTHER_LEAVE`，半天假无法分色。

本次只改月矩阵展示合同和报表中心考勤明细页。计算引擎、OA 同步、加班认定公式不动。Wave7 正式月矩阵继续读旧字段，不改成客户样表布局。

## Goals / Non-Goals

**Goals:**

- 后端按上午段/下午段组装格子文案、色码、合并标记和悬停材料。
- 报表中心日期格：一格两段独立色；全天同假合并居中一个字；其它假别出字可不上色。
- 加班色 = 生效 OA 加班单 ∩ 当日认可加班；主表保留时间。
- 假别小时写死：上午 3.5、下午 4.5，只进悬停。
- 旧字段 `firstPunchAt` / `lastPunchAt` / `badges` 继续返回。

**Non-Goals:**

- 不改成每人两行物理行，不加签到/签退列。
- 不横着合并跨天单元格。
- 不展示驻外、不打卡、离职、入职、停职留薪。
- 不按班次实算半天小时（大连/成都差异下次再做）。
- 不改请假统计、加班汇总、出勤率等其它报表页。
- 不把 Wave7 `ReportsPage` 月矩阵改成客户样表样式。
- 不改出勤计算或 OA 同步。

## Decisions

### 1. 展示组装放在后端 DayCell，前端只绘制

前后端都做会让 OA 半天规则在 mapper 里再实现一遍。后端 `AttendanceMonthMatrixAssembler` 已经握有日事实和 OA 区间，在这里产出 slot 展示。前端 `toAttendanceDetailRows` 只把 slot 映射成两段 CSS，不再从 badges 猜主色。

备选：只改前端。否决，因为现有 badges 是整日的，做不出「上午年假、下午 18:01」。

### 2. 日格合同加法扩展，不删旧字段

`DayCell` 增加：

- `morning` / `afternoon`：`text`、`tone`（可空）、可选 `punchAt`
- `merged`：两段文案和 tone 都相同且属于假/出差/外出/调休时为 true
- `hover`：多行说明字符串（班次、上下段、假别小时、加班、补签）

保留 `firstPunchAt`、`lastPunchAt`、`badges`。`formulaVersion` 升为 `ATTENDANCE_MONTH_MATRIX_V2`。OpenAPI 与 `AttendanceMonthMatrixDay` 同步加字段。Wave7 页可忽略新字段。

Tone 使用已有徽章码：`LATE`、`EARLY_DEPARTURE`、`MISSING_PUNCH`、`RECOGNIZED_OVERTIME`、`TIME_OFF`、`OUTING`、`TRIP`、`PERSONAL_LEAVE`、`SICK_LEAVE`、`ANNUAL_LEAVE`、`PUNCH_CORRECTION`、`REST_DAY`。其它假别 `text` 为中文假名，`tone` 为空。

备选：为陪产假等扩展 BadgeCode。本次不上色，不必加枚举；中文名直接放 `text`。

### 3. OA 覆盖窗口写死为样表半天

业务确认半天小时写死，覆盖窗口与总部冬令对齐（Asia/Shanghai）：

- 上午段：当天 08:30–12:00
- 下午段：当天 13:00–17:30
- 区间与该窗口有交集即盖住该段
- 两段都盖住 → 当天全天合并
- 半天单：起止落在同一天且只盖一段
- 全天/多天：一条连续半开区间，按天投影，不把中间工作日断开

午餐 12:00–13:00 不单独成段。草稿单据仍排除。出差/外出/调休/补签/加班单用同一套窗口。

备选：按当日班次工作段切。更准，但这次写死 3.5/4.5，班次切割放到后续。

### 4. 每段文案优先级

对每一段单独套 spec 中的优先级。缺卡不得压过已盖住该段的假/外出/出差/调休。免打卡仍抑制该段漏刷，不写「免打卡」。

迟到只标上午段，早退只标下午段。补签段：有校正时刻则 `补签HH:mm`，否则 `补签`；与单据同段则并写 `补签 外出`，tone 用单据，补签红字由前端对 `PUNCH_CORRECTION` 或文本含「补签」处理。

缺卡分段：有应出勤且该段无打卡、也无替代单据 → `漏刷`。用 `firstPunchAt` / `lastPunchAt` 是否存在（及是否同一时刻）近似上下卡，而不是把整天 `missingPunchCount` 涂在两段上。

### 5. 加班色

`RECOGNIZED_OVERTIME` tone 仅当：

1. 当天有生效 OA `OVERTIME` 单据，且
2. 日事实 `recognizedOvertimeMinutes > 0`

主表两段仍显示时间（或该段其它更高优先级文案）。有加班 tone 的段用加班绿；若两段都是时间且当天加班，两段都可绿，不合并成「加班」单字。悬停加「加班」。无加班单的晚走保持无加班色。

备选：仅有加班单就涂绿。否决，避免未认定加班也被涂成加班。

### 6. 前端一格两段，全天居中

`AttendanceDayCell` 增加 `primaryStatus` / `secondaryStatus` / `merged` / `mergedLabel` / `mergedStatus`。`AttendanceMatrixRow`：

- `merged`：一个铺满的居中标签，无内部分割线
- 否则：同一 `td` 里上下两个 50% 色条，中间无间隙，外框仍是一个日期格

图例色沿用 `attendanceLegend`。`tone` 为空的其它假别只出字、白底。导出复用同一 mapper，全天一格一个字，半天上下两行写进同一个导出单元格（换行），不要拆成两行员工。

演示 `applyAttendanceStatus` 改成同一套全天合并 / 半天两段，避免 demo 和正式页两套语义。

### 7. 中文假别从 OA leaveType 出字

组装器增加 leaveType → 中文名映射（年假、事假、病假、调休、婚假、产假、陪产假、丧假、工伤假、护理假、哺乳假、孕检假、计生假）。未知值用 OA 原始类别或「请假」，禁止对已识别假别输出「其他假别」。

## Risks / Trade-offs

[Risk] 写死 08:30–12:00 / 13:00–17:30，大连上午 4.5、成都下午更长，悬停小时和覆盖窗口会对不齐。
→ Mitigation: spec 写明本次写死；后续改按班次工作段和真实分钟。

[Risk] `firstPunchAt`/`lastPunchAt` 不能区分「缺上班卡」和「缺下班卡」的全部情形（只有一张卡且时刻落在中午附近）。
→ Mitigation: 等于同一时刻的一对卡视为缺另一端；剩余歧义进悬停不进主表假字。补签时刻优先用单据区间落在哪一段。

[Risk] 加法字段让月矩阵 JSON 变大。
→ Mitigation: 每格两个短 slot + 一行 hover，相对已有日数组可接受；不把原始 OA 区间数组下发。

[Risk] Wave7 页仍展示整日 badges，和报表中心新格子不一致。
→ Mitigation: 提案明确不改 Wave7 布局；旧 badges 继续算整日并集，避免那页空白。

[Risk] 缓存仍按旧 `formulaVersion` 命中，用户看到旧格子。
→ Mitigation: 升到 `ATTENDANCE_MONTH_MATRIX_V2`，与现有实时缓存键一起失效。

## Migration Plan

1. 先扩后端 DayCell 和测试（半天、全天合并、陪产假、加班需单据、漏刷字面）。
2. 再改 OpenAPI / TS 合同 / mapper / 报表中心绘制和导出。
3. 演示数据与正式映射对齐。
4. Wave7 合同测试只断言旧字段仍在。
5. 回滚：前端忽略新字段即回到「两行时间 + 整格一色」；后端可再切回 V1 组装，但默认不保留双公式开关。

## Open Questions

无未决产品问题。班次相关半天小时留到后续 change，不阻塞本次。

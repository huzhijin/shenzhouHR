## Context

月矩阵格子已经按上下两段组装（`AttendanceMonthMatrixAssembler` + 前端 slot）。现场仍对不上三件事：

1. 假别展示认 `TIME_OFF` / `ANNUAL` 这类字符串，入库却写 `LeaveType.name()`（调休是 `COMPENSATORY`），于是调休变成白底「请假」。其它假别有字、`tone` 为空。
2. 休息日 `firstPunchAt` / `lastPunchAt` 只从计算「已消费」的打卡 ID 解析。休息日没有班次，打卡只有在加班认定成功时才会被消费，所以周六加班单即使批准，格子仍是空米色。
3. 异常总览正式投影有日期和 `MISSING_PUNCH_PENDING` / `OVERDUE`，前端收成「缺卡待补签/超期」，主表还放证据摘要，看不出缺哪一头卡。

现有图例 12 色写死在 `REPORT_BADGE_COLORS` / `CustomerReportLegendColors`。用户要求：有几个假别就有几个颜色，且不与现有非该假别的颜色重复。

## Goals / Non-Goals

**Goals:**

- 每种已识别假别独立图例色；`COMPENSATORY` 显示为调休色「调休」。
- 休息日有卡出时间；有生效加班单能看出加班。
- 异常总览主表：类型明确、详情可读、证据摘要不占主列；缺卡分上班/下班。

**Non-Goals:**

- 不改得力同步节奏、补卡配额、工资。
- 不改 Wave7 `ReportsPage` 布局。
- 不把异常引擎拆成新的 `ExceptionType` 枚举值（报告层映射即可）。
- 不重新设计图例成「家族色」；本次按假别一色。
- 不在本次核验 OA `field0096`「加班费」与「现金」枚举 ID 是否同一项（见 Open Questions）。休息日展示不依赖加班分钟认定成功。

## Decisions

### 1. 假别字符串按枚举名和 leave code 双表认

`leaveAppearance` / `leaveBadge` 同时接受：

| 入库值 | 标签 | tone |
|---|---|---|
| `ANNUAL`, `ANNUAL_LEAVE`, 年假, 年休假 | 年假 | `ANNUAL_LEAVE` |
| `PERSONAL`, `PERSONAL_LEAVE`, 事假 | 事假 | `PERSONAL_LEAVE` |
| `SICK`, `SICK_LEAVE`, 病假 | 病假 | `SICK_LEAVE` |
| `COMPENSATORY`, `TIME_OFF`, 调休, 调休假 | 调休 | `TIME_OFF` |
| `MARRIAGE`, `MARRIAGE_LEAVE`, 婚假, 结婚假 | 婚假 | `MARRIAGE_LEAVE` |
| `MATERNITY`, `MATERNITY_LEAVE`, 产假 | 产假 | `MATERNITY_LEAVE` |
| `PATERNITY`, `PATERNITY_LEAVE`, 陪产假 | 陪产假 | `PATERNITY_LEAVE` |
| `BEREAVEMENT`, `BEREAVEMENT_LEAVE`, 丧假 | 丧假 | `BEREAVEMENT_LEAVE` |
| `WORK_INJURY`, `WORK_INJURY_LEAVE`, 工伤, 工伤假 | 工伤假 | `WORK_INJURY_LEAVE` |
| `NURSING_LEAVE`, 护理假 | 护理假 | `NURSING_LEAVE` |
| `BREASTFEEDING_TIME`, 哺乳时间, 哺乳假 | 哺乳假 | `BREASTFEEDING_LEAVE` |
| `PRENATAL_EXAM_TIME`, `PRENATAL_NURSING`, 产检时间, 孕检假 | 孕检假 | `PRENATAL_EXAM_LEAVE` |
| `FAMILY_PLANNING`, `FAMILY_PLANNING_LEAVE`, 计生假 | 计生假 | `FAMILY_PLANNING_LEAVE` |

备选：改入库为 `TIME_OFF` leave code。否决，V48 约束已经是 `LeaveType` 枚举名，展示层兼容更便宜。

### 2. 新 BadgeCode + 锁定不撞色的 hex

`BadgeCode`、OpenAPI、`attendanceMonthMatrixBadgeCodes`、`AttendanceStatusKey`、图例、Excel `CustomerReportLegendColors` 同步加 9 个假别。现有 调休/事假/病假/年假/迟到/早退/漏刷/加班/外出/出差/休息日/补签 hex **不改**。

| 标签 | tone / status key | 日间 hex | 理由 |
|---|---|---|---|
| 婚假 | `MARRIAGE_LEAVE` / `marriage-leave` | `#E07CC0` | 粉，避开漏刷紫 `#BE6CBB` |
| 产假 | `MATERNITY_LEAVE` / `maternity-leave` | `#C45C9E` | 更深玫红，与婚假可分 |
| 陪产假 | `PATERNITY_LEAVE` / `paternity-leave` | `#8E6CC9` | 紫，避开漏刷 |
| 丧假 | `BEREAVEMENT_LEAVE` / `bereavement-leave` | `#5C5C5C` | 灰，避开休息日米 |
| 工伤假 | `WORK_INJURY_LEAVE` / `work-injury-leave` | `#E07A3D` | 橙，避开调休金、迟到珊瑚 |
| 护理假 | `NURSING_LEAVE` / `nursing-leave` | `#3D6BB3` | 蓝，避开早退 `#5AA3EA` |
| 哺乳假 | `BREASTFEEDING_LEAVE` / `breastfeeding-leave` | `#F4A6C8` | 浅粉，比婚假浅 |
| 孕检假 | `PRENATAL_EXAM_LEAVE` / `prenatal-exam-leave` | `#7EC8E3` | 天蓝，避开外出青 `#43D4D0` |
| 计生假 | `FAMILY_PLANNING_LEAVE` / `family-planning-leave` | `#6B8F3E` | 橄榄，避开加班绿 `#3F8850` |

图例顺序：现有 12 项保持，后面接 婚假、产假、陪产假、丧假、工伤假、护理假、哺乳假、孕检假、计生假。深色底用白字（病假/年假/丧假同一规则）。

`isLeaveLike` / `isLeaveBadge` / 全天合并把上述 tone 算进可合并假别。

备选：一个「其它带薪假」共用色。否决，用户明确每种假别一色。

### 3. 休息日打卡取当天计算窗口内全部卡，不取「已消费」子集

`convertToVerifiedFacts` 今天只解析 `consumedPunchEventIds`。休息日无班次，卡不会被班次匹配消费。

改为：该员工该业务日送进计算器的 `dayPunches` 的最早/最晚瞬间写入 `firstPunchAt` / `lastPunchAt`（补签合成卡算在内）。工作日同样用这批卡，与「当天窗口内最早上班、最晚下班」一致。

休息日默认 `overnightCut` 在无班次时退回当天 08:30，会把周六 08:17 算进周五。休息日（`DayType` 为周六/周日/节假日）证据窗口改为当天 00:00 至次日班次切点（无次日班次则次日 00:00），这样周六加班 08:30–15:00 一定落在周六。

`composeSlot`：有时间就显示时间；休息日无时间才涂休息日米色。有生效加班单且无时间时，格子文案为 `加班`、tone `RECOGNIZED_OVERTIME`，禁止空米色。

休息日加班色：该日有生效 OA `OVERTIME` 单据（`overtimeDocument`）或 `recognizedOvertimeMinutes > 0`。工作日加班色仍要认可分钟，避免晚走无单涂绿。有时间的休息日加班格保留时间，加班进悬停。

备选：只在加班认定成功后显示休息日卡。否决，正是当前空白格的原因。

### 4. 异常类型在报告层映射，详情在计算器拼

计算引擎继续产出 `LATE` / `EARLY_DEPARTURE` / `MISSING_PUNCH_PENDING` / `MISSING_PUNCH_OVERDUE` / `ABSENCE` / `FAKE_OVERTIME`。`AttendanceReportCalculator` 增加 `exception-details` 字段。前端类型映射：

- `MISSING_PUNCH_*` → 看当日 `firstPunchAt` / `lastPunchAt`：只有下班卡 → 上班缺卡；只有上班卡 → 下班缺卡；两头都无且不是旷工行 → 仍按缺卡，详情写明无卡。全天旷工保持 `ABSENCE` → 旷工，不改成缺卡超期。
- 其它类型中文标签保持。

主表列：考勤日期、异常类型、工号、姓名、部门、详情、处理状态。证据摘要、级别、班次、应出勤、打卡摘要、异常分钟、负责人、处理时限不作为默认主列（仍可留在 API）。详情句式按 spec 示例，用已有分钟和打卡时刻拼，不把脱敏证据摘要搬进详情。

导出与页面同一组列。

备选：引擎新增 `MISSING_ON_DUTY` / `MISSING_OFF_DUTY`。否决，指纹与历史案件会裂开；报告层足够。

## Risks / Trade-offs

[Risk] 图例从 12 项加到 21 项，窄屏拥挤。
→ Mitigation: 图例允许换行；颜色饱和度拉开；深色假别白字。

[Risk] 工作日改用窗口内全部打卡作首末卡，可能让中间无效卡变成最晚下班。
→ Mitigation: 窗口仍是过夜切点；与已定「最早/最晚」规则一致。测试覆盖工作日一对卡不变。

[Risk] 休息日窗口改 00:00 后，周五晚加班离开卡若落在周六 00:00 后可能进周六。
→ Mitigation: 周五业务日窗口仍到周六班次上班点；休息日窗口只用于休息日那天的计算。交叉测试周五过夜 + 周六加班。

[Risk] OA 加班类别「加班费」若枚举 ID 未映射，单子 `effective=false`，认可分钟为 0。
→ Mitigation: 休息日展示走 `source_status=APPROVED` 的 reportable OA 加班单，不要求 activation。另列 Open Question 核验枚举。

[Risk] 缺卡侧别用首末卡近似，正午附近单卡会判错段。
→ Mitigation: 与现有矩阵 12:00 切段同一规则；详情写「仅有一张卡 HH:mm」避免硬判错时完全无信息。

## Migration Plan

1. 后端 BadgeCode、组装器映射、休息日窗口与首末卡、异常 details 字段与测试。
2. OpenAPI + 前端图例/色表/status key、异常表列。
3. Excel 导出跟色跟列。
4. 实时矩阵 formulaVersion 顺延（例如 `ATTENDANCE_MONTH_MATRIX_V3`）以免旧缓存格子。
5. 已钉住的公司月快照不回溯；OPEN 期重新计算后休息日卡才会进日事实。

回滚：前端旧图例仍能忽略未知 tone（无色出字）；新 tone 对旧前端是无色字，不炸。

## Open Questions

- OA 加班单「加班费」的 `field0096` 是否就是代码里的 PAID（展示值「现金」）那条枚举 ID。不影响休息日「有单就看见」，但影响认可加班分钟和加班统计页。
- 异常总览筛选是否要按新类型「上班缺卡/下班缺卡」过滤。默认做，因主表类型已经拆开。

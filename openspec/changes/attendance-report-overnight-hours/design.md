## Context

报表中心和 OA 加班插件今天用两套不一致、且都含固定 06:00 的过夜逻辑。得力卡全部 `AUTO`；白班 17:30 可以不打。现场过夜是「当天只有上班卡，次日上班点之前离开」，加到 7 点通常配上午请假单。客户月度工时样表要求计薪加班、转调休加班、已休调休分列。日事实 `actualAttendanceDays` 只允许 0 或 1。请假 `LeaveType` 没有计生假/其他。`szsc` 的 `CROSS_DAY_CUTOFF = 06:00` 会拒绝加到 07:00 的单。

约束：HR 仓库本变更；OA 插件在 `/Users/huzhijin/Downloads/szsc`。已钉快照必须显式重新计算才换公式。两仓独立发布，行为必须同一套切点（次日班次上班点）。

## Goals / Non-Goals

**Goals:**

- 白班最早/最晚打卡同时驱动迟到和工时；过夜离开卡归前一天。
- 加到次日上班点之前 + 上午请假为合法；过夜后上白班不强制休息。
- 虚假加班仅当加班盖住未请假的上班时段。
- 假别周末口径、班次 3.5/4.5（及大连 4.5/3.5）、月度工时公式、异常过滤、出勤率 0.5 天一次改齐。
- OA 插件去掉 06:00，过夜看班次上班点，并扣除请假覆盖的上班时段。

**Non-Goals:**

- 不引入排班夜班班次。
- 不改补卡每月 1 次配额、得力/OA 同步 cron、义务加班入月度工时。
- 不自动重算已钉快照。
- 不在 HR 里实现 OA 表单发起（只认已同步单据）。

## Decisions

### 1. Overnight cut is next-day shift start, never 06:00

**Choice:** For employee E on calendar date D, punches in `[D 00:00, shiftStart(E,D))` belong to D−1 overtime off-duty. `shiftStart` is the earliest published WORK segment start that day (Yangzhou 08:30, Dalian 07:30, Chengdu 09:00). Summer/winter follows the published shift version.

**Why not 06:00:** No such node in customer shifts. Why not natural-day min/max: Tuesday 07:00 would become Tuesday on-duty.

**HR:** Expand Monday's calculation evidence window through Tuesday `shiftStart`, not only Monday departure-window end. Keep Tuesday's scheduled pairing inside Tuesday arrival/departure windows.

**OA:** Replace `CROSS_DAY_CUTOFF` with the same `shiftStart` from HR `segments_json`. Close the open 06:00 decision as rejected.

### 2. Two punch pairs, not one calendar pair

**Choice:** Scheduled pair = earliest in arrival window + latest in departure window. Overnight off-duty is a separate punch hanging on the previous daily fact (`lastPunchAt` may be next-morning). Month matrix: Monday afternoon/off slot can show that time (overtime tone only after an approved form); Tuesday morning slot uses Tuesday arrival-window punch or leave, never the 07:00 leaving punch.

Same-day evening overtime still needs a same-day evening punch (pattern A is overnight-only).

### 3. Fake overtime is overlap with unleaved work, not "OT then came to work"

**Choice:** Raise fake overtime iff `overtimeInterval ∩ scheduledWorkInterval − leaveCoverage` is non-empty. 07:00 + 08:32 without leave is legal. 07:00 + morning leave is legal. 08:30–10:00 OT without leave is fake; recognized minutes 0.

**OA:** When testing work-interval overlap, subtract effective leave. Treat `[D 00:00, shiftStart)` as overnight continuation so "only after workEnd" does not fire on 00:00–07:00.

**HR:** New exception type `FAKE_OVERTIME` (or equivalent stable code) with a Chinese evidence summary. Do not reuse `OVERTIME_DOCUMENT_MISSING_OR_LATE`.

### 4. Half-day rate is 0.5, stored as decimal

**Choice:** Change `actualAttendanceDays` from integer 0/1 to a one-decimal value 0 / 0.5 / 1. Morning and afternoon segments are 0.5 each. Paid leave on a segment counts; 事假 does not. Schema: widen the daily-fact column to `DECIMAL(3,1)` (or store tenths as `SMALLINT` 0/5/10 — prefer DECIMAL to match the rate formula). Rate = `SUM(actual) / SUM(scheduled)` with scheduled still 1 per work day.

**Alternative rejected:** Keep integers and lose half-day. Rejected by the customer.

### 5. Leave weekend hours by type; hours from shift segments

**Choice:** Classify leave:

- Include weekend (and public holiday): 婚假、计生假、陪产假、产假、病假、丧假、孕检假、哺乳假、其他
- Exclude weekend and public holiday: 年休假、调休假、事假

For included weekend days with no WORK segments, copy that employee's current-season weekday template minutes (two WORK segments). Add `FAMILY_PLANNING` and `OTHER` to `LeaveType`; keep 孕检/哺乳 mapped but report their OA labels. Stop quarantining 计生假/其他.

**OA leave hours** in the leave report use this recognition, not raw calendar duration of the form.

### 6. Monthly hours computed in the report calculator

**Choice:** Backend `WORK_HOURS` fields:

| Column | Source |
|---|---|
| `{n}月应出勤工时` | sum scheduled minutes |
| 加班时数 | `paidOvertimeMinutes` only |
| 事假+病假+其他假期 | OA leave minutes except 年假 and 调休 |
| 年假 | annual leave minutes |
| 加班换调休 | `compensatoryOvertimeMinutes` |
| 实际调休 | used TIME_OFF minutes |
| 个人实际出勤工时 | formula in spec, not `confirmed+all OT` |

Frontend labels and export follow the same fields. Demo formula that subtracted 加班换调休 must be replaced.

Add `COMPENSATORY_OVERTIME_HOURS` already exists; stop mapping `TIME_OFF_HOURS` to the 加班换调休 label. Introduce/use a distinct used-time-off field for 实际调休.

### 7. Exception overview filters after projection

**Choice:** Keep writing daily exception facts, then filter the EXCEPTIONS dataset: drop LATE when penalized minutes are 0; drop rest-day empty punches; drop exempt days; drop resolved-by-supplement. Show ABSENCE as 旷工, not `MISSING_PUNCH_OVERDUE`. Show `FAKE_OVERTIME` and overnight missing off-duty.

Pending overtime with a valid leaving punch is not an exception.

### 8. Two repositories, one behavior

**Choice:** Implement HR first for report numbers, then `szsc` (or in parallel after shift-start helper is specified). Plugin reads leave via a new HR read (effective leave intervals by employee-number and date) rather than OA form tables, so it uses the same classified types. Weekend lunch window is hardcoded 12:00–13:00 in the plugin; dinner stays shift-off + 30 minutes from `ShiftRuleResolver`.

HR weekend overtime meal deduction must use the same 12:00–13:00 lunch on SATURDAY/SUNDAY/PUBLIC_HOLIDAY regardless of summer weekday lunch.

## Risks / Trade-offs

- [Risk] Expanding Monday's punch window to Tuesday shift start can pull Tuesday's early Dalian 07:20 on-duty into Monday if someone starts at 07:30 and punches 07:20 — **Mitigation:** a punch in Tuesday arrival window is Tuesday on-duty; only punches strictly before shift start and not in Tuesday arrival window attach to Monday. If arrival window starts before shift start (07:00–09:00 for 08:30 shift), prefer: punches before shift start go to Monday OT unless they fall in Tuesday arrival window **and** there is already a Monday overnight candidate. Simpler rule for v1: punches in `[00:00, shiftStart)` always Monday OT; Dalian 07:20 is before 07:30 so it is Monday OT, Tuesday on-duty is the next punch in the arrival window. Document this for Dalian early arrivals.
- [Risk] DECIMAL attendance days vs existing INT columns and pins — **Mitigation:** formula catalog version bump; old pins remain until recalculate; reject 0/1-only invariant in `DailyFact`.
- [Risk] szsc without leave read will still block 07:00 OT — **Mitigation:** plugin change is in scope; do not ship HR overnight OT recognition as fileable if plugin still uses 06:00. Coordinate releases: plugin first or same window.
- [Risk] 工伤假 not in the customer's weekend lists — **Mitigation:** treat as calendar leave (include weekend), same as 婚假/产假, recorded in open questions as assumed.
- [Risk] Half-day leave vs shift 3.5/4.5 hours — **Mitigation:** hours follow shift; days follow 0.5 per segment; do not mix.

## Migration Plan

1. Ship HR calculator + report field + schema for decimal days and new exception type; bump formula catalog.
2. Deploy `szsc` overlay with shift-start cutoff, leave subtraction, weekend lunch 12:00–13:00; restart OA.
3. HR admin recalculates open months.
4. Rollback: revert plugin overlay; HR keeps old pins if recalculate was not run; if recalculated, recalculate again after revert (pins are append-only).

## Open Questions

- 工伤假未出现在客户周末表中；本设计按含周末的日历假处理。
- 过次日上班点离开但当天全天请假/休息：不报虚假加班（没有未请假的上班时段）。
- 大连到达窗若早于上班点，07:20 归过夜离开；若现场要把 07:20 当大连上班卡，再开变更。

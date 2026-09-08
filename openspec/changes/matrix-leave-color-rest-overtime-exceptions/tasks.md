## 1. Leave type mapping

- [x] 1.1 Map `COMPENSATORY` / `TIME_OFF` / `调休` / `调休假` to label `调休` and tone `TIME_OFF` in `leaveAppearance` and `leaveBadge`
- [x] 1.2 Map every other `LeaveType` enum name and catalog code to the Chinese label and new tone in the design table; never emit `请假` for a known type
- [x] 1.3 Treat the new leave tones as leave-like for hover hours, `isLeaveBadge`, and full-day merge
- [x] 1.4 Cover assembler cases: afternoon `COMPENSATORY` stays `调休` with 调休 color; morning punch kept; hover says 调休 not 请假

## 2. Unique leave colors

- [x] 2.1 Add BadgeCodes `MARRIAGE_LEAVE`, `MATERNITY_LEAVE`, `PATERNITY_LEAVE`, `BEREAVEMENT_LEAVE`, `WORK_INJURY_LEAVE`, `NURSING_LEAVE`, `BREASTFEEDING_LEAVE`, `PRENATAL_EXAM_LEAVE`, `FAMILY_PLANNING_LEAVE`
- [x] 2.2 Put the locked hex values from design.md into `CustomerReportLegendColors` without changing existing 12 legend hexes
- [x] 2.3 Extend OpenAPI month-matrix badge/tone enums and bump matrix `formulaVersion` to `ATTENDANCE_MONTH_MATRIX_V3`
- [x] 2.4 Extend frontend `attendanceMonthMatrixBadgeCodes`, `AttendanceStatusKey`, `REPORT_BADGE_COLORS`, `attendanceLegend`, mapper `toneStatus`, and Excel `STATUS_TO_BADGE` with the same hexes
- [x] 2.5 Render the extra leave types on the matrix legend (wrap allowed) and use white text on dark fills (丧假 plus existing 年假/病假)
- [x] 2.6 Cover 陪产假/婚假/丧假 each using its own color, not uncolored and not colliding with 年假/病假/调休/加班

## 3. Rest-day punches and overtime

- [x] 3.1 For Saturday/Sunday/public holiday, set the calculation punch window from local midnight to the next-day overnight cut (or next midnight)
- [x] 3.2 Write daily `firstPunchAt` / `lastPunchAt` from all `dayPunches` for that business date, not only consumed punch IDs
- [x] 3.3 Show rest-day punch times in `composeSlot`; do not blank the cell just because `restDay` is true
- [x] 3.4 On a rest day with an approved OA overtime document and no punches, show `加班` with overtime tone instead of empty beige
- [x] 3.5 On a rest day with that document and punches, keep the times, apply overtime color, and put 加班 in hover
- [x] 3.6 Keep weekday overtime color requiring recognized minutes so late checkout without a form stays uncolored
- [x] 3.7 Cover orchestrator/assembler tests for Saturday two punches, Saturday overtime form without punches, and weekday pair unchanged

## 4. Exception overview details

- [x] 4.1 Add `exception-details` to the exceptions report fields and fill it from type + minutes + that day's punch pair
- [x] 4.2 Map `MISSING_PUNCH_PENDING` / `OVERDUE` to 上班缺卡 or 下班缺卡 using first/last punch; keep 旷工 for `ABSENCE`
- [x] 4.3 Keep 迟到 / 早退 / 虚假加班 type labels; put the concrete sentence in details
- [x] 4.4 Replace the exception sheet main columns with 考勤日期、异常类型、工号、姓名、部门、详情、处理状态; do not render 证据摘要 as a default column
- [x] 4.5 Point CSV/xlsx exception export at the same columns
- [x] 4.6 Cover mapper and calculator tests for 下班缺卡 details, 迟到 details, and 旷工 not relabeled as 缺卡超期

## 5. Verification

- [x] 5.1 Run assembler, orchestrator, calculator, customer-report, and export tests that cover this change
- [x] 5.2 Manually check 鞠园-style afternoon 调休 (yellow `调休`, morning time kept) and a 陪产假/婚假 cell with its new color
- [x] 5.3 Manually check a Saturday with punches and an overtime form (times + green) and a Saturday form without punches (`加班`, not empty beige)
- [x] 5.4 Manually check 异常总览: date + 上班缺卡/下班缺卡/迟到/早退/旷工 + 详情, no 证据摘要 column

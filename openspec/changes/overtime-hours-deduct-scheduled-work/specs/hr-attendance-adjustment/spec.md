## ADDED Requirements

### Requirement: HR can replace a missing punch with clock times

Principals with `ATTENDANCE_ADJUST:MANAGE` SHALL be able to save on-duty and/or off-duty clock times for one employee and business date, with a required reason. The adjustment is append-only. Recalculation of the OPEN month SHALL treat those times as punches, including rest days. Closed months MUST refuse. The 考勤日报, month matrix, and exception overview SHALL follow the new pin.

#### Scenario: Weekend overtime missing off-duty is filled
- **WHEN** HR saves an off-duty time of `21:00` for a Saturday overtime day that previously showed 漏刷
- **THEN** 考勤日报 下班 is `21:00`
- **AND** 异常总览 has no 下班缺卡 for that date

### Requirement: HR can override that day's overtime hours and clear exceptions

The same adjustment dialog SHALL accept an optional overtime-hours override (0.5 grid) and optional clears of `迟到`, `早退`, `缺卡`, or `旷工`. Overrides persist with actor and reason. After recalc, 加班日报 / 考勤日报 overtime columns SHALL equal the override, and cleared exception types SHALL NOT appear on 异常总览 for that person-date. Recalc MUST NOT drop the override.

#### Scenario: Override weekday overtime to 3 hours
- **WHEN** HR sets overtime hours to `3` for 2026-08-04
- **THEN** 考勤日报 加班 is `3`
- **AND** 加班日报 工作日加班 is `3`

#### Scenario: Clear late
- **WHEN** HR clears 迟到 for a person-date that had a late exception
- **THEN** 异常总览 has no 迟到 row for that person-date

## ADDED Requirements

### Requirement: One current exception per person, day, and report type

Within one published pin, the system SHALL persist at most one exception fact for each `(employeeId, businessDate, reportExceptionType)` where `reportExceptionType` is the type shown on 异常总览 (`迟到`, `早退`, `上班缺卡`, `下班缺卡`, `旷工`, `虚假加班`, and the other actionable types). Historical observations of the same stable case MUST NOT create extra rows. Query pages and the official exception sheet SHALL read those unique facts.

#### Scenario: Same person-day is not listed three times as 缺卡
- **WHEN** 李立飞 on 2026-08-17 would previously produce `MISSING_PUNCH_PENDING`, `MISSING_PUNCH_OVERDUE`, and a metric-fallback 缺卡
- **THEN** 异常总览 contains at most one 上班缺卡 and at most one 下班缺卡 for that date
- **AND** it does not also list a generic 缺卡 row for the same sides

#### Scenario: Recalculation replaces duplicate pin rows
- **WHEN** an authorized principal recalculates a company-month that currently has duplicate exception facts
- **THEN** the new pin's exception facts satisfy the uniqueness rule
- **AND** query of that month no longer returns the old duplicate rows

### Requirement: Covered leave SHALL suppress punch exceptions on that slot

If an effective snapped leave, time-off, outing, trip, or punch-exemption interval covers the morning work slot, the system MUST NOT emit 上班缺卡 or 迟到 for that person-day. If it covers the afternoon work slot, the system MUST NOT emit 下班缺卡 or 早退. If it covers the whole scheduled day, the system MUST NOT emit 旷工, 上班缺卡, or 下班缺卡 for lack of punches.

#### Scenario: Full-day personal leave is not 缺卡 plus 旷工
- **WHEN** 李立飞 has approved 事假 `09:00–12:00` and `13:00–17:00` on 2026-08-17
- **THEN** 异常总览 has no 上班缺卡, 下班缺卡, 迟到, 早退, or 旷工 for that date caused by missing punches
- **AND** 请假统计 still lists the leave documents

#### Scenario: Afternoon leave does not create 下班缺卡
- **WHEN** the employee punches in at 08:31 and has approved afternoon leave covering the off-duty slot
- **THEN** 异常总览 does not list 下班缺卡 for that date

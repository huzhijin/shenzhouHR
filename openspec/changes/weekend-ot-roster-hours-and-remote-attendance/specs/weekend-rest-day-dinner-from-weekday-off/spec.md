## ADDED Requirements

### Requirement: Rest-day overtime dinner uses weekday shift-off

On SATURDAY, SUNDAY, or PUBLIC_HOLIDAY, recognized overtime minutes SHALL subtract a 30-minute dinner window only when the snapped OA interval fully covers that window. The dinner window SHALL start at the employee's published shift-off for that date if work segments exist, otherwise at the same employee's current-season weekday template shift-off (latest WORK segment end on the most recent weekday in the same calculation window). The system MUST NOT fall back to `17:30` solely because the rest day has no published segments.

Lunch on rest days remains `12:00–13:00` and SHALL be deducted only when the snapped interval fully covers that hour.

Daily-fact overtime minutes and OA-fact recognized minutes for the same document MUST match.

#### Scenario: Yan Qian Saturday 09:00–18:00 is eight hours
- **WHEN** 颜倩 `SZST0036` has an effective overtime document snapping to `2026-08-29 09:00–18:00` Asia/Shanghai
- **AND** 2026-08-29 is Saturday with no published shift segments
- **AND** her August weekday template shift-off is `18:00`
- **THEN** dinner is `18:00–18:30` and is not covered
- **AND** recognized hours are `8.0` (9.0 minus 1.0 lunch)
- **AND** 日报加班小时, 加班统计, and 个人月度工时 overtime for that day are `8.0`, not `7.5`

#### Scenario: Saturday evening still deducts dinner
- **WHEN** a rest-day overtime document snaps to `18:00–21:00` in summer
- **AND** weekday template shift-off is `18:00`
- **THEN** recognized hours are `2.5`

#### Scenario: Empty rest-day segments do not use 17:30
- **WHEN** `formOvertimeFromEvidence` is given only that Saturday's empty segment list
- **THEN** dinner start is still the weekday template off, not `17:30`

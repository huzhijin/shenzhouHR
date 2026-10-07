## ADDED Requirements

### Requirement: Overtime report hours SHALL come from the snapped OA interval minus meal deductions

For an approved, activated overtime document, recognized overtime minutes SHALL equal the duration of the Asia/Shanghai interval after `OaIntervalGrid` snapping, minus meal minutes that apply under `overtime-meal-window-coverage`. The system MUST NOT use punch pairing, punch ∩ form, or intersection with scheduled WORK segments as the overtime hour total.

Start and end shown on 加班统计 SHALL be the snapped clocks. Hours SHALL be that recognized duration in hours and MUST be a multiple of 0.5 (including 0).

Daily-fact paid, compensatory, and voluntary overtime minutes for the same person-month SHALL equal the sum of those document hours split by OA overtime type, so 月度工时 matches 加班统计.

#### Scenario: Weekday evening form with no covering punches still has hours
- **WHEN** an approved overtime document snaps to `18:00–21:00` on a summer weekday
- **AND** the employee has no punch pair inside that window
- **THEN** 加班统计 hours for that row are `2.5` (3.0 minus summer dinner 0.5)
- **AND** 开始时间 is `18:00` and 结束时间 is `21:00`
- **AND** 月度工时 计薪加班 or 加班换调休 includes `2.5` hours according to the overtime type

#### Scenario: Saturday daytime form is not zeroed by punch pairing
- **WHEN** Saturday is a rest day
- **AND** an approved overtime document snaps to `08:30–17:00`
- **THEN** hours are `7.5` (8.5 minus weekend lunch 1.0)
- **AND** the hours are not `0` even if punch count that day is odd or empty

#### Scenario: Minutes below 30 snap to the hour before duration is taken
- **WHEN** an approved OA overtime runs `18:10–21:00` Asia/Shanghai on a summer weekday
- **THEN** calculation uses `18:00–21:00`
- **AND** hours are `2.5`

#### Scenario: Minutes from 30 up to 59 snap to half past before duration is taken
- **WHEN** an approved OA overtime runs `18:45–21:10` Asia/Shanghai on a summer weekday
- **THEN** calculation uses `18:30–21:00`
- **AND** hours are `2.5` (dinner `18:00–18:30` is not fully covered)

#### Scenario: Degenerate snap remains zero hours
- **WHEN** an approved OA overtime runs `18:00–18:17`
- **THEN** the snapped interval is `18:00–18:00`
- **AND** recognized minutes are `0`
- **AND** the row still appears with start and end shown as `18:00`

#### Scenario: Fake overtime does not zero the document hours
- **WHEN** an overtime interval covers un-leaved scheduled work
- **THEN** the exception 虚假加班 still appears
- **AND** 加班统计 hours remain the snapped form duration minus applicable meals

#### Scenario: Query page does not print punch-tail decimals
- **WHEN** recognized minutes after snapping and meals are 150
- **THEN** 加班统计 小时 is `2.5`
- **AND** the cell is not `2.5000000000000004` or `1.3333333333333333`

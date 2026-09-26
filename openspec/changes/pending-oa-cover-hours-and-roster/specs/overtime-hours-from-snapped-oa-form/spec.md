## MODIFIED Requirements

### Requirement: Overtime report hours SHALL come from the snapped OA interval minus meal deductions

For a pending or approved, activated overtime document, recognized overtime minutes SHALL equal the duration of the Asia/Shanghai interval after `OaIntervalGrid` snapping, minus scheduled WORK segments on weekdays, minus meal minutes that apply under `overtime-meal-window-coverage`. The system MUST NOT use punch pairing, punch ∩ form, last-punch capping, or `capToLastPunch` as the overtime hour total.

Start and end shown on 加班统计 SHALL be the snapped clocks. Hours SHALL be that recognized duration in hours and MUST be a multiple of 0.5 (including 0). Query pages and 报表中心 MUST render those hours as `0` / `0.5` / `1` / `1.5` … and MUST NOT print IEEE tails such as `63.400000000000006`.

Daily-fact paid, compensatory, and voluntary overtime minutes for the same person-month SHALL equal the sum of those document hours split by OA overtime type.

#### Scenario: Weekday evening form with no covering punches still has hours
- **WHEN** an overtime document snaps to `18:00–21:00` on a summer weekday
- **AND** the employee has no punch pair inside that window
- **THEN** 加班统计 hours for that row are `2.5`
- **AND** 开始时间 is `18:00` and 结束时间 is `21:00`

#### Scenario: Last punch after the form end does not create tenths
- **WHEN** an overtime document snaps to `18:00–21:00` on a summer weekday
- **AND** the last punch that day is `21:12`
- **THEN** hours remain `2.5`
- **AND** the cell is not `2.7` or `2.2`

#### Scenario: No punch does not zero a valid form
- **WHEN** an overtime document snaps to `18:00–21:00` on a summer weekday
- **AND** the employee has no afternoon punch
- **THEN** hours are `2.5`
- **AND** they are not `0` because `capToLastPunch` returned null

#### Scenario: Finance total is not a float tail
- **WHEN** recognized minutes across the month sum to 3804
- **THEN** 财务加班 加班费 displays `63.4` only if 63.4 is a 0.5-grid value; otherwise it displays the 0.5-grid hour total
- **AND** the cell text is never `63.400000000000006`

## ADDED Requirements

### Requirement: OA form clocks that are already on the hour or half-hour stay on that grid after meals and work subtraction

OA overtime submitted on `:00` or `:30` SHALL remain on the 30-minute grid after weekday WORK subtraction and meal windows of 30 or 60 minutes. The system MUST NOT introduce `.2`, `.3`, or `.7` hour cells from punch clocks.

#### Scenario: Shao Zexiang style totals stay on the half-hour
- **WHEN** each OA overtime for a person in August is on the hour or half-hour
- **THEN** 平时加班, 周末加班, 节假日加班, and 加班费 are each a multiple of 0.5
- **AND** 罗毅-style `28.2` and 丁建权-style `33.6` do not appear from punch tails

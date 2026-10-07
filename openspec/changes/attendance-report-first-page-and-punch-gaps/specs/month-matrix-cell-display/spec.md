## ADDED Requirements

### Requirement: Employees with effective OA in the month SHALL have a matrix row
An employee who is on the authorized company roster and has at least one effective OA attendance document overlapping the requested month MUST appear as a month-matrix row even when they have no attendance-group daily facts for that month. Empty scheduled weekdays on that row SHALL paint as 漏刷. Dates covered by an effective OA document SHALL use that document's legend color and MUST NOT be painted as 漏刷 solely because punches are missing.

#### Scenario: 霍岩 appears because of overtime
- **WHEN** 霍岩 `SZST0445` is on the 江苏神州 roster in 客户现场服务部-大连办事处
- **AND** an approved overtime document covers 2026-08-01
- **AND** the employee has no daily-fact row in the query-page matrix
- **THEN** the official 考勤报表 month matrix still includes 霍岩
- **AND** 2026-08-01 uses overtime legend color
- **AND** other scheduled weekdays without punches use 漏刷 color

#### Scenario: OA color outranks 漏刷
- **WHEN** a weekday has an effective leave, outing, trip, or overtime document and no punches
- **THEN** the cell uses the document color
- **AND** the cell is not 漏刷

### Requirement: Document color outranks rest-day color
When a calendar date is a rest day and also has an effective OA overtime (or other attendance) document, the cell SHALL use the document legend color, not rest-day beige. Punch times, if present, remain visible on that color.

#### Scenario: Saturday overtime is not rest-day beige
- **WHEN** 2026-08-01 is Saturday and 霍岩 has an approved overtime document that day
- **THEN** the cell uses overtime color
- **AND** MUST NOT use rest-day color as the primary fill

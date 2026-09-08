## ADDED Requirements

### Requirement: OA interval endpoints SHALL snap down to a 30-minute grid

Before leave, overtime, time-off, outing, or trip documents enter calculation or report hours, the system SHALL convert each endpoint to Asia/Shanghai local time and replace its clock as follows:

- minute `0` or `30`, and seconds already zero: keep the existing clock (previous logic)
- `0 ≤ minute < 30`: set minute and second to `0` (整点)
- `30 ≤ minute < 60`: set minute to `30` and second to `0` (半点)

Start and end SHALL be snapped independently. The snapped interval is the only interval used for recognized minutes, overlap with shifts, overtime presence, and query-page hours. Raw OA instants remain in the source tables for audit.

#### Scenario: Minutes below 30 snap to the hour
- **WHEN** an approved OA leave runs `09:17–12:00` Asia/Shanghai
- **THEN** calculation and the leave report use `09:00–12:00`
- **AND** recognized hours follow the existing half-day shift windows on that snapped interval

#### Scenario: Minutes from 30 up to 59 snap to half past
- **WHEN** an approved OA overtime runs `18:45–21:10` Asia/Shanghai
- **THEN** calculation and the overtime report use `18:30–21:00`

#### Scenario: Exact hour or half-hour is unchanged
- **WHEN** an approved OA leave already runs `13:00–17:30`
- **THEN** the interval used for hours is still `13:00–17:30`

#### Scenario: Overnight document keeps the calendar day split
- **WHEN** an approved OA overtime runs `23:50–07:20` the next morning
- **THEN** the snapped interval is `23:30–07:00` the next morning
- **AND** minutes still belong to the overnight attendance day under the existing cut-over rule

#### Scenario: Degenerate interval after snapping is zero hours
- **WHEN** an approved OA document runs `09:00–09:17`
- **THEN** the snapped interval is `09:00–09:00`
- **AND** recognized minutes for that document are `0`
- **AND** the document row still appears with start and end shown as `09:00`

### Requirement: Query and official hours SHALL use the same snapped documents

Leave hours, time-off hours, overtime hours, and the leave components of 月度工时 SHALL be derived from the snapped intervals. The query pages and the official report center SHALL not show a different hour total for the same document in the same pin.

#### Scenario: Leave query hours match the work-hours leave component
- **WHEN** 张珍珍 has one approved annual-leave document of snapped duration 4.5 hours in the month
- **THEN** 请假统计 hours for that document are 4.5
- **AND** 月度工时 年假小时 is 4.5
- **AND** those 4.5 hours are not also added again as generic 请假小时

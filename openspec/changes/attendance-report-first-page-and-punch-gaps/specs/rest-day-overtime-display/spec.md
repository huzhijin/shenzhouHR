## ADDED Requirements

### Requirement: Rest-day punches from Deli SHALL be persisted on the daily fact
Rest-day clock times that are attached to the employee MUST be written to `first_punch_at` / `last_punch_at` on that calendar date even when `scheduled_minutes` is 0. The matrix MUST NOT drop rest-day punches during projection.

#### Scenario: 彭伟 Saturday 8/8 has Excel clocks
- **WHEN** 彭伟 `SZST0335` has complementary Deli workbook times on Saturday 2026-08-08 and no overtime document that day
- **THEN** the month-matrix cell shows those times
- **AND** the cell is not an empty rest-day block

### Requirement: Overtime legend ranks above rest-day legend
The customer report primary cell color order SHALL place overtime (and other effective OA document colors) above rest-day. A rest day with an effective overtime document MUST render overtime color even if the day also carries the REST_DAY badge.

#### Scenario: Rest-day badge does not hide overtime
- **WHEN** a Saturday cell has both REST_DAY and RECOGNIZED_OVERTIME badges
- **THEN** the visible fill is overtime color
- **AND** punch times remain if present

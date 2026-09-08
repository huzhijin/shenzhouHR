## ADDED Requirements

### Requirement: Daily time-off is a person-by-day query sheet

The system SHALL provide a query report 「调休日报」 at `/attendance/queries/time-off-daily` for principals with `ATTENDANCE_REPORT_QUERY:READ`. Each row is one authorized employee and one calendar date with recognized 调休 hours that day. The sheet MUST NOT be the existing balance page 「调休额度」.

#### Scenario: Menu and empty month
- **WHEN** the principal opens 调休日报 for a company-month with no 调休 documents
- **THEN** the table is empty with the standard empty hint
- **AND** 调休额度 remains a separate menu item

#### Scenario: One person two days
- **WHEN** 张三 has approved 调休 4.0h on 2026-08-06 and 8.0h on 2026-08-12
- **THEN** 调休日报 lists two rows for 张三 with those dates and hours

### Requirement: Daily time-off filters match daily overtime granularity

The sheet SHALL filter by department, employee, and date range inside the month.

#### Scenario: Department filter
- **WHEN** the operator selects one department
- **THEN** rows whose organization is outside that department (including descendants if the tree filter is on) are omitted

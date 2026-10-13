## ADDED Requirements

### Requirement: Daily overtime is a first-class query report

A principal with `ATTENDANCE_REPORT_QUERY:READ` MUST see 「每日加班查询」 in the left menu at `/attendance/queries/finance-overtime`. The same allow-list MUST also keep 加班日报、考勤日报、补签、请假汇总 visible when the server sends those items.

#### Scenario: Menu shows daily overtime
- **WHEN** login menu includes `/attendance/queries/finance-overtime` and the principal has `ATTENDANCE_REPORT_QUERY:READ`
- **THEN** `authorizedMenu` contains that path
- **AND** opening it queries sheet `finance-overtime`

### Requirement: 加班费 and 转调休 columns after holiday overtime

The report-center daily overtime table and the query daily-overtime / finance-overtime sheets SHALL add two numeric hour columns immediately after 「节假日加班」: 「加班费」 and 「转调休」. Values MUST come from that person-day's paid and compensatory overtime minutes (hours, one decimal).

#### Scenario: Mixed treatments on one person-month
- **WHEN** an employee has 4.0h 加班费 and 2.0h 转调休 in the filtered window, of which 2.0h fall on a public holiday
- **THEN** the row shows holiday overtime 2.0, 加班费 4.0, 转调休 2.0
- **AND** the same two columns exist on the report-center 「每日加班」 export

### Requirement: Calendar cells use overtime-treatment colors

On the daily overtime calendar, each day cell with recognized overtime SHALL use a distinct fill for 加班费, 转调休, and 义务加班. A day with more than one treatment MUST still be distinguishable (split fill or the dominant treatment plus hover listing all).

#### Scenario: Compensatory Saturday is not the same green as paid overtime
- **WHEN** Saturday has only 转调休 hours
- **THEN** that cell's fill is not the paid-overtime / generic overtime color
- **AND** hover names 转调休

### Requirement: Daily overtime filters are finer than month and person

The query sheet SHALL filter by overtime treatment (加班费 / 转调休 / 义务加班), date range inside the month, department, and employee. Empty filters mean all authorized rows.

#### Scenario: Filter 转调休
- **WHEN** the operator sets overtime treatment to 转调休
- **THEN** rows whose compensatory hours in range are 0 are omitted

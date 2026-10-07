## ADDED Requirements

### Requirement: Time-off statistics is a new query page
The system SHALL add a 「调休统计表」 item in the 「查询报表」 menu immediately after the existing 「调休额度」 item, at path `/attendance/queries/time-off-stat`. The existing 「调休额度」 page SHALL remain unchanged. Visibility SHALL use `ATTENDANCE_REPORT_QUERY:READ`.

#### Scenario: New menu appears beside existing time-off
- **WHEN** a principal with `ATTENDANCE_REPORT_QUERY:READ` opens the query-report menu
- **THEN** both 「调休额度」 and 「调休统计表」 are listed

### Requirement: Time-off statistics columns without seniority bands
Each row SHALL be one employee for one calendar year and SHALL include sequence, level-one department, level-two department, name, opening hours, overtime-credit hours, remaining days, remaining hours, and used amounts for months 1–12. The table MUST NOT include hire date, company tenure, prior tenure, cumulative tenure, entitled days by seniority, or new-hire calendar entitled days. Current opening balances are the 31 July ledger used as the August opening (effective 2026-08-01). For months before that opening month the used cell SHALL display `/`. For months on or after the opening month with no usage the cell SHALL display `0`. For months with usage the cell SHALL display the used amount.

#### Scenario: Pre-opening months show a slash
- **WHEN** the 2026 time-off statistics table is shown and opening starts 2026-08
- **THEN** columns for January through July display `/` and August onward display numeric used amounts

#### Scenario: Time-off table omits annual-leave seniority columns
- **WHEN** the time-off statistics page renders
- **THEN** it does not show 按累计工龄当年应休天数 or 新员工计算年休假日历天数

### Requirement: Time-off detail can adjust quota with an audit ledger
Clicking an employee SHALL open a detail drawer. The drawer SHALL load the live time-off account for that year. A principal with `ANNUAL_LEAVE:ADJUST` SHALL be able to post an hour adjustment with a required reason using the existing time-off adjust API. The drawer SHALL list ledger entries including the new adjustment. A principal without that capability SHALL see the drawer read-only.

#### Scenario: Adjuster saves time-off hours and sees the ledger line
- **WHEN** an authorized adjuster subtracts 8 hours with a reason on the 2026 time-off statistics detail
- **THEN** the live remaining balance updates in the drawer and a ledger entry records actor, time, −8 hours, reason, and source

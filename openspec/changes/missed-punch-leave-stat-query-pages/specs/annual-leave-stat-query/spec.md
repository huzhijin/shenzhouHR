## ADDED Requirements

### Requirement: Annual-leave statistics is a new query page
The system SHALL add a 「年假统计表」 item in the 「查询报表」 menu immediately after the existing 「年休假」 item, at path `/attendance/queries/annual-leave-stat`. The existing 「年休假」 page SHALL remain unchanged. Visibility SHALL use `ATTENDANCE_REPORT_QUERY:READ`.

#### Scenario: New menu appears beside existing annual leave
- **WHEN** a principal with `ATTENDANCE_REPORT_QUERY:READ` opens the query-report menu
- **THEN** both 「年休假」 and 「年假统计表」 are listed

### Requirement: Annual-leave statistics columns and opening window
Each row SHALL be one employee for one calendar year and SHALL include sequence, level-one department, level-two department, name, hire date, company tenure, proven prior tenure, cumulative tenure years, entitled days by cumulative tenure, new-hire calendar entitled days, remaining days, remaining hours, and used days for months 1–12. When new-hire calendar entitled days have no value, the cell SHALL display `0`. Current opening balances are the 31 July ledger used as the August opening (effective 2026-08-01). For months before that opening month the used-days cell SHALL display `/`. For months on or after the opening month with no usage the cell SHALL display `0`. For months with usage the cell SHALL display the used amount.

#### Scenario: Pre-opening months show a slash
- **WHEN** the 2026 annual-leave statistics table is shown and opening starts 2026-08
- **THEN** columns for January through July display `/` and are not omitted

#### Scenario: August with no usage shows zero
- **WHEN** an employee has no annual-leave usage in 2026-08 after opening
- **THEN** the August used cell displays `0`

#### Scenario: Missing new-hire calendar days show zero
- **WHEN** an employee is not on the new-hire calendar proration
- **THEN** the new-hire calendar entitled-days cell displays `0` rather than blank

### Requirement: Annual-leave detail can adjust quota with an audit ledger
Clicking an employee SHALL open a detail drawer. The drawer SHALL load the live annual-leave account for that year. A principal with `ANNUAL_LEAVE:ADJUST` SHALL be able to post an hour adjustment with a required reason using the existing annual-leave adjust API. The drawer SHALL list ledger entries including the new adjustment. A principal without that capability SHALL see the drawer read-only. The list page MAY keep pinned snapshot totals and MUST NOT silently overwrite them without explanation.

#### Scenario: Adjuster saves hours and sees the ledger line
- **WHEN** an authorized adjuster adds 8 hours with reason 「补发额度」 on the 2026 annual-leave statistics detail
- **THEN** the live remaining balance updates in the drawer and a ledger entry records actor, time, +8 hours, reason, and source

#### Scenario: Viewer cannot edit
- **WHEN** a principal has query-report read but not `ANNUAL_LEAVE:ADJUST`
- **THEN** the detail shows remaining quota and ledger without an adjust control

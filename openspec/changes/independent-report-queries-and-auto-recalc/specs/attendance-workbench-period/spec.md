## ADDED Requirements

### Requirement: Workbench defaults to the current month and allows choosing the window
The organization attendance workbench SHALL default to the current calendar month in Asia/Shanghai instead of only today. The page SHALL let the user switch among 当天, 本月, and a specific month. Summary cards, trend, type distribution, organization ranking, and the exception list SHALL follow the selected window. Reads SHALL use the qualifying pinned snapshot for that month (or today within the month) and MUST NOT run the month calculation engine. The workbench remains behind `ATTENDANCE_DASHBOARD:READ` and the principal's company / organization / self data scope.

#### Scenario: Default landing shows the month
- **WHEN** an authorized dashboard user opens 考勤工作台 with no period query
- **THEN** the title and metrics cover the current month to date, not only today's exceptions

#### Scenario: User switches to today or another month
- **WHEN** the user selects 当天 or 2026-07
- **THEN** the same widgets refresh for that window from the matching pin without a full month recalculation

#### Scenario: Workbench month has no punch data
- **WHEN** the user selects a month with no committed source watermark and no pin
- **THEN** the workbench shows that the period has no punch or calculation data and does not fail the page with a server error

#### Scenario: Organization scope still applies
- **WHEN** a department-scoped user views the monthly workbench
- **THEN** counts and lists include only employees in that organization scope

### Requirement: My attendance defaults to the current month and allows choosing the window
The employee self-service 「我的考勤」 page SHALL default to the current calendar month in Asia/Shanghai instead of only today. The page SHALL let the user switch among 当天, 本月, and a specific month. Summary, trend, exception list, and related personal metrics SHALL follow the selected window. Reads MUST be limited to the signed-in employee (`ATTENDANCE_SELF:READ` and self data scope) and MUST NOT run the company-month calculation engine. When the selected month has no punch or pin data, the page SHALL show the same missing-data hint as query pages and MUST NOT fail with a server error.

#### Scenario: Default my-attendance landing shows the month
- **WHEN** an authorized employee opens 「我的考勤」 with no period query
- **THEN** the metrics cover the current month to date for that employee, not only today's punches

#### Scenario: Employee switches month
- **WHEN** the employee selects 当天 or 2026-07
- **THEN** the page refreshes that employee's data for the window without exposing other employees

#### Scenario: Employee month has no punch data
- **WHEN** the employee selects a month with no committed source watermark and no pin
- **THEN** the page states that the period has no punch or calculation data and does not fail with a server error

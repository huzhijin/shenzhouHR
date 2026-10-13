## ADDED Requirements

### Requirement: Missed-punch statistics is a new query page
The system SHALL add a 「忘打卡统计表」 item in the 「查询报表」 menu immediately after the existing 「忘打卡」 item, at path `/attendance/queries/missed-punch-stat`. The existing 「忘打卡」 page SHALL remain unchanged. Visibility SHALL use `ATTENDANCE_REPORT_QUERY:READ`. The page SHALL be a new query sheet and MUST NOT reuse the missed-punch event-list layout.

#### Scenario: New menu appears beside existing missed-punch
- **WHEN** a principal with `ATTENDANCE_REPORT_QUERY:READ` opens the query-report menu
- **THEN** both 「忘打卡」 and 「忘打卡统计表」 are listed, and the statistics page is reachable at `/attendance/queries/missed-punch-stat`

#### Scenario: Existing missed-punch list is unchanged
- **WHEN** a principal opens `/attendance/queries/missed-punch`
- **THEN** the page still shows one row per missing-punch event with date, punch side, and details

### Requirement: Day cells match attendance-detail slots for miss and makeup
Each employee-month row SHALL use one cell per calendar day with morning and afternoon slots. A normal punched slot SHALL show the clock time and MUST have no background color. A missing slot SHALL show 「漏刷」 and the missed-punch legend color on that slot only. A made-up slot SHALL show red text 「补签」 or 「补签HH:mm」 when a correction time exists, with no makeup background fill. Leave, outing, trip, rest, or exemption covering a slot MUST NOT be labelled 漏刷.

#### Scenario: Normal day has times and no fill
- **WHEN** both slots have punches and neither is a correction or miss
- **THEN** the cell shows morning and afternoon times and has no background color

#### Scenario: Morning miss is labelled and colored
- **WHEN** the morning slot is missing and the afternoon slot has a punch
- **THEN** morning text is 「漏刷」 with missed-punch color and afternoon shows the punch time

#### Scenario: Makeup shows red text
- **WHEN** a slot was filled by an approved punch correction at 08:18
- **THEN** that slot shows red 「补签08:18」 without a correction fill color

### Requirement: Missed-punch statistics query pages by employee
The GET for this sheet SHALL read the latest qualifying pinned snapshot, MUST NOT run month calculation, SHALL page by employee, and SHALL apply company, department (including descendants), employee, and sheet filters in SQL. The default result set SHALL include only employees who have at least one 漏刷 or 补签 slot in the month. Filters SHALL include miss vs makeup and morning vs afternoon miss.

#### Scenario: Default list hides fully normal employees
- **WHEN** an authorized user queries 忘打卡统计表 for a pinned month without extra filters
- **THEN** employees whose every slot is a normal punch, rest, or covered leave are omitted

#### Scenario: Query does not calculate
- **WHEN** no qualifying pin exists for the selected company-month
- **THEN** the page explains that calculation has not finished and the server does not calculate inside that GET

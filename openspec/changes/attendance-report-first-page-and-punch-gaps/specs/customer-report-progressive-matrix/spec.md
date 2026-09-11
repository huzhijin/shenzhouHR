## ADDED Requirements

### Requirement: First matrix page is interactive within five seconds
When an authorized user opens the official 考勤报表 month matrix for 江苏神州 (or any company of similar size, no more than 5,000 active employees) with no employee filter and a complete pinned snapshot, the system SHALL return the first page of 50 employees within 5 seconds on the customer environment. The page SHALL contain day cells for that page, not an empty “暂无记录” table and not all-zero KPI cards presented as final results.

#### Scenario: Open 江苏神州 2026-08 with no person selected
- **WHEN** an authorized user opens `/attendance/reports` for company 江苏神州 and period 2026-08 without selecting an employee
- **THEN** the first 50 employee rows of the month matrix are visible within 5 seconds
- **AND** the header does not claim 数据尚未加载 as a finished state
- **AND** the grid is not replaced by “当前筛选条件下暂无记录”

#### Scenario: Loading is not painted as empty
- **WHEN** the first matrix page has not yet returned
- **THEN** the UI shows an in-progress preview banner
- **AND** MUST NOT paint KPI cards as authoritative zeros for 范围员工 / 出勤人日 / 异常人日

### Requirement: Remaining employees fill in without blocking the first page
After the first page is shown, the system SHALL continue loading subsequent pages of 50 employees in the background and append them to the same matrix until every authorized employee for that company-month is present. KPI and “共 N 名授权员工” SHALL update to the full count only after the last page arrives. Until then the banner SHALL say the grid is a preview.

#### Scenario: Progressive fill to the full company
- **WHEN** the first 50 rows are on screen and the pinned snapshot has 551 employees
- **THEN** the client keeps requesting later pages
- **AND** the table grows until 551 rows are shown
- **AND** the truncation notice “超过最大加载行数” is not used for this unfiltered company view

#### Scenario: Filtered employee stays a single request
- **WHEN** the user selects one employee
- **THEN** the system loads that employee’s row in one request
- **AND** does not keep paging the whole company

### Requirement: Official matrix GET pages pinned facts in SQL
Each month-matrix GET SHALL read only the requested employee page from the pinned daily-fact (and related OA/exception) tables. It MUST NOT load every daily fact for the company-month into the request heap in order to slice `page`/`size` in memory. It MUST NOT run company-month calculation inside an ordinary GET when a complete pin exists.

#### Scenario: Page 0 does not load page 10 employees
- **WHEN** GET `/api/v1/attendance-reports/month-matrix` is called with `page=0&size=50` and a pin exists
- **THEN** the SQL employee list is limited to those 50 identities
- **AND** day cells loaded for that response are only those employees’ dates
- **AND** the response includes `totalEmployees` for the full authorized set so the client knows when to stop paging

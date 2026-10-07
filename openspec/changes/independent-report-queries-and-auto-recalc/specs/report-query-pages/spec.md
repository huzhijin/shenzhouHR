## ADDED Requirements

### Requirement: Independent query reports appear as a new assignable menu
The system SHALL expose a distinct 「查询报表」 menu group that lists every monthly report sheet as its own page: 考勤明细, 请假统计, 加班统计, 月度工时, 异常总览, 迟到统计, 忘打卡, 出勤率, and 年休假. The existing 「考勤报表」 center SHALL remain available under its current capability. Visibility of the query menu SHALL require an assignable capability `ATTENDANCE_REPORT_QUERY:READ` that can be granted or revoked in role administration. A principal who has that capability MUST see all nine query pages, not a subset. Roles that already have `ATTENDANCE_REPORT:READ` SHALL receive `ATTENDANCE_REPORT_QUERY:READ` by default at migration without removing the ability to change that later.

#### Scenario: Assigned role sees every query page
- **WHEN** a principal whose role includes `ATTENDANCE_REPORT_QUERY:READ` opens the application menu
- **THEN** the 「查询报表」 group lists all nine report pages and each page is reachable

#### Scenario: Role without the query capability does not see the group
- **WHEN** a principal has `ATTENDANCE_REPORT:READ` but the query capability has been revoked from their role
- **THEN** the official report center remains available and the 「查询报表」 group is not shown

#### Scenario: Role administration can assign the query capability
- **WHEN** an authorized role administrator grants or revokes `ATTENDANCE_REPORT_QUERY:READ` on a role
- **THEN** subsequent sessions for principals with that role gain or lose the entire query menu accordingly

#### Scenario: Query API denies without the capability
- **WHEN** a caller without `ATTENDANCE_REPORT_QUERY:READ` requests a query-report endpoint
- **THEN** the system denies the request without returning report rows

### Requirement: Each query page filters and pages on the server
Each query page SHALL accept company, department (including descendants), employee number, paging, and every page-specific filter listed below, SHALL apply those filters in the database against the pinned snapshot facts for that sheet, and SHALL return only the requested page. The client SHALL NOT fetch every page to assemble the view. Department and employee pickers SHALL remain on every query page as filter controls; their option lists SHALL load from an authorized directory that is not the month-matrix endpoint. The system MUST NOT drop these filters or apply them only in the browser.

Required page-specific filters:

- 考勤明细: attendance status and in-month date
- 请假统计: start–end period, leave type, approval state
- 加班统计: start–end period, overtime treatment (paid / compensatory / duty), occurrence day
- 月度工时: employment status (在职 / 本月入职 / 本月离职)
- 异常总览: start–end period, exception type, severity, state
- 迟到统计: late-count band and late-minute band
- 忘打卡: punch side (上班缺卡 / 下班缺卡)
- 出勤率: attendance type or below-rate threshold
- 年休假: balance band and department level one / two

Leave, overtime, and exception period filters SHALL accept at least presets 本月, 上月, 近三个月, and a custom start–end range up to 12 months. A range that spans calendar months SHALL read the matching pinned facts for each covered month and still return a single paged result. Monthly summary pages (work hours, attendance rate, annual leave, matrix) MAY keep a month selector in addition to their listed filters.

#### Scenario: All fine filters are accepted on leave
- **WHEN** an authorized user queries 请假统计 with department (including descendants), employee number, leave type 事假, approval state, and a custom date range
- **THEN** the server applies every submitted filter in SQL and does not ignore department, employee number, leave type, or the date range

#### Scenario: Document period longer than one month
- **WHEN** an authorized user queries 请假统计 or 异常总览 with 近三个月 or a custom range covering more than one calendar month, and each covered month has a qualifying pin
- **THEN** the paged result includes matching document or exception rows from every covered month and still returns only the requested page size

#### Scenario: Exception query returns one filtered page
- **WHEN** an authorized user queries 异常总览 for a pinned company-month with exception type 缺卡待补正, page size 50, page 0
- **THEN** the response contains at most 50 matching exception rows from the pinned exception facts and a total count, without loading unrelated OA or time-account facts for that request

#### Scenario: Changing page does not reload the whole month in the browser
- **WHEN** the user opens page 2 of a query report that has more than one page
- **THEN** the client requests only that page from the server and does not loop remaining pages in the background

#### Scenario: Directory does not use the month matrix
- **WHEN** a query page loads department and employee filter options for a company-month
- **THEN** the options come from an authorized people or organization directory and not from paging the attendance month matrix

### Requirement: Query pages read the pin and never calculate
An authorized query-page GET SHALL return rows from the latest complete pinned snapshot whose formula catalog version matches the current calculator. It MUST NOT run the company-month calculation engine, MUST NOT persist a projection, and MUST NOT wait for an in-flight calculation to finish. When no qualifying pin exists, the system SHALL return a retryable not-ready error. While a request is in flight, the query page MUST disable the query and refresh actions and MUST NOT present all-zero summary cards or the empty copy 「当前筛选条件下暂无记录」 as if the filters produced no data.

#### Scenario: Warm query uses stored facts only
- **WHEN** a qualifying pin exists and the user submits the page's fine filters
- **THEN** the system returns filtered pinned rows within the warm one-second budget without starting month calculation

#### Scenario: Small row counts are still served from SQL
- **WHEN** a query such as 年休假 returns on the order of tens of rows for a pinned company-month
- **THEN** the server still uses indexed SQL paging and MUST NOT load the full company-month daily, OA, exception, and account facts to produce that page

#### Scenario: Missing pin does not block the browser for minutes
- **WHEN** an authorized user opens a query page for a company-month that has no qualifying pin
- **THEN** the page explains that calculation has not finished and the server does not calculate inside that GET

#### Scenario: Query during load does not report zero records from empty memory
- **WHEN** the user clicks 「查询」 before the first page has returned
- **THEN** the control is disabled or ignored until the request finishes, and the UI does not claim 0 records from an empty client cache

### Requirement: Query pages share the official pin and authorization
Query pages, the official report center, dashboard, and export for the same company-month SHALL use the same latest qualifying pin unless a request carries an older snapshot token. Every query SHALL re-resolve the current principal's data scope before returning rows. Company-level totals MUST NOT leak employees outside the intersection of scope and filters.

#### Scenario: Organization-scoped query page
- **WHEN** a department-scoped user opens 请假统计 for a company pin that includes other departments
- **THEN** only leave rows in the current organization scope (including descendants when configured) are returned

#### Scenario: Same pin as the official center
- **WHEN** the official report center and the leave query page are opened for the same company-month without an older token
- **THEN** both expose the same snapshot token and the same leave business values for the overlapping employees

### Requirement: Missing source months are hinted, not failed
When the requested period covers a month with no committed Deli or OA watermark and no qualifying pin, the system SHALL return a successful empty page for that month and SHALL tell the user that the period has no punch or calculation data. It MUST NOT return a server error. When some months in a range have pins and others have no source data, the system SHALL return rows from the months that have pins and SHALL name the months that were omitted. When a month has committed source watermarks but no qualifying pin, the system SHALL indicate that calculation has not finished, without calculating inside the GET.

#### Scenario: Query June when only July and August have punches
- **WHEN** an authorized user queries a leave or exception page for 2026-06 and that company has no committed source watermark and no pin for June
- **THEN** the response is successful, contains no rows, and states that the period has no punch or calculation data

#### Scenario: Last-three-months includes an empty month
- **WHEN** an authorized user queries 近三个月 and only two of the three months have qualifying pins while the other month has no source data
- **THEN** the page returns matching rows from the months that have data and lists the month that has none

#### Scenario: Filter match is empty after a successful pin read
- **WHEN** a qualifying pin exists and the submitted fine filters match no rows
- **THEN** the page reports that the current filters have no records, and this copy is not used while the request is still loading

### Requirement: Data scope is applied on every query page request
Having `ATTENDANCE_REPORT_QUERY:READ` SHALL only grant access to the query menu and APIs. Every query, directory, and export request MUST re-resolve the principal's company, organization (including descendants when the scope says so), or self data scope and MUST return only the intersection of that scope and the submitted filters. Department and employee pickers MUST list only in-scope identities. A filter that names an out-of-scope organization or employee MUST NOT return that subject's rows. The system MUST NOT reveal whether another company has punches or pins.

#### Scenario: Department head cannot query another organization by employee number
- **WHEN** a principal whose data scope is one organization submits an employee number that belongs to a different organization in the same company
- **THEN** the query returns no that employee's rows and the employee picker does not list that person

#### Scenario: Company list is authorized companies only
- **WHEN** a principal is authorized for one company and opens a query page
- **THEN** the company selector contains only that company and requesting another companyId is denied without describing the other company's data

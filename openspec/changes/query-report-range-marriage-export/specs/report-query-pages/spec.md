## MODIFIED Requirements

### Requirement: Each query page filters and pages on the server

Each query page SHALL accept company, department (including descendants), employee number, paging, and every page-specific filter listed below, SHALL apply those filters in the database against the pinned snapshot facts for that sheet, and SHALL return only the requested page. The client SHALL NOT fetch every page to assemble the view. Department and employee pickers SHALL remain on every query page as filter controls; their option lists SHALL load from an authorized directory that is not the month-matrix endpoint. The system MUST NOT drop these filters or apply them only in the browser.

Required page-specific filters:

- 考勤明细: attendance status and in-month date range
- 请假统计: start–end period, leave type, approval state
- 加班统计: start–end period, overtime treatment (paid / compensatory / duty), occurrence day
- 月度工时: employment status (在职 / 本月入职 / 本月离职)
- 异常总览: start–end period, exception type, severity, state
- 迟到统计: late-count band and late-minute band
- 忘打卡: punch side (上班缺卡 / 下班缺卡)
- 出勤率: attendance type or below-rate threshold
- 年休假: balance band and department level one / two

All query sheets except 年假统计表 and 调休统计表 SHALL use a start–end date range as the only period control, with presets 本月, 上月, and 近三个月, and a custom range up to 12 months. They MUST NOT show a separate 月份 picker. 年假统计表 and 调休统计表 SHALL keep a 自然年 year picker and query that calendar year. A range that spans calendar months SHALL read the matching pinned facts for each covered month and still return a single paged result, except 考勤明细 which MUST reject a range that is not contained in one calendar month.

#### Scenario: All fine filters are accepted on leave
- **WHEN** an authorized user queries 请假统计 with department (including descendants), employee number, leave type 事假, approval state, and a custom date range
- **THEN** the server applies every submitted filter in SQL and does not ignore department, employee number, leave type, or the date range

#### Scenario: Query period UI has start and end dates only
- **WHEN** an authorized user opens 请假统计, 异常总览, 月度工时, or 考勤明细
- **THEN** the period group shows 起止日期 and does not show a 月份 month picker

#### Scenario: Annual-leave statistics keep a year picker
- **WHEN** an authorized user opens 年假统计表 or 调休统计表
- **THEN** the period group shows 自然年 and not a month picker or a start–end range

#### Scenario: Document period longer than one month
- **WHEN** an authorized user queries 请假统计 or 异常总览 with 近三个月 or a custom range covering more than one calendar month, and each covered month has a qualifying pin
- **THEN** the paged result includes matching document or exception rows from every covered month and still returns only the requested page size

#### Scenario: Attendance matrix rejects a cross-month range
- **WHEN** the user selects a start date and end date that fall in different calendar months on 考勤明细
- **THEN** the client refuses the range with a 暂不支持跨月 warning
- **AND** the warning does not tell the user to pick a 月份 first

#### Scenario: Exception query returns one filtered page
- **WHEN** an authorized user queries 异常总览 for a pinned company-month with exception type 缺卡待补正, page size 50, page 0
- **THEN** the response contains at most 50 matching exception rows from the pinned exception facts and a total count, without loading unrelated OA or time-account facts for that request

#### Scenario: Changing page does not reload the whole month in the browser
- **WHEN** the user opens page 2 of a query report that has more than one page
- **THEN** the client requests only that page from the server and does not loop remaining pages in the background

#### Scenario: Directory does not use the month matrix
- **WHEN** a query page loads department and employee filter options for a company-month
- **THEN** the options come from an authorized people or organization directory and not from paging the attendance month matrix

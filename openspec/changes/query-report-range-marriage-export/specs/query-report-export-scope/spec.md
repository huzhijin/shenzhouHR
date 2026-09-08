## ADDED Requirements

### Requirement: Query reports SHALL export the visible table or calendar, not drawers

Every query-report sheet SHALL offer export when the principal has export create and download capabilities. The workbook SHALL contain the same columns the table shows, excluding action columns such as 详情 and 改打卡. Drawer contents (month calendar, leave-account ledger, exception field list) SHALL NOT be written as extra sheets. 考勤明细 export SHALL remain the sign-in / sign-out calendar workbook for the selected date range. 每日加班 export SHALL remain the wide date-column workbook. The official report-center export at `/attendance/reports` is out of scope and MUST keep its current behavior.

#### Scenario: Leave statistics export matches the table
- **WHEN** an authorized user exports 请假统计
- **THEN** the xlsx columns are 工号, 姓名, 部门, 假别, 开始时间, 结束时间, 小时, 审批状态
- **AND** there is no 详情 column and no drawer dump

#### Scenario: Attendance matrix export stays a calendar
- **WHEN** an authorized user exports 考勤明细
- **THEN** each included employee occupies a 签到 row and a 签退 row with one column per date in the selected range
- **AND** the file is not a list of 异常天数 only

#### Scenario: Report center export is unchanged
- **WHEN** a user clicks 「导出当前报表」 on `/attendance/reports`
- **THEN** the existing report-center export path still runs
- **AND** this change does not alter its job, columns, or download copy

### Requirement: Query export SHALL offer all matching rows or the current page

The query export endpoint SHALL accept `exportScope=ALL` or `exportScope=PAGE`. When `exportScope` is omitted it SHALL behave as `ALL`. `ALL` SHALL encode every matching row up to the existing export row cap. `PAGE` SHALL encode only the same `page` and `size` as the on-screen query. Query pages SHALL let the user choose page size 50, 100, or 200 and SHALL send that size on both query and `PAGE` export.

#### Scenario: Export all downloads every matching leave row
- **WHEN** 请假统计 has 120 matching rows across three pages of size 50 and the user chooses 导出全部
- **THEN** the xlsx contains all 120 rows

#### Scenario: Export current page downloads one page of people
- **WHEN** 考勤明细 page 2 of size 50 is on screen and the user chooses 导出当前页
- **THEN** the calendar workbook contains those 50 people as 签到/签退 pairs
- **AND** it does not include people from other pages

#### Scenario: Page size 200 is used for query and page export
- **WHEN** the user sets 每页 200, queries, then exports 当前页
- **THEN** the query request and the export request both use size 200
- **AND** the workbook has at most 200 data people or rows for that page

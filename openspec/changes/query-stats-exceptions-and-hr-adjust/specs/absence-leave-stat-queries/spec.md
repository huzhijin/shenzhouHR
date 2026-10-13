## ADDED Requirements

### Requirement: 旷工统计表 is a person-by-date wide table like 每日加班查询

The system SHALL add query page `/attendance/queries/absence-stat` labeled 旷工统计表, visible with `ATTENDANCE_REPORT_QUERY:READ`. Layout SHALL match 每日加班查询: one row per employee, identity columns, an absence-hours total, then one column per date in the selected range. A cell with no absence is blank, not 0. Hours come from pinned daily `absenceMinutes`. Export SHALL use the same visible columns. Existing 异常总览 旷工 rows remain.

#### Scenario: Wide table lists absence hours by date
- **WHEN** an authorized user queries 旷工统计表 for 2026-08-01 to 2026-08-31
- **THEN** each row is a person who has at least one day with absence minutes in the range
- **AND** a day with 8 absence hours shows 8
- **AND** a day with 0 absence hours is blank
- **AND** a person whose absence minutes are all 0 is omitted

#### Scenario: Same capability as other query pages
- **WHEN** a role has `ATTENDANCE_REPORT_QUERY:READ` and not a new capability
- **THEN** 旷工统计表 appears in 查询报表

### Requirement: 请假统计表 is a person-by-date wide table like 每日加班查询

The system SHALL add query page `/attendance/queries/leave-stat` labeled 请假统计表. Layout SHALL match 每日加班查询: one row per employee, identity columns, total leave hours in range, then one column per date. Cell value is recognized leave hours for that day; cell color SHALL follow the 考勤明细 leave-type legend when a single leave type covers the day. Empty days are blank. Existing 请假统计 (one OA document per row) and 请假汇总 MUST remain.

#### Scenario: Wide table lists leave hours by date
- **WHEN** an authorized user queries 请假统计表 for a range that includes a 事假 afternoon of 4.5 hours
- **THEN** that date cell shows 4.5
- **AND** 请假统计 still lists the OA document as its own row

#### Scenario: Document list is not replaced
- **WHEN** the user opens 请假统计
- **THEN** the page is still the document list at `/attendance/queries/leave`

## ADDED Requirements

### Requirement: Work-hours query page is named 月度工时统计表

The query sheet `work-hours` SHALL use the Chinese label 月度工时统计表 on the sidebar, query page title, and Excel sheet name. The path `/attendance/queries/work-hours` and sheet key `work-hours` MUST NOT change.

#### Scenario: Menu and page title
- **WHEN** a principal with `ATTENDANCE_REPORT_QUERY:READ` opens the sidebar
- **THEN** the item that navigates to `/attendance/queries/work-hours` is labeled 月度工时统计表
- **AND** the query page heading is 月度工时统计表

#### Scenario: Export file uses the new name
- **WHEN** an authorized user exports 月度工时统计表
- **THEN** the workbook sheet name is 月度工时统计表

### Requirement: Query-report menu group sits at the bottom of the sidebar

The sidebar group 查询报表 (every item whose path starts with `/attendance/queries/`) SHALL render after 工作区、人员、考勤设置、数据源、 and 权限 groups. It MUST NOT sit immediately under 工作区.

#### Scenario: Query reports are last
- **WHEN** an HR admin with all menu capabilities opens the app
- **THEN** 查询报表 is the last navigation group
- **AND** 员工 / 考勤组 / 考勤机数据 appear above it

### Requirement: Late, makeup, and absence query pages omit people with no matching data

迟到统计 SHALL list only employees whose pinned daily facts in the query range have `late_minutes > 0`. 补签 SHALL list only punch-correction documents (employees with no makeup document in range MUST NOT appear). 旷工统计表 SHALL list only employees whose pinned daily facts in the range have `absence_minutes > 0`. Zero values MUST NOT produce a person row. Empty date cells on 旷工统计表 stay blank.

#### Scenario: Late page drops a person with no late minutes
- **WHEN** 张三 has 0 late minutes in the selected range and 李四 has 12
- **THEN** 迟到统计 contains 李四
- **AND** 迟到统计 does not contain 张三

#### Scenario: Makeup page is documents only
- **WHEN** only 王五 has an approved punch-correction in the range
- **THEN** 补签 lists 王五's makeup row
- **AND** employees without a makeup document are absent

#### Scenario: Absence stat drops a person with no absence
- **WHEN** 赵六 has 0 absence minutes in the range and 钱七 has 8 hours on one day
- **THEN** 旷工统计表 contains 钱七
- **AND** 旷工统计表 does not contain 赵六

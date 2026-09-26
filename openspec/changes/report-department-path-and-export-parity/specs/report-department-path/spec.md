## ADDED Requirements

### Requirement: Report department column is the full short-name path
Customer reports that show a department SHALL display one 「部门」 column whose value is the hyphen-joined short names from 一级部门 down to the assignment leaf, walking `organization_current_closure` and skipping the COMPANY node. Deeper nodes SHALL be appended with `-`. The system MUST NOT omit 一级部门 when a deeper level exists. The system MUST NOT include the company legal name. The system MUST NOT display an organization UUID as the department field.

This supersedes the earlier concatenation that started at 二级部门.

#### Scenario: Three-level assignment includes the center
- **WHEN** an employee is assigned to RF-B组 under 工程二部 under 服务中心
- **THEN** the report 部门 column is `服务中心-工程二部-RF-B组`

#### Scenario: Deeper leaf keeps appending
- **WHEN** the assignment chain is 服务中心 / 工程一部 / MATCH组 / MATCH1组
- **THEN** the report 部门 column is `服务中心-工程一部-MATCH组-MATCH1组`

#### Scenario: First-level-only employee is not hyphenated
- **WHEN** an employee is assigned directly to 销售中心
- **THEN** the report 部门 column is `销售中心`

#### Scenario: Company root is not a department segment
- **WHEN** the ancestor chain is 江苏神州半导体科技股份有限公司 / 服务中心 / 工程二部 / RF-B组
- **THEN** the report 部门 column is `服务中心-工程二部-RF-B组`
- **AND** the company legal name does not appear in that column

### Requirement: Every displayed report sheet uses the same department column
请假统计、加班汇总、个人月度工时、考勤异常、迟到、忘打卡、出勤率、月度考勤明细矩阵, and 年休假汇总 SHALL each show department as that single 「部门」 column. 年休假汇总 MUST NOT keep separate 「一级部门」 and 「二级部门」 columns on the sheet.

Annual-leave filter controls MAY still offer 一级/二级 picks; those filters MUST NOT reintroduce split department columns on the grid.

#### Scenario: Annual leave sheet has one department column
- **WHEN** an authorized user opens 年休假汇总
- **THEN** the sheet header includes 「部门」
- **AND** it does not include 「一级部门」 or 「二级部门」 as data columns
- **AND** a row for an employee in RF-B组 under 工程二部 under 服务中心 shows `服务中心-工程二部-RF-B组`

#### Scenario: Work-hours and leave sheets use the same path
- **WHEN** the same employee appears on 个人月度工时 and 请假统计
- **THEN** both 部门 cells show the identical full path string

### Requirement: Long department paths wrap between hierarchy segments
When the department path does not fit the column, the system SHALL wrap only between path segments. A segment that itself contains `-` (for example `RF-B组`) MUST remain on one line. Wrapping MUST NOT split Chinese characters inside a segment. Row height SHALL grow with wrapped lines so identity columns, sticky matrix columns, and date cells stay aligned.

#### Scenario: Path wraps after each department level
- **WHEN** `服务中心-工程二部-RF-B组` is too wide for the department column
- **THEN** the visible lines are `服务中心-` then `工程二部-` then `RF-B组` (or the same segments wrapped at those boundaries)
- **AND** `RF-B组` is not split into `RF` and `B组`

#### Scenario: Matrix sticky columns stay aligned after wrap
- **WHEN** a month-matrix department cell wraps to more than one line
- **THEN** that employee's date cells share the same row height
- **AND** the sticky 工号, 姓名, and 部门 columns remain aligned with that row

### Requirement: Organization and employee directory stay short-name trees
The organization page tree and the employee-page left directory SHALL continue to render parent/child short names. Node titles MUST NOT become the hyphen-joined report path.

#### Scenario: Directory node stays a short name
- **WHEN** an authorized user opens the employee directory tree
- **THEN** 工程二部 is titled `工程二部`
- **AND** it is not titled `服务中心-工程二部`

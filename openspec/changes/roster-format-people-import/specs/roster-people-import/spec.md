## ADDED Requirements

### Requirement: Opening import is hidden from the operator
The operator-facing menu and `/people/import` SHALL NOT present the six-step 组织与员工期初导入 wizard (organization / employee / employment / prior-service templates). The roster import is the only people import entry.

#### Scenario: Menu no longer says opening import
- **WHEN** an operator with people-import permission opens the app
- **THEN** the people section offers roster import, not 「组织与员工期初导入」

### Requirement: Downloadable template matches the roster
The system SHALL provide one `.xlsx` template whose header row is exactly:

`序号 | 公司名称 | 工号 | 姓名 | 一级部门 | 二级部门 | 三级部门 | 组别 | 职位 | 入职日期`

The template SHALL NOT require 组织编码, 上级组织编码, or 外部精确员工ID. Empty levels in filled files MAY use `/` or blank. The template SHALL NOT include 副岗.

#### Scenario: Operator downloads the template
- **WHEN** the operator clicks download template on the people import page
- **THEN** the file is a single-sheet workbook with those ten Chinese headers in that order

### Requirement: One file imports departments and people together
The system SHALL parse roster rows, infer organization paths from 一级/二级/三级/组别 within each company, match or create those nodes, and create or update employees with employment on the leaf path. Publish SHALL write departments and people in one confirmation. Precheck MUST NOT write master data.

#### Scenario: New department path and new employee in one row
- **WHEN** a row has company 上海晟州聚能, number `SZJN0042`, name 高露浩, path 研发一部 / 硬件组, hire date 2026-09-02
- **THEN** precheck proposes creating 硬件组 under the existing R&D department aliased as 研发一部 / RD1, and creating the employee with employment on 硬件组

#### Scenario: Existing path only adds the person
- **WHEN** a 江苏神州 row uses 技术支持中心 / 现场服务部 / 武汉产品服务组
- **THEN** precheck does not propose a new organization for that path and proposes employment on the existing leaf

### Requirement: Company column selects the company
The system SHALL map `江苏神州` to 江苏神州半导体, `上海晟州聚能` to 上海晟州聚能半导体, and `上海昇州` to 上海昇州半导体. A leading `聚能-` on a department name SHALL be stripped. One workbook MAY contain multiple companies. 芯越 rows SHALL be rejected as blocking.

#### Scenario: Mixed-company workbook
- **WHEN** the file contains 江苏神州 and 上海晟州聚能 rows
- **THEN** precheck attributes each row to the mapped company and does not require the operator to pick a single company first

#### Scenario: Unknown company name
- **WHEN** a row company name does not map
- **THEN** that row is a blocking issue and is not published

### Requirement: Conflicts are shown before write
Precheck SHALL classify each row as added, updated, unchanged, conflict, or error. Publish of master data SHALL require operator confirmation. Blocking errors MUST be resolved or explicitly skipped; they MUST NOT silently write.

Conflicts MUST include at least:
- same company employee number with a different name
- same-file duplicate employee number
- department path that matches a name under a different parent
- existing employee whose current department differs from the file (proposed move)

Same name with a different number in the same company SHALL be a warning, not an automatic merge.

#### Scenario: Number exists with another name
- **WHEN** the file has `SZST0743` / 张立强 but that number already belongs to someone else in 江苏神州
- **THEN** the row is a conflict, and confirm is blocked until the operator resolves or skips it

#### Scenario: Person already in another department
- **WHEN** the number matches and the current employment leaf differs from the file path
- **THEN** precheck shows the current path and the proposed path as a conflict move

### Requirement: September roster hires are loaded
After the import is usable, the system SHALL contain these people with employment on the stated paths. Passwords, punch events, OA documents, and published report projections MUST NOT be rewritten by this load.

江苏神州 `SZST0731`–`SZST0747` from the current roster screenshot, including:
- `SZST0743` 张立强 on 技术支持中心 / 现场服务部 / 武汉产品服务组 with empty job title

上海晟州聚能:
- `SZJN0042` 高露浩 — 研发一部 / 硬件组 — 硬件助理工程师 — 2026-09-02
- `SZJN0043` 金凯雯 — 人事行政部 — 人事行政助理 — 2026-09-02
- `SZJN0044` 王明鑫 — 研发一部 / 测试组 — 测试助理工程师 — 2026-09-03
- `SZJN0045` 马泽成 — 研发一部 / 测试组 — 测试助理工程师 — 2026-09-03
- `SZJN0046` 韦天宇 — 研发一部 / 硬件组 — 硬件助理工程师 — 2026-09-04

`研发一部` MUST resolve to the existing 聚能 department currently named `RD1` (rename or alias, not a second R&D root). `硬件组` MUST be created under that department if missing. `人事行政部` MUST match the existing 聚能 node.

#### Scenario: Zhang Liqiang is in Wuhan product service
- **WHEN** the September 神州 rows are published
- **THEN** employee `SZST0743` 张立强 is ACTIVE with employment on 武汉产品服务组 under 技术支持中心 / 现场服务部

#### Scenario: Jueneng new hires land under existing departments
- **WHEN** the five 聚能 rows are published
- **THEN** `SZJN0042`–`SZJN0046` exist in 上海晟州聚能, 金凯雯 is under 人事行政部, and the other four are under the RD1 / 研发一部 tree (硬件组 or 测试组), with no second 聚能 R&D root

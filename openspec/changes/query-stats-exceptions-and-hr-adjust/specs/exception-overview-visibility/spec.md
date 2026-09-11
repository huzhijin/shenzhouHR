## ADDED Requirements

### Requirement: Exception overview SHALL hide undeclared overtime and long punch-span review

The 异常总览 default actionable list MUST NOT include `OVERTIME_DOCUMENT_MISSING_OR_LATE`（未报加班）or `LONG_PUNCH_SPAN_REVIEW`（长时在岗待审）. Filter options and Excel labels for those types MUST also be absent. The calculator MAY still persist them; query pages, 考勤报表异常 sheet, and workbench exception lists MUST NOT show them.

#### Scenario: Undeclared overtime is not listed
- **WHEN** an employee has last punch after 18:30 and no covering overtime document
- **THEN** 异常总览 does not contain a 未报加班 row for that person-day

#### Scenario: Long punch span is not listed
- **WHEN** first punch to last punch is at least 14 hours
- **THEN** 异常总览 does not contain a 长时在岗待审 row

### Requirement: Fake overtime SHALL be labeled 加班异常

`FAKE_OVERTIME` SHALL display as 加班异常 on 异常总览, query filters, Excel, and dashboards. The stored type code remains `FAKE_OVERTIME`.

#### Scenario: Overview row uses the new label
- **WHEN** overtime covers unleaved scheduled work
- **THEN** the exception type shown is 加班异常
- **AND** it is not labeled 虚假加班

### Requirement: Wuhan and Dalian people have no August 2026 exception rows

For business dates in 2026-08, employees whose report department path contains `武汉` or `大连` MUST NOT appear on 异常总览. Identification is the published department path, including 武汉办事处、武汉产品服务组、大连办事处 and descendants. This mute is only for 2026-08. 考勤明细 cells MAY still show 漏刷 or 迟到.

#### Scenario: Wuhan field service in August is silent
- **WHEN** an employee in 技术支持中心-现场服务部-武汉产品服务组 has a late punch on 2026-08-12
- **THEN** 异常总览 for 2026-08 has no row for that person-day

#### Scenario: Dalian office in August is silent
- **WHEN** an employee in 客户现场服务部-大连办事处 has a missing punch on 2026-08-05
- **THEN** 异常总览 for 2026-08 has no row for that person-day

#### Scenario: Other months still report
- **WHEN** the same Wuhan employee has a late punch on 2026-07-15
- **THEN** 异常总览 for that July range still lists the late row

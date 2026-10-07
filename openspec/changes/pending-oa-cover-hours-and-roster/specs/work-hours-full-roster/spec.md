## ADDED Requirements

### Requirement: Monthly work hours SHALL list every in-scope employee

月度工时 for a company and date window SHALL include every employee whose employment assignment is active in that company during the window, including standing punch-exempt people and people with no punches and no OA. The system MUST NOT drop a person solely because they have no daily-fact rows, no punches, or no overtime. People with no calculated minutes still appear, with scheduled/actual/overtime hours as zero or the published scheduled hours when shifts exist.

Punch-exempt scheduled work days SHALL display as normal white attendance on 考勤明细, the same as Jiangsu Shenzhou exempt people: not `漏刷`, not empty absence. Rest days remain rest days.

#### Scenario: Exempt employee with no punches still has a work-hours row
- **WHEN** an authorized user queries 月度工时 for 昇州 `2026-08`
- **AND** an in-scope employee is standing punch-exempt and has no August punches
- **THEN** that employee is a row on 月度工时
- **AND** 考勤明细 scheduled weekdays for that person are white normal attendance, not `漏刷`

#### Scenario: Ye Jian appears under Shengzhou
- **WHEN** 叶剑 employee number is `SZSZ0000`
- **AND** the operator opens 昇州 月度工时 for `2026-08`
- **THEN** 叶剑 is on that sheet
- **AND** he is not missing solely because he does not punch
- **AND** his login username remains `SZSZ0000`

### Requirement: Ye Jian assignment SHALL be Shengzhou

Employee `SZSZ0000` 叶剑 SHALL be assigned to 上海昇州半导体, not Jiangsu Shenzhou 总经办. The prior roster cutover that moved him to Jiangsu Shenzhou 总经办 MUST be reversed for current employment. Account username MUST stay `SZSZ0000`. Punch-exemption remains on this employee id.

#### Scenario: Shengzhou reports include Ye Jian after reassignment
- **WHEN** current employment for `SZSZ0000` is under 昇州
- **THEN** 昇州 directory, 月度工时, and 考勤明细 include 叶剑
- **AND** Jiangsu Shenzhou 月度工时 does not list him as a current Jiangsu employee

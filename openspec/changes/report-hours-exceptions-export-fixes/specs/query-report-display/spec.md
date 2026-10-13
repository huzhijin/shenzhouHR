## ADDED Requirements

### Requirement: Query tables SHALL have one action column

月度工时, 迟到统计, 忘打卡, and any other query sheet that currently renders both a `details`/`note` button and a `action` 「查看详情」 SHALL keep a single 「详情」 column as the opener. Content columns (`备注`, `说明`) SHALL show the business text or `—`, and MUST NOT turn into a second 「查看详情」 / 「查看」 button.

#### Scenario: Work-hours 备注 is not a duplicate opener
- **WHEN** 月度工时 lists 张珍珍 with shift label 无班次 stored on daily facts
- **THEN** the table has columns 应出勤小时, 计薪加班小时, 请假小时, 年假小时, 调休小时, 实际工时, 备注, 详情
- **AND** 备注 shows `—` or a real employment remark, not 「无班次查看」
- **AND** only 详情 is the button 「查看详情」

#### Scenario: Late 说明 is not a second 查看详情
- **WHEN** 迟到统计 has no per-row narrative text
- **THEN** 说明 is `—` or omitted
- **AND** 详情 is the only 「查看详情」 control

### Requirement: Detail drawers SHALL show only Chinese business fields

The query detail drawer SHALL list the same fields as the sheet's business columns, using the Chinese titles. It MUST NOT append leftover JSON keys such as `annualLeaveHours`, `timeOffHours`, `lateMinutes`, or `penalizedLateMinutes`. If those quantities are in scope, they SHALL appear under Chinese titles (`年假小时`, `调休小时`, `迟到分钟`, `计罚迟到分钟`).

#### Scenario: Work-hours drawer has no English keys
- **WHEN** the user opens 月度工时详情 for 张珍珍
- **THEN** the drawer shows 工号, 姓名, 部门, 应出勤小时, 计薪加班小时, 请假小时, 年假小时, 调休小时, 实际工时, 备注
- **AND** it does not show the labels `annualLeaveHours` or `timeOffHours`

#### Scenario: Late drawer has no English keys
- **WHEN** the user opens 迟到统计详情 for 刘锐
- **THEN** the drawer shows 工号, 姓名, 部门, 迟到次数, and Chinese minute fields when present
- **AND** it does not show the labels `lateMinutes` or `penalizedLateMinutes`

### Requirement: Work-hours 备注 SHALL not be the shift label

月度工时 备注 SHALL be an employment remark (本月入职 / 本月离职 / empty). It MUST NOT be `MAX(shift_label)` and MUST NOT display 「无班次」 for employees who have scheduled hours that month.

#### Scenario: Scheduled employee is not annotated 无班次
- **WHEN** an employee has 128 scheduled hours in the month
- **THEN** 备注 is `—` unless they joined or left that month
- **AND** 「无班次」 does not appear as the work-hours note

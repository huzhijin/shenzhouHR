## ADDED Requirements

### Requirement: Official monthly hours list the roster, not only daily facts

个人月度工时 (report-center sheet and Excel export) and 月度工时统计表 SHALL include every employee whose employment assignment overlaps the company and date window on the HR roster. The system MUST NOT drop a person solely because `attendance_report_daily_fact` is missing, punches are missing, or OA is missing.

Who is in scope is the HR 花名册 (employee number + current employment org path), not a side department spreadsheet. A name that is not on the roster (谭钊) MUST NOT be created.

Standing punch-exempt executives who are still employed — 陈觉晓 `SZST0000`, 朱培文 `SZST0001`, 熊都 `SZST0003` — SHALL have rows. Their scheduled workdays SHALL contribute published (or Yangzhou template) WORK minutes as full attendance, not zero hours and not `漏刷`.

Directory membership for the window SHALL use employment overlap with the window, not “still ACTIVE at month-end only”.

The query sheet 月度工时统计表 SHALL reuse the report-center `WORK_HOURS` dataset (same employees, same seven hour columns, same OA leave split). It MUST NOT independently aggregate `leave_or_time_off_minutes` from daily facts or invent 0-hour roster rows that the official sheet does not have. Column titles on the query sheet SHALL match the official names (`应出勤工时` / `加班时数` / `事假+病假+其他假期` / `年假` / `加班换调休` / `实际调休` / `个人实际出勤工时`). An employee-number column MAY precede them.

#### Scenario: Chen Juexiao has August hours without punches
- **WHEN** an authorized user opens 江苏神州 2026-08 个人月度工时
- **AND** 陈觉晓 `SZST0000` is standing punch-exempt and employed
- **THEN** the sheet contains 陈觉晓
- **AND** `{n}月应出勤工时` is the sum of weekday template WORK minutes for employed workdays
- **AND** 个人实际出勤 follows `应出勤 + 计薪加班 − (事假+病假+其他) − 年假 + 加班换调休 − 实际调休`

#### Scenario: Query work-hours equals report center
- **WHEN** the same company, month, and authorization open 个人月度工时 and 月度工时统计表
- **THEN** both sheets contain the same employee set
- **AND** for each employee, 应出勤、加班时数、事假+病假+其他、年假、加班换调休、实际调休、个人实际出勤 are equal
- **AND** query leave hours are not `daily leave_or_time_off − 年假 − 调休`

#### Scenario: Tan Zhao is not invented
- **WHEN** 谭钊 is not on the HR 花名册
- **THEN** no work-hours or matrix row is created for that name

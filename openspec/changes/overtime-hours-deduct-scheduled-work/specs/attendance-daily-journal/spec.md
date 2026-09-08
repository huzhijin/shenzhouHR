## ADDED Requirements

### Requirement: Daily journal is one person-day with punch and exception columns

查询报表 and 考勤报表 SHALL expose 「考勤日报」 as a flat table, one row per employee per business date in the selected start–end window:

`序号 | 部门 | 工号 | 姓名 | 日期 | 班次 | 上班 | 下班 | 迟到 | 早退 | 旷工 | 请假 | 加班 | 备注`

- 上班/下班 SHALL be `HH:mm` from first/last punch, or `漏刷` when that side is missing on a day that requires a punch.
- 迟到/早退/旷工/加班 SHALL show hours (0.5 grid) when minutes > 0, otherwise empty.
- 请假 SHALL show the leave type name when leave minutes > 0.
- 加班 hours SHALL use the same recognized daily overtime as 加班日报 (after WORK/meal deductions).

Excel export MUST use this same column layout (not the month-matrix sign-in/sign-out two-row workbook). Date range follows the report start–end filter.

#### Scenario: A worked weekday with evening overtime
- **WHEN** the journal covers a weekday with on-duty `08:29`, off-duty `21:00`, and 2.5 recognized overtime hours
- **THEN** the row shows those punch times and 加班 `2.5`

#### Scenario: Export matches the page
- **WHEN** an authorized user exports 考勤日报
- **THEN** the xlsx header is the same column titles as the page
- **AND** each exported row is one person-date

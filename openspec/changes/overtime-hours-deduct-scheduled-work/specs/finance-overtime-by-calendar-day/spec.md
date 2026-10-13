## ADDED Requirements

### Requirement: Finance overtime is a separate person-month hours matrix

查询报表 and 考勤报表 SHALL expose a new sheet 「财务加班」 (`finance-overtime`), in addition to existing 「加班日报」 and 「加班统计」. Those existing sheets MUST keep their current columns and one-row-per-person-day / one-row-per-document layouts.

「财务加班」 is one row per employee who has recognized overtime minutes greater than zero in the query window. Columns SHALL be 部门, 工号, 加班人, 平时加班, 周末加班, 节假日加班, then one column per calendar date in the window. Hours come from pinned daily facts (`day_type` + recognized overtime minutes), not from collapsing overtime documents.

- 平时加班: sum of recognized overtime on `WEEKDAY` or `ADJUSTED_WORKDAY`
- 周末加班: sum of recognized overtime on `SATURDAY` or `SUNDAY`
- 节假日加班: sum of recognized overtime on `PUBLIC_HOLIDAY` (even if that date is Saturday/Sunday)
- Daily cell: that date's recognized overtime hours, or blank when zero
- Date header: `M月D日` with ISO weekday number 1–7 (Monday=1) on the second header row
- Last row: 总计 of the exported people

Page and Excel export SHALL use this layout. This capability MUST NOT add payroll, salary, tax, or social-insurance fields.

#### Scenario: One person with weekday and weekend overtime is one row
- **WHEN** an employee has 2.5 recognized overtime hours on Tuesday and 7.5 on Saturday in the same month
- **THEN** 财务加班 has one row for that person
- **AND** 平时加班 is `2.5` and 周末加班 is `7.5`
- **AND** the Tuesday and Saturday date columns show those hours
- **AND** other date columns are blank

#### Scenario: Holiday Saturday is not weekend
- **WHEN** a public holiday falls on Saturday with 8 recognized overtime hours
- **THEN** 节假日加班 includes `8`
- **AND** 周末加班 does not include that day

#### Scenario: Existing overtime sheets stay unchanged
- **WHEN** an operator opens 加班日报 or 加班统计
- **THEN** 加班日报 is still one person-date per row with 工作日加班 / 周末加班 / 节假日加班
- **AND** 加班统计 is still one overtime document per row

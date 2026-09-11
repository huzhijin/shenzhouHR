## ADDED Requirements

### Requirement: Overtime query and report center list one document per row

加班统计 on the query pages and the overtime sheet on 考勤报表中心 SHALL list **one row per overtime document** (OA and paper). The system MUST NOT collapse a person-month into a single total row and MUST NOT show a department overtime summary block on these overtime sheets. One person with four overtime dates SHALL appear as four rows. Columns SHALL include employee number, name, department, overtime date or snapped start/end, overtime type, hours, reason when stored, approval state, and source (OA or 纸质).

#### Scenario: Four dates for one person are four rows
- **WHEN** 陈士庆 10012 has approved overtime on 8-18, 8-20, 8-23, and 8-25
- **THEN** 加班统计 and the report-center overtime sheet each show four rows for 10012

#### Scenario: Department totals are not on the overtime sheet
- **WHEN** an authorized user opens the report-center overtime sheet
- **THEN** there is no 「部门加班汇总」 table
- **AND** there is no one-row-per-person 计薪/转调休/义务/汇总 block

### Requirement: Monthly work-hours overtime columns remain daily-fact sums

月度工时 SHALL continue to show per-person monthly paid overtime, compensatory overtime, and used time-off as sums of daily facts. Changing overtime sheets to document detail MUST NOT remove those work-hours columns or change the actual-attendance formula.

#### Scenario: Work hours still total the month
- **WHEN** 10012 has two paid overtime documents of 2.5h and 3.0h in the month
- **THEN** 月度工时 加班时数 includes 5.5 hours
- **AND** 加班统计 still shows two document rows

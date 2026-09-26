## ADDED Requirements

### Requirement: Monthly work-hours sheet SHALL match the customer columns

The 个人月度工时 sheet SHALL expose, in order: 姓名, 部门, `{n}月应出勤工时`, 加班时数, 事假+病假+其他假期, 年假, 加班换调休, 实际调休, 个人实际出勤工时. `{n}` is the queried month number. A remarks column MAY follow.

#### Scenario: June headers include the month number
- **WHEN** the user opens 个人月度工时 for 2026-06
- **THEN** the scheduled-hours column label is `6月应出勤工时`
- **AND** both 加班换调休 and 实际调休 columns are present

### Requirement: Overtime-hours column SHALL count paid overtime only

加班时数 SHALL equal recognized paid overtime hours (`加班费`). Compensatory overtime and voluntary overtime MUST NOT be included in 加班时数.

#### Scenario: Paid and compensatory overtime are split
- **WHEN** an employee has 8 paid overtime hours and 4 compensatory overtime hours in the month
- **THEN** 加班时数 is 8.0
- **AND** 加班换调休 is 4.0

### Requirement: Personal actual hours SHALL use the signed formula

The system SHALL compute:

`个人实际出勤工时 = 应出勤工时 + 计薪加班 − (事假+病假+其他假期) − 年假 + 加班换调休 − 实际调休`

Where 事假+病假+其他假期 is all effective leave hours except 年假 and 调休 used, 加班换调休 is recognized compensatory overtime, and 实际调休 is approved 调休 used. Voluntary overtime and fake overtime SHALL NOT enter the formula.

#### Scenario: Formula with mixed leave and overtime
- **WHEN** scheduled hours are 176, paid overtime is 8, other leave (personal/sick/other) is 8, annual leave is 8, compensatory overtime is 4, and used time-off is 8
- **THEN** 个人实际出勤工时 is 164.0

#### Scenario: Demo formula must not subtract time-off twice
- **WHEN** live data is shown
- **THEN** 加班换调休 is added
- **AND** 实际调休 is subtracted
- **AND** the cell is not `confirmed work + all overtime`

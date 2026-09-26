## MODIFIED Requirements

### Requirement: Employees with effective OA in the month SHALL have a matrix row
An employee who is on the authorized company roster and has at least one effective OA attendance document overlapping the requested month MUST appear as a month-matrix row even when they have no attendance-group daily facts for that month. Dates covered by an effective OA document SHALL use that document's legend color and MUST NOT be painted as 漏刷 solely because punches are missing.

For **2026-08** dates only, employees whose department path contains `大连` or `武汉` SHALL paint empty scheduled weekdays as full-attendance white, not `漏刷`. From **2026-09**, those sites use the same OA-only weekday `漏刷` fill as other punch-required sites when they have no punches and no covering document.

#### Scenario: 霍岩 appears because of overtime
- **WHEN** 霍岩 `SZST0445` is on the 江苏神州 roster in 客户现场服务部-大连办事处
- **AND** an approved overtime document covers 2026-08-01
- **AND** the employee has no daily-fact row in the query-page matrix
- **THEN** the official 考勤报表 month matrix still includes 霍岩
- **AND** 2026-08-01 uses overtime legend color
- **AND** other scheduled weekdays without punches use full-attendance white, not 漏刷 color

### Requirement: Outing without times yields to overtime form display and hours

外出/出差单据没有可用时刻时，格子仍按日历日覆盖。同一天若有加班单，显示和加班小时 SHALL 以加班单取整后的起止为准，不得只写「外出」而无加班时刻。工作日加班小时仍扣除当天已发布 WORK 段（外出盖住上班时段等于把班次工时扣掉）。周末/节假日有外出又有加班单时，格子 SHALL 用加班色和加班单时刻，不得用休息日底色。

#### Scenario: Weekday outing plus overtime shows overtime clocks
- **WHEN** a weekday has an all-day outing and an overtime form `18:00–21:00`
- **THEN** the cell shows overtime start/end clocks, not `外出` / `加班` with no times
- **AND** recognized overtime hours deduct published WORK minutes

#### Scenario: Weekend outing plus overtime shows overtime
- **WHEN** Saturday has an outing without times and an overtime form
- **THEN** the cell uses overtime legend color and the form clocks
- **AND** it is not rest-day beige

#### Scenario: OA color outranks 漏刷
- **WHEN** a weekday has an effective leave, outing, trip, or overtime document and no punches
- **THEN** the cell uses the document color
- **AND** the cell is not 漏刷

#### Scenario: Dalian and Wuhan August documents keep color
- **WHEN** a 大连 or 武汉 employee in 2026-08 has overtime, leave, outing, or trip on a day
- **THEN** that cell uses the document legend color
- **AND** full-attendance white applies only to days without a covering OA document

## ADDED Requirements

### Requirement: Dalian and Wuhan OA-only weekdays are not missing-punch
The matrix assembler MUST NOT emit `MISSING_PUNCH` / `漏刷` for a Dalian or Wuhan employee-day solely because `oaOnlyEmployee` is true and the date is a weekday before as-of with no accumulator.

#### Scenario: Wuhan weekday without OA is white
- **WHEN** a 武汉产品服务组 employee has no punches and no OA on a Wednesday
- **AND** they are employed that day
- **THEN** the cell is ordinary attendance, not 漏刷

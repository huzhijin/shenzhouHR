## ADDED Requirements

### Requirement: Recognized overtime SHALL come from the snapped OA overtime interval

For an approved, activated overtime document, recognized overtime minutes SHALL be computed from the snapped OA interval, the employee's punches that fall in that interval, and the existing meal-deduction and fake-overtime rules. The system MUST NOT take the intersection of the overtime interval with weekday scheduled WORK segments as the overtime duration.

#### Scenario: Rest-day overtime is not zeroed by missing shift overlap
- **WHEN** Saturday is a rest day with no WORK segments
- **AND** an approved overtime document covers `09:00–17:30` after snapping
- **AND** the employee has on/off punches inside that window
- **THEN** overtime recognized minutes are greater than zero after weekend lunch (12:00–13:00) and any dinner rule that applies
- **AND** 加班统计 shows those hours on that document row
- **AND** 月度工时 计薪加班 or 加班换调休 includes them according to the overtime type

#### Scenario: Weekday overtime after off-duty is counted
- **WHEN** the scheduled shift ends at 17:30
- **AND** an approved overtime document covers `18:00–21:00` after snapping
- **AND** punches cover that window
- **THEN** recognized overtime is the eligible off-schedule presence in that window after meal deductions
- **AND** the hours are not required to overlap 08:30–17:30 WORK segments

#### Scenario: Overtime query start and end are the snapped clocks
- **WHEN** the overtime query page lists an approved overtime document
- **THEN** 开始时间 and 结束时间 are the snapped Asia/Shanghai clocks, not empty
- **AND** 小时 equals recognized overtime hours for that document, not `0.00` when punches and an approved form exist

#### Scenario: Fake overtime still does not count
- **WHEN** an overtime interval covers un-leaved scheduled work
- **THEN** that overlapping part is not recognized overtime
- **AND** the exception 虚假加班 still appears once for that person-day

### Requirement: Work-hours overtime columns SHALL follow paid vs compensatory split

月度工时 计薪加班小时 SHALL equal recognized paid overtime hours after snapping. 加班换调休 SHALL equal recognized compensatory overtime. Voluntary overtime MUST NOT enter 计薪加班小时 or the personal-actual formula.

#### Scenario: Paid rest-day overtime appears in work hours
- **WHEN** an employee has 7.5 recognized paid overtime hours on rest days in the month and no weekday paid overtime
- **THEN** 月度工时 计薪加班小时 is 7.5
- **AND** 个人实际工时 includes those 7.5 hours in the existing formula

## ADDED Requirements

### Requirement: Human-facing overtime hours SHALL sit on the overtime start date

考勤日报、月矩阵加班色与悬停小时、加班日报 SHALL attribute recognized minutes of an overtime document that continues after midnight and ends before the next day's published shift start entirely to the document's start calendar date. The next calendar date SHALL NOT show those continuation minutes unless it has a separate overtime document that starts that day.

#### Scenario: Overnight form hours all on 08-11
- **WHEN** an approved overtime document is 2026-08-11 18:00–2026-08-12 00:30 (or the snapped equivalent) and 08-12 shift start is 08:30
- **THEN** 考勤日报 08-11 加班 shows the full recognized hours
- **AND** 考勤日报 08-12 加班 does not include that continuation
- **AND** 08-12 matrix is not overtime-green solely because of 00:00–shiftStart continuation

#### Scenario: Next-day evening overtime stays on the next day
- **WHEN** Monday has overnight overtime ending Tuesday 02:00 and Tuesday has a separate approved form 18:00–21:00
- **THEN** Tuesday journal overtime includes only the Tuesday-starting form
- **AND** Monday journal overtime includes the overnight form

### Requirement: Finance overtime SHALL keep calendar-day splits

财务加班 (person × date cells, 平时/周末/节假日) SHALL keep splitting snapped overtime intervals at Asia/Shanghai midnight, then apply the existing weekday WORK / meal deductions per calendar slice. A Friday 21:00–Saturday 02:00 form SHALL put Saturday's slice in 周末加班 when Saturday is a rest day.

#### Scenario: Weekday overnight split for finance
- **WHEN** a weekday form is 21:00–next-day 02:00 and dinner does not apply to that interval
- **THEN** 财务加班 start date cell includes 3.0 hours
- **AND** the next date cell includes 2.0 hours

#### Scenario: Friday night into Saturday
- **WHEN** overtime is Friday 21:00–Saturday 02:00 and Saturday is SATURDAY
- **THEN** 平时加班 includes Friday's slice
- **AND** 周末加班 includes Saturday's slice

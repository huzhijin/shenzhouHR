## ADDED Requirements

### Requirement: Daily form overtime uses the same rest-day lookup as OA facts

`formOvertimeFromEvidence` and `allocateFormOvertime` SHALL resolve rest-day dinner using the employee's segments for the whole calculation window (or an equivalent weekday-template off), not only the current day's possibly empty segment list. Recognized minutes written to `attendance_report_daily_fact` MUST equal `attendance_report_oa_fact.recognized_minutes` for the same snapped document.

#### Scenario: Daily fact matches OA fact on Saturday 09:00–18:00
- **WHEN** OA fact recognized hours are `8.0` for a Saturday `09:00–18:00` summer form
- **THEN** the daily fact `recognized_overtime_minutes` for that start date are `480`
- **AND** Excel 加班导出 for that cell is `8`, not `7.5`

#### Scenario: Per-day empty segments still see weekday off
- **WHEN** Saturday `daySegments` is empty
- **AND** August weekdays for that employee end at `18:00`
- **THEN** rest-day dinner starts at `18:00`, not `17:30`

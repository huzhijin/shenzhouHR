## ADDED Requirements

### Requirement: Attendance rate SHALL count a half-day as 0.5 days

`actualAttendanceDays` and the attendance-rate numerator SHALL accept 0, 0.5, and 1 per scheduled work day. A half-day is one published morning or afternoon work segment, not 3.5/8 or 4.5/8 of a day. Paid leave on a segment still counts as attendance for that half-day. Unpaid 事假 on a segment does not.

#### Scenario: Morning work and afternoon personal leave is 0.5
- **WHEN** the employee works the morning segment and takes approved afternoon 事假
- **THEN** actualAttendanceDays for that date is 0.5
- **AND** scheduledAttendanceDays is 1
- **AND** that day contributes 50% toward the monthly rate

#### Scenario: Morning annual leave and afternoon work is 1.0
- **WHEN** the employee takes approved morning 年休假 and works the afternoon
- **THEN** actualAttendanceDays for that date is 1.0

#### Scenario: Morning overtime-related leave and afternoon work
- **WHEN** the employee takes approved morning leave after overnight overtime and works the afternoon
- **THEN** the morning half-day follows the leave type's attendance rule
- **AND** the afternoon half-day is 0.5 actual attendance if worked

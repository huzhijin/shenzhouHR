# Attendance Rate by Days Specification

## Purpose

Defines how attendance rate is calculated using a day-based formula instead of minute-based, where paid leave days count as attendance and remain in the denominator.

## ADDED Requirements

### Requirement: Attendance rate SHALL use day-based formula

The system SHALL calculate attendance rate using the formula: `actualAttendanceDays ÷ scheduledAttendanceDays × 100%`

Where:
- `scheduledAttendanceDays`: Total work days in the period (including paid leave days)
- `actualAttendanceDays`: Days with valid attendance (punch records + approved paid leave days)

This replaces the previous minute-based formula.

#### Scenario: Normal attendance with no leave
- **WHEN** an employee has 22 scheduled work days and punched in for all 22 days
- **THEN** attendance rate = 22 ÷ 22 × 100% = 100.00%

#### Scenario: Attendance with paid annual leave
- **WHEN** an employee has 22 scheduled work days, punched in for 20 days, and took 2 days approved annual leave
- **THEN** scheduledAttendanceDays = 22 (includes leave days)
- **AND** actualAttendanceDays = 20 (punch) + 2 (annual leave) = 22
- **AND** attendance rate = 22 ÷ 22 × 100% = 100.00%

#### Scenario: Attendance with unpaid personal leave
- **WHEN** an employee has 22 scheduled work days, punched in for 20 days, and took 2 days unpaid personal leave
- **THEN** scheduledAttendanceDays = 22 (includes leave days)
- **AND** actualAttendanceDays = 20 (only punch records, personal leave does not count)
- **AND** attendance rate = 20 ÷ 22 × 100% = 90.91%

#### Scenario: Attendance with absence
- **WHEN** an employee has 22 scheduled work days, punched in for 20 days, and was absent for 2 days (no leave request)
- **THEN** scheduledAttendanceDays = 22
- **AND** actualAttendanceDays = 20
- **AND** attendance rate = 20 ÷ 22 × 100% = 90.91%

### Requirement: Paid leave days SHALL count as actual attendance

The system SHALL count all approved paid leave types as actual attendance days (increment actualAttendanceDays by 1 per day).

Paid leave types include:
- Annual leave (年假)
- Compensatory leave (调休)
- Sick leave (病假) - even if partially paid
- Marriage leave (婚假)
- Maternity leave (产假)
- Paternity leave (陪产假)
- Bereavement leave (丧假)
- Work injury leave (工伤假)
- Prenatal checkup/nursing leave (孕检假/哺乳假)

#### Scenario: Sick leave counts as attendance
- **WHEN** an employee takes 1 day approved sick leave
- **THEN** actualAttendanceDays SHALL increment by 1
- **AND** attendance rate SHALL not be reduced

#### Scenario: Multiple paid leave types in same month
- **WHEN** an employee has 22 scheduled days, punches 19 days, takes 2 days annual leave and 1 day sick leave
- **THEN** actualAttendanceDays = 19 + 2 + 1 = 22
- **AND** attendance rate = 100.00%

### Requirement: Unpaid leave SHALL NOT count as actual attendance

The system SHALL NOT count unpaid leave as actual attendance days.

Unpaid leave types include:
- Personal leave (事假)

#### Scenario: Personal leave reduces attendance rate
- **WHEN** an employee takes 1 day approved personal leave
- **THEN** actualAttendanceDays SHALL NOT increment
- **AND** attendance rate SHALL be reduced accordingly

### Requirement: Paid leave days SHALL remain in scheduled attendance

The system SHALL NOT subtract paid leave days from scheduledAttendanceDays. Paid leave days remain in the denominator.

#### Scenario: Scheduled days unchanged by paid leave
- **WHEN** an employee takes 5 days annual leave in a month
- **THEN** scheduledAttendanceDays SHALL still equal the total calendar work days
- **AND** the denominator SHALL NOT be reduced by 5

### Requirement: Attendance rate SHALL display with 2 decimal places

The system SHALL format attendance rate as a percentage with exactly 2 decimal places (e.g., "95.45%").

#### Scenario: Rounding to 2 decimal places
- **WHEN** calculated attendance rate is 20 ÷ 22 = 0.909090...
- **THEN** system SHALL display "90.91%"

#### Scenario: Exact percentage
- **WHEN** calculated attendance rate is 22 ÷ 22 = 1.0
- **THEN** system SHALL display "100.00%"

### Requirement: Zero scheduled days SHALL show N/A

The system SHALL display "N/A" for attendance rate when scheduledAttendanceDays is zero (to avoid division by zero).

#### Scenario: No scheduled work days
- **WHEN** a period has zero scheduled work days (e.g., all weekends/holidays)
- **THEN** attendance rate SHALL display "N/A"
- **AND** system SHALL NOT throw a division error

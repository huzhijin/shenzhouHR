# Absence from Late Specification

## Purpose

Defines the rule that converts severe lateness (≥30 minutes) into absence (旷班), which results in loss of attendance credit for the entire work segment.

## ADDED Requirements

### Requirement: Lateness of 30 minutes or more SHALL be treated as absence

The system SHALL treat any lateness of 30 minutes or more as absence (旷班) instead of lateness (迟到).

When lateness ≥ 30 minutes:
- The employee SHALL NOT receive attendance credit for that day (actualAttendanceDays -= 1)
- The absence penalty SHALL be the full scheduled work minutes for that segment (e.g., 480 minutes)
- Late minutes SHALL be zero (the time is counted as absence, not lateness)

#### Scenario: Late by exactly 30 minutes becomes absence
- **WHEN** scheduled start time is 09:00 and employee arrives at 09:30
- **THEN** system SHALL record this as absence, not lateness
- **AND** actualAttendanceDays SHALL NOT increment for this day
- **AND** absenceMinutes SHALL be 480 (full day penalty)
- **AND** lateMinutes SHALL be 0

#### Scenario: Late by more than 30 minutes becomes absence
- **WHEN** scheduled start time is 09:00 and employee arrives at 10:00
- **THEN** system SHALL record this as absence
- **AND** actualAttendanceDays SHALL NOT increment for this day
- **AND** absenceMinutes SHALL be 480 (full day penalty)
- **AND** lateMinutes SHALL be 0

#### Scenario: Late by less than 30 minutes remains lateness
- **WHEN** scheduled start time is 09:00 and employee arrives at 09:20
- **THEN** system SHALL record this as lateness, not absence
- **AND** lateMinutes SHALL be 20 (after grace period deduction)
- **AND** absenceMinutes SHALL be 0
- **AND** actualAttendanceDays MAY still increment (if no other issues)

### Requirement: Grace period SHALL be applied before 30-minute threshold check

The system SHALL first apply the configured grace period to lateness, then check if the resulting late time exceeds 30 minutes.

#### Scenario: Late time after grace period determines threshold
- **WHEN** scheduled start time is 09:00, grace period is 15 minutes, and employee arrives at 09:40
- **THEN** raw lateness = 40 minutes
- **AND** after grace period = 40 - 15 = 25 minutes
- **AND** 25 < 30, so this remains lateness
- **AND** lateMinutes SHALL be 25

#### Scenario: Grace period does not prevent absence conversion
- **WHEN** scheduled start time is 09:00, grace period is 15 minutes, and employee arrives at 09:50
- **THEN** raw lateness = 50 minutes
- **AND** after grace period = 50 - 15 = 35 minutes
- **AND** 35 ≥ 30, so this becomes absence
- **AND** absenceMinutes SHALL be 480
- **AND** lateMinutes SHALL be 0

### Requirement: 30-minute threshold SHALL be a fixed rule

The 30-minute threshold for converting lateness to absence SHALL be a fixed business rule, not a configurable parameter.

Grace period (e.g., 15 minutes) MAY be configured per attendance group, but the 30-minute absence threshold is constant.

#### Scenario: Threshold is not configurable
- **WHEN** an attendance group has grace period = 10 minutes
- **THEN** the absence threshold SHALL still be 30 minutes
- **AND** late time between 10 and 30 minutes counts as lateness
- **AND** late time ≥ 30 minutes counts as absence

### Requirement: Absence from late SHALL use scheduled work minutes as penalty

When lateness converts to absence, the penalty SHALL be the full scheduled work minutes for that work segment.

For a standard 8-hour work day (480 minutes), absenceMinutes = 480.

#### Scenario: Full day absence penalty for morning lateness
- **WHEN** employee is late ≥30 minutes for morning shift
- **THEN** absenceMinutes SHALL equal the scheduled work minutes (480 for standard day)
- **AND** this penalty applies even if afternoon punch is valid

### Requirement: Absence from late SHALL affect attendance rate

Absence resulting from lateness SHALL reduce actualAttendanceDays, thereby reducing attendance rate.

#### Scenario: Absence from late reduces attendance rate
- **WHEN** an employee has 22 scheduled days, punches normally for 21 days, and is late ≥30 minutes on 1 day
- **THEN** actualAttendanceDays = 21 (the late-absence day does not count)
- **AND** attendance rate = 21 ÷ 22 = 95.45%

### Requirement: Absence from late SHALL be distinguishable from other absence types

The system SHALL record absence-from-late separately from other absence types (e.g., no-show, unapproved leave) to enable distinct reporting and analysis.

#### Scenario: Absence cause is tracked
- **WHEN** employee is late ≥30 minutes
- **THEN** system SHALL record absence cause as "late-converted-to-absence" or equivalent
- **AND** this SHALL be distinguishable from "no-punch-record" absence

#### Scenario: Reports can filter by absence type
- **WHEN** generating an absence report
- **THEN** system SHALL allow filtering by absence type
- **AND** "absence from severe lateness" SHALL be a distinct category

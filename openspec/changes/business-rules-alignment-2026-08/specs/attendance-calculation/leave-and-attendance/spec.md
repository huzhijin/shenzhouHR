# Leave and Attendance Specification Delta

## MODIFIED Requirements

### Requirement: All paid leave types SHALL count as actual attendance

The system SHALL count all approved paid leave types as actual attendance days.

Paid leave types that count as attendance:
- Annual leave (年假)
- Compensatory leave (调休)
- **Sick leave (病假)** - counts as attendance even if partially paid
- Marriage leave (婚假)
- Maternity leave (产假)
- Paternity leave (陪产假)
- Bereavement leave (丧假)
- Work injury leave (工伤假)
- Prenatal checkup/nursing leave (孕检假/哺乳假)

Unpaid leave types that do NOT count as attendance:
- Personal leave (事假)

**Key change**: Sick leave now explicitly counts as attendance, following the principle "if there is salary, it counts as attendance."

#### Scenario: Sick leave counts as attendance
- **WHEN** employee takes 1 day approved sick leave
- **THEN** actualAttendanceDays SHALL increment by 1
- **AND** attendance rate SHALL not be reduced
- **AND** the day SHALL be counted as attended

#### Scenario: Multiple days of sick leave count as attendance
- **WHEN** employee takes 3 days approved sick leave
- **THEN** actualAttendanceDays SHALL increment by 3
- **AND** attendance rate SHALL not be affected by sick leave

#### Scenario: Personal leave does not count as attendance
- **WHEN** employee takes 1 day approved personal leave (事假)
- **THEN** actualAttendanceDays SHALL NOT increment
- **AND** attendance rate SHALL be reduced

### Requirement: Sick leave days SHALL be displayed separately in reports

While sick leave counts as attendance (does not reduce attendance rate), the system SHALL display sick leave days separately in attendance reports for health monitoring purposes.

Reports SHALL include a dedicated column showing sick leave days.

#### Scenario: Report shows sick leave separately
- **WHEN** viewing employee monthly attendance report
- **AND** employee took 2 days sick leave and 3 days annual leave
- **THEN** report SHALL show "病假天数: 2"
- **AND** report SHALL show "年假天数: 3"
- **AND** both types count toward attendance

#### Scenario: Department report aggregates sick leave
- **WHEN** viewing department attendance report
- **THEN** report SHALL show total sick leave days for the department
- **AND** management can monitor health trends

### Requirement: Leave type SHALL be preserved in daily records

The system SHALL preserve the specific leave type (leave category) in daily attendance records, not just a binary "has leave or not."

This enables:
- Distinguishing between different paid leave types in reports
- Monitoring sick leave trends
- Analyzing leave usage patterns

#### Scenario: Daily record stores leave type
- **WHEN** employee takes sick leave on 2026-08-15
- **THEN** daily attendance record SHALL store leave_type = "sick_leave"
- **AND** this SHALL be queryable for reporting

#### Scenario: Multiple leave types can be queried
- **WHEN** generating leave usage report
- **THEN** system SHALL be able to filter by leave type
- **AND** count days per leave type separately

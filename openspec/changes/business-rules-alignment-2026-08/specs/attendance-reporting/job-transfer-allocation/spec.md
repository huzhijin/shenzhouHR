# Job Transfer Allocation Specification Delta

## MODIFIED Requirements

### Requirement: Attendance SHALL be allocated to the department at time of occurrence

When an employee transfers from one department to another during a reporting period, attendance records SHALL be allocated to the department the employee belonged to at the time the attendance occurred.

**Key principle**: Attendance follows the "occurrence-time department," not the current department or month-end department.

#### Scenario: Mid-month transfer splits attendance by occurrence date
- **WHEN** employee is in Department A from Aug 1-14
- **AND** employee transfers to Department B on Aug 15
- **AND** employee is in Department B from Aug 15-31
- **THEN** Aug 1-14 attendance SHALL be allocated to Department A
- **AND** Aug 15-31 attendance SHALL be allocated to Department B

#### Scenario: Transfer on first day of month
- **WHEN** employee transfers from Department A to Department B on Aug 1
- **THEN** all August attendance SHALL be allocated to Department B
- **AND** no August attendance SHALL be allocated to Department A

#### Scenario: Transfer on last day of month
- **WHEN** employee is in Department A from Aug 1-30
- **AND** employee transfers to Department B on Aug 31
- **THEN** Aug 1-30 attendance SHALL be allocated to Department A
- **AND** only Aug 31 attendance SHALL be allocated to Department B

### Requirement: Monthly reports SHALL display transfer as separate rows

When generating monthly attendance reports, employees who transferred during the month SHALL appear as separate rows for each department period.

Each row represents one department period within the month.

#### Scenario: Transfer creates two report rows
- **WHEN** generating August attendance report
- **AND** employee transferred from Dept A to Dept B on Aug 15
- **THEN** report SHALL show 2 rows for this employee:
  - Row 1: Employee name, Department A, Aug 1-14, 10 actual days, 10 scheduled days, 100% rate
  - Row 2: Employee name, Department B, Aug 15-31, 12 actual days, 12 scheduled days, 100% rate

#### Scenario: Row shows department period, not full month
- **WHEN** employee transferred mid-month
- **THEN** each row's date range SHALL show the actual period in that department
- **AND** scheduled/actual days SHALL only count the period in that department

### Requirement: Transfer allocation SHALL use assignment effective date

The system SHALL determine department allocation based on the assignment (任职) effective date.

When an employee has multiple assignments in a month, the effective date determines which assignment (and thus which department) the attendance belongs to.

#### Scenario: Assignment effective date determines allocation
- **WHEN** employee has assignment to Dept A effective until 2026-08-14
- **AND** employee has new assignment to Dept B effective from 2026-08-15
- **THEN** Aug 14 attendance SHALL go to Dept A
- **AND** Aug 15 attendance SHALL go to Dept B

### Requirement: Multi-assignment periods SHALL be handled chronologically

When an employee has multiple assignments in a month (including overlapping assignments or gaps), the system SHALL:
1. Sort assignments by effective date
2. Allocate each day's attendance to the assignment active on that day
3. For overlapping assignments, use the most recent effective date

#### Scenario: No gap between assignments
- **WHEN** employee assignment A ends 2026-08-14
- **AND** employee assignment B starts 2026-08-15
- **THEN** there is no gap
- **AND** every day from Aug 1-31 is allocated to one department

#### Scenario: Gap between assignments
- **WHEN** employee assignment A ends 2026-08-14
- **AND** employee assignment B starts 2026-08-20
- **THEN** Aug 15-19 have no assignment
- **AND** attendance for Aug 15-19 SHALL be allocated based on business rule (e.g., to previous department A, or marked as "no department")

### Requirement: Department aggregation SHALL include partial-month periods

When calculating department-level attendance, the system SHALL include employees who were in the department for only part of the month.

The department's total includes the days those employees were assigned to it.

#### Scenario: Department counts transferring-in employee
- **WHEN** employee transfers into Department B on Aug 15
- **THEN** Department B's August totals SHALL include this employee's Aug 15-31 attendance
- **AND** Department B scheduled days SHALL include Aug 15-31 for this employee

#### Scenario: Department counts transferring-out employee
- **WHEN** employee transfers out of Department A on Aug 14
- **THEN** Department A's August totals SHALL include this employee's Aug 1-14 attendance
- **AND** Department A scheduled days SHALL include Aug 1-14 for this employee

### Requirement: Employee transfer SHALL NOT create duplicate attendance days

When an employee transfers mid-month, the total scheduled and actual days across both department rows SHALL equal the full month total.

No days are double-counted or lost.

#### Scenario: Sum of rows equals full month
- **WHEN** employee transfers on Aug 15
- **AND** Row 1 (Dept A): scheduledDays=10, actualDays=10
- **AND** Row 2 (Dept B): scheduledDays=12, actualDays=11
- **THEN** total scheduledDays = 10+12 = 22 (matches calendar work days)
- **AND** total actualDays = 10+11 = 21

### Requirement: Query filters SHALL respect transfer boundaries

When filtering reports by department, the system SHALL only include the attendance records that occurred while the employee was in that department.

Filtering by Department A SHALL NOT include days after the employee transferred out.

#### Scenario: Department filter excludes post-transfer days
- **WHEN** user filters report by Department A
- **AND** employee transferred from Dept A to Dept B on Aug 15
- **THEN** results SHALL include this employee's Aug 1-14 attendance
- **AND** results SHALL NOT include Aug 15-31 attendance

#### Scenario: Employee name search shows all rows
- **WHEN** user searches by employee name (not department filter)
- **AND** employee transferred mid-month
- **THEN** results SHALL show both department rows
- **AND** user can see the complete month split by department

# Department Aggregation Specification Delta

## MODIFIED Requirements

### Requirement: Department attendance rate SHALL use day-based weighted average

The system SHALL calculate department-level attendance rate by aggregating employee-level attendance days (not minutes), using the formula:

`Department Attendance Rate = SUM(actualAttendanceDays) ÷ SUM(scheduledAttendanceDays) × 100%`

Where the sums are across all employees in the department for the reporting period.

**Key change**: Changed from minute-based weighted average to day-based weighted average, aligning with the individual employee attendance rate formula change.

#### Scenario: Department rate aggregates by days
- **WHEN** Department A has 3 employees in August
- **AND** Employee 1: actualDays=22, scheduledDays=22
- **AND** Employee 2: actualDays=20, scheduledDays=22
- **AND** Employee 3: actualDays=21, scheduledDays=22
- **THEN** department attendance rate = (22+20+21) ÷ (22+22+22) × 100%
- **AND** department attendance rate = 63 ÷ 66 = 95.45%

#### Scenario: Department rate is day-weighted, not simple average
- **WHEN** Department B has 2 employees
- **AND** Employee 1: 22÷22 = 100.00%
- **AND** Employee 2: 18÷22 = 81.82%
- **THEN** simple average would be (100+81.82)÷2 = 90.91%
- **BUT** day-weighted calculation = (22+18)÷(22+22) = 40÷44 = 90.91%
- **AND** system SHALL use day-weighted (which happens to equal simple average in this case)

#### Scenario: Day-weighted differs from simple average when scheduled days vary
- **WHEN** Department C has 2 employees
- **AND** Employee 1: actualDays=20, scheduledDays=20 (new hire, shorter period) = 100%
- **AND** Employee 2: actualDays=20, scheduledDays=22 (full month) = 90.91%
- **THEN** simple average = (100+90.91)÷2 = 95.45%
- **BUT** day-weighted = (20+20)÷(20+22) = 40÷42 = 95.24%
- **AND** system SHALL use 95.24% (day-weighted), not 95.45%

### Requirement: Department aggregation SHALL exclude employees with zero scheduled days

When aggregating department attendance rate, the system SHALL exclude employees who have zero scheduled attendance days for the period.

Employees with scheduledAttendanceDays = 0 do not contribute to either numerator or denominator.

#### Scenario: Zero scheduled days excluded from department total
- **WHEN** Department D has 3 employees
- **AND** Employee 1: actualDays=22, scheduledDays=22
- **AND** Employee 2: actualDays=0, scheduledDays=0 (not employed this period)
- **AND** Employee 3: actualDays=20, scheduledDays=22
- **THEN** department rate = (22+20) ÷ (22+22) = 42÷44 = 95.45%
- **AND** Employee 2 is excluded from the calculation

### Requirement: Department aggregation SHALL respect company boundaries

Department-level aggregation SHALL only include employees within the same company (company_id boundary).

Multi-company departments do not exist in this system; each department belongs to exactly one company.

#### Scenario: Department aggregation is company-scoped
- **WHEN** calculating department attendance rate
- **THEN** system SHALL filter employees by department_id AND company_id
- **AND** employees from different companies SHALL NOT be aggregated together

### Requirement: Department attendance rate SHALL use 2 decimal places

Department attendance rate SHALL be formatted as a percentage with exactly 2 decimal places, consistent with individual employee attendance rate.

#### Scenario: Department rate displayed with 2 decimals
- **WHEN** department attendance rate is calculated as 0.9545...
- **THEN** system SHALL display "95.45%"

### Requirement: Zero scheduled days in entire department SHALL show N/A

When a department has zero total scheduled attendance days (e.g., all employees on leave, or no active employees), the system SHALL display "N/A" for department attendance rate.

#### Scenario: Department with no scheduled days shows N/A
- **WHEN** SUM(scheduledAttendanceDays) = 0 for a department
- **THEN** department attendance rate SHALL display "N/A"
- **AND** system SHALL NOT throw division by zero error

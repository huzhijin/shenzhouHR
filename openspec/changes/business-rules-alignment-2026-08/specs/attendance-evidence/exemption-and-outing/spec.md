# Exemption and Outing Specification

## Purpose

Defines the combined logic for handling exemption (免打卡) and outing (外出) requests from OA, where exemption requests directly grant attendance credit without punch records, while outing requests require both the approved request AND a punch record.

## ADDED Requirements

### Requirement: Approved exemption request SHALL grant attendance without punch

When an employee has an approved exemption request for a date, the system SHALL grant attendance credit for that date WITHOUT requiring punch records.

Exemption request = OA 免打卡申请 (punch exemption application)

#### Scenario: Exemption grants attendance without punch
- **WHEN** employee has approved exemption request for 2026-08-15
- **AND** employee has no punch records for 2026-08-15
- **THEN** actualAttendanceDays SHALL increment by 1
- **AND** missingPunchCount SHALL be 0 (no punches expected)
- **AND** attendance rate SHALL not be affected

#### Scenario: Exemption covers partial day
- **WHEN** employee has approved exemption request for morning of 2026-08-15
- **AND** employee has valid afternoon punch
- **THEN** system SHALL treat the day as having complete attendance
- **AND** no missing punch penalties SHALL apply

### Requirement: Outing request SHALL require both approval AND punch record

When an employee has an approved outing request (外出), the system SHALL only grant attendance credit if BOTH conditions are met:
1. Approved outing request exists (OA state = 3)
2. Valid punch record exists for that date

If outing request exists but no punch record, the employee SHALL be marked as absent.

#### Scenario: Outing with punch grants attendance
- **WHEN** employee has approved outing request for 2026-08-15
- **AND** employee has valid punch records for 2026-08-15
- **THEN** actualAttendanceDays SHALL increment by 1
- **AND** attendance SHALL be normal

#### Scenario: Outing without punch results in absence
- **WHEN** employee has approved outing request for 2026-08-15
- **AND** employee has NO punch records for 2026-08-15
- **THEN** actualAttendanceDays SHALL NOT increment
- **AND** system SHALL record as absence (缺勤)
- **AND** missingPunchCount SHALL reflect missing punches

### Requirement: Business trip SHALL NOT be queried from OA

The system SHALL NOT query business trip (出差) requests from OA.

Business trips are treated as normal work days where employees are expected to punch in as usual. The presence or absence of a business trip request does not affect attendance calculation.

#### Scenario: Business trip does not grant exemption
- **WHEN** employee is on business trip on 2026-08-15
- **AND** employee has no punch records
- **THEN** system SHALL record as absence (same as any other day without punch)
- **AND** system SHALL NOT query OA for business trip requests

#### Scenario: Business trip with punch is normal attendance
- **WHEN** employee is on business trip on 2026-08-15
- **AND** employee has valid punch records
- **THEN** attendance SHALL be calculated normally
- **AND** system treats it identically to non-business-trip days

### Requirement: Exemption request end date SHALL include the end date itself

When an exemption request specifies a date range (start_date to end_date), the end_date SHALL be inclusive.

For example, exemption request from 2026-08-01 to 2026-08-05 covers 5 days: Aug 1, 2, 3, 4, and 5.

#### Scenario: End date is included in exemption period
- **WHEN** exemption request has start_date = 2026-08-01 and end_date = 2026-08-05
- **THEN** system SHALL apply exemption to all days from 08-01 through 08-05 inclusive
- **AND** 2026-08-05 SHALL be treated as exempt

#### Scenario: Single day exemption
- **WHEN** exemption request has start_date = end_date = 2026-08-15
- **THEN** system SHALL apply exemption to 2026-08-15 only

### Requirement: OA date range query SHALL use inclusive end condition

When querying OA exemption or outing requests by date, the system SHALL use `date <= end_date` (not `date < end_date`).

SQL condition: `WHERE date >= start_date AND date <= end_date`

#### Scenario: Query includes end date
- **WHEN** querying exemption requests for 2026-08-05
- **AND** exemption request has end_date = 2026-08-05
- **THEN** query SHALL match this request
- **AND** SQL SHALL use `date <= end_date`

### Requirement: Only approved exemption and outing requests SHALL be used

The system SHALL only consider OA requests with approval state = 3 (approved).

Requests with state = 0 (pending), 2 (cancelled), or NULL (draft) SHALL be ignored in attendance calculation.

#### Scenario: Only approved exemption counts
- **WHEN** employee has pending exemption request (state = 0) for 2026-08-15
- **AND** employee has no punch records
- **THEN** system SHALL NOT grant exemption
- **AND** system SHALL record as missing punch

#### Scenario: Cancelled exemption does not apply
- **WHEN** employee had approved exemption that was later cancelled (state = 2)
- **THEN** system SHALL NOT grant exemption
- **AND** normal punch requirements SHALL apply

### Requirement: Exemption takes precedence over outing

When an employee has both an exemption request and an outing request for the same date, the exemption request SHALL take precedence.

Exemption grants attendance without punch, so the outing request's punch requirement is moot.

#### Scenario: Exemption overrides outing punch requirement
- **WHEN** employee has approved exemption for 2026-08-15
- **AND** employee also has approved outing for 2026-08-15
- **AND** employee has no punch records
- **THEN** actualAttendanceDays SHALL increment by 1 (exemption applies)
- **AND** outing's punch requirement SHALL be ignored

### Requirement: Exemption and outing do not affect scheduled attendance days

Neither exemption nor outing requests SHALL reduce scheduledAttendanceDays.

The denominator in attendance rate calculation remains unchanged regardless of exemption or outing.

#### Scenario: Exemption does not reduce scheduled days
- **WHEN** employee has 22 scheduled work days
- **AND** employee has 5 days of exemption requests
- **THEN** scheduledAttendanceDays SHALL still be 22
- **AND** attendance rate denominator SHALL not be reduced

### Requirement: Exemption requests and high-level exemption roles are distinct

The system SHALL distinguish exemption requests (temporary, requires OA approval) from high-level exemption roles (permanent, assigned to executives).

- **Exemption request**: Temporary exemption for specific dates via OA application
- **Exemption role**: Permanent no-punch-required status for high-level roles (e.g., executives)

Both grant attendance without punch, but roles do not expire and do not require per-date approval.

#### Scenario: Exemption request is temporary
- **WHEN** employee has exemption request for Aug 1-5
- **THEN** exemption SHALL only apply to those 5 days
- **AND** normal punch requirements SHALL resume on Aug 6

#### Scenario: Exemption role is permanent
- **WHEN** employee is assigned high-level exemption role
- **THEN** employee SHALL never have punch requirements
- **AND** no OA exemption requests are needed

### Requirement: Outing duration SHALL NOT affect attendance calculation

The duration of an outing request (how many hours the employee is out) SHALL NOT affect daily attendance calculation.

As long as the employee has an approved outing request and punch records, the day counts as full attendance regardless of outing duration.

#### Scenario: Half-day outing counts as full attendance
- **WHEN** employee has approved 4-hour outing request for 2026-08-15
- **AND** employee has valid punch records
- **THEN** actualAttendanceDays = 1 (full day)
- **AND** outing duration does not reduce attendance credit

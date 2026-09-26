# Punch Supplement Specification

## Purpose

Defines the punch supplement (补卡) application process with a monthly quota limit of 1 application per employee, allowing employees to correct missing punch records through an approval workflow.

## ADDED Requirements

### Requirement: Employee SHALL be able to submit punch supplement applications

The system SHALL provide an interface for employees to submit punch supplement applications when they have missing punch records.

A punch supplement application includes:
- Supplement date (the date with missing punch)
- Reason for missing punch
- Which punch side(s) to supplement (morning in, evening out, or both)

#### Scenario: Employee submits supplement for missing morning punch
- **WHEN** employee discovers missing morning punch on 2026-08-15
- **THEN** employee SHALL be able to submit a supplement application
- **AND** application SHALL specify date = 2026-08-15
- **AND** application SHALL specify reason (e.g., "forgot to punch")
- **AND** application SHALL specify punch side = morning in

#### Scenario: Employee submits supplement for entire day missing
- **WHEN** employee has no punch records for 2026-08-15
- **THEN** employee SHALL be able to submit a supplement application
- **AND** application SHALL specify both morning in and evening out

### Requirement: Each employee SHALL have monthly quota of 1 supplement application

The system SHALL enforce a limit of 1 punch supplement application per employee per calendar month.

"Per month" means per natural calendar month (e.g., August 2026), not a rolling 30-day period.

#### Scenario: First application in month is allowed
- **WHEN** employee has submitted 0 supplement applications in current month
- **THEN** employee SHALL be allowed to submit 1 application
- **AND** system SHALL accept the submission

#### Scenario: Second application in same month is rejected
- **WHEN** employee has already submitted 1 supplement application in current month
- **AND** that application is approved or pending
- **THEN** employee SHALL NOT be allowed to submit another application
- **AND** system SHALL reject with error "本月补卡次数已用完"

#### Scenario: Quota resets at start of new month
- **WHEN** employee used 1 supplement in August 2026
- **AND** current date is September 1, 2026
- **THEN** employee SHALL have 1 available supplement quota for September
- **AND** system SHALL allow new submission

### Requirement: Rejected supplement applications SHALL NOT count toward quota

Only approved or pending supplement applications SHALL count toward the monthly quota. Rejected applications SHALL NOT consume the quota.

#### Scenario: Rejected application does not block retry
- **WHEN** employee submits supplement application and HR rejects it
- **THEN** employee SHALL still have available quota in that month
- **AND** employee SHALL be able to submit another application

### Requirement: Supplement application SHALL require HR approval

Punch supplement applications SHALL go through an approval workflow. Only after HR approval SHALL the missing punch records be corrected.

#### Scenario: Pending application does not immediately reduce missing punch count
- **WHEN** employee submits a supplement application
- **AND** application status is pending
- **THEN** missingPunchCount SHALL NOT be reduced yet
- **AND** attendance calculation SHALL still reflect missing punches

#### Scenario: Approved application reduces missing punch count
- **WHEN** HR approves a supplement application for both morning and evening punches
- **THEN** missingPunchCount SHALL be reduced by 2
- **AND** attendance calculation SHALL treat that day as having valid punches

### Requirement: System SHALL validate supplement date

The system SHALL validate that the supplement date is a valid work day for the employee and is within the current or previous month.

#### Scenario: Cannot supplement for future dates
- **WHEN** current date is 2026-08-15
- **AND** employee tries to submit supplement for 2026-08-20
- **THEN** system SHALL reject with error "不能为未来日期补卡"

#### Scenario: Cannot supplement for dates beyond previous month
- **WHEN** current date is 2026-08-15
- **AND** employee tries to submit supplement for 2026-06-10 (more than 1 month ago)
- **THEN** system SHALL reject with error "只能为本月和上月补卡"

#### Scenario: Can supplement for previous month
- **WHEN** current date is 2026-08-05
- **AND** employee tries to submit supplement for 2026-07-28
- **THEN** system SHALL accept the submission

### Requirement: System SHALL check quota before allowing submission

The system SHALL check the employee's remaining monthly quota before allowing a new supplement application submission.

#### Scenario: Quota check returns remaining count
- **WHEN** employee requests to check quota via GET /api/attendance/punch-supplement/quota
- **THEN** system SHALL return JSON: `{"remainingQuota": 1, "usedQuota": 0, "totalQuota": 1}`

#### Scenario: Quota exhausted shows zero
- **WHEN** employee has used 1 supplement in current month
- **AND** employee requests quota check
- **THEN** system SHALL return `{"remainingQuota": 0, "usedQuota": 1, "totalQuota": 1}`

### Requirement: Approved supplement SHALL be recorded in attendance evidence

When a supplement application is approved, the system SHALL record synthetic punch evidence for the supplemented date.

#### Scenario: Approved supplement creates evidence records
- **WHEN** HR approves supplement for morning in and evening out on 2026-08-15
- **THEN** system SHALL create punch evidence records for 2026-08-15
- **AND** these records SHALL be marked as "supplemented" (not from actual device)
- **AND** attendance calculation SHALL use these records

### Requirement: Supplement SHALL handle high-level exemption roles

Employees with exemption roles (e.g., executives who do not need to punch) SHALL NOT be able to submit supplement applications, as they have no punch requirement.

#### Scenario: Exemption role cannot submit supplement
- **WHEN** an executive with no-punch-required role tries to submit supplement
- **THEN** system SHALL reject with error "您的岗位无需打卡"

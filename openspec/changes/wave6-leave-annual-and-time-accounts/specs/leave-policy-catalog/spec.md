## ADDED Requirements

### Requirement: Leave types use typed immutable policy versions
The system SHALL model every leave type with a typed policy version containing calendar basis, request unit, minimum amount and step, qualification, evidence/material, grant, validity, carry/expiry, cancellation/reconciliation, conflict and insufficient-balance behavior. Published versions MUST be immutable, MUST reject unknown executable fields, and MUST be selected by business date and scope.

#### Scenario: New leave type defaults to workdays
- **WHEN** an authorized administrator creates a leave type without an explicit calendar basis
- **THEN** its draft SHALL use `WORKDAY` as the default and validation SHALL show the inherited published defaults

#### Scenario: Published version is not edited in place
- **WHEN** an administrator changes a parameter of a published leave policy
- **THEN** the system SHALL create a new draft/version and SHALL retain the prior snapshot for historical results

#### Scenario: Executable policy field is rejected
- **WHEN** a policy payload contains a script, expression, command, unknown field or unregistered enum
- **THEN** validation SHALL reject the payload before publish or calculation

### Requirement: Leave duration follows the employee work calendar
For a `WORKDAY` leave type, the system SHALL deduct only scheduled work segments from the employee's effective attendance group/calendar. A half day SHALL mean the actual selected half-day work segment and MUST NOT be hard-coded to four hours. `CALENDAR_DAY` and `HOUR` policies SHALL use their explicitly published semantics.

#### Scenario: Workday leave skips rest days
- **WHEN** a workday-policy request spans Friday through Monday and Saturday/Sunday have no scheduled work segments
- **THEN** only the Friday and Monday scheduled segments SHALL be deducted

#### Scenario: Half day uses actual segment length
- **WHEN** a synthetic shift has a 4.5-hour morning segment and a 3.5-hour afternoon segment
- **THEN** a morning half-day request SHALL deduct 4.5 hours and an afternoon half-day request SHALL deduct 3.5 hours

### Requirement: Cancellation and reconciliation preserve history
Cancelling an unapproved request SHALL create no time-account entry. An approved cancellation or early-return reconciliation SHALL append return/reversal entries only for approved but unconsumed segments, SHALL retain the original request and deduction entries, and SHALL request recalculation of only the affected employee/dates.

#### Scenario: Unapproved request is cancelled
- **WHEN** a pending or rejected leave request is cancelled
- **THEN** no ledger entry SHALL be created and the request history SHALL remain queryable

#### Scenario: Approved request is partially reconciled
- **WHEN** an approved leave interval is shortened after an employee returns early
- **THEN** the system SHALL append a return/reversal for only the unconsumed approved interval and SHALL NOT delete or update the original use entry

#### Scenario: Closed period blocks reconciliation
- **WHEN** reconciliation would change a frozen, closed or unknown attendance period
- **THEN** the system SHALL reject the mutation until an authorized reopen supplies a new period version

### Requirement: Balance-controlled leave never silently overdrafts
Annual leave and time-off requests SHALL reject insufficient balance or return an explicitly configured split proposal. They MUST NOT create a negative balance, switch to another leave type, or hide the shortage from the requester.

#### Scenario: Annual leave balance is insufficient
- **WHEN** an employee requests more annual-leave hours than the replayed available balance
- **THEN** the system SHALL reject the request or return the published split option and SHALL append no use entry

#### Scenario: Concurrent requests target the same balance
- **WHEN** two requests concurrently attempt to consume the same remaining hours
- **THEN** account/version locking and a second balance check SHALL allow at most the available amount to be appended

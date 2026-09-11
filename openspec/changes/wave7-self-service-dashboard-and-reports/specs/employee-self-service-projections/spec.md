## ADDED Requirements

### Requirement: Session-bound employee projections
The system SHALL derive the employee identity for all `/me` projections from the authenticated session and SHALL NOT accept a client-supplied employee identifier as an authority for today, attendance records, leave/time-account balances, or feedback.

#### Scenario: Forged employee identifier is ignored
- **WHEN** an authenticated employee opens a self-service route with an employee identifier in a path, query, or request body
- **THEN** the projection is resolved only for the employee bound to the session and the forged identifier does not widen access

#### Scenario: Employee binding is unavailable
- **WHEN** an authenticated account has no active employee binding
- **THEN** the system returns a non-leaking 403 projection state without exposing another employee or whether a requested employee exists

### Requirement: Today projection
The today projection SHALL contain the employee's scheduled shift, first and last effective punch display values, provisional or final attendance status, authorized issue labels, confirmed duration, data-as-of time, period state, and version references.

#### Scenario: Today data is available
- **WHEN** an employee with `ATTENDANCE_SELF:READ` opens `/me/today`
- **THEN** the page renders the authorized today projection with its data-as-of time and version metadata

#### Scenario: No scheduled work exists
- **WHEN** the authorized today projection contains no scheduled work and no effective attendance fact
- **THEN** the page renders an empty state and does not invent a shift, punch, or attendance result

### Requirement: Attendance records projection
The attendance records projection SHALL expose an authorized month summary and dated records whose confirmed duration, status labels, issues, evidence explanation reference, and source version can be rendered without recalculation in the browser.

#### Scenario: Month records render from one projection version
- **WHEN** an employee opens `/me/records` for an authorized month
- **THEN** the summary and all listed daily records identify the same projection version and the UI does not combine rows from another version

#### Scenario: Frozen month remains readable
- **WHEN** the selected month projection is `FROZEN` or `CLOSED`
- **THEN** the records remain readable, a frozen notice identifies the immutable version, and no client action is enabled unless listed in `allowedActions`

### Requirement: Leave and time-account projection
The leave projection SHALL expose only authorized account types and SHALL provide granted, opening, used, remaining, expiry, source/ledger reference, data-as-of time, and version metadata. Annual leave SHALL also provide hours and the configured equivalent-day display without browser-side balance calculation.

#### Scenario: Authorized balances render
- **WHEN** an employee with `LEAVE_SELF:READ` opens `/me/leave`
- **THEN** each returned account renders its server-projected balance components, expiry, and source reference

#### Scenario: No account is visible
- **WHEN** the authorized leave projection contains no visible accounts
- **THEN** the page renders an empty state rather than zero-valued synthetic accounts

### Requirement: Feedback projection and action contract
The feedback projection SHALL expose only feedback owned by the session employee, including attendance date, problem type, plain-text content, processing state, ordered progress, authorized HR reply text, and an optional linked adjustment result. Feedback creation SHALL require `ATTENDANCE_FEEDBACK:CREATE` in addition to feedback read access.

#### Scenario: Employee reads own feedback
- **WHEN** an employee with `ATTENDANCE_FEEDBACK:READ` opens `/me/feedback`
- **THEN** only that employee's feedback projections are shown with their ordered progress and safe linked result

#### Scenario: Read-only employee cannot submit
- **WHEN** an employee has feedback read access but lacks `ATTENDANCE_FEEDBACK:CREATE`
- **THEN** the route remains readable and the submission action is absent or disabled with an accessible reason

#### Scenario: Feedback text is rendered safely
- **WHEN** feedback or reply text contains markup-like characters
- **THEN** the UI renders the value as plain text and does not execute HTML, script, or rich-text content

### Requirement: Self-service projection metadata
Every self-service projection SHALL contain a projection version, upstream source version references, `dataAsOf`, `periodState`, a server-authored scope label, and `allowedActions`.

#### Scenario: Projection metadata is incomplete
- **WHEN** a projection fixture or future API response lacks required version, freshness, scope, period, or allowed-action metadata
- **THEN** contract validation fails and the UI does not present the projection as ready

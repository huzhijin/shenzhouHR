## MODIFIED Requirements

### Requirement: Query results are consistent within one input snapshot
The system SHALL bind report pages, drill-down reads, month-matrix pages, dashboard reads, and production exports for the same company-month to the latest pinned snapshot token of that company-month. Until an independent version query covers every personnel and configuration input, the system SHALL NOT describe the token as a complete configuration-version catalog. Ordinary queries SHALL keep returning that latest token's stored facts across process restarts and multiple application instances. After a successor latest pin is stored, a request that still carries a superseded snapshot token MUST receive a retryable snapshot-changed error and MUST NOT mix rows from archived and live pins. The system SHALL NOT keep superseded pins readable as a long-lived query contract.

#### Scenario: Source watermark changes between pages
- **WHEN** the Deli or OA committed watermark changes after the first page of a pinned snapshot is returned
- **THEN** a request carrying the earlier snapshot token either returns the stored result for that exact token while it is still the latest pin, or responds with a retryable snapshot-changed error after a successor latest pin exists, and never mixes rows from both snapshots

#### Scenario: Same company-month is queried after restart
- **WHEN** an equivalent authorized query is repeated after the application process has restarted and the pinned snapshot has not been recalculated
- **THEN** the system returns the stored latest snapshot and the business values and snapshot token remain identical

#### Scenario: Superseded token after recalculate
- **WHEN** a client retries a page with a snapshot token that is no longer the latest pin
- **THEN** the system returns a retryable snapshot-changed error
- **AND** it does not reconstruct that old token from archived projections as the ordinary query path

### Requirement: Explicit recalculate replaces the pinned snapshot
The system SHALL provide a recalculate operation that recalculates the requested window from current committed inputs and writes the result into the single latest complete pin for each affected company-month. The system MUST NOT append a second live snapshot for that company-month as the default persist path. Ordinary GET requests and page reloads SHALL NOT perform this recalculate. Whole-month recalculate SHALL cover the whole company-month. Partial windows follow `report-date-range-and-partial-recalc`. The official report page SHALL expose this operation as a distinct 「重新计算」 action, not as 「刷新数据」. The recalculate operation MUST require `ATTENDANCE_REPORT:REFRESH` in addition to report read authorization, and MUST deny the request without calculating when that capability is absent.

#### Scenario: Privileged user recalculates after new evidence
- **WHEN** a principal with `ATTENDANCE_REPORT:REFRESH` requests recalculate for a company-month that already has a pinned snapshot and newer committed Deli or OA evidence exists
- **THEN** the system calculates into the latest pin for that company-month
- **AND** subsequent default queries return the new business values and token

#### Scenario: Recalculate with unchanged inputs
- **WHEN** a principal with `ATTENDANCE_REPORT:REFRESH` requests recalculate and the complete calculated output matches the current latest pin
- **THEN** the system keeps the existing latest snapshot token and does not present a different set of business values

#### Scenario: Ordinary GET does not recalculate
- **WHEN** an authorized report reader repeats the report GET after source watermarks have advanced
- **THEN** the response keeps the previously pinned business values and does not persist a new snapshot

#### Scenario: Employee, department manager, or executive cannot recalculate
- **WHEN** a principal whose roles are only `EMPLOYEE_SELF`, `DEPARTMENT_HEAD`, or `EXECUTIVE` requests the recalculate operation
- **THEN** the system denies the request without calculating or replacing the pinned snapshot, and the official report page does not show 「重新计算」

#### Scenario: HR admin or system admin can recalculate
- **WHEN** a principal with an `HR_ADMIN` or `SYSTEM_ADMIN` role that carries `ATTENDANCE_REPORT:REFRESH` opens the official report page
- **THEN** the page shows a distinct 「重新计算」 button, and requesting that action recalculates without requiring a separate publication action
- **AND** the write updates the latest pin rather than leaving two live projections

### Requirement: Report consumers share the latest pin
Attendance report GET, month-matrix, dashboard, and production export for the same authorized company-month SHALL use the same latest pinned snapshot. A request that carries a superseded snapshot token MUST NOT be served mixed facts; it MUST fail closed with a retryable snapshot-changed error.

#### Scenario: Export after a report page load
- **WHEN** a user exports the official report after viewing it without recalculating
- **THEN** the export uses the same latest pinned snapshot token and business values as the page

#### Scenario: Dashboard matches the report center
- **WHEN** dashboard summary and the official report page query the same company-month without an explicit older token
- **THEN** both return values from the same latest pinned snapshot

#### Scenario: Stale token is not mixed
- **WHEN** export carries a snapshot token that is no longer latest
- **THEN** the system returns a retryable snapshot-changed error instead of mixing live and archived rows

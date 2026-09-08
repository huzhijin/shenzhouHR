## MODIFIED Requirements

### Requirement: Report query calculates without publication
The system SHALL return an attendance report when an authorized report query is received and SHALL NOT require a manual publication, approval, reopen, or republish operation before returning the report. When no complete pinned snapshot exists for the requested company-month, the system SHALL calculate once from current committed inputs, persist that complete result as the pinned snapshot, and return it. When a complete pinned snapshot already exists, the system SHALL return that snapshot and SHALL NOT recalculate.

#### Scenario: First query for a company month
- **WHEN** an authorized user queries a company and month for which no complete pinned snapshot exists
- **THEN** the system reads the current committed calculation inputs, calculates the requested report, persists the complete result as the pinned snapshot, and returns it without asking the user to publish it first

#### Scenario: Repeat query uses the pinned snapshot
- **WHEN** an authorized user queries a company and month that already has a complete pinned snapshot
- **THEN** the system returns the pinned business values and snapshot token without recalculating from later committed inputs

### Requirement: Calculation uses the authoritative attendance inputs
The system SHALL calculate reports from the latest locally committed Deli punch evidence, OA attendance documents, employee identity and employment assignments, organization versions, attendance-group assignments, shift definitions, calendars, punch corrections, exemption roles, and signed attendance policies that are effective for the requested business dates at calculation time. A report query SHALL NOT call the Deli or OA external network directly. After a snapshot is pinned, later committed evidence SHALL NOT change displayed report values until an authorized explicit recalculate pins a successor snapshot.

#### Scenario: Deli punches and OA documents overlap a scheduled shift
- **WHEN** a scheduled employee has committed Deli punches and an approved OA document overlapping the same business date and the system is calculating a new snapshot
- **THEN** the system applies the confirmed attendance business rules to those inputs and returns one deterministic employee-day result

#### Scenario: Source data has not been committed
- **WHEN** an external source page is still in progress, failed, or quarantined and has not advanced its committed watermark
- **THEN** the calculation excludes that uncommitted page and exposes the latest committed source cutoff instead of treating the missing page as zero attendance

#### Scenario: Newly committed evidence waits for recalculate
- **WHEN** Deli or OA evidence is committed after a company-month snapshot has been pinned
- **THEN** subsequent ordinary queries continue to return the pinned values, and the new evidence is included only after an authorized explicit recalculate succeeds

### Requirement: Query results are consistent within one input snapshot
The system SHALL bind report pages, drill-down reads, month-matrix pages, dashboard reads, and production exports for the same company-month to one pinned snapshot token. Until an independent version query covers every personnel and configuration input, the system SHALL NOT describe the token as a complete configuration-version catalog. Ordinary queries SHALL keep returning that token's stored facts across process restarts and multiple application instances.

#### Scenario: Source watermark changes between pages
- **WHEN** the Deli or OA committed watermark changes after the first page of a pinned snapshot is returned
- **THEN** a request carrying the earlier snapshot token either returns the stored result for that exact token or responds with a retryable snapshot-changed error, and never mixes rows from both snapshots

#### Scenario: Same company-month is queried after restart
- **WHEN** an equivalent authorized query is repeated after the application process has restarted and the pinned snapshot has not been recalculated
- **THEN** the system returns the stored snapshot and the business values and snapshot token remain identical

### Requirement: Realtime report queries meet the fast response budget
For a company-month containing no more than 5,000 active employees, the system SHALL avoid per-employee and per-day database queries, SHALL use bounded batch reads for a cold calculation, and SHALL meet a warm-query p95 response time of 1 second and a cold-query p95 response time of 5 seconds on the customer acceptance environment. A warm query is a read of an already pinned snapshot.

#### Scenario: Warm repeated report query
- **WHEN** the same authorized company-month is queried after one successful pinned calculation
- **THEN** the complete report response is returned within the warm-query performance budget without repeating full source reads or daily calculations

#### Scenario: Cold company-month query
- **WHEN** no pinned snapshot exists for an authorized company-month
- **THEN** the system loads each input category in bounded batch operations, persists the complete calculation as the pin, and returns the report within the cold-query performance budget

### Requirement: Existing report clients remain compatible
The existing attendance report GET routes SHALL remain available, SHALL return the established report row and column contract, and SHALL expose the pinned snapshot token wherever the contract previously exposed a projection version.

#### Scenario: Existing report page loads after upgrade
- **WHEN** the current frontend calls the established attendance report endpoint with its normal report type, period, company, organization, employee, status, and paging filters
- **THEN** the endpoint returns pinned snapshot rows using the existing response shape without requiring a separate frontend publication action

## ADDED Requirements

### Requirement: Explicit recalculate replaces the pinned snapshot
The system SHALL provide a recalculate operation that recalculates the requested company-month from current committed inputs, appends a new complete snapshot, and leaves previous snapshots readable by their original token. Ordinary GET requests and page reloads SHALL NOT perform this recalculate. Recalculate SHALL cover the whole company-month and SHALL NOT patch individual employee-days. The official report page SHALL expose this operation as a distinct 「重新计算」 action, not as 「刷新数据」. The recalculate operation MUST require `ATTENDANCE_REPORT:REFRESH` in addition to report read authorization, and MUST deny the request without calculating when that capability is absent.

#### Scenario: Privileged user recalculates after new evidence
- **WHEN** a principal with `ATTENDANCE_REPORT:REFRESH` requests recalculate for a company-month that already has a pinned snapshot and newer committed Deli or OA evidence exists
- **THEN** the system calculates a new complete snapshot, persists it as the latest pin, and subsequent default queries return the new business values and token

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
- **THEN** the page shows a distinct 「重新计算」 button, and requesting that action recalculates the company-month without requiring a separate publication action

### Requirement: Source-newer-than-pin is visible without changing numbers
The system SHALL compare the latest committed Deli and OA watermarks with the pinned snapshot's recorded source cutoffs and SHALL expose whether sources are newer than the pin. The system SHALL NOT treat twice-daily source synchronization itself as a recalculate.

#### Scenario: Sync completes after the pin
- **WHEN** a scheduled Deli or OA sync commits a newer watermark than the pinned snapshot
- **THEN** the report response indicates that sources are newer than the displayed snapshot while the displayed rows stay unchanged, and only principals with `ATTENDANCE_REPORT:REFRESH` are offered the 「重新计算」 action

#### Scenario: Sources match the pin
- **WHEN** the latest committed Deli and OA watermarks are not newer than the pinned snapshot
- **THEN** the report does not claim that a recalculate is required because of source freshness

### Requirement: Incomplete calculations are not pinned
The system SHALL persist a snapshot only after a complete company-month calculation finishes. Provisional, timed-out, partial, or preview results SHALL NOT become the pinned snapshot and SHALL NOT replace an existing pin.

#### Scenario: First-page preview is not stored
- **WHEN** a company-month calculation has not finished and only a preview result is available
- **THEN** the system does not persist that preview as the pinned snapshot

#### Scenario: Failed recalculate keeps the previous pin
- **WHEN** an explicit recalculate calculation fails closed
- **THEN** subsequent ordinary queries continue to return the previously pinned snapshot

### Requirement: Pinned facts stay filtered by current authorization
The system MUST persist company-month facts without authorization results and MUST re-resolve the current principal's effective data scopes on every query or recalculate before returning rows or aggregates.

#### Scenario: Organization-scoped user reads a company pin
- **WHEN** a user with an organization scope queries a company-month that has a pinned snapshot covering employees outside that scope
- **THEN** the system returns only employees in the intersection of the current scope and requested filter and does not reveal hidden employees' values through company-level aggregates

### Requirement: Report consumers share the latest pin
Attendance report GET, month-matrix, dashboard, and production export for the same authorized company-month SHALL use the same latest pinned snapshot unless a request explicitly carries an older snapshot token.

#### Scenario: Export after a report page load
- **WHEN** a user exports the official report after viewing it without recalculating
- **THEN** the export uses the same pinned snapshot token and business values as the page

#### Scenario: Dashboard matches the report center
- **WHEN** dashboard summary and the official report page query the same company-month without an explicit older token
- **THEN** both return values from the same latest pinned snapshot

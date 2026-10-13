## ADDED Requirements

### Requirement: Report route and query authorization
The `/attendance/reports` route SHALL require `ATTENDANCE_REPORT:READ`, and every report query SHALL be evaluated for the current capability, scope, filters, field policy, period state, and session conditions.

#### Scenario: Authorized report query
- **WHEN** a session with `ATTENDANCE_REPORT:READ` requests a report in its authorized scope
- **THEN** the system returns a report projection containing only allowed rows and fields

#### Scenario: Direct route without capability
- **WHEN** an authenticated user without `ATTENDANCE_REPORT:READ` opens `/attendance/reports`
- **THEN** the UI renders a generic 403 state without revealing report types, row counts, scope names, or field names

### Requirement: Version-consistent report drill-down
Report summaries and drill-down rows SHALL identify and use the same projection/source version, filters, scope reference, and data-as-of time.

#### Scenario: Summary drills into detail
- **WHEN** a user activates a report summary drill-down
- **THEN** the detail query is bound to the summary projection version and cannot silently switch to a newer or wider dataset

#### Scenario: Requested version is stale
- **WHEN** the bound report projection version is no longer available for drill-down
- **THEN** the system returns an explicit conflict or stale state and does not substitute another version

### Requirement: Export request binding
An export request SHALL reuse the visible report's authorized scope, normalized filters, projection version, query fingerprint, and ordered field allowlist. The client SHALL NOT add hidden, precise-location, sensitive-leave, raw-evidence, or PAYROLL fields.

#### Scenario: User starts export
- **WHEN** a user activates export for the current report
- **THEN** the request contains the current bound query contract, selected allowed columns, and declared purpose

#### Scenario: Client requests a non-allowlisted field
- **WHEN** an export request contains a field outside the report projection's export allowlist
- **THEN** creation is rejected and no export job or file is produced

### Requirement: Export creation and download are separately authorized
Export creation SHALL require `ATTENDANCE_REPORT:EXPORT_CREATE`; file download SHALL revalidate the current session, download capability, scope, projection/version access, job ownership or delegated access, and expiry before returning content.

#### Scenario: Creation capability is missing
- **WHEN** a report reader lacks `ATTENDANCE_REPORT:EXPORT_CREATE`
- **THEN** the report remains readable and the export creation action is absent or disabled with an accessible reason

#### Scenario: Permission is revoked before download
- **WHEN** a user creates an export and loses required access before download
- **THEN** the download is rejected without returning file content and the UI renders `REVOKED` or a non-leaking 403 state

### Requirement: Synchronous and asynchronous threshold
An authorized export with at most 50,000 rows SHALL use the synchronous-ready contract, while an authorized export above 50,000 rows SHALL create an asynchronous job.

#### Scenario: Export is within threshold
- **WHEN** the authorized export row count is 50,000 or fewer
- **THEN** the export result becomes `READY` through the synchronous contract without presenting a queued background job

#### Scenario: Export exceeds threshold
- **WHEN** the authorized export row count is greater than 50,000
- **THEN** the system returns an asynchronous job whose state can progress through `QUEUED`, `RUNNING`, `READY`, `FAILED`, `EXPIRED`, or `REVOKED`

### Requirement: Export audit metadata
Every export projection SHALL retain the normalized filters, projection version, scope reference, selected fields, purpose, requester, created time, generated time when present, expiry, and safe audit reference.

#### Scenario: Export job is displayed
- **WHEN** a report export job is returned
- **THEN** the UI displays its safe scope/purpose/timing/status metadata without exposing inaccessible row data or secrets

### Requirement: Frozen reports remain reportable
Authorized `FROZEN` or `CLOSED` report projections SHALL remain readable and exportable only from the immutable bound version when the corresponding export permissions and allowed actions are present.

#### Scenario: Closed-period export
- **WHEN** an authorized user exports a closed-period report whose projection allows export
- **THEN** the export binds to the closed version and does not trigger live recalculation or overwrite history

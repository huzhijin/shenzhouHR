## ADDED Requirements

### Requirement: Balance summary and ledger detail share one version
The system SHALL derive account balance, granted/opening/used/returned/expired/adjusted totals, next expiry and ledger detail from the same immutable entry set and projection/period/close version. Summary drill-down MUST bind that version and MUST reconcile to zero difference.

#### Scenario: Summary drills into ledger
- **WHEN** an authorized user opens a balance summary and then requests its detail with the returned version token
- **THEN** the ledger totals SHALL equal the summary and the reconciliation difference SHALL be zero

#### Scenario: Projection changes after summary load
- **WHEN** a new ledger entry creates a newer projection after the summary was loaded
- **THEN** the old version token SHALL return the old consistent view or a stale-version conflict, never mixed-version rows

### Requirement: Reports expose expiry without implicit mutation
Balance views SHALL show each account's available hours, 8-hour equivalent days where applicable, validity range, next expiry date and source totals. Reporting an expired date MUST NOT itself mutate the ledger; expiry processing SHALL be represented by a separately appended `EXPIRY` entry.

#### Scenario: Grant approaches expiry
- **WHEN** a grant is within the configured expiry-warning window
- **THEN** the report SHALL show the remaining hours and expiry date without appending or deleting a ledger entry

### Requirement: Report access uses capability, scope and field minimization
Employee-self queries SHALL derive the employee from the authenticated session and ignore client-supplied employee identity. Manager/HR queries SHALL enforce capability and organization/group scope before count, pagination and data selection. Ordinary reports MUST exclude sensitive leave reasons, attachments, exact locations, payroll and unrelated employee fields.

#### Scenario: Employee forges another employee ID
- **WHEN** an employee-self request supplies another employee ID
- **THEN** the server SHALL ignore it or reject the request and SHALL return only the session employee's account data

#### Scenario: Group lead views balances
- **WHEN** a group lead has balance-summary capability for a synthetic assigned group
- **THEN** the response MAY include member annual/time-off remaining hours and expiry warning but SHALL exclude medical reason, attachment, location and payroll fields

### Requirement: Export reauthorizes create and download
Report export SHALL reuse the page filters, scope and field whitelist. `<=50,000` rows SHALL use the approved synchronous path and larger results SHALL use an asynchronous job. Both export creation and download SHALL independently revalidate session, capability, scope, version and field policy and SHALL write access audit.

#### Scenario: Large export is requested
- **WHEN** an authorized filtered result contains more than 50,000 rows
- **THEN** the service SHALL create an asynchronous export job instead of generating a synchronous response

#### Scenario: Permission is revoked before download
- **WHEN** export creation succeeded but the user's scope/capability is revoked before download
- **THEN** download SHALL be denied and no report content SHALL be returned

### Requirement: W7 consumes a stable W6 reporting contract
W6 SHALL expose management balance/ledger/expiry query and export contracts without implementing employee self-service or dashboard UI inside this change. W7 SHALL consume the versioned W6 contract rather than recalculating balances in React or a separate store.

#### Scenario: W7 requests self-service balance
- **WHEN** W7 implements the employee leave page after W6 acceptance
- **THEN** it SHALL render the W6 versioned account projection and SHALL not sum raw ledger data client-side

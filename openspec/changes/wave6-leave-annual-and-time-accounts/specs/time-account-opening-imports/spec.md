## ADDED Requirements

### Requirement: Opening-balance workbook is versioned and prechecked
The system SHALL provide a versioned `.xlsx` template with `batch_no`, `employee_no`, `employee_name`, `account_type`, `opening_hours`, `balance_as_of`, optional `valid_until` and `source_remark`. Upload SHALL only create a draft; precheck SHALL validate template/version, employee and effective employment matching, account type, unit conversion, decimal/range, dates, duplicates and protected-period state before any formal ledger append.

#### Scenario: Valid synthetic workbook is previewed
- **WHEN** an authorized HR user uploads a valid synthetic opening-balance workbook
- **THEN** precheck SHALL show before balance, proposed opening amount, after balance, difference, affected employee/account counts and no formal ledger change

#### Scenario: Invalid row blocks strict publish
- **WHEN** a row has an unknown employee number, ambiguous employment, unsupported account type, negative hours, more than two decimals or `valid_until < balance_as_of`
- **THEN** precheck SHALL produce a row/field error and strict publish SHALL append nothing

#### Scenario: Name is comparison-only
- **WHEN** employee name matches multiple people but employee number uniquely resolves one effective employment
- **THEN** matching SHALL use the employee number and SHALL treat name only as a displayed consistency check

### Requirement: Opening publication is idempotent and append-only
Publication SHALL use `batch_no + employee_no + account_type + balance_as_of` as the business idempotency key and SHALL also bind the precheck digest, source file digest, template version and period version. Same-key same-digest retries SHALL replay the committed response; same-key changed-digest requests SHALL return conflict. Each published row SHALL append exactly one `OPENING` ledger entry.

#### Scenario: Identical batch is retried
- **WHEN** the same prechecked batch and idempotency digest is published twice
- **THEN** the second request SHALL return the original publication and no duplicate opening entry SHALL exist

#### Scenario: Same key has changed amount
- **WHEN** a previously published business key is submitted with a different opening amount or file digest
- **THEN** publication SHALL return conflict and SHALL not append a new entry

### Requirement: Opening-balance reversal retains the batch
Published opening entries MUST NOT be updated or deleted. Void/reverse SHALL append exact reversal entries referencing the original openings, retain file/row/publication/error evidence, and obey W5 period protection. Consumption after publication MUST NOT authorize physical rollback.

#### Scenario: Published batch is reversed
- **WHEN** an authorized user reverses an open-period opening batch
- **THEN** each committed opening SHALL receive at most one exact reversal and the batch, rows and original entries SHALL remain queryable

#### Scenario: Frozen period blocks reversal
- **WHEN** any affected business date is frozen, closed or unknown
- **THEN** the system SHALL reject reversal until an authorized reopen and new precheck bind the new period version

### Requirement: Opening balance does not alter employment or entitlement inputs
Opening publication SHALL NOT modify employment periods, latest start date, prior-service records, annual qualification, tier or anniversary. System grants already represented in an opening value before the cutover date MUST NOT be automatically appended again.

#### Scenario: Opening annual-leave balance is published
- **WHEN** an annual-leave opening entry is published for a qualified synthetic employee
- **THEN** the employee's employment/prior-service snapshots and annual entitlement assessment SHALL remain unchanged

#### Scenario: Cutover already includes a grant
- **WHEN** the published opening snapshot declares that a pre-cutover grant is included
- **THEN** automatic grant processing SHALL not append the same grant a second time

### Requirement: Import results are auditable and synthetic-safe
The result SHALL report success employees, accounts, total hours, failures and a downloadable row-level error report. Upload, precheck, publish, file/error access and reverse SHALL use separate capabilities, scoped data access, request/correlation IDs and audit events. Test fixtures MUST contain only synthetic identities and no real leave reason.

#### Scenario: User lacks error-report permission
- **WHEN** a user may view batch counts but lacks error-report download capability
- **THEN** the service SHALL deny file access without revealing row contents and SHALL leave the batch unchanged

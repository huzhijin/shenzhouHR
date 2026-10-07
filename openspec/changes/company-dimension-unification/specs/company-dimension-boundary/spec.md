## ADDED Requirements

### Requirement: Company is the authoritative top-level dimension
The system SHALL represent the top-level employee, organization, attendance,
integration, reporting, and authorization boundary as a company identified by
one stable `companyId`.

#### Scenario: Company identity is used across product contracts
- **WHEN** a current API, runtime model, UI, export, or active operational document refers to the top-level boundary
- **THEN** it uses company terminology and the exact stable `companyId`

#### Scenario: A company ID is never inferred
- **WHEN** a request supplies only a company name, organization name, employee name, or external-source label
- **THEN** the system rejects the request rather than inferring a company ID

### Requirement: Existing top-level identities migrate without data loss
The V11 migration SHALL retain every existing top-level identifier and
relationship byte-for-byte while renaming the authoritative latest schema to
company terminology.

#### Scenario: V10 data upgrades to V11
- **WHEN** a valid V10 database is migrated to V11
- **THEN** company, employee, organization, scope, source, configuration, evidence, projection, and export row counts and stable identifier digests remain unchanged

#### Scenario: Legacy scope values are migrated atomically
- **WHEN** V11 migrates an `auth_data_scope` row with the old top-level scope value
- **THEN** the row has `scope_type=COMPANY`, retains its scope ID and company ID, and satisfies the new exact shape constraint

#### Scenario: Published migrations remain pinned
- **WHEN** the company migration is delivered
- **THEN** V1 through V8 and V10 bytes remain unchanged, V9 differs only by the exact quoted `row_number` compatibility correction, and every expected checksum is pinned

#### Scenario: Existing V9 history requires controlled reconciliation
- **WHEN** an external database already records a V9 checksum different from the corrected pinned checksum
- **THEN** migration stops before V11 and does not run an automatic Flyway repair

### Requirement: Attendance authorization has exactly three scope types
The system SHALL authorize attendance data with only `COMPANY`,
`ORGANIZATION`, or `SELF` scopes and SHALL fail closed for unknown, legacy,
expired, inactive, or structurally invalid scope grants.

#### Scenario: Company scope
- **WHEN** a principal has a current required capability and an active `COMPANY` scope for company A
- **THEN** the principal can observe only authorized records whose exact company ID is A

#### Scenario: Organization scope
- **WHEN** a principal has an active organization scope
- **THEN** the system first proves that the organization belongs to one company and limits access to permitted current organizations and employees inside that same company

#### Scenario: Self scope
- **WHEN** an employee uses a self-service operation
- **THEN** the company and employee are derived from the server session and a client-supplied employee or company ID cannot expand access

#### Scenario: Legacy scope input
- **WHEN** an API or role-assignment request supplies the old top-level scope literal
- **THEN** strict request validation rejects it and no grant is written

### Requirement: Company filtering precedes all observable data operations
The system SHALL apply the authorized company predicate to every company-bound
list, count, search, detail, mutation, calculation, projection, export, and
integration operation before pagination, aggregation, object selection, or
state change.

#### Scenario: Cross-company list request
- **WHEN** a principal scoped to company A requests company B in a list or report query
- **THEN** no rows, count, names, existence signal, or pagination metadata from company B are returned

#### Scenario: Cross-company object identifier
- **WHEN** a principal scoped to company A submits an object ID owned by company B
- **THEN** the operation fails without disclosing whether the object exists

#### Scenario: Cross-company mutation
- **WHEN** a source, employee, organization, policy, calculation, or report command mixes company IDs
- **THEN** the transaction rolls back and writes no success audit or partial business state

### Requirement: Integrations are bound to one company
The system SHALL bind every Deli source instance, OA evidence record, import
batch, device binding, employee resolution, and recalculation intent to exactly
one company and SHALL never use an external department or person label to
expand that boundary.

#### Scenario: Deli source page ingestion
- **WHEN** a Deli page is ingested for a source registered to company A
- **THEN** all configuration, employee resolution, facts, watermarks, quarantine records, and intents are written only under company A

#### Scenario: OA person matching across companies
- **WHEN** an OA `org_member.code` could match employees in more than one company or a different company
- **THEN** the record is quarantined unless one exact employee in the source-bound company is proven

### Requirement: Reports and exports remain company-bound
The system SHALL bind report directories, queries, projections, asynchronous
jobs, artifacts, and downloads to the same exact company ID, current
authorization, query fingerprint, and visible-content digest.

#### Scenario: Company selection
- **WHEN** a user can access multiple companies
- **THEN** the report UI requires an explicit company selection and sends no report or export request before selection

#### Scenario: Export authorization changes
- **WHEN** a report was created for company A and the user loses company A scope before download
- **THEN** download is denied even if the job ID, password reauthentication, and artifact hash are otherwise valid

#### Scenario: Cross-company export task
- **WHEN** a task or artifact company differs from the request, projection, or authorized company
- **THEN** the operation fails closed and returns no artifact bytes

### Requirement: Public APIs and UI expose no active legal-entity vocabulary
The system SHALL use only company terminology for this boundary in current
OpenAPI operations, JSON schemas, query parameters, frontend production types,
labels, selectors, error messages, and current handoff documentation.

#### Scenario: Legacy property is sent
- **WHEN** a client sends a legacy top-level ID property or query parameter
- **THEN** strict deserialization or parameter validation rejects the request and does not silently map it

#### Scenario: Product vocabulary scan
- **WHEN** the current runtime, API, frontend production source, and active documentation are scanned
- **THEN** the old boundary vocabulary is absent outside an explicit pinned historical migration/evidence allowlist

### Requirement: Company migration and access are independently verifiable
The change SHALL include deterministic migration, contract, authorization,
frontend, and source-scan tests that can fail independently.

#### Scenario: Empty database migration
- **WHEN** an empty approved MySQL database migrates from V1 through latest
- **THEN** the latest schema contains the company table and company columns, the `COMPANY` scope constraint, and no runtime dependency on the old names

#### Scenario: Upgrade migration
- **WHEN** a populated V10 fixture migrates to V11
- **THEN** retained data and relationships pass exact before/after checks

#### Scenario: Cross-company negative matrix
- **WHEN** list, detail, mutation, role grant, source ingestion, report, export creation, status, and download are tested with mismatched companies
- **THEN** every operation fails closed before disclosing or changing company B data

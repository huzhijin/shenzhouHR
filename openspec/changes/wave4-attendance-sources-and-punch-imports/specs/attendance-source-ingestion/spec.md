## ADDED Requirements

### Requirement: Source instances are explicit, scoped and versioned
The system SHALL model each 得力、OA、设备 Excel 或标准 Excel来源 as a stable source instance scoped to one legal entity, and SHALL store configuration changes as immutable revisions with an effective time.

#### Scenario: Configure a source without storing a secret
- **WHEN** an authorized caller creates or revises a source configuration with a repository-external secret reference
- **THEN** the system stores the source type, legal entity, timezone, adapter settings, effective time and secret reference name without storing or returning the secret value

#### Scenario: Reject a cross-entity source reference
- **WHEN** a caller attempts to attach a device, mapping or job to a source in another legal entity
- **THEN** the server rejects the request before mutation and records a failure audit without leaking the target source

### Requirement: Source boundaries exclude organization sync and upstream writes
The system MUST use 得力 only as a punch source and 致远 OA only as a read-only attendance-document source. It MUST NOT create organization synchronization, OA/得力 write-back, biometric-template collection or upstream delete operations.

#### Scenario: No organization synchronization capability is exposed
- **WHEN** the W4 OpenAPI, controllers, routes, menus, source jobs and adapters are enumerated
- **THEN** no operation can start continuous/manual organization sync or write organization changes to OA

#### Scenario: Adapter receives no write command
- **WHEN** a 得力 punch or OA document is normalized, quarantined, superseded or reversed
- **THEN** the source adapter is never called with a create/update/delete/write-back operation

### Requirement: Deli punch adapter has a canonical consumer contract
The system SHALL define a provider-independent 得力 punch page contract containing string source IDs, person reference, punch instant and original time, timezone, direction, verification method, device/location evidence, source version and next cursor.

#### Scenario: Ingest a valid Deli page
- **WHEN** the synthetic 得力 adapter returns a valid page containing IDs longer than JavaScript's safe integer range
- **THEN** every ID remains byte-exact as a string and the records enter the shared raw pipeline without numeric coercion

#### Scenario: Do not collect biometric templates
- **WHEN** a provider payload contains face/fingerprint template material outside the canonical allowlist
- **THEN** the adapter drops and flags the forbidden material, and neither the database, logs nor API response contains it

#### Scenario: Preserve unknown coordinate system without plotting
- **WHEN** a punch contains a location summary with an unknown coordinate-system tag
- **THEN** the system retains only the authorized source evidence and tag, performs no coordinate conversion and exposes no map-ready coordinate

### Requirement: OA attendance documents use a versioned read-only contract
The system SHALL normalize only supported OA attendance business documents and SHALL retain business key, source version, employee reference, type, interval, original timezone, status, submit/approve/modify/revoke times and source batch.

#### Scenario: Approved OA document becomes candidate evidence
- **WHEN** a versioned OA status mapping classifies a supported document version as approved and its interval is valid
- **THEN** the document creates an immutable normalized record and candidate effective interval evidence

#### Scenario: Draft, rejected and unknown status do not become effective
- **WHEN** an OA document is draft, rejected or has an unmapped status
- **THEN** the raw source fact is retained, the record is quarantined with an explicit reason, and it creates no active business-document evidence

#### Scenario: Modification or revocation appends a new version
- **WHEN** OA returns a later modification, leave revocation or cancellation for an existing business key
- **THEN** the system appends a new raw fact and reversal/supersession lifecycle facts while preserving every previous version

#### Scenario: Out-of-order version is retained but cannot replace newer knowledge
- **WHEN** an older source version arrives after a newer committed version
- **THEN** it is idempotently retained or recognized, marked superseded for current knowledge, and does not reactivate obsolete evidence

### Requirement: Device and person bindings are effective-dated
The system SHALL represent devices and device-person/external-person bindings with legal-entity, location and half-open effective periods, and SHALL reject overlapping bindings that make matching ambiguous.

#### Scenario: Binding matches at punch time
- **WHEN** no employee number is supplied and exactly one location + device + device-person binding covers the punch instant
- **THEN** that binding is eligible for employee and employment-period resolution

#### Scenario: Adjacent bindings are allowed
- **WHEN** one binding ends exactly when its successor begins
- **THEN** both can be published and each instant resolves to exactly one binding

#### Scenario: Overlapping bindings fail closed
- **WHEN** two bindings for the same source person overlap at the fact instant
- **THEN** the fact is quarantined as ambiguous and no employee or effective event is guessed

### Requirement: AC-SOURCE-01 committed watermark and retry semantics
The system MUST advance a source watermark only in the same transaction that commits the complete fetched page and its raw/normalized/match/effective or quarantine outcomes. A failed page MUST leave the prior committed watermark unchanged, and retry MUST resume from that watermark.

#### Scenario: Successful page advances once
- **WHEN** a page is completely persisted and its transaction commits
- **THEN** the committed watermark advances to the page cursor exactly once and the job page records counts, digest and request ID

#### Scenario: Transport or whole-page parse failure does not advance
- **WHEN** transport fails, the cursor loops/regresses, the entire page cannot be parsed or the database transaction rolls back
- **THEN** the watermark remains byte-identical and a retry starts from the previous committed value

#### Scenario: Quarantined row can commit with its page
- **WHEN** one syntactically valid source record has an unknown business status or unmatched employee while the page itself is valid
- **THEN** its raw/quarantine record and page counters commit and the page watermark may advance without making the row effective

#### Scenario: Replayed page is idempotent
- **WHEN** the adapter returns an already committed page after a timeout or retry
- **THEN** source-key/version uniqueness prevents new raw facts or effective events and the resulting watermark remains correct

### Requirement: Source jobs have observable bounded state
The system SHALL expose source jobs with the exact states `QUEUED`, `RUNNING`, `SUCCEEDED`, `PARTIALLY_QUARANTINED`, `FAILED` and `CANCELLED`, stable pagination, freshness, counts, last committed watermark, bounded error details and correlation ID.

#### Scenario: Partially quarantined job remains successful for committed pages
- **WHEN** all pages commit but one or more rows are quarantined
- **THEN** the job ends `PARTIALLY_QUARANTINED`, reports exact accepted/quarantined counts and retains the committed watermark

#### Scenario: Failed job exposes a safe retry reason
- **WHEN** a job fails before committing its current page
- **THEN** the job returns a stable reason code and request ID without credentials, SQL, local paths or full sensitive source payload

#### Scenario: Pagination is deterministic
- **WHEN** jobs with identical start times are paged repeatedly
- **THEN** the server uses a documented stable sort plus immutable job ID tie-breaker with no duplicate or missing row

### Requirement: Source job mutations are durable and idempotent
Starting and retrying a source job SHALL require CSRF, `Idempotency-Key`, `X-Change-Reason` and the relevant source capability. Only a committed success MAY be replayed.

#### Scenario: Same key and request replays exact response
- **WHEN** the same actor repeats an identical committed start/retry request with the same key and canonical digest
- **THEN** the server returns the original status, business headers and body and creates no second job

#### Scenario: Same key with changed reason conflicts
- **WHEN** the same actor reuses a key with a different source, payload, If-Match or change reason
- **THEN** the server returns 409 and creates no job or success audit

#### Scenario: Failed transaction can be retried
- **WHEN** a start/retry transaction rolls back before completed success is committed
- **THEN** an identical request can safely retry and no stale started record is treated as successful

### Requirement: Source permissions and data scope are enforced before query or mutation
The server SHALL separately enforce `ATTENDANCE_SOURCE:READ`, `CONFIGURE`, `RUN`, `RETRY` and `QUARANTINE_READ`, plus legal-entity/location/organization scope. Technical system administration MUST NOT imply raw attendance access.

#### Scenario: System administrator lacks attendance detail by default
- **WHEN** a system administrator without attendance-source read capability requests source rows or quarantine details
- **THEN** the server rejects before business-row selection and returns no employee, punch or document data

#### Scenario: Scoped source list is filtered in SQL
- **WHEN** an authorized caller lists sources or jobs with access to only one legal entity/location
- **THEN** the database query selects only that scope before pagination and counts

#### Scenario: Auditor remains read-only
- **WHEN** an auditor has source read but not configure/run/retry capability
- **THEN** read endpoints return authorized metadata while every mutation and UI mutation affordance is absent or rejected

### Requirement: External contract evidence is distinct from live integration evidence
The W4 internal acceptance SHALL require passing 得力/OA consumer contract fixtures but MUST report real provider integration separately as `NOT_VERIFIED` until approved endpoints, credentials and de-identified samples are used.

#### Scenario: Stub passes without live credentials
- **WHEN** all synthetic provider fixtures pass but no approved live credential is available
- **THEN** the report states `DELI_CONTRACT_STUB=PASS`, `OA_CONTRACT_STUB=PASS`, `DELI_LIVE=NOT_VERIFIED` and `OA_LIVE=NOT_VERIFIED`

#### Scenario: Stub result cannot be relabeled as live
- **WHEN** evidence contains only local mock/fixture traffic
- **THEN** verification rejects any claim that real 得力 or OA integration passed

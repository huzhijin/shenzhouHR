## ADDED Requirements

### Requirement: AC-PUNCH-01 versioned XLSX template contract
The system SHALL provide an authorized, versioned `.xlsx` template containing `导入说明`, `考勤打卡导入`, `设备人员映射（可选）`, `字段说明`, `枚举值` and `示例数据`, with the 14 canonical punch fields, 9 device-person mapping fields and synthetic examples.

#### Scenario: Download a template
- **WHEN** a caller with `ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD` downloads the current template
- **THEN** the response supplies a safe filename, no-store headers, ETag/file SHA-256, machine-readable template version and canonical field-contract SHA-256

#### Scenario: Template artifact is safe
- **WHEN** the workbook package is mechanically inspected
- **THEN** all six sheets and required fields exist, examples are synthetic, and no formula, macro, external link, OLE/DDE or credential exists

#### Scenario: Server does not trust workbook styling
- **WHEN** valid data appears after the pre-styled/table range but remains within 50,000 rows
- **THEN** the server validates every data row using the field contract instead of ignoring it due to missing style or validation

### Requirement: AC-PUNCH-02 only raw punch facts can be imported
The import SHALL accept only source metadata, person identifiers, punch time, direction, verification method, source record ID, event code, timezone and note fields. It MUST reject calculated attendance conclusions and MUST NOT act as a punch-correction application.

#### Scenario: Calculated result column is rejected
- **WHEN** a workbook maps or contains columns such as late minutes, absence, recognized overtime, exception result or payroll hours
- **THEN** precheck reports a blocking forbidden-result-column issue and no raw fact can be published

#### Scenario: Manual punch correction is not disguised as a device row
- **WHEN** a user supplies a manually invented punch without an allowed device/standard-template source classification and required provenance
- **THEN** the server rejects it and directs the case to the later authorized correction/evidence workflow

### Requirement: AC-PUNCH-03 file limits and active-content rejection
The server MUST enforce configurable defaults of 20 MiB compressed file size and 50,000 data rows, accept only valid OOXML `.xlsx`, and reject macro, formula, external-link, embedded active content and unsafe ZIP structures without executing content.

#### Scenario: Exact size and row boundaries
- **WHEN** a safe workbook is exactly at the configured size/row limit
- **THEN** it can enter parsing, while a workbook one byte or one data row over the limit receives the documented 413/validation error without partial staging

#### Scenario: Formula cell is rejected
- **WHEN** any imported or mapping cell contains a formula, including a cached result
- **THEN** the workbook fails precheck and the formula is never evaluated

#### Scenario: Macro or external link is rejected
- **WHEN** OOXML content types or relationships contain VBA, external links, DDE/OLE or embedded package content
- **THEN** upload/precheck fails with a stable safe-file reason and no active content is opened

#### Scenario: Extension or MIME cannot bypass content inspection
- **WHEN** a non-XLSX file is renamed `.xlsx`, or the browser supplies an empty/misleading MIME
- **THEN** the server decides from extension plus ZIP/OOXML structure and accepts only a valid safe `.xlsx`

#### Scenario: ZIP abuse is rejected
- **WHEN** the package exceeds configured entry, uncompressed-size, path or compression-ratio bounds
- **THEN** parsing stops safely, creates no staged row and returns no local path or parser stack

### Requirement: AC-PUNCH-04 mapping profiles are scoped and immutable by version
The system SHALL support stable mapping-profile identities with immutable versions scoped by company, vendor, model and optional location. Mapping operations SHALL be limited to registered canonical fields, date formats, timezone, trim policy and enum maps.

#### Scenario: Save a new mapping version
- **WHEN** an authorized caller changes a used mapping profile
- **THEN** the system creates a successor version, preserves the old version and requires later batches to select an explicit version

#### Scenario: Reject script or expression mapping
- **WHEN** mapping configuration contains JavaScript, formula, regular-expression code execution or an unregistered transform
- **THEN** validation rejects the profile before publication

#### Scenario: Batch preserves selected mapping
- **WHEN** a profile is revised after a batch precheck
- **THEN** the batch continues to reference the original immutable mapping version and its old precheck digest cannot silently use the new version

### Requirement: AC-PUNCH-05 deterministic employee and employment matching
Punch precheck SHALL first use a unique employee number and validate the punch-time employment period; otherwise it SHALL use an effective location + device + device-person binding. Name and department are comparison-only fields.

#### Scenario: Unique employee number and active employment
- **WHEN** a row contains an employee number with exactly one employment period covering the punch instant
- **THEN** precheck records that employee and employment-period ID as the match

#### Scenario: Device binding fallback
- **WHEN** employee number is absent and exactly one effective location/device/person binding resolves
- **THEN** precheck uses the binding and records its immutable version

#### Scenario: Name-only match is forbidden
- **WHEN** a row contains only a name or a name and department
- **THEN** the row receives a blocking unmatched issue and no employee is guessed

#### Scenario: Rehire gap is blocking
- **WHEN** the punch instant falls in the gap between two employment periods
- **THEN** the row is unmatched even if the employee number exists

#### Scenario: Scope is checked during match
- **WHEN** a row's location or resolved employee lies outside the caller's scope
- **THEN** precheck reports a non-disclosing scope issue and publish cannot include the row

### Requirement: AC-PUNCH-06 file hash prevents renamed republish
The system SHALL identify identical files by company, source scope, content SHA-256 and template/mapping context. Changing only the filename MUST NOT publish a second set of raw facts.

#### Scenario: Renamed identical upload
- **WHEN** an already uploaded file is uploaded again with another filename in the same scope
- **THEN** the server returns the existing file/batch relation or a new precheck attempt linked to it, and a publish attempt creates no duplicate raw facts

#### Scenario: Same bytes in a different company are isolated
- **WHEN** authorized callers upload identical bytes to two companies
- **THEN** file content may be deduplicated at storage level but batches, permissions and publication identities remain legally isolated

### Requirement: AC-PUNCH-07 source ID and stable fingerprint idempotency
Published rows SHALL use source record ID as the highest-priority source identity. If absent, the canonical stable fingerprint SHALL prevent exact duplicates while preserving legitimate multiple punches.

#### Scenario: Source record ID duplicate
- **WHEN** two rows in one or multiple batches share the same source instance, scope and source record ID/version
- **THEN** the later row is classified exact duplicate and creates no new raw punch

#### Scenario: Fingerprint duplicate
- **WHEN** a row without source ID repeats with identical location, device, person/employee, normalized time and direction
- **THEN** it is classified exact duplicate by the same fingerprint in precheck and publish

#### Scenario: Different second remains distinct
- **WHEN** two real punches differ by time or direction and do not satisfy exact identity
- **THEN** both raw facts are retained unless the near-duplicate review rule intentionally holds their active events

### Requirement: AC-PUNCH-08 Excel and Deli exact duplicate merge
An Excel row and 得力 API record with the same device stream identity or employee + exact time + normalized direction SHALL retain both source raw facts but create exactly one active effective event.

#### Scenario: Import before online record
- **WHEN** Excel publish commits first and the exact 得力 fact arrives later
- **THEN** the final state contains both raw facts, one active effective event and evidence links to both sources

#### Scenario: Online record before import
- **WHEN** arrival order is reversed
- **THEN** the final effective cardinality and canonical evidence digest are identical

### Requirement: AC-PUNCH-09 near duplicates require review
Different-source records for the same employee and normalized direction separated by 1 through the configured default 60 seconds SHALL be published as raw facts in `PENDING_DUPLICATE_REVIEW` and SHALL produce zero active effective events until an authorized immutable resolution.

#### Scenario: Precheck reports near candidates
- **WHEN** a valid row is 1～60 seconds from an existing other-source fact
- **THEN** preview reports the review group, raw members and zero-event impact as a warning or configured blocker

#### Scenario: Publish warning without activating event
- **WHEN** strict policy permits the default warning and the caller confirms publication
- **THEN** raw facts and the pending group commit but no member enters calculation intent as an active event

#### Scenario: Resolve as same or distinct
- **WHEN** a reviewer with `DUPLICATE_REVIEW` resolves the group with reason and current If-Match
- **THEN** `SAME_FACT` yields exactly one active event and `DISTINCT_FACTS` yields one per raw member, with all raw facts preserved

#### Scenario: Window version is preserved
- **WHEN** the near-duplicate window changes after a precheck or resolution
- **THEN** the old attempt/group retains the original window version and is not silently reclassified

### Requirement: AC-PUNCH-10 strict and controlled partial publication
Strict publication SHALL be the default and SHALL reject a batch containing any blocking issue. Controlled valid-row-only publication SHALL require a separate capability, explicit mode/reason and confirmation, and SHALL end `PARTIALLY_PUBLISHED` with every error row retained.

#### Scenario: Strict batch with one blocker does not publish
- **WHEN** one of many rows has a blocking validation issue under strict mode
- **THEN** no batch raw fact, effective event or recalculation intent commits

#### Scenario: Authorized partial publication
- **WHEN** an authorized caller with `PARTIAL_PUBLISH` explicitly confirms valid-row-only mode
- **THEN** valid rows publish, invalid rows remain with issues/error report, exact counts reconcile and the batch becomes `PARTIALLY_PUBLISHED`

#### Scenario: Unauthorized partial mode
- **WHEN** a caller with ordinary publish but not partial-publish capability requests valid-row-only mode
- **THEN** the server returns 403 before any publication

### Requirement: AC-PUNCH-11 publication creates only affected-range intents
For an open period, publication SHALL append recalculation intents only for employees and candidate business dates affected by newly active/retracted evidence. It MUST NOT request an unrelated employee or whole-month recalculation.

#### Scenario: One employee and one day
- **WHEN** a batch publishes one daytime punch for one employee
- **THEN** only that employee and authoritative candidate date appear in committed intents

#### Scenario: Cross-midnight candidate
- **WHEN** W3 resolves a punch as potentially belonging to the previous cross-midnight shift
- **THEN** the intent uses the authoritative candidate set and does not truncate by natural date

#### Scenario: Unrelated employee stays unchanged
- **WHEN** a test fixture includes another employee/date with an existing downstream calculation marker
- **THEN** W4 creates no intent or mutation for that unrelated employee/date

### Requirement: AC-PUNCH-12 frozen and closed periods protect publication
Upload and precheck MAY run against a frozen or closed period, but publication, reversal and recalculation intent MUST be blocked. After reopen the caller MUST run a new precheck tied to the new period version.

#### Scenario: Frozen period precheck
- **WHEN** valid rows target a frozen period
- **THEN** the batch becomes `BLOCKED_BY_FROZEN_PERIOD`, preview explains the protected ranges, and no raw publication/effective event/intent is committed

#### Scenario: Old confirmation cannot publish after reopen
- **WHEN** the period is reopened but the caller submits the pre-reopen token
- **THEN** the server returns 409 and requires a new precheck

#### Scenario: Reopened period produces a new intent version
- **WHEN** new precheck succeeds against the reopened period and publish commits
- **THEN** evidence and intents reference the new period/provider version without overwriting the prior closed snapshot

### Requirement: AC-PUNCH-13 published batch can only be voided or reversed
A `PUBLISHED` or `PARTIALLY_PUBLISHED` batch MUST NOT be physically deleted or restored in place. Authorized void/reverse in an open/reopened period SHALL append reversal evidence, state, intents and audit while retaining the file, rows, hashes and prior events.

#### Scenario: Void an open-period batch
- **WHEN** an authorized caller supplies reason, If-Match and idempotency key for a published batch in an open period
- **THEN** the system appends reversal facts/lifecycle, narrow intents and a `VOIDED` state in one transaction

#### Scenario: Void in protected period is blocked
- **WHEN** the batch affects a frozen or closed period
- **THEN** void/reverse returns 409 and all existing evidence/state remain unchanged

#### Scenario: Physical delete is impossible
- **WHEN** API, repository and runtime-account privileges are inspected after publication
- **THEN** there is no path to DELETE published batch/file/row/raw/evidence history

### Requirement: AC-PUNCH-14 batch and row trace is complete
Every batch SHALL retain source type, company, location/device/timezone, original filename, object reference, file hash/size, template and mapping version, attempts, counts, actors, timestamps and request IDs. Every row SHALL retain row number, raw/normalized values, match decision, fingerprint, issues, publication/effective IDs and evidence links as permitted.

#### Scenario: Trace a published row
- **WHEN** an authorized raw-row reader opens a published row
- **THEN** the response can traverse batch → file → attempt → row → raw fact → event/evidence → intent using immutable string IDs

#### Scenario: Raw file access is separately authorized
- **WHEN** a batch reader lacks `RAW_FILE_READ`
- **THEN** batch metadata remains available but file bytes/object ref/path are not returned

#### Scenario: Download error report is audited
- **WHEN** an authorized caller downloads an error report
- **THEN** the server rechecks scope/capability, emits no-store safe download headers and records a download audit

### Requirement: AC-PUNCH-15 failure states and retry prerequisites are exact
API, database and UI MUST use the distinct states `VALIDATION_FAILED`, `BLOCKED_BY_FROZEN_PERIOD` and `PUBLISH_FAILED`; a generic `FAILED` MUST NOT replace them.

#### Scenario: Validation failure retry
- **WHEN** file, mapping or row validation has blockers
- **THEN** the batch enters `VALIDATION_FAILED` and can proceed only after correction followed by a new precheck

#### Scenario: Frozen-block retry
- **WHEN** period protection blocks otherwise valid rows
- **THEN** the batch enters `BLOCKED_BY_FROZEN_PERIOD` and can proceed only after reopen plus a new precheck/token

#### Scenario: Publish transaction failure retry
- **WHEN** persistence fails after entering PUBLISHING but before commit
- **THEN** zero publication raw facts/effective events/intents/success audits remain, the batch records `PUBLISH_FAILED`, and an identical idempotent retry can safely rerun

#### Scenario: Enum closure
- **WHEN** OpenAPI schemas, Java enums, database CHECKs, frontend unions, labels and transition tests are compared
- **THEN** they contain the same exact batch states and allowed transitions with no unknown or generic failure state

### Requirement: Batch state history and optimistic concurrency are durable
The current batch state SHALL derive from append-only state events, and every mapping/precheck/publish/void mutation SHALL use strong If-Match plus durable idempotency.

#### Scenario: Stale mapping update
- **WHEN** a caller updates mapping with an old ETag
- **THEN** the server returns 409 and creates no attempt or state event

#### Scenario: Same publish key replays committed response
- **WHEN** the same actor repeats the same committed publish request and precheck token
- **THEN** status, headers and body replay exactly with no new publication or audit

#### Scenario: Changed publish mode conflicts
- **WHEN** the same idempotency key is reused with a different strict/partial mode, reason or token
- **THEN** the server returns 409 before mutation

### Requirement: Punch import actions are independently authorized and scoped
The server SHALL independently enforce template download, upload, precheck, publish, partial publish, void/reverse, raw file read, raw row read, error report download, duplicate review and recalculation-intent actions, plus company/location/organization scope.

#### Scenario: Upload does not imply publish
- **WHEN** a caller can upload and precheck but lacks publish
- **THEN** the caller can create/inspect a batch but the publish endpoint and UI affordance are denied

#### Scenario: Technical admin cannot read raw file by default
- **WHEN** SYSTEM_ADMIN lacks explicit raw-file capability
- **THEN** direct API, modified URL and UI navigation cannot expose the file

#### Scenario: SQL scope applies before pagination
- **WHEN** a user lists batches/rows across mixed locations
- **THEN** only authorized rows are selected/countable before sort and pagination

#### Scenario: Recalculation intent is separately authorized
- **WHEN** a caller requests recalculation intents for a published batch in an open/reopened period
- **THEN** only a caller with `ATTENDANCE_PUNCH_IMPORT:RECALCULATE` can append idempotent intents for that batch's previously published affected employees/dates, and W4 does not execute or broaden the calculation

#### Scenario: Recalculation capability is absent or period is protected
- **WHEN** the caller lacks recalculation capability or the batch touches a frozen/closed/unknown period
- **THEN** the server rejects before intent creation and the existing evidence and downstream results remain unchanged

### Requirement: Import UI implements the real state machine responsively
The routes `/sources/attendance-excel` and `/sources/attendance-excel/:batchId` SHALL use the real API in normal mode and SHALL render upload/mapping/precheck/preview/errors/publish/reversal states without relying on static prototype IDs.

#### Scenario: Complete real import flow
- **WHEN** an authorized normal-mode user uploads a synthetic workbook, maps, prechecks and publishes against real backend/MySQL
- **THEN** route, breadcrumb, state labels, counts, ETag and resulting evidence all match server responses

#### Scenario: Conflict and frozen states are actionable
- **WHEN** the server returns stale If-Match, duplicate review, validation failure or frozen block
- **THEN** the UI preserves user context, shows the exact next permitted action and never labels the operation successful

#### Scenario: Narrow viewport is usable
- **WHEN** the flow is exercised at 360×800, 390×844 and 430×932
- **THEN** there is no page-level horizontal scroll, tables use cards or bounded local scrolling, dialogs retain focus and controls meet 44px targets

#### Scenario: Read-only user sees no mutation affordance
- **WHEN** an auditor has authorized batch read but no import mutation capability
- **THEN** history and permitted evidence are visible while upload/precheck/publish/void/review controls are absent and direct calls remain denied

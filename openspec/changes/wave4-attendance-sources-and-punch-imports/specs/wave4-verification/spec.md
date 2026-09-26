## ADDED Requirements

### Requirement: W3 final acceptance is a hard prerequisite
W4 implementation and final verification MUST consume a valid W3 final manifest produced from the same retained W1～W3 source baseline. It MUST NOT edit W3 artifacts or treat an invalidated, historical, partial or narrow W3 run as a prerequisite pass.

#### Scenario: Current W3 manifest is valid
- **WHEN** W4 verification starts
- **THEN** it verifies W3 final status, manifest/integrity hashes, source hash, V1～V7 checksums and all W3 required gate IDs before any W4 PASS can be derived

#### Scenario: W3 source changed after its run
- **WHEN** current retained W1～W3 source or migration checksum differs from the W3 final manifest
- **THEN** W4 verification stops RED and requires a new valid upstream acceptance instead of updating W3 evidence

### Requirement: V8/V9 migration contract is independently anchored
Verification SHALL maintain a review-owned expected registry for every W4 table, ordered column/logical type, PK, FK/equivalent integrity, CHECK, unique and query index. Expected values MUST NOT be generated from the migration under test, H2 schema, Mapper or live database.

#### Scenario: Empty database reaches latest
- **WHEN** an empty authorized test schema migrates from V1 through V9/current latest
- **THEN** Flyway history, expected registry, V1～V7 checksums and all W4 tables/constraints/indexes match exactly

#### Scenario: Same database upgrades from V7
- **WHEN** a database with W1～W3 synthetic retained rows at V7 migrates to V8, V9 and any later version
- **THEN** W1～W3 rows and canonical digests remain unchanged and W4 schema/seed rows match the independent registry

#### Scenario: Second migration is a no-op
- **WHEN** migrate and validate are executed again on the same latest database
- **THEN** no table, index, state, catalog row or checksum changes

#### Scenario: Published version collision is rejected
- **WHEN** V8 or V9 is already occupied by another published migration
- **THEN** implementation stops and updates this change by an explicit forward renumbering rather than editing or overwriting that migration

### Requirement: W4 OpenAPI and implementation have bidirectional closure
The OpenAPI document, Spring controllers, Java/TypeScript DTOs and state enums SHALL be exact for all W4 operations. Contract verification MUST compare both directions and resolved schemas, not only check that YAML parses.

#### Scenario: Operation closure
- **WHEN** Controller mappings and OpenAPI paths are normalized by method + path template
- **THEN** both sets are equal and contain no undocumented W4 operation or unimplemented documented operation

#### Scenario: Request/response closure
- **WHEN** each W4 operation is inspected
- **THEN** path/query/header/multipart parts, CSRF/idempotency/If-Match/change reason, statuses, enums, unknown-field rules, nullability, pagination and correlation headers match runtime behavior

#### Scenario: Failure enums close across layers
- **WHEN** batch/job/event states and reason codes are compared across OpenAPI, Java, database and TypeScript
- **THEN** the sets and allowed transitions are exact, including the three distinct import failure states

### Requirement: Required W4 leaf gates are fixed and independently executed
One frozen-source W4 run SHALL produce these exact 24 pre-review leaf gates, each from its own real command or bounded orchestrated test with non-empty raw evidence:

1. `W4-VER-OPENSPEC-STRICT`
2. `W4-VER-RETAINED-W1-W3`
3. `W4-VER-MIGRATION-CONTRACT`
4. `W4-VER-OPENAPI-CLOSURE`
5. `W4-VER-BACKEND-FULL`
6. `W4-VER-W1-REGRESSION`
7. `W4-VER-W2-REGRESSION`
8. `W4-VER-W3-REGRESSION`
9. `W4-VER-W4-REGRESSION`
10. `W4-VER-FRONTEND-TYPECHECK`
11. `W4-VER-FRONTEND-LINT`
12. `W4-VER-FRONTEND-TESTS`
13. `W4-VER-PROD-BUILD`
14. `W4-VER-DEMO-BUILD`
15. `W4-VER-XLSX-CONTRACT`
16. `W4-VER-DELI-STUB`
17. `W4-VER-OA-STUB`
18. `W4-VER-MYSQL-8410`
19. `W4-VER-NORMAL-BROWSER`
20. `W4-VER-DEMO-ISOLATION`
21. `W4-VER-AUTH-SCOPE`
22. `W4-VER-PAYROLL-ORG-SYNC-ZERO`
23. `W4-VER-SECURITY-SCAN`
24. `W4-VER-EVIDENCE-CONCURRENCY`

#### Scenario: A leaf gate is missing or reused
- **WHEN** any fixed leaf ID is absent, duplicated, from another run/source/DB, has null/empty evidence, or reports `NOT_VERIFIED`
- **THEN** W4 final acceptance is RED

#### Scenario: Backend and frontend commands are distinct
- **WHEN** backend full/regression, frontend typecheck/lint/tests and prod/demo builds run
- **THEN** each gate records its own command, start/end, exit status, test/count summary and artifact digest instead of reusing one umbrella success marker

### Requirement: XLSX contract gate verifies the artifact and parser
`W4-VER-XLSX-CONTRACT` SHALL inspect the actual served/downloaded template package and execute parser security/boundary tests, including 20 MiB, 50,000 rows, formula, macro, external link, ZIP abuse, mapping version and calculated-result-column rejection.

#### Scenario: Workbook structure and metadata match
- **WHEN** the served template is unpacked read-only
- **THEN** its six sheets, 14/9 fields, synthetic examples, template version, field-contract hash, file hash and safe OOXML relationships match the frozen template oracle

#### Scenario: Exact parser boundaries run
- **WHEN** generated synthetic workbooks exercise exact limit and limit+1 cases plus hostile package fixtures
- **THEN** the exact boundary is accepted/rejected as specified and no fixture is executed

### Requirement: Provider contract gates cover failures and reversals
`W4-VER-DELI-STUB` and `W4-VER-OA-STUB` SHALL execute the same consumer contracts expected of real adapters against deterministic synthetic servers/fixtures.

#### Scenario: Deli contract matrix
- **WHEN** the 得力 stub gate runs
- **THEN** it covers pagination, duplicate/replayed page, cursor regression, timeout/rate limit, committed watermark retry, long IDs, exact/near duplicate and forbidden biometric payload

#### Scenario: OA contract matrix
- **WHEN** the OA stub gate runs
- **THEN** it covers approved/draft/rejected/unknown, modification, revocation, supplement, out-of-order version, overlap splitting, long IDs, timezone and committed watermark retry

#### Scenario: No external live claim
- **WHEN** the run has no approved live provider environment
- **THEN** required stub gates may PASS while `DELI_LIVE` and `OA_LIVE` remain explicit informational `NOT_VERIFIED`, never masquerading as required-leaf evidence

### Requirement: Evidence concurrency gate proves cardinality and rollback
`W4-VER-EVIDENCE-CONCURRENCY` SHALL run real MySQL concurrent transactions for source-key idempotency, exact cross-source merge, near-duplicate group creation/resolution, file publish replay, source page retry and reversal.

#### Scenario: Exact duplicate race
- **WHEN** two source transactions commit the same employee/exact instant/direction concurrently
- **THEN** the raw cardinality follows source identity while active effective-event cardinality is exactly one

#### Scenario: Near duplicate and review race
- **WHEN** near arrivals and incompatible review requests race
- **THEN** the pending state has zero active events and only one valid immutable resolution can commit

#### Scenario: Publish rollback
- **WHEN** a deterministic fault is injected after some staged publication work but before commit
- **THEN** committed raw/event/intent/success-audit deltas are zero and an identical request can retry safely

#### Scenario: Watermark rollback
- **WHEN** a source page transaction fails after fetching records
- **THEN** the committed watermark and prior facts remain unchanged

### Requirement: MySQL 8.4.10 is a hard W4 database gate
`W4-VER-MYSQL-8410` SHALL run on an isolated official MySQL whose `SELECT VERSION()` SemVer core is exactly `8.4.10`. H2, MariaDB, MySQL 8.0 or another 8.4 patch MUST NOT substitute.

#### Scenario: Exact isolated server is verified
- **WHEN** the gate starts
- **THEN** it verifies the official 8.4.10 distribution/source checksum, absolute basedir/datadir/socket/log/pid paths and loopback port 13306 before executing tests

#### Scenario: Existing MySQL remains untouched
- **WHEN** an existing local MySQL 8.0 service is present
- **THEN** PID/version/socket/datadir/service state recorded before and after is unchanged and W4 never uses port 3306 or `/tmp/mysql.sock` for the isolated gate

#### Scenario: Fresh and upgrade paths run on 8.4.10
- **WHEN** W4 database verification executes
- **THEN** it runs empty V1→latest, same-DB V7→V8→V9→latest, validate, second-migrate no-op, retained rows, constraints, indexes, transaction rollback and concurrency

#### Scenario: Least-privilege runtime is enforced
- **WHEN** the W4 API reads/writes the isolated test schema
- **THEN** the application account can perform required DML but DDL, GRANT and cross-schema access fail

### Requirement: Authorization gate is action, scope, field and state complete
`W4-VER-AUTH-SCOPE` SHALL exercise every W4 capability independently with authenticated principals, company/location/organization scopes and protected-period/object states, asserting both response and database delta.

#### Scenario: Capability pair matrix
- **WHEN** each action is tested once with its exact capability and once without it
- **THEN** authorized in-scope behavior succeeds and the denied path returns the specified 403/404 with zero business mutation

#### Scenario: System admin does not inherit raw access
- **WHEN** SYSTEM_ADMIN lacks raw-file/raw-row attendance capability
- **THEN** direct URL/API/download attempts are rejected before sensitive selection and no raw data reaches response/log

#### Scenario: Pagination cannot reveal out-of-scope counts
- **WHEN** list data contains mixed companies and locations
- **THEN** SQL-level scope changes rows, total counts and cursors so unauthorized cardinality is not inferable

#### Scenario: Period and stale-state conflicts
- **WHEN** an authorized caller uses a stale If-Match or targets frozen/closed evidence
- **THEN** the server returns 409, writes no success audit and leaves business rows unchanged

### Requirement: Normal browser gate uses real backend and MySQL
`W4-VER-NORMAL-BROWSER` SHALL run production-like normal mode against the real W4 API and isolated/current authorized MySQL with synthetic data at exactly `360x800`, `390x844`, `430x932`, `768x1024`, `1024x768`, `1366x768`, `1440x900` and `1920x1080`.

#### Scenario: Attendance administrator flow
- **WHEN** an HR_ADMIN principal with explicit W4 capabilities opens all W4 routes
- **THEN** source jobs, OA metadata, upload/mapping/precheck/preview/publish/evidence/reversal flow uses real HTTP/DB markers and correct menu/breadcrumb/redirect/status

#### Scenario: Auditor flow
- **WHEN** AUDITOR has read-only W4 capabilities
- **THEN** permitted history/evidence is visible, all mutation affordances are absent, keyboard navigation works and direct mutations are denied

#### Scenario: Technical administrator boundary
- **WHEN** SYSTEM_ADMIN lacks attendance detail capabilities
- **THEN** route guards and APIs do not reveal raw file/row/business-document data even through modified URLs

#### Scenario: Responsive and accessible states
- **WHEN** loading, empty, error, session-expired, 403, 404, stale, validating, failure, frozen, partial, duplicate-review and success states are exercised
- **THEN** no page-level narrow-screen overflow, focus trap/Escape/restore, accessible names, focus-visible, 44px targets and automated accessibility checks pass

### Requirement: Demo and real modes are mechanically isolated
`W4-VER-DEMO-ISOLATION` SHALL prove demo mode performs zero W4 business network requests and zero database writes, while normal/prod mode imports no demo fixture and renders only server-provided data.

#### Scenario: Demo flow is network-free
- **WHEN** all W4 demo routes and actions are exercised
- **THEN** business network request count is zero, MySQL W4 table fingerprints are unchanged and the UI is visibly labeled synthetic demo

#### Scenario: Normal flow contains no demo marker
- **WHEN** the same routes run in normal mode
- **THEN** requests hit the real API, returned DB marker is visible and no demo fixture/ID/label appears

#### Scenario: Build inventories are separate
- **WHEN** production and demo builds are inspected
- **THEN** each has a newer-than-source inventory and production chunks contain no reachable demo business dataset

### Requirement: Payroll and legacy organization-sync discoverability remain zero
`W4-VER-PAYROLL-ORG-SYNC-ZERO` SHALL Unicode-normalize and scan product source, routes, menus, search, notifications, exports, OpenAPI, controllers and built assets for current-user discoverability.

#### Scenario: Product surfaces remain absent
- **WHEN** real-role routes/menus/search/export and direct API probes execute
- **THEN** PAYROLL UI/API data is unavailable and no organization continuous/manual sync control or job exists

#### Scenario: QA/spec markers do not mask product findings
- **WHEN** verification files contain required words for negative assertions
- **THEN** a strict path/marker allowlist distinguishes QA evidence from product surface, and any product-scope hit fails the gate

### Requirement: Security scan covers secrets, active files and sensitive fields
`W4-VER-SECURITY-SCAN` SHALL inspect repository, source/build artifacts, logs, evidence and process-safe summaries for database/provider credentials, tokens, real personal data, full sensitive raw payload, local paths, formulas/macros and unauthorized exact coordinates.

#### Scenario: Secret reference without value is allowed
- **WHEN** configuration names a repository-external environment/secret key
- **THEN** only the variable/reference name may appear; any value, encoded value or URL-embedded password fails

#### Scenario: Synthetic data is distinguishable
- **WHEN** fixtures, screenshots and database rows are inspected
- **THEN** employee/device/source records are explicitly synthetic and contain no real contact, identity, location, attendance or credential data

#### Scenario: Error output is safe
- **WHEN** parser, adapter, authorization and database failures are forced
- **THEN** responses/logs include stable reason and correlation ID but no SQL, stack path, secret, full raw row or forbidden sensitive field

### Requirement: Frozen-source evidence DAG is acyclic and tamper-evident
The W4 source SHALL be frozen before final execution. A fresh non-reused `runId` under `docs/verification/wave4/runs/<runId>/` SHALL bind source hash, DB identity/version and every artifact.

#### Scenario: Source tree hash is bounded and stable
- **WHEN** the run starts and ends
- **THEN** a symlink-safe repo-relative UTF-8 NFC/binary-sorted hash covers backend source/resources, frontend source/public, API, W3/W4 OpenSpec, QA scripts, MySQL scripts and template sources while excluding build/run outputs; both hashes are equal

#### Scenario: Checkbox normalization cannot hide content edits
- **WHEN** W4 `tasks.md` enters the source hash
- **THEN** only `- [ ]`/`- [x]` tokens normalize to one marker; every other byte, including task text, remains hashed

#### Scenario: Evidence order is one-way
- **WHEN** all 24 leaf gates finish
- **THEN** verification creates an artifact registry, non-self-hashing matrix/report, independent review, a final manifest hashing those artifacts plus detached completion metadata, and finally a read-only post-manifest integrity result

#### Scenario: No backwards/self dependency
- **WHEN** the evidence DAG is validated
- **THEN** no leaf depends on review/final/integrity, review does not review itself/final/integrity, manifest-hashed artifacts do not reference the manifest hash, and integrity does not modify the manifest

### Requirement: Independent review and exact final derivation are mandatory
A process distinct from implementation SHALL read raw evidence and challenge exactly the fixed 24 leaf gates. W4 final PASS SHALL derive only from the 24 leaf gates plus `W4-VER-INDEPENDENT-REVIEW` and `W4-VER-EVIDENCE-INTEGRITY`, for exactly 26 required IDs.

#### Scenario: Reviewer finds unsupported evidence
- **WHEN** any leaf claim is inconsistent with command output, test counts, source hash, DB version/identity or artifact digest
- **THEN** independent review fails and has no waiver mechanism

#### Scenario: Exact 26-ID set passes
- **WHEN** and only when all 24 leaves, independent review and post-manifest integrity are current-run PASS
- **THEN** `W4_FINAL_INDEPENDENT_ACCEPTANCE=PASS` may be emitted and detached checkbox completion may be applied without changing normalized source hash

#### Scenario: Informational external status does not alter exact set
- **WHEN** live 得力/OA/storage environments are unavailable
- **THEN** their explicit `NOT_VERIFIED` informational statuses remain in the report but neither replace nor add a required final gate

### Requirement: Successful W4 hands off without implementing W5
Completing W4 SHALL make the unified evidence input ready for W5, but MUST NOT create W5 daily results, exceptions, calculation execution, freeze/close or reopen behavior inside this change.

#### Scenario: W4 final is green
- **WHEN** the exact 26 required IDs pass
- **THEN** tasks may be completed and the next W5 change can consume evidence/recalculation intents without W4 modifying or archiving W3

#### Scenario: A W5 aggregate appears in W4
- **WHEN** changed production files or migrations create formal daily result, exception, calculation version or period-close data
- **THEN** W4 scope verification fails even if source/import tests pass

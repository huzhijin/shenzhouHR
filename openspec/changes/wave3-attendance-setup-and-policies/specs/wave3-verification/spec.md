## ADDED Requirements

### Requirement: W2 retained contract is independently preserved
The gate SHALL verify W2 V4 policy DDL/API shape and W1/W2 retained rows using an independent registry/canonicalizer shared with W3. Expected values SHALL NOT be derived from V7 or current application output.

Evidence contract:

- ID: `W3-VER-W2-RETAINED`
- marker: `W3_W2_RETAINED_SUBSET=PASS`
- artifacts: registry, V4 schema oracle, before/after snapshot, subset diff, W1/W2 full regression log
- failure: any missing/changed before row, W2 schema/API drift, duplicate registry entry or noncanonical value makes W3 RED

#### Scenario: Preserve W2 policy rows and schema
- **GIVEN** V6 W2 schema/rows
- **WHEN** target7 and latest are applied
- **THEN** V4 table columns/indexes/constraints/API schemas are equal and every canonical W2 before line exists unchanged after

### Requirement: W2 Java and H2 public contract uses versioned oracle governance
The v1 oracle is immutable invalidated audit evidence, not the current-source gate. Decision `openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/w2-public-contract-v1-supersession-v2.json` SHALL have version 2 and status/decision `INVALIDATED_SUPERSEDED`, identify authoritative accepted baseline `32a6365bcebfa4cde1c523a64aac4c596b367e1b`, and declare that v1 captured the unaccepted W3 `lockTemplate` chain. The following v1 artifacts SHALL remain byte-identical and SHALL be checked before any current-source positive gate:

- `openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/w2-public-contract-v1.json` SHA256 `72eb8502b0c467f5339792ac22fb48678105222cdef2fe5696136373108b5b50`
- `openspec/changes/wave3-attendance-setup-and-policies/specs/wave3-verification/oracles/extract-w2-public-contract-v1.sh` SHA256 `a319048ecfe5a496c933c278938b2f0119370e722cc62cba46c06ffbd1025b31`
- `scripts/qa/verify_w2_public_contract.py` SHA256 `8c8a9ce499d77dfce13996ac2c396a55416c5ca90bed3844d82dac82afa16538`

The gate SHALL NOT modify v1, change current source to match v1, relax the v1 comparator, or execute v1 current-source positive/self-test as a gate. It SHALL mechanically validate the fixed v1 SHA values and every fixed supersession decision field, then use only review-candidate fixture `w2-public-contract-v2.json`, extractor `extract-w2-public-contract-v2.sh`, and exact comparator `scripts/qa/verify_w2_public_contract_v2.py` for current source.

The v2 expected contract SHALL be manually pre-anchored from the immutable accepted baseline Java/OpenAPI blobs and immutable V4/H2 constraints, never from current actual or the tested extractor. Its fixed provenance SHALL identify those blobs, the v1 supersession decision, candidate status `AWAITING_INDEPENDENT_REVIEW` without claiming review PASS, empty Java/OpenAPI baseline drift, and exactly one baseline adjustment: immutable V4 `ck_policy_version_period` mirrored synonymously as H2 `ck_test_policy_version_period`. `ATTENDANCE_GROUP` already exists in the accepted baseline and remains the sole retained additive compatibility decision on the W2 Java/OpenAPI scope enum; it is not new baseline drift. That decision SHALL NOT authorize `lockTemplate`, `AttendanceLegalEntityId`, `legalEntityId/legal_entity_id`, or attendance-specific generic validation. The expected contract SHALL keep all six `PolicyDtos` helpers `templateDetail`, `templatePage`, `versionDetail`, `versionPage`, `validation`, and `conflicts` package-private and `PolicyVersionDetail` in its original W2 `allOf` form.

Governance SHALL resolve commit `32a6365bcebfa4cde1c523a64aac4c596b367e1b` from `.umadev/checkpoints.git`, verify the fixture-declared OID and SHA256 of all seven accepted baseline blobs, construct the baseline contract directly from those real checkpoint objects, and read-only exact-compare it with expected. Java/OpenAPI/Mapper and all other contract drift SHALL fail closed; H2 permits only the V4-proven period CHECK synonym above. Every Git subprocess SHALL explicitly use `--git-dir=.umadev/checkpoints.git --work-tree=.`.

The v2 exporter SHALL parse only explicitly enumerated W2 Java files, W2 `PolicyMapper.xml`, W2 OpenAPI `/policy-templates` operations/schemas, and H2 V4 `policy_*` plus final V6 `audit_event`; it SHALL exclude attendance/W3 source, sort every structure deterministically, emit no absolute path, and never update expected. Current source SHALL be extracted independently twice and compared byte-for-byte before exact verification. The comparator SHALL exact-diff `expected.contract` and actual. The v2 self-test SHALL first pass unmodified source and then execute exactly 20 temporary source mutations, not JSON, while using the same production extractor/comparator path for all six helper visibilities, `PolicyVersionDetail` composition, forbidden boundary tokens, Mapper closure/SQL and H2 critical contracts. All 20 mutants SHALL fail verification. Whole-file SHA lists are not a valid actual export. Any future fixture/extractor change requires another version, rationale and independent W2 re-review; in-place self-update is RED.

Evidence contract:

- ID: `W3-VER-W2-PUBLIC-ORACLE`
- marker: `W3_W2_PUBLIC_ORACLE=PASS fixtureVersion=2 deterministic=PASS`
- artifacts: v1 frozen SHA integrity report, fixed supersession decision, v2 provenance, golden signature/model/repository/lifecycle/DTO/OpenAPI/Mapper/H2 fixture, extractor hash, normalized actual export, exact diff and source-mutant log
- failure: v1 byte drift, decision drift, a v1 current-source positive invocation, missing/extra/changed public member, record field, route/schema, mapper statement/result, H2 column/index/constraint, v2 expected generated from current output/tested extractor, surviving source mutant or forbidden法人 field makes W3 RED

#### Scenario: Detect a W3 field leaking into W2
- **WHEN** any W2 Java/API/Mapper/H2 surface gains `legalEntityId` or changes its V4 uniqueness/effective contract
- **THEN** the independent exact diff fails before any W3 lifecycle result can count as compatible

#### Scenario: Reject the superseded v1 positive gate
- **GIVEN** v1 has the three fixed SHA256 values and its decision is `INVALIDATED_SUPERSEDED`
- **WHEN** current-source verification runs
- **THEN** v1 integrity is verified without executing its positive/self-test, and only v2 exact positive/self-test determines the current-source oracle result

#### Scenario: Detect W2 helper visibility or composition drift
- **WHEN** any of the six W2 DTO helpers becomes public or `PolicyVersionDetail` is flattened from the accepted `allOf` form
- **THEN** the v2 exact diff fails and the corresponding temporary-source mutant is killed

### Requirement: Retained canonicalization is deterministic
The canonicalizer SHALL use the exact W2 table/PK/column registry declared in design Decision 12, review-owned all-W3 registry SHA256 `aa82a8d941bdb02b0f206b8715691d5f048fb4cfa34e01fc488e4263241803cb`, and independent golden SHA256 `ca11e3d565d6abf5c754a102f6a40713a7fb441e65f1e16a930e8fc69cf9f33a`. Every field SHALL use `tag:UTF8-byte-length:value`; row SHALL use `R:<table-byte-length>:<table><field-count>:<field-frames>`; snapshot line SHALL use `L:<row-byte-length>:<row><hash-byte-length>:<row-sha256>`. Rows SHALL sort by normalized PK unsigned bytes, and final SHA256 SHALL cover the ordered full `L:` frames. UTF-8 NFC, RFC 8785-compatible JSON, UTC microseconds, exact non-exponent decimal, boolean 0/1 and lowercase binary hex SHALL be locale/timezone independent. The independent golden SHALL contain at least two tables/three rows/multiple columns and fix every field frame, row frame, row hash, ordered snapshot line and final hash while covering NULL/empty/delimiter/Unicode/JSON/decimal/time/boolean/binary. `--self-test` SHALL exact-diff the fixture under `LC_ALL=C,TZ=UTC` and a second available locale/non-UTC timezone. V7/current schema/H2/tested registry are actual only and SHALL NOT generate expected.

Evidence contract:

- ID: `W3-VER-CANONICALIZER`
- marker: `W3_RETAINED_CANONICAL_GOLDENS=PASS`
- artifacts: registry version/hash, canonicalizer executable hash, golden input/output file, repeat-run diff
- failure: registry/current-schema mismatch, locale/timezone dependence, golden mismatch or nondeterministic order makes W3 RED

#### Scenario: Distinguish tricky values
- **GIVEN** rows containing NULL, empty string, delimiters, composed/decomposed Unicode, reordered JSON, decimals, microseconds, booleans and binary
- **WHEN** canonicalization runs twice under distinct locale/timezone settings
- **THEN** expected equivalent values match, distinct values differ, and both runs are byte-identical

### Requirement: V7 seeds match an independent fixed oracle
The gate SHALL validate fixed IDs, legal entity, kinds, v1 status, half-open interval, complete canonical JSON and precomputed SHA-256 without reading expected values from V7 SQL.

Fixed canonical snapshot strings and digests:

- `MEAL_DEDUCTION`: `{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"applicableDayTypes":["SPECIAL_WORKDAY","WORKDAY"],"deductionMinutes":30,"enabled":true,"mealWindowEnd":"20:00","mealWindowStart":"18:00","triggerMinutes":240},"policyKind":"MEAL_DEDUCTION","scopeId":"25100000-0000-0000-0000-000000000001","templateId":"25000000-0000-0000-0000-000000000001","versionNumber":1}` → `4279b3f40121f995d09245f3450e1087b4d5757e237ded4f8d4e0343fb210981`
- `LATE_GRACE`: `{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15},"policyKind":"LATE_GRACE","scopeId":"25100000-0000-0000-0000-000000000002","templateId":"25000000-0000-0000-0000-000000000002","versionNumber":1}` → `ea11c63a991838a20b61d6d3e9d0281035263b8529b2fb767fa0b2e631306ce9`
- `MONTHLY_LATE_EXEMPTION`: `{"effectiveFrom":"1970-01-01","effectiveTo":null,"legalEntityId":"30000000-0000-0000-0000-000000000001","parameters":{"enabled":true,"graceMinutes":15,"monthlyUses":1,"resetOnGroupChange":false},"policyKind":"MONTHLY_LATE_EXEMPTION","scopeId":"25100000-0000-0000-0000-000000000003","templateId":"25000000-0000-0000-0000-000000000003","versionNumber":1}` → `b0a533500852c464c7065812fd56519f0c571ebbf453834a8b189388e29f1a51`

All three scoped-version rows SHALL have no `status` column; PUBLISHED SHALL be derived from fixed seed lifecycle facts. Current V7 `maximumLateMinutes/minimumLateMinutes`, any version `status` column and old digest `643f6bff05feddc128e0f0aaf6fe52da47058f7e629a63ca50ce40ce91925310` are explicitly invalid implementation inputs and SHALL NOT be reused.

Evidence contract:

- ID: `W3-VER-SEED-ORACLE`
- marker: `W3_TARGET7_SEED_ORACLE=PASS templates=3 scopes=3 versions=3`
- artifacts: immutable oracle fixture, DB export, digest recomputation log, no-extras query
- failure: use of `MIN(legal_entity_id)`, wrong/missing/extra row, JSON/digest/status/interval mismatch makes W3 RED

#### Scenario: Validate exact target7 delta
- **GIVEN** the fixed legal-entity fixture and V6 database
- **WHEN** V7 is applied
- **THEN** target7 contains exactly the three oracle templates/scopes/PUBLISHED v1 versions and W2 policy tables are unchanged

### Requirement: Migration paths use one orchestrated database identity
The outer gate SHALL own runId and DB identity, capture W2 before and W3 target7 snapshots before any child migrates latest, then allow target7→latest with V8+ support. It SHALL separately run empty→latest and real V6-shape→target7→latest.

Evidence contract:

- ID: `W3-VER-MIGRATION-PATHS`
- markers: `W3_EMPTY_TO_LATEST=PASS`, `W3_V6_TARGET7_LATEST=PASS`, `W3_TARGET7_SNAPSHOT_BEFORE_CHILD_LATEST=PASS`
- artifacts: DB UUID, Flyway info before/target7/latest, ordered child invocation log, retained snapshots, repeat/no-op fingerprints
- failure: distinct DBs, child latest before snapshot, exact-1..7/no-post-V7 assumption, checksum drift or repeat difference makes W3 RED

#### Scenario: Upgrade V6 through target7 to latest
- **GIVEN** one V6 database with fixed tenant and W2 before snapshot
- **WHEN** outer orchestrator migrates target7, captures W3, then migrates latest
- **THEN** W2/W3 retained subsets hold, V8+ is accepted, and repeat migrate is no-op

#### Scenario: Build empty database to latest
- **GIVEN** an isolated empty schema and deterministic fixed-tenant bootstrap at the declared boundary
- **WHEN** V1→latest runs
- **THEN** all migrations/constraints/oracles pass without relying on an already-V7 database

### Requirement: Real MySQL 8.4.10 is isolated and authoritative
MySQL PASS SHALL use official source file `mysql-8.4.10.tar.gz` from `https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10.tar.gz` with SHA256 `d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96`, built on `127.0.0.1:13306` with isolated basedir/datadir/socket/pid/log and absolute binaries. Existing `/usr/local/mysql` 8.0.34 on 3306 SHALL remain unchanged.

Evidence contract:

- ID: `W3-VER-MYSQL8410`
- marker: `W3_MYSQL8410=PASS version=8.4.10 port=13306`
- artifacts: tarball SHA256, build/install/init log, `SELECT VERSION()`/port/socket/datadir, migration/constraint/lock/DML/grant log, existing-instance before/after identity
- failure: parsed SemVer core from `SELECT VERSION()` not exactly equal to `8.4.10` (including every other 8.4.x), port/socket/path collision, existing PID/version/socket/datadir/service change, missing semantic marker or build failure makes W3 RED

#### Scenario: Verify schema, plans, locks and privileges
- **GIVEN** representative nonempty data on isolated MySQL 8.4
- **WHEN** schema/index/FK/CHECK ENFORCED, EXPLAIN chosen key, locking current-read, app DML and denied DDL/GRANT/cross-schema probes run
- **THEN** every W3 table/constraint and concurrency invariant passes in one bound log

#### Scenario: Preserve the existing MySQL instance exactly
- **GIVEN** `/usr/local/mysql` 8.0.34 is serving 3306 and `/tmp/mysql.sock`
- **WHEN** the isolated 8.4.10 build, initialization, verification and shutdown complete
- **THEN** before/after exports of PID, normalized version, socket, datadir and service/launchd identity are byte-identical and no command stopped, reconfigured or wrote into the existing instance

### Requirement: Controller and OpenAPI are bidirectionally closed
The gate SHALL compare exact normalized Controller method/path sets and validate query/path/header/status/request/response/unknown-field/nullability/schema-ref closure for every W3 operation.

Evidence contract:

- ID: `W3-VER-OPENAPI-CLOSURE`
- marker: `W3_CONTROLLER_OPENAPI_CLOSURE=PASS`
- artifacts: parsed Controller mapping export, parsed OpenAPI operation export, set diff, resolved-ref report, legal/illegal instance validation log
- failure: operation on only one side, dangling ref, missing lifecycle `legalEntityId/Idempotency-Key`, unknown field accepted, primitive default bypass or DTO/schema extra field makes W3 RED

#### Scenario: Reject unknown JSON
- **WHEN** an otherwise valid closed request adds one unknown property
- **THEN** runtime returns validation error and schema validation rejects the instance

### Requirement: Normal browser acceptance covers fixed role and viewport matrix
Normal mode SHALL use real backend/database and exactly the canonical design-token viewport targets: `390x844`, `768x1024`, `1024x768`, `1366x768`, `1440x900`, `1920x1080`. It SHALL cover only the V2 real roles `SYSTEM_ADMIN`, `HR_ADMIN` and `AUDITOR` across capability, route, menu, breadcrumb, redirect, HTTP/UI 403/404/error, keyboard and axe behavior; AUDITOR is mandatory and read-only. Demo cannot substitute and invented `HR_MANAGER/HR_OPERATOR/EMPLOYEE` principals cannot satisfy the matrix.

Evidence contract:

- ID: `W3-VER-NORMAL-BROWSER`
- marker: `W3_NORMAL_BROWSER_MATRIX=PASS roles=SYSTEM_ADMIN,HR_ADMIN,AUDITOR viewports=390x844,768x1024,1024x768,1366x768,1440x900,1920x1080 transport=normal db=real`
- artifacts: normal runtime source hash, real DB UUID/Flyway version, authenticated principal/role/capability export, exact role×viewport×route matrix, Playwright trace/screenshots, HTTP request/response log, backend correlation/requestId log, DB read marker, axe/keyboard report
- failure: demo transport, missing role/viewport/state, empty authorized menu, unsafe redirect/breadcrumb, fake 404 or axe critical/serious issue makes W3 RED

#### Scenario: Exercise role-route matrix
- **WHEN** each role opens `/`, `/login`, `/rules*`, four W3 direct routes and unknown routes at all declared viewports
- **THEN** only authorized routes/actions are visible/reachable, AUDITOR has no simulation/impact/manage path, and UI/HTTP/correlation/backend/DB markers match the same matrix row

### Requirement: PAYROLL has zero discoverability
The static deny scan SHALL Unicode-normalize and case-fold only product/user-discoverable prod/demo frontend source/dist, OpenAPI, backend REST Controller/interface routes/DTOs and normal-runtime outputs. Menus, client routes, capabilities, API response status/body/headers and UI text SHALL be scanned without exception. A negative probe request target containing `/payroll` or `/me/payslips` MAY be excluded only at an exact repo-relative/current-run realpath plus exact JSON Pointer naming that request-target scalar; sibling response/status/body fields remain scanned. QA exceptions SHALL be individual entries for the exact evidence-ID declaration, exact marker declaration and exact report marker line, each bound to realpath, line or JSON Pointer, literal SHA256, owner, reason and expiry; only the deny-regex declaration and `W3_PAYROLL_ZERO_DISCOVERABILITY=PASS` marker literal qualify. No directory, file-wide, substring or source-code blanket exclusion is permitted.

Evidence contract:

- ID: `W3-VER-PAYROLL-ZERO`
- marker: `W3_PAYROLL_ZERO_DISCOVERABILITY=PASS`
- artifacts: scan scope manifest, exact realpath+JSON-Pointer request-target exclusions, line-addressed minimum allowlist, normalized scan output, prod/demo hashes, normal runtime probes for all three real roles across menu/routes/capabilities/UI/API responses
- failure: any discoverable feature token/route/menu/capability or unscanned required root makes W3 RED

#### Scenario: Probe forbidden routes for all roles
- **WHEN** every role probes `/payroll`, `/me/payslips` and corresponding APIs in normal mode
- **THEN** no menu/route/capability/feature is discoverable and all probes use the non-feature contract

### Requirement: Final evidence is source-frozen and tamper evident
Only a fresh non-reused run after spec/source/migration freeze MAY be final. Each item SHALL have unique ID, artifact type/realpath, verdict, START/END, mtime, SHA256, runId and source-tree hash. At END the source hash SHALL be recomputed and unchanged. `tasks.md` checkbox tokens SHALL normalize to a constant token for source hashing while every other byte remains bound; detached completion metadata SHALL carry checkbox verdicts until FINAL-GATE.

The executable DAG SHALL be: fixed 20 pre-review leaf gates and raw artifacts → `artifact-registry.json` → mutually non-hashing `acceptance-matrix.json` and `WAVE3-VERIFICATION.md` → `W3-VER-INDEPENDENT-REVIEW` challenging only those 20 gates/raw evidence → detached `final-manifest.json` hashing registry/matrix/report/review/detached metadata in that direction → post-manifest `W3-VER-EVIDENCE-INTEGRITY` mechanical validation → FINAL. No registry/matrix/report/review/metadata node SHALL contain the manifest's full SHA256. Integrity SHALL validate but not rewrite the manifest.

Evidence contract:

- ID: `W3-VER-EVIDENCE-INTEGRITY`
- marker: `W3_EVIDENCE_INTEGRITY=PASS`
- inputs: completed detached final manifest and its five hashed upstream nodes, source START/END records and fixed 20-ID list
- output: `evidence-integrity.json` containing only mechanical checks and marker
- artifacts: source include/exclude/checkbox-normalization protocol, START/END tree records/hashes, detached completion metadata, artifact registry, acceptance matrix, report, independent review, detached final manifest, post-manifest DAG validation
- failure: pre-review count not exactly 20, reused/invalidated run, run-root escape/symlink, duplicate ID, null/NOT_VERIFIED, mtime outside window, non-checkbox source change, checkbox normalization drift, report older than source, graph cycle, backward manifest reference or hash/reference mismatch makes W3 RED

#### Scenario: Reject the historical candidate
- **GIVEN** `w3-20260726-1917`
- **WHEN** any automation reads its report
- **THEN** `INVALIDATED_NON_FINAL` is encountered before historical PASS/conclusion text and the run is never eligible for promotion

#### Scenario: Invalidate on source change
- **WHEN** any included source differs between START and END
- **THEN** the run is invalidated and all runtime/build/browser/MySQL evidence must be regenerated

### Requirement: Frozen backend full tests pass
The frozen-source backend full suite SHALL run without module/test omission.
Evidence contract: ID `W3-VER-BACKEND-FULL`; marker `W3_BACKEND_FULL=PASS`; artifact `backend-full.log` plus machine-readable test summary; failure is any failure/error/skip not explicitly allowlisted, incomplete module, or log predating frozen source.

#### Scenario: Execute the full backend suite
- **GIVEN** the final frozen source hash
- **WHEN** the repository backend full-test command runs
- **THEN** all discovered modules/tests complete successfully and emit the unique marker

### Requirement: Frozen W1 regression passes
The frozen-source W1 regression SHALL execute its complete named suite independently.
Evidence contract: ID `W3-VER-W1-REGRESSION`; marker `W3_W1_REGRESSION=PASS`; artifact `w1-regression.log`; failure is any missing W1 suite, failure/error or historical wrapper.

#### Scenario: Execute W1 regression
- **GIVEN** the final frozen source hash
- **WHEN** the W1 regression command runs
- **THEN** its complete current suite passes and emits only its unique marker

### Requirement: Frozen W2 regression passes
The frozen-source W2 regression SHALL execute its complete named suite independently.
Evidence contract: ID `W3-VER-W2-REGRESSION`; marker `W3_W2_REGRESSION=PASS`; artifact `w2-regression.log`; failure is any missing W2 suite, failure/error or substitution for retained/public-oracle gates.

#### Scenario: Execute W2 regression
- **GIVEN** the final frozen source hash
- **WHEN** the W2 regression command runs
- **THEN** its complete current suite passes without substituting other W2 evidence

### Requirement: Frozen W3 regression passes
The frozen-source W3 regression SHALL execute all W3 semantic suites independently.
Evidence contract: ID `W3-VER-W3-REGRESSION`; marker `W3_W3_REGRESSION=PASS`; artifact `w3-regression.log`; failure is any missing W3 semantic suite, failure/error or narrow-only execution.

#### Scenario: Execute W3 regression
- **GIVEN** the final frozen source hash
- **WHEN** the W3 regression command runs
- **THEN** all W3 semantic suites pass and a narrow selection cannot emit the marker

### Requirement: Frozen frontend typecheck passes
The frozen-source frontend typecheck SHALL complete with zero ignored diagnostics.
Evidence contract: ID `W3-VER-FRONTEND-TYPECHECK`; marker `W3_FRONTEND_TYPECHECK=PASS`; artifact `frontend-typecheck.log`; failure is nonzero exit, ignored diagnostics or log predating source.

#### Scenario: Execute frontend typecheck
- **GIVEN** the final frozen frontend source
- **WHEN** the project typecheck command runs
- **THEN** it exits zero with no suppressed diagnostic and emits the unique marker

### Requirement: Frozen frontend lint passes
The frozen-source frontend lint SHALL complete without warning, error or governance evasion.
Evidence contract: ID `W3-VER-FRONTEND-LINT`; marker `W3_FRONTEND_LINT=PASS`; artifact `frontend-lint.log`; failure is warning/error outside an explicit zero-entry allowlist or mechanical governance evasion.

#### Scenario: Execute frontend lint
- **GIVEN** the final frozen frontend source
- **WHEN** the project lint command and semantic governance checks run
- **THEN** they exit zero without mechanical evasion and emit the unique marker

### Requirement: Frozen frontend full tests pass
The frozen-source frontend full test suite SHALL run without demo-only selection.
Evidence contract: ID `W3-VER-FRONTEND-TESTS`; marker `W3_FRONTEND_TESTS=PASS`; artifact `frontend-tests.log` and summary; failure is missing suite, failure/error or demo-only test selection.

#### Scenario: Execute frontend full tests
- **GIVEN** the final frozen frontend source
- **WHEN** the unfiltered project test command runs
- **THEN** all discovered tests pass and emit the unique marker

### Requirement: Frozen production build passes
The production build SHALL be independent, reproducible and newer than every relevant frozen source.
Evidence contract: ID `W3-VER-PROD-BUILD`; marker `W3_PROD_BUILD=PASS`; artifact `prod-build.log` plus dist inventory/hash; failure is nonzero exit, dist not newer than every relevant source, or mixed demo output.

#### Scenario: Build production output
- **GIVEN** a clean production output directory and frozen source
- **WHEN** the production build runs
- **THEN** its inventory is production-only, hash-bound and newer than all relevant source

### Requirement: Frozen demo build passes
The demo build SHALL be independent, reproducible and newer than every relevant frozen source.
Evidence contract: ID `W3-VER-DEMO-BUILD`; marker `W3_DEMO_BUILD=PASS`; artifact `demo-build.log` plus separate dist inventory/hash; failure is nonzero exit, stale/mixed output or overwrite of prod inventory.

#### Scenario: Build demo output
- **GIVEN** a separate clean demo output directory and frozen source
- **WHEN** the demo build runs
- **THEN** its inventory is demo-only, hash-bound, newer than source and does not alter production output

### Requirement: Demo isolation passes
Demo runtime SHALL short-circuit all business transport before network or database access.
Evidence contract: ID `W3-VER-DEMO-ISOLATION`; marker `W3_DEMO_ISOLATION=PASS businessRequests=0 mysqlConnections=0`; artifacts `demo-network.har`, runtime log and DB connection delta; failure is any business API/WebSocket request, MySQL activity or fallback to normal transport.

#### Scenario: Exercise demo isolation
- **GIVEN** the independent demo build with network and DB observation enabled
- **WHEN** every W3 demo route and action is exercised
- **THEN** business request and MySQL connection deltas remain exactly zero

### Requirement: Current W2 smoke passes
The current frozen run SHALL exercise W2 smoke behavior against the same bound database identity.
Evidence contract: ID `W3-VER-W2-CURRENT-SMOKE`; marker `W3_W2_CURRENT_SMOKE=PASS`; artifact `w2-current-smoke.log` bound to current run/source/DB; failure is historical evidence, different DB/source or any W2 response/data mismatch.

#### Scenario: Execute current W2 smoke
- **GIVEN** the current runId, frozen source hash and orchestrated DB UUID
- **WHEN** W2 smoke probes execute
- **THEN** expected W2 responses/data pass and every marker binds the same run/source/DB

### Requirement: Independent semantic review passes
The reviewer SHALL be read-only, SHALL not be the implementation process, SHALL receive the frozen spec/source hash and raw registered artifacts rather than implementation conclusions, and SHALL challenge exactly the fixed 20 pre-review IDs listed by FINAL. It SHALL NOT challenge itself, final manifest, `W3-VER-EVIDENCE-INTEGRITY` or FINAL and SHALL have no authority to waive a hard gate.

Evidence contract: ID `W3-VER-INDEPENDENT-REVIEW`; marker `W3_INDEPENDENT_REVIEW=PASS reviewerMode=read-only sourceHash=<hash>`; artifact `independent-review.md` plus reviewer identity/method declaration; failure is self-review by the mutating process, missing child challenge, reliance on summary-only evidence or any unresolved finding.

#### Scenario: Challenge the frozen evidence independently
- **GIVEN** raw registered artifacts and the frozen spec/source hash
- **WHEN** a distinct read-only reviewer challenges exactly the 20 pre-review children and their raw evidence
- **THEN** PASS is emitted only if no finding or hard-gate waiver remains

### Requirement: Final frozen-tree gates all pass
After the last source change the system SHALL first complete this exact 20-ID pre-review dependency set:

`W3-VER-W2-RETAINED`, `W3-VER-W2-PUBLIC-ORACLE`, `W3-VER-CANONICALIZER`, `W3-VER-SEED-ORACLE`, `W3-VER-MIGRATION-PATHS`, `W3-VER-MYSQL8410`, `W3-VER-OPENAPI-CLOSURE`, `W3-VER-BACKEND-FULL`, `W3-VER-W1-REGRESSION`, `W3-VER-W2-REGRESSION`, `W3-VER-W3-REGRESSION`, `W3-VER-FRONTEND-TYPECHECK`, `W3-VER-FRONTEND-LINT`, `W3-VER-FRONTEND-TESTS`, `W3-VER-PROD-BUILD`, `W3-VER-DEMO-BUILD`, `W3-VER-DEMO-ISOLATION`, `W3-VER-W2-CURRENT-SMOKE`, `W3-VER-NORMAL-BROWSER`, `W3-VER-PAYROLL-ZERO`.

Final acceptance SHALL then derive from those exact 20 plus `W3-VER-INDEPENDENT-REVIEW` and post-manifest `W3-VER-EVIDENCE-INTEGRITY`, for an exact total of 22 unique child IDs. No other, duplicate, self-referential or implicit child is allowed.

Evidence contract:

- ID: `W3-VER-FINAL-GATE`
- marker: `W3_FINAL_INDEPENDENT_ACCEPTANCE=PASS`
- artifacts: exact prerequisite evidence ID set, detached completion metadata and a derived-only parent verdict
- failure: any child absent/non-PASS or any later source mtime keeps W3 RED

#### Scenario: Derive completion only from atomic children
- **WHEN** every atomic evidence requirement is uniquely PASS and source remains frozen
- **THEN** parent tasks 6.3–6.6 may be derived PASS; otherwise they remain unchecked

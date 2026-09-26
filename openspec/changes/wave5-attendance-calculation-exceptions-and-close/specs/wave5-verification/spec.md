## ADDED Requirements

### Requirement: W4 FINAL is a hard gate for real W5 wiring
Real W4 evidence, recalculation-intent, period-provider, watermark/blocker, persistence and API wiring SHALL NOT be marked complete until a synchronized W4 FINAL manifest, source commit and contract set are valid. Pure domain fakes MAY support isolated W5 development but MUST NOT be labeled integration evidence.

#### Scenario: W4 is not final
- **WHEN** the current baseline contains only an in-progress W4 OpenSpec or no valid W4 FINAL manifest
- **THEN** all real W4 adapter/MyBatis/migration/HTTP integration tasks remain incomplete with exact `BLOCKED_ON_W4_FINAL` dependencies

#### Scenario: Synthetic fake passes
- **WHEN** pure W5 domain tests pass using synthetic evidence/configuration/intent/period fakes
- **THEN** the report says domain PASS and W4 integration `NOT_VERIFIED`, never FINAL

#### Scenario: W4 final later changes source contract
- **WHEN** synchronized W4 FINAL signatures or cardinalities differ from W5 assumptions
- **THEN** W5 adds/updates anti-corruption adapters and tests rather than modifying W4 history or weakening its gates

### Requirement: RED-first tests freeze W5 contracts before implementation
W5 SHALL record a staged RED-first commit whose focused tests compile against interface skeletons and fail for the intended missing domain behavior, followed by implementation commits that make independently implementable tests pass.

#### Scenario: RED phase
- **WHEN** interface skeletons and golden tests are first executed
- **THEN** failures identify unimplemented calculation/difference/freeze behavior rather than environment, credential or unrelated W1-W4 failures

#### Scenario: Domain green phase
- **WHEN** pure domain implementation is complete
- **THEN** the same focused tests pass without deleting assertions, relaxing expected values or replacing them with mocks of the behavior under test

### Requirement: Synthetic golden cases cover V1.9 boundaries
The W5 domain suite SHALL use obviously synthetic identities/data and SHALL cover mixed segments, evidence precedence/conflict, punch single-use, late-grace boundaries, missing-punch deadlines, cross-midnight, overtime deadlines/meal deductions, deterministic replay, narrow differences, freeze and reopen.

#### Scenario: Required boundary inventory
- **WHEN** golden-case identifiers are enumerated
- **THEN** cases exist for `late=0/1/15/16`, seventh-day submit/ninth-day approval, overdue one-segment absence, 02:00 previous-day attribution, 47:59/48:01 overtime, same-priority conflict, order-independent digest, unrelated version stability, closed rejection and reopened new version

#### Scenario: Real personal data scan
- **WHEN** fixtures, logs and evidence are scanned
- **THEN** only explicitly synthetic employee/source/document/location identifiers exist and no real contact, identity, attendance, location or credential value appears

### Requirement: Migration registry is deferred and collision-safe
No W5 Flyway file or migration number SHALL be created before W4 FINAL establishes the published latest registry. After synchronization, W5 SHALL use only new forward versions and MUST preserve every prior filename, byte and checksum.

#### Scenario: Current isolated implementation
- **WHEN** W5 domain work is inspected before W4 FINAL
- **THEN** there is no new migration file and no task claims V9/V10 or another historical planning number

#### Scenario: W4 FINAL is synchronized
- **WHEN** implementation resumes after validating the actual latest registry
- **THEN** W5 assigns the next available forward versions, updates its design/tasks and proves no collision

#### Scenario: Existing version is occupied
- **WHEN** a planned number is already published
- **THEN** W5 shifts forward and never edits/replaces the occupied migration

### Requirement: Public API is published only with bidirectional closure
The main OpenAPI document, Controller operations, DTOs, capabilities, errors and frontend client SHALL be added together only after W4 FINAL dependency closure. Method + normalized path and request/response/error enums MUST be bidirectionally exact.

#### Scenario: Interface skeleton phase
- **WHEN** only provider-neutral Java use-case ports exist
- **THEN** the main OpenAPI contains no unwired W5 product path and no false endpoint claim is made

#### Scenario: Real API phase
- **WHEN** W4 FINAL adapters, persistence and controllers are implemented
- **THEN** automated closure proves every W5 OpenAPI operation has one implementation and every implementation has one operation

#### Scenario: Failure enum drift
- **WHEN** OpenAPI, backend or frontend contains an extra/missing W5 state or error code
- **THEN** contract verification fails

### Requirement: W1 through W4 retained gates cannot regress
W5 verification SHALL preserve W1-W3 completed public/source/semantic gates and the eventual exact W4 FINAL retained evidence. It MUST NOT weaken authorization, scope, security, demo isolation, PAYROLL zero, migration checksum or independent-review requirements.

#### Scenario: Retained source changes unexpectedly
- **WHEN** W5 changes a retained W1-W4 product contract or source outside an authorized adapter extension
- **THEN** retained verification fails and W5 cannot claim completion

#### Scenario: Payroll discoverability appears
- **WHEN** W5 source, routes, menus, OpenAPI, builds or normal-mode probes expose PAYROLL UI/API data
- **THEN** W5 verification fails even if attendance domain tests pass

#### Scenario: Demo evidence substitutes for real mode
- **WHEN** a normal-mode HTTP/MySQL gate uses demo fixtures or performs zero real business requests
- **THEN** the gate fails and cannot be relabeled PASS

### Requirement: MySQL 8.4 verification is mandatory after persistence exists
After W5 physical persistence is authorized, a frozen-source gate SHALL run on the project-required official MySQL 8.4.10 identity and SHALL cover fresh/upgrade migration, validate/no-op, retained rows/checksums, constraints/indexes, transaction rollback, deterministic locks, idempotency, close/reopen concurrency and least privilege. H2 MUST NOT substitute.

#### Scenario: Domain-only phase
- **WHEN** no W5 persistence/migration exists because W4 FINAL is pending
- **THEN** MySQL W5 status is explicitly `NOT_RUN/NOT_VERIFIED`, not PASS or FAIL by fake

#### Scenario: Exact database gate
- **WHEN** W5 persistence enters verification
- **THEN** the gate records exact server identity/version and isolated paths before running migration and concurrency tests

#### Scenario: H2-only result
- **WHEN** tests pass only on H2
- **THEN** W5 database acceptance remains incomplete

### Requirement: Authorization and privacy gates cover every W5 action
Real W5 verification SHALL test capability-present/absent pairs, company/location/group/organization scope, field redaction, protected period and stale token for every read/mutation, asserting both response and database delta.

#### Scenario: Technical admin lacks attendance detail
- **WHEN** SYSTEM_ADMIN lacks explicit W5 read/adjust/close capabilities
- **THEN** direct route/API attempts disclose no attendance evidence and write no business data

#### Scenario: Denied mutation
- **WHEN** capability, scope, state or token denies an adjustment/recalculation/close/reopen action
- **THEN** the expected 403/404/409 occurs with zero business/success-audit delta

#### Scenario: Explanation field policy
- **WHEN** a caller can read a result but lacks raw-row/file/location detail
- **THEN** calculation reasons remain useful while protected raw fields are absent and counts do not leak scope

### Requirement: W5 source and evidence remain secure and synthetic
Verification SHALL scan source, build artifacts, logs, test output and evidence for secrets, credentials, real personal/attendance/location data, local paths and unsafe raw payloads. All errors SHALL use bounded stable reasons and correlation IDs.

#### Scenario: Secret reference only
- **WHEN** future configuration names an external secret
- **THEN** only the reference/environment variable name may appear; any value or URL-embedded credential fails

#### Scenario: Safe forced error
- **WHEN** calculation, adapter, authorization or persistence errors are forced
- **THEN** output contains stable reason/correlation ID but no stack path, SQL, secret, complete raw row or forbidden location

### Requirement: Full W5 FINAL requires frozen-source independent evidence
A full W5 acceptance run SHALL bind one non-reused run ID to source hash, exact upstream FINAL manifests, database identity and every required leaf artifact. Independent review and post-manifest integrity SHALL be mandatory; domain-only work cannot satisfy FINAL.

#### Scenario: Domain subset is green
- **WHEN** OpenSpec and pure domain tests pass but W4 adapters, persistence, API, MySQL or authorization gates are pending
- **THEN** status remains partial and no `W5_FINAL` PASS marker is emitted

#### Scenario: Evidence is stale or reused
- **WHEN** an artifact comes from another source hash, database, run ID or contains empty/`NOT_VERIFIED` required evidence
- **THEN** independent review fails with no waiver

#### Scenario: All real gates eventually pass
- **WHEN** the frozen required leaf set, independent review and post-manifest integrity all pass for one run
- **THEN** and only then may W5 FINAL be declared and downstream W6/W7/W8 consume its closed-snapshot contracts

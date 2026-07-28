## 1. Baseline, scope and dependency gates

- [x] 1.1 Verify this worktree is exactly based on `codex/sync-w3-w9-20260728` / `0c09fc375b1d2973915234e50a9aac10900446ca`, is isolated from the original worktree, and contains no unrelated changes.
- [x] 1.2 Cross-check PRD V1.9 sections 8/9/12, the W6 roadmap, AC-LEAVE/ANNUAL/TIME/REPORT and the requirement-test traceability matrix; create proposal, design and six capability specs.
- [x] 1.3 Record that W2 currently exposes prior service as `amountDays/totalDays` while PRD annual tiers require published months; keep domain input in months and block persistence wiring until a versioned conversion rule is approved.
- [ ] 1.4 Synchronize the exact W5 FINAL commit/manifest and verify accepted period status, close snapshot, reopen version, recalculation intent, retained test and highest migration contracts.
- [ ] 1.5 Re-run W1～W5 retained baselines after W5 synchronization and update this change's design/spec/tasks only for verified upstream shapes.

## 2. Independent annual-leave and ledger domain TDD

- [x] 2.1 Add RED tests named for qualification/tier separation, anniversary eve/day, 10-year eve/day, 20-year day, prior service, configurable qualification and invalid future/negative inputs.
- [x] 2.2 Add RED tests named for grant validity/expiry, expiry-before-grant ordering, mid-cycle no top-up, rehire anchor/history isolation and February-29 `FEBRUARY_28`/`MARCH_1` policies.
- [x] 2.3 Implement complete-calendar-month and anniversary primitives without days/30 or days/365 conversion.
- [x] 2.4 Implement separate qualification, cumulative-service tier and entitlement composition with default `[12,120)`, `[120,240)`, `[240,+)` month tiers.
- [x] 2.5 Implement anniversary cycle and grant-plan values using `[anniversary,nextAnniversary)`, displayed expiry date and deterministic expiry-before-grant order.
- [x] 2.6 Add RED tests for signed entry categories, exact decimal replay, provenance, unique ID/sequence, missing/cross-account/duplicate reversal, reversing use/grant, expiry and negative-prefix rejection.
- [x] 2.7 Implement immutable time-account ledger entries, direction/precision validation, deterministic sequence replay, per-type totals and negative-balance protection.
- [x] 2.8 Implement reversal creation and validation as a single exact inverse append that retains the original entry.
- [x] 2.9 Add architecture and data-safety tests proving W6 domain has no Spring/MyBatis/REST/W5 dependency and fixtures use only synthetic identities.
- [ ] 2.10 Pass the focused W6 tests and the full backend regression suite on Java 21.

## 3. W2/W5 application ports after dependency acceptance

- [ ] 3.1 Freeze an `EmploymentPeriodSnapshotPort` contract for unique point-in-time employee/employment resolution with `[startDate,endExclusive)` and version identity.
- [ ] 3.2 Obtain and encode the approved W2 `totalDays` → published prior-service-months conversion policy; implement `PriorServiceResolutionPort` with raw unit/value and conversion version, failing closed when unresolved.
- [ ] 3.3 Freeze fail-closed `PeriodProtectionPort`, `CloseSnapshotReferencePort` and `LeaveRecalculationIntentPort` contracts from actual W5 FINAL rather than inferred tables.
- [ ] 3.4 Add retained contract tests for termination day/day-after, rehire history, W2 replay provenance, W5 OPEN/REOPENED/FROZEN/CLOSED/UNKNOWN and close/reopen version changes.
- [ ] 3.5 Implement annual anniversary orchestration with durable idempotency, W2 snapshots, W5 period second-check and atomic expiry-before-grant append intent.

## 4. Forward-only persistence and migration

- [ ] 4.1 Read the synchronized highest Flyway version and select the next available W6 migration number without modifying or renaming any existing migration/checksum.
- [ ] 4.2 Add an independent W6 schema registry and RED migration tests for empty→latest, synchronized-upstream→W6, validate, existing checksum immutability and second migrate no-op.
- [ ] 4.3 Add leave type/revision/policy snapshot and annual qualification/tier/grant logical objects with legal-entity, scope, policy/employment and immutable snapshot constraints.
- [ ] 4.4 Add time account and append-only ledger objects with exact decimal hours, unique sequence/business idempotency, reversal FK/cardinality, source/version/request provenance and bounded query indexes.
- [ ] 4.5 Add opening-import batch/file/row/issue/precheck/publication/state/error-report logical objects and exact idempotency/digest constraints.
- [ ] 4.6 Add versioned balance/expiry/report projection and access-audit objects that reference the W5 close/reopen contract without duplicating W5 authority.
- [ ] 4.7 Implement MyBatis rows/mappers/repositories and guards proving no business UPDATE/DELETE path exists for grant, ledger, reversal, publication or audit facts.
- [ ] 4.8 Pass exact MySQL 8.4 migration, FK/CHECK/unique/index, decimal, append-only, concurrency, rollback and query-plan tests with synthetic `shenzhou_hr_test` data.

## 5. Leave policy, annual and ledger application/API

- [ ] 5.1 Add RED OpenAPI/HTTP tests for typed leave policies, annual previews, requests/cancellation/reconciliation, time accounts, ledger and replay with closed DTO/enums/errors.
- [ ] 5.2 Implement versioned leave policy draft/validate/preview/publish/deactivate lifecycle with default `WORKDAY`, scope/effective-period conflict checks and no executable fields.
- [ ] 5.3 Implement workday/calendar-day/hour duration resolution, actual half-day segment hours and policy snapshot provenance using authoritative calendars.
- [ ] 5.4 Implement balance-controlled request/use append with account lock/version, inside-transaction replay and insufficient-balance/split behavior.
- [ ] 5.5 Implement cancellation/early-return reconciliation that appends only unconsumed return/reversal entries, retains request/use history and emits narrow recalculation intents.
- [ ] 5.6 Implement anniversary preview/processing, termination expiry, mid-cycle policy/tier behavior and rehire isolation against W2/W5 adapters.
- [ ] 5.7 Add exact `LEAVE:*` capabilities, organization/group/self scope, CSRF, If-Match, idempotency, reason, field minimization and success/failure/access audits.
- [ ] 5.8 Close OpenAPI, Controller, Java and database operations/enums in both directions and keep all W6 endpoints `PLANNED_NON_CALLABLE` until dependency and runtime gates pass.

## 6. Opening balance and reporting

- [ ] 6.1 Produce and contract-test the versioned eight-field synthetic `.xlsx` opening-balance template with machine-readable version/digest.
- [ ] 6.2 Implement safe upload, immutable staging and precheck for template, employee/effective employment, account, unit/precision/date, duplicate, period and before/proposed/after balance.
- [ ] 6.3 Implement strict idempotent publication that appends exactly one `OPENING` per successful row and conflicts on changed digest.
- [ ] 6.4 Implement batch reverse as exact per-entry reversal with W5 protection and no deletion of files, rows, publication or consumed history.
- [ ] 6.5 Implement version-bound balance, source totals, expiry risk, ledger detail and zero-difference summary/detail reconciliation queries.
- [ ] 6.6 Implement scoped/minimized synchronous `<=50,000` and asynchronous larger export with independent create/download reauthorization and audit.
- [ ] 6.7 Freeze the stable W6 reporting/self query contract that W7 consumes without client-side balance calculation.

## 7. W6 React routes and runtime states

- [ ] 7.1 Generate typed W6 clients and register authorized management routes for leave policy, annual preview, accounts/ledger, opening imports and reports without adding W7 self-service or PAYROLL.
- [ ] 7.2 Implement leave-policy and annual-preview screens with published-version provenance and anniversary/rehire/leap-day explanations.
- [ ] 7.3 Implement account list, balance/expiry, ledger drill-down, replay/reconciliation and high-risk reversal confirmation against real normal-mode APIs.
- [ ] 7.4 Implement opening-import upload/precheck/diff/errors/confirm/publish/reverse flow driven only by server state/digests.
- [ ] 7.5 Cover loading, empty, error, session-expired, 403, 404, stale, insufficient balance, frozen, processing, validation failed, publish failed, partial and success states.
- [ ] 7.6 Pass keyboard/focus/axe/semantic-token/Tabler checks and responsive browser acceptance at required mobile/tablet/desktop breakpoints.
- [ ] 7.7 Prove demo mode short-circuits before API, normal mode uses real backend/MySQL, and production bundles contain no demo employee/account/ledger dataset.

## 8. Final regression, evidence and handoff

- [ ] 8.1 Pass all AC-LEAVE-01～04, AC-ANNUAL-01～09, AC-TIME-01～04 and W6-owned AC-REPORT-01 domain/API/DB/security/browser scenarios.
- [ ] 8.2 Pass full backend and frontend typecheck/lint/test/build plus separately recorded W1～W5 retained suites.
- [ ] 8.3 Verify MySQL app-DML/denied-DDL/cross-schema, scope negatives, no unauthorized mutation, account concurrency and deterministic replay under the real runtime account.
- [ ] 8.4 Verify normal/demo network and database isolation, PAYROLL and organization-sync zero discoverability, and secret/real-person/sensitive-leave-data scans.
- [ ] 8.5 Build the W6 source/artifact/test matrix and independent evidence-integrity result from current source rather than historical `target/dist` output.
- [ ] 8.6 Report all commit SHAs, exact commands/counts/results, migration and MySQL state, OpenSpec progress, W5/W7 handoff contracts and any `NOT_VERIFIED/EXTERNAL` condition; do not push or merge.

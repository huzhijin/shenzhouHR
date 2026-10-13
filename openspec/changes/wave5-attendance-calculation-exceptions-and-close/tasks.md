## 1. Domain Boundary And RED Baseline

- [x] 1.1 Create the framework-free `attendance.calculation` package structure and provider-neutral use-case/store/upstream port skeletons without importing W4 DTOs, Mappers or Controllers.
- [x] 1.2 Add immutable input, segment, evidence, adjustment, result, explanation, exception, difference and period records with closed enums and constructor invariants.
- [x] 1.3 Add synthetic RED tests for canonical input order, segment calculation/explanation and exception behavior, then record the intended failures.
- [x] 1.4 Add synthetic RED tests for version difference and frozen/closed/stale period protection, then record the intended failures.

## 2. Deterministic Calculation And Explanation

- [x] 2.1 Implement canonical calculation-input ordering/digest using explicit UTF-8 fields, UTC instants, enum names and stable ID ordering.
- [x] 2.2 Implement half-open atomic interval splitting and fixed-priority evidence resolution with fail-closed same-level conflict.
- [x] 2.3 Implement deterministic punch candidate selection, consumed-event single-use and ambiguous-match decisions.
- [x] 2.4 Implement work-segment result items and integer-minute `S/W_in/E/O/L/A/actualWork` summaries that recompute from items.
- [x] 2.5 Implement late/early raw-versus-chargeable minutes and employee-month grace boundary decisions.
- [x] 2.6 Implement pending/timely/overdue missing-punch decisions without inventing endpoints or widening default absence beyond the affected segment.
- [x] 2.7 Implement cross-midnight ownership/consumption and overtime authorization/deadline/meal-deduction decisions.
- [x] 2.8 Implement a complete result → item → rule/evidence/adjustment/exception/request explanation graph and stable semantic result digest.
- [x] 2.9 Make the deterministic calculation/explanation golden tests pass without weakening their RED assertions.

## 3. Exception And Correction Domain

- [x] 3.1 Implement stable exception fingerprints, controlled type/severity/blocking metadata and append-only case observations/transitions.
- [x] 3.2 Implement reconciliation of previous cases with recalculated findings for observe, resolve and reopen behavior.
- [x] 3.3 Implement immutable authorized adjustment/reversal facts and validate interval, reason, approval, scope-decision, period-token and optimistic-version inputs.
- [x] 3.4 Prove correction/adjustment behavior never edits source evidence, prior results, prior exceptions or close snapshots.
- [x] 3.5 Make exception/correction synthetic tests pass and retain minimized audit/reference fields.

## 4. Recalculation Version And Difference Domain

- [x] 4.1 Implement exact deduplicated recalculation targets and controlled batch lifecycle/idempotency request model.
- [x] 4.2 Implement immutable calculation-version selection that reuses identical input+algorithm versions and appends changed versions.
- [x] 4.3 Implement stable semantic item keys and added/removed/modified result, metric, rule, evidence and exception differences with causal categories.
- [x] 4.4 Prove unrelated employee/date current versions and record fingerprints remain unchanged for narrow recalculation.
- [x] 4.5 Implement provider-neutral intent inbox claim/acknowledge/retry interface skeleton without claiming W4 integration.
- [x] 4.6 Make version/difference/idempotency synthetic tests pass.

## 5. Period Close And Freeze Domain

- [x] 5.1 Implement versioned period states/transitions and provider response records compatible with `OPEN/FROZEN/CLOSED/REOPENED/UNKNOWN` protection semantics.
- [x] 5.2 Implement reusable `FrozenPeriodProtection` for ordinary mutations, stale tokens and immutable close snapshots.
- [x] 5.3 Implement close-precheck input/report/token models for calculation coverage, blockers, running work, freshness, provider versions and control totals.
- [x] 5.4 Implement immutable close snapshot/member/control-total models and domain close token revalidation/idempotency rules.
- [x] 5.5 Implement authorized reopen domain behavior that preserves old snapshots and issues a new period version/token.
- [x] 5.6 Implement post-close difference references and same-snapshot report reconciliation helpers.
- [x] 5.7 Make frozen/closed/stale/reopen/immutability synthetic tests pass.

## 6. Isolated-Tree Verification And Handoff

- [x] 6.1 Run OpenSpec strict validation and confirm proposal/design/five specs/tasks are apply-ready.
- [x] 6.2 Run focused W5 domain tests and backend full tests; classify any unrelated retained failure without suppressing it.
- [x] 6.3 Verify no W5 Flyway file/migration number, main OpenAPI product path, Controller/Mapper/W4 concrete import, real data or PAYROLL surface was added.
- [x] 6.4 Document exact provider-neutral W4 handoff interfaces and keep real adapter status `NOT_VERIFIED`.
- [x] 6.5 Review staged changes for W1-W4 gate weakening, secret/path/personal-data leakage and scope drift before the implementation commit.

## 7. Synchronize W4 FINAL Contracts — BLOCKED_ON_W4_FINAL

- [ ] 7.1 Synchronize and validate the exact W4 FINAL commit, manifest, source hash, independent review, integrity result and retained W1-W3 evidence. `BLOCKED_ON_W4_FINAL`
- [ ] 7.2 Record the actual W4 latest migration registry and update W5 logical migration mapping without changing published W1-W4 files. `BLOCKED_ON_W4_FINAL`
- [ ] 7.3 Freeze the W4 evidence knowledge-time/event/slice/conflict IDs, cardinalities, digests and query contract consumed by `AttendanceEvidenceSnapshotPort`. `BLOCKED_ON_W4_FINAL`
- [ ] 7.4 Freeze W4 recalculation-intent identity, candidate dates, lease/version, idempotency and claim/ack/retry transaction contract. `BLOCKED_ON_W4_FINAL`
- [ ] 7.5 Freeze the W4-facing period protection provider signature/token and close-precheck watermark/running-job/quarantine/conflict blocker contract. `BLOCKED_ON_W4_FINAL`
- [ ] 7.6 Add anti-corruption adapters and contract tests for the finalized W2/W3/W4 ports without modifying upstream history. `BLOCKED_ON_W4_FINAL`

## 8. Persistence And Migration — BLOCKED_ON_W4_FINAL

- [ ] 8.1 Assign only the next free forward Flyway versions after the synchronized W4 registry and add calculation/result/explanation/exception/recalculation logical tables. `BLOCKED_ON_W4_FINAL`
- [ ] 8.2 Add the following forward migration for period/transition/close-snapshot/member/result-difference logical tables. `BLOCKED_ON_W4_FINAL`
- [ ] 8.3 Implement MyBatis rows/mappers/stores with append-only history, named uniqueness/indexes, string IDs, UTC timestamps and optimistic versions. `BLOCKED_ON_W4_FINAL`
- [ ] 8.4 Implement transactional narrow intent consumption, calculation/current-version update and exception reconciliation with deterministic locks. `BLOCKED_ON_W4_FINAL`
- [ ] 8.5 Implement transactional close/reopen, locked dependency-token revalidation, snapshot immutability and post-close differences. `BLOCKED_ON_W4_FINAL`
- [ ] 8.6 Add migration-registry, retained checksum, constraint/index, rollback, idempotency and concurrency RED/green tests. `BLOCKED_ON_W4_FINAL`

## 9. Real API Authorization Audit And UI — BLOCKED_ON_W4_FINAL

- [ ] 9.1 Freeze W5 capabilities, DTOs, errors and all attendance day/calculation/explanation/exception/recalculation/period operation families in the main OpenAPI. `BLOCKED_ON_W4_FINAL`
- [ ] 9.2 Implement Controllers/use cases with CSRF, idempotency, reason, strong If-Match, bounded pagination and correlation IDs. `BLOCKED_ON_W4_FINAL`
- [ ] 9.3 Wire W1 capability/scope/field authorization and minimized audit for every read, adjustment, recalculation, close, reopen and snapshot action. `BLOCKED_ON_W4_FINAL`
- [ ] 9.4 Prove OpenAPI/Controller/frontend enum and method+normalized-path bidirectional closure. `BLOCKED_ON_W4_FINAL`
- [ ] 9.5 Implement real React attendance detail, explanation, exception and period-close states with no demo fixture in normal/prod. `BLOCKED_ON_W4_FINAL`
- [ ] 9.6 Verify responsive/loading/empty/error/session/403/404/409/frozen/stale/processing/partial/success states and demo isolation. `BLOCKED_ON_W4_FINAL`

## 10. Real Database Runtime And FINAL — BLOCKED_ON_W4_FINAL

- [ ] 10.1 Run empty baseline→latest, W4 FINAL latest→W5→latest, validate, second-migrate no-op and retained-row tests on exact isolated MySQL 8.4.10. `BLOCKED_ON_W4_FINAL`
- [ ] 10.2 Run least-privilege, transaction rollback, idempotency and competing calculation/close/reopen tests against MySQL. `BLOCKED_ON_W4_FINAL`
- [ ] 10.3 Run normal-browser real HTTP/MySQL authorization/scope/field/frozen flows and prove demo/real isolation plus PAYROLL zero. `BLOCKED_ON_W4_FINAL`
- [ ] 10.4 Run representative full-month recalculation/close performance, recovery and old-snapshot report reconciliation. `BLOCKED_ON_W4_FINAL`
- [ ] 10.5 Produce one frozen-source W5 run with fixed leaf evidence, independent review, final manifest and post-manifest integrity; only then emit W5 FINAL. `BLOCKED_ON_W4_FINAL`

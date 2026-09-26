## Why

WAVE-4 is intended to provide immutable, normalized attendance evidence and narrow recalculation intents, but the system still lacks a deterministic consumer that turns those inputs into segment-level attendance results, actionable exceptions, reproducible recalculation differences and immutable monthly close snapshots. WAVE-5 establishes that calculation and close boundary now while keeping all real W4 persistence/API wiring explicitly blocked until W4 FINAL is synchronized.

## What Changes

- Add deterministic business-day and cross-midnight calculation over scheduled work segments, rule snapshots, point/interval evidence and approved adjustments.
- Add a complete explanation chain from daily result items through rule hits and selected, rejected or conflicting evidence back to immutable source/request identifiers.
- Add exception lifecycles for missing punches, evidence conflicts, configuration gaps and other blocking/non-blocking findings; corrections append evidence or authorized adjustment decisions and never rewrite source facts.
- Add narrow, idempotent recalculation batches that preserve old calculation versions, emit reproducible version differences and leave unrelated employee/date versions unchanged.
- Add attendance-period precheck, close and reopen state-machine contracts, immutable close snapshots, post-close differences and ordinary-mutation freeze protection.
- Add W5 OpenAPI/error/capability skeletons and RED-first domain/contract tests without claiming wired endpoints, MyBatis persistence or migrations.
- Reserve logical W5 persistence objects without assigning migration numbers; migration names are selected only after W4 FINAL establishes the published registry.
- Treat W4 evidence, interval slices, recalculation intents and period-provider tokens as unresolved upstream ports. No W4 table, Mapper, DTO or endpoint is assumed to exist in this change until W4 FINAL synchronization.
- Preserve all W1-W4 retained gates, PAYROLL zero-discoverability and synthetic-data-only verification.

## Capabilities

### New Capabilities

- `attendance-segment-calculation`: Deterministic work-segment calculation, cross-midnight attribution, evidence precedence, rule hits, result items and complete explanation traces.
- `attendance-exception-corrections`: Exception classification/lifecycle, timely corrections, authorized adjustments, conflict handling and append-only audit semantics.
- `attendance-recalculation-differences`: Narrow idempotent recalculation batches, immutable calculation versions, stable input digests and explainable old/new differences.
- `attendance-period-close`: Period precheck, close/reopen state machine, immutable close snapshots, freeze guards and post-close difference behavior.
- `wave5-verification`: RED-first golden cases and retained W1-W4, contract, authorization, migration-registry, MySQL, performance, security and independent-evidence gates.

### Modified Capabilities

None. WAVE-4 capabilities remain upstream contracts and are not modified or completed by this change.

## Impact

- Backend: a new attendance-calculation domain/application boundary, upstream evidence/intent/configuration ports, version/difference/close models, freeze guards and REST contract skeletons.
- API: planned `/api/v1` attendance-day, calculation, exception, recalculation and period-close operation families with stable errors and string identifiers; concrete controller publication waits for W4 FINAL dependency closure.
- Persistence: logical W5 objects for scheduled segments, calculation versions/results/rule hits/items, exceptions/adjustments/recalculation batches, periods/transitions/snapshots/differences; no migration number or physical schema is created before W4 FINAL.
- Verification: synthetic golden cases cover cross-midnight work, evidence splitting/precedence/conflict, missing-punch deadlines, deterministic replay, narrow differences, frozen rejection and reopen versioning.
- Upstream: real wiring requires W4 FINAL contracts for evidence/event/slice reads, calculation-intent claiming/acknowledgement and authoritative period-token coordination.

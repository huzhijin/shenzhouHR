# W5 ↔ W4 FINAL handoff contract

Status: `BLOCKED_ON_W4_FINAL`

W5 baseline: `0c09fc375b1d2973915234e50a9aac10900446ca`

W5 change: `wave5-attendance-calculation-exceptions-and-close`

This file records the exact provider-neutral seams implemented by the isolated W5 domain work. It does **not** claim that W4 tables, Mappers, DTOs, Controllers or transactions exist. Real adapter status remains `NOT_VERIFIED` until a valid W4 FINAL commit and evidence manifest are synchronized.

## 1. Evidence snapshot read

W5 seam:

```java
AttendanceEvidenceSnapshotPort.EvidenceSnapshot read(
    String legalEntityId,
    String employeeId,
    LocalDate businessDate,
    Instant knowledgeCutoff,
    String expectedProviderToken)
```

W4 FINAL must freeze:

- snapshot/reference identity and immutable digest;
- exact point-event fields: stable string ID, instant, normalized direction, current lifecycle at `knowledgeCutoff`, evidence/raw references permitted to W5;
- exact interval-slice fields: stable string ID, `[start,end)`, evidence type/priority, selected/rejected/conflict cardinality and immutable source-version references;
- how reversal/supersession is resolved at a knowledge timestamp;
- authoritative candidate business dates and cross-midnight attribution;
- legal-entity/employee/employment/configuration consistency failure codes;
- provider token meaning, comparison strength and repeatable-read behavior;
- bounded read/cardinality behavior and integrity error when zero/multiple authoritative snapshots exist.

W5 will add an anti-corruption adapter. It will not import W4 persistence rows into the domain.

## 2. Recalculation intent inbox

W5 seam:

```java
List<ClaimedIntent> claim(int limit, String workerId, Instant now)
void acknowledge(
    String intentId,
    String leaseToken,
    String recalculationBatchId,
    String calculationVersionId)
void retry(
    String intentId,
    String leaseToken,
    String stableReasonCode,
    Instant retryNotBefore)
```

`ClaimedIntent` currently requires:

- intent ID and optimistic/stream version;
- lease token;
- legal entity and employee string IDs;
- deduplicated authoritative candidate business dates;
- evidence/source trigger references;
- provider token.

W4 FINAL must freeze:

- intent uniqueness/idempotency key and append-only lifecycle;
- claim ordering, batch limit, lease duration, expiry and competing-worker behavior;
- whether claim is destructive, stateful or derived;
- acknowledge/retry optimistic token and transaction boundaries;
- rollback behavior: a failed W5 calculation must not lose or acknowledge an intent;
- duplicate/replayed intent behavior and old/new OA interval union semantics;
- protected-period intent behavior before and after reopen.

## 3. Period state provider exposed to W4

W5 provider record:

```text
periodId
legalEntityId
[startDate,endExclusive)
version
state = OPEN | FROZEN_FOR_CLOSE | CLOSED | REOPENED | UNKNOWN
strong token
optional immutable closeSnapshotReference
```

W4 FINAL must freeze its planned `AttendancePeriodProtectionPort` consumer signature and confirm:

- mapping between W5 `FROZEN_FOR_CLOSE` and W4 `FROZEN`;
- which business dates/scopes map to one period;
- token comparison and stale response/error;
- entry check plus transaction-lock second check;
- raw staging allowed during protected state versus effective publication/reversal/intent forbidden;
- reopened version/token invalidates every old precheck/publication confirmation;
- `UNKNOWN` always fails closed.

## 4. Close dependency snapshot

W5 seam:

```java
CloseDependencySnapshot read(
    String legalEntityId,
    LocalDate startDate,
    LocalDate endExclusive,
    Instant knowledgeCutoff)
```

W4 FINAL must provide a stable digest and exact counts/status for:

- committed source watermarks and freshness threshold evaluation;
- running source jobs/import validations/publications/reversals;
- unresolved quarantine, employee/configuration match and `EVIDENCE_CONFLICT` blockers;
- pending duplicate-review groups that yield zero active event;
- protected/post-close late raw facts;
- adapter/integrity unavailability.

The digest must change whenever a close-relevant dependency changes and must be re-read while the period is locked.

## 5. Configuration snapshot

W5 seam:

```java
ConfigurationSnapshot read(
    String employeeId,
    LocalDate businessDate,
    Instant knowledgeCutoff)
```

The W2/W3 adapter must return exact employment period, attendance group/revision, shift/work segments, calendar day, policies, business timezone and one immutable configuration digest. W5 does not duplicate W3 effective-date, DST, attendance-group or cross-midnight resolution.

## 6. Persistence handoff

W5 currently freezes store semantics only:

- calculation versions, results, rule hits, items and explanations are append-only;
- exception observations/transitions and adjustment/reversal facts are append-only;
- current calculation selection uses optimistic comparison;
- period transitions, close snapshots/members and result differences are immutable;
- close and reopen revalidate dependency/period tokens while locked.

After W4 FINAL, implementation must first read the actual immutable Flyway registry and select only the next free forward versions. Planning labels such as V9/V10 are not reserved and no published migration may be renamed, overwritten or have its checksum changed.

## 7. Mechanical unblock criteria

Real W4 wiring may start only when all are available together:

1. exact W4 FINAL commit;
2. valid W4 FINAL manifest, source hash, independent review and post-manifest integrity;
3. retained W1-W3 evidence accepted by that run;
4. actual latest migration registry;
5. finalized Java/OpenAPI/evidence/intent/period contracts above;
6. no upstream source drift after the FINAL run.

Until then:

- synthetic port fakes prove only W5 domain behavior;
- W4 adapter/MyBatis/migration/OpenAPI/Controller/UI/MySQL tasks remain unchecked;
- W5 does not emit a FINAL marker.

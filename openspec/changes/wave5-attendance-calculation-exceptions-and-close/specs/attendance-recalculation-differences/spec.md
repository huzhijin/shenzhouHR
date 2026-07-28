## ADDED Requirements

### Requirement: Recalculation targets are exact and provider-derived
Recalculation SHALL operate only on a deduplicated stable set of `(legalEntityId, employeeId, businessDate)` targets derived from authoritative evidence/configuration/adjustment changes or an explicitly authorized scope expansion. Client-supplied dates MUST NOT override W3/W4 authoritative candidate dates.

#### Scenario: One evidence intent
- **WHEN** W4 supplies one employee and one authoritative candidate business date
- **THEN** the W5 batch contains only that target and does not broaden to another employee or whole month

#### Scenario: Cross-midnight candidate set
- **WHEN** an upstream intent authoritatively includes current and previous business-date candidates
- **THEN** both exact targets are deduplicated and retained without natural-date truncation

#### Scenario: OA modification range
- **WHEN** an approved interval changes
- **THEN** targets cover the authoritative old/new union and no date outside that union

#### Scenario: Upstream identity is ambiguous
- **WHEN** intent identity, employee match, business date or provider version is missing/ambiguous
- **THEN** the target is rejected/retryable with a stable reason and no guessed calculation

### Requirement: Recalculation batch lifecycle is durable and bounded
A batch SHALL have controlled states `REQUESTED`, `RUNNING`, `SUCCEEDED`, `PARTIALLY_FAILED`, `FAILED` and `CANCELLED`, bounded target count, stable ordering, counts, timestamps, reason, actor and request/correlation IDs.

#### Scenario: All targets succeed
- **WHEN** every target commits or deterministically reuses an identical successful version
- **THEN** the batch becomes `SUCCEEDED` with exact calculated/reused counts

#### Scenario: One target fails independently
- **WHEN** the authorized batch mode allows independent targets and one target has an integrity failure
- **THEN** committed targets remain traceable, the failed target keeps a stable reason and the batch is `PARTIALLY_FAILED`

#### Scenario: Batch-wide precondition fails
- **WHEN** period token, authorization or input-provider availability fails before target execution
- **THEN** no target current version changes and the batch records `FAILED` or remains retryable according to the stable failure class

### Requirement: Recalculation mutations are durably idempotent
Batch creation SHALL key idempotency by actor, operation, resource scope and idempotency key with a canonical request digest. Only a fully committed success MAY be replayed as success.

#### Scenario: Same key and digest after success
- **WHEN** an identical batch request is repeated after committed success
- **THEN** the exact batch identity/status/result is replayed without extra versions, differences or success audits

#### Scenario: Same key with different targets or reason
- **WHEN** the same idempotency key is reused with a different canonical request digest
- **THEN** the request returns conflict and the original batch is unchanged

#### Scenario: Failed transaction is retried
- **WHEN** the first attempt rolls back before success commit
- **THEN** an identical request can execute again and a prior `REQUESTED/RUNNING` marker is not misreported as success

### Requirement: Calculation versions and current selection are immutable
Every successful semantic result SHALL have an immutable calculation version referencing its exact input/result digests. Recalculation MUST append or deterministically reuse a version and MUST NOT overwrite prior results, rule hits, items, explanations or exception observations.

#### Scenario: Identical input replay
- **WHEN** the current successful version already has the same input digest and algorithm version
- **THEN** the target records `NO_CHANGE_REPLAY` or reuses the version without creating a semantically duplicate result

#### Scenario: Changed evidence
- **WHEN** a later evidence snapshot changes the input digest
- **THEN** a new calculation version is appended and the old version remains byte-identical/queryable

#### Scenario: Current pointer is stale
- **WHEN** another transaction advances the employee/date current version first
- **THEN** optimistic comparison rejects or safely recomputes the loser; it never silently overwrites the winner

### Requirement: Unrelated calculation versions never change
An exact-scope recalculation MUST leave every employee/date outside its target set unchanged, including current version, result rows, exception transitions and close-difference rows.

#### Scenario: Single employee/day batch
- **WHEN** the repository contains another synthetic employee and another date
- **THEN** fingerprints for all unrelated records remain byte-identical after the batch

#### Scenario: Failed target
- **WHEN** one target fails calculation
- **THEN** failure does not advance unrelated or failed-target current versions

### Requirement: Version differences are stable and explanatory
When old and new successful versions differ, the system SHALL create a stable difference containing old/new IDs and digests, metric deltas, added/removed/modified result items, rule hits, evidence decisions, exceptions and causative trigger references.

#### Scenario: Evidence resolves a missing punch
- **WHEN** a correction changes a segment from pending missing punch to confirmed work
- **THEN** the difference identifies the removed exception, changed result items/minutes and causal evidence/request IDs

#### Scenario: Rule snapshot changes
- **WHEN** evidence is identical but the effective missing-punch or meal rule snapshot changes
- **THEN** the difference category includes `RULE_CHANGED` and identifies the relevant rule hit delta

#### Scenario: Collection order changes only
- **WHEN** semantically identical old/new inputs differ only in collection order
- **THEN** canonical matching produces no semantic difference

#### Scenario: Stable keys not storage positions
- **WHEN** persisted row IDs or list ordering differ across equivalent versions
- **THEN** difference matching uses employee/date/segment/slice/item semantic keys and remains identical

### Requirement: Protected period behavior distinguishes ordinary and post-close paths
Ordinary recalculation MUST reject `FROZEN/CLOSED/UNKNOWN` and stale tokens. After authorized reopen, recalculation SHALL use the new period version and SHALL preserve the old close-linked calculation set.

#### Scenario: Closed ordinary recalculation
- **WHEN** a batch targets a closed period without reopen
- **THEN** it returns conflict with zero calculation-version and difference delta

#### Scenario: Reopened recalculation
- **WHEN** reopen creates a new period version and an authorized batch uses its current token
- **THEN** new calculation versions/differences reference the reopened version and never replace old close snapshot members

### Requirement: W4 intent consumption is lossless and deferred until final contract
The W5 boundary SHALL define claim, acknowledge and retry semantics for durable upstream recalculation intents, but a real adapter MUST NOT be declared complete until the synchronized W4 FINAL identity, lease/version, candidate-date and transaction contracts are verified.

#### Scenario: Synthetic inbox drives domain test
- **WHEN** a fake inbox supplies a deterministic synthetic intent
- **THEN** pure W5 scope/idempotency behavior can pass without claiming W4 database/API integration

#### Scenario: W4 FINAL is absent
- **WHEN** implementation status is reported before a valid W4 FINAL manifest and contract are synchronized
- **THEN** real intent claim/ack wiring remains explicitly incomplete and no fake evidence is labeled as integration PASS

#### Scenario: Acknowledgement cannot lose failed work
- **WHEN** future real calculation fails or the transaction rolls back
- **THEN** the upstream intent is not acknowledged as successfully consumed and remains retryable under the FINAL adapter contract

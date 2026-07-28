## ADDED Requirements

### Requirement: AC-CALC-01 all sources share one evidence pipeline
得力、Excel and OA SHALL create source-specific raw facts but MUST use the same normalized-record, employee-match, effective-event, evidence-link and recalculation-intent services and tables.

#### Scenario: Three sources converge
- **WHEN** a 得力 punch, an Excel punch and an OA approved document are ingested for synthetic employees
- **THEN** each has its own raw fact while all three are queryable through the same normalized/match/effective/evidence contract

#### Scenario: Vendor path cannot write a private result table
- **WHEN** schema, Mapper and service dependencies are enumerated
- **THEN** no vendor adapter bypasses the shared evidence application port or creates vendor-specific day/calculation results

### Requirement: AC-CALC-02 raw facts and corrections are append-only
Raw facts MUST be immutable. Source modifications, late arrivals, revocations, supplement records, duplicate resolutions and corrections SHALL append facts, versions or lifecycle events and MUST NOT update/delete the source history they correct.

#### Scenario: Reversal preserves prior fact
- **WHEN** an approved OA document or published punch event is reversed
- **THEN** the original raw fact and evidence links remain queryable and a new reversal fact/lifecycle event retracts current effectiveness

#### Scenario: Database role cannot mutate raw history
- **WHEN** the application runtime account or Mapper attempts UPDATE or DELETE on a raw fact
- **THEN** the operation is denied by contract/privilege and the row digest remains unchanged

#### Scenario: Knowledge-time replay is reproducible
- **WHEN** evidence is resolved at a knowledge timestamp before and after a later correction
- **THEN** the earlier replay excludes the later lifecycle fact while the later replay includes it, without changing historical rows

### Requirement: Normalization preserves source time and canonical time
Every temporal fact SHALL preserve its original time text/timezone and SHALL store a canonical instant or half-open interval. Business-date candidates MUST be resolved by the authoritative W3 timezone/shift resolver, not by truncating UTC or client-supplied dates.

#### Scenario: Cross-midnight punch includes prior-day candidate
- **WHEN** W3 resolves a 02:00 punch inside the preceding shift's cross-midnight cutoff
- **THEN** the evidence retains the exact instant and produces a recalculation candidate for the previous business date without reusing the fact as a guessed next-day entry

#### Scenario: Missing or invalid timezone fails closed
- **WHEN** no source timezone, mapping timezone or authoritative location timezone can be resolved
- **THEN** the record is quarantined with a timezone issue and produces no active event or recalculation intent

#### Scenario: Interval is half-open
- **WHEN** one OA document ends exactly when another begins
- **THEN** `[start,end)` semantics produce adjacent non-overlapping evidence slices

### Requirement: Employee and employment matching is point-in-time and deterministic
The evidence pipeline SHALL prefer a unique employee number with an effective employment period, otherwise an effective confirmed device/external-person binding. Names and department text MUST NOT determine identity.

#### Scenario: Employee number wins over display name
- **WHEN** an input contains a valid unique employee number and a mismatching display name
- **THEN** the employee number and fact-time employment period determine the match while the name mismatch is a review issue, not an alternate employee guess

#### Scenario: Rehire resolves the correct employment period
- **WHEN** an employee has two non-overlapping employment periods and a fact falls in the second
- **THEN** the match references the second period and does not rewrite or reuse the first period

#### Scenario: Termination gap is unmatched
- **WHEN** a fact falls after one period's `end_exclusive` and before a later rehire
- **THEN** matching fails with an explicit employment-gap issue and no effective event is created

#### Scenario: Ambiguity creates no effective event
- **WHEN** multiple employees or employment periods satisfy a source identity at the same instant
- **THEN** the record is quarantined and zero active events are produced

### Requirement: Source-record and stable-fingerprint idempotency are exact
The system SHALL use source instance + business key + source version when a source record ID exists, and a canonical SHA-256 fingerprint when it does not. These keys MUST block exact replay without removing legitimate different punches.

#### Scenario: Same source record version is replayed
- **WHEN** the same source instance, record ID and version arrive more than once
- **THEN** only one raw fact exists for that version and each retry returns the same ingestion outcome

#### Scenario: Fingerprint blocks a byte-equivalent record
- **WHEN** a record without source ID repeats with the same legal entity, location, device, person/employee, normalized instant and direction
- **THEN** it is recognized as an exact duplicate and creates no additional raw punch

#### Scenario: Legitimate second punch remains
- **WHEN** two records differ by instant or normalized direction outside exact identity
- **THEN** the stable fingerprint differs and the pipeline does not silently discard the second record

### Requirement: Cross-source exact duplicates create one active event
Different-source raw facts with the same source device record or the same employee, exact instant and normalized direction SHALL all remain traceable but SHALL resolve to exactly one active effective event.

#### Scenario: Excel arrives before Deli
- **WHEN** an Excel raw punch is published and the same 得力 device fact later arrives
- **THEN** both raw facts remain, one active event links both, and no second active event is created

#### Scenario: Deli arrives before Excel
- **WHEN** the same case arrives in reverse order
- **THEN** the final raw/effective/link cardinalities and canonical event digest are identical

#### Scenario: Concurrent exact arrivals have one winner
- **WHEN** two transactions ingest exact cross-source duplicates concurrently
- **THEN** deterministic locks and unique keys commit two raw source facts but exactly one active event linked to both

### Requirement: Near-duplicate review has zero, one or N active events
Different-source punches for the same employee and normalized direction with an absolute time difference of 1 through the versioned default 60 seconds SHALL form one pending review group. Before resolution the group MUST have zero active events.

#### Scenario: Boundaries 1 and 60 seconds are pending
- **WHEN** candidate pairs differ by exactly 1 second or exactly 60 seconds
- **THEN** each pair enters `PENDING_DUPLICATE_REVIEW`, retains all raw facts and has zero active events

#### Scenario: Boundaries 0 and 61 seconds are not near-pending
- **WHEN** candidates differ by 0 seconds or 61 seconds
- **THEN** 0 seconds follows exact-duplicate behavior and 61 seconds remains distinct, subject to ordinary punch rules

#### Scenario: Existing event is retracted when a near candidate arrives
- **WHEN** one source previously produced an active event and another source later creates a near-duplicate group
- **THEN** the group creation transaction appends a retraction lifecycle fact so the pending group has zero active events

#### Scenario: SAME_FACT creates exactly one
- **WHEN** an authorized reviewer resolves a pending group as `SAME_FACT`
- **THEN** the system appends a resolution and exactly one active event linked to every raw member

#### Scenario: DISTINCT_FACTS creates one per member
- **WHEN** an authorized reviewer resolves a pending group as `DISTINCT_FACTS`
- **THEN** the system appends a resolution and one active event per raw member

#### Scenario: Review cannot be overwritten
- **WHEN** a resolved group is submitted again with another conclusion
- **THEN** the server returns 409 and preserves the original resolution and active-event cardinality

### Requirement: AC-CALC-04 evidence intervals are split and prioritized deterministically
The system SHALL split overlapping OA intervals at every boundary into non-overlapping `[start,end)` slices and SHALL attach ordered evidence candidates using the fixed priority chain. It MUST NOT use last-write-wins.

#### Scenario: Different boundaries create atomic slices
- **WHEN** approved documents cover 09:00–12:00 and 10:00–11:00
- **THEN** the stored slice boundaries are 09:00–10:00, 10:00–11:00 and 11:00–12:00 with exact candidate references

#### Scenario: Same-level mutually exclusive documents conflict
- **WHEN** approved leave and approved outing overlap the same employee/time slice
- **THEN** that slice is `EVIDENCE_CONFLICT`, has no selected winner and remains a blocking input for W5

#### Scenario: Revocation applies only to referenced interval
- **WHEN** an approved revocation shortens or cancels a prior document
- **THEN** only slices covered by the referenced document/revocation change current effectiveness, while unrelated evidence remains active

#### Scenario: Reingestion order does not change slices
- **WHEN** the same source versions are ingested in different arrival orders
- **THEN** the sorted boundaries, candidate order, conflict status and slice digest are byte-identical

### Requirement: Effective event lifecycle is append-only and cardinality checked
The current status of an effective event SHALL derive from append-only `ACTIVATED`, `RETRACTED` and `SUPERSEDED` lifecycle facts. Resolvers MUST assert cardinality instead of using arbitrary `LIMIT 1`.

#### Scenario: One current event resolves
- **WHEN** an event has one valid activation and no later retraction at the knowledge time
- **THEN** the resolver returns exactly that active event and its complete evidence links

#### Scenario: Ambiguous lifecycle fails closed
- **WHEN** corrupted or racing lifecycle facts would produce more than one current successor
- **THEN** the resolver raises a stable integrity error and returns no guessed event

### Requirement: Evidence trace is complete and permission-aware
An authorized evidence query SHALL return the source label, raw-reference metadata, normalized values, match decision, event lifecycle, duplicate/reversal relationship, request IDs and immutable digests needed for explanation, while redacting fields not allowed by raw-file/raw-row/location permissions.

#### Scenario: Authorized row-level trace
- **WHEN** a caller has evidence read and raw-row permission within scope
- **THEN** the event trace contains every linked raw fact and transformation ID without exposing credentials or local object paths

#### Scenario: Raw permission is absent
- **WHEN** a caller can read an event summary but lacks raw-row/file or location permission
- **THEN** the server returns the permitted explanation with those fields absent or redacted and records no false raw-access audit

#### Scenario: Cross-scope event is hidden
- **WHEN** a caller requests an event outside legal-entity/location/organization scope
- **THEN** the server returns the configured non-disclosing 404 path before selecting sensitive rows

### Requirement: Period protection blocks effective publication but not raw staging
The pipeline SHALL allow upload/precheck or online raw quarantine during `FROZEN/CLOSED`, but MUST NOT activate new published import events or request recalculation for a protected period. `UNKNOWN` MUST fail closed.

#### Scenario: Online late fact in a closed period
- **WHEN** a valid online raw fact arrives for a closed period
- **THEN** the raw fact is retained, current closed results are untouched, the record is marked post-close/protected and no active mutation or recalculation intent is committed

#### Scenario: Reopened period uses a new provider token
- **WHEN** a period is reopened and the same evidence is reprocessed
- **THEN** a new knowledge/version path may activate evidence and create a new intent while preserving the earlier protected record

### Requirement: Recalculation intents are exact and do not implement calculation
Every committed activation, retraction, duplicate resolution or OA modification SHALL append idempotent recalculation intents for only affected employees and candidate business dates. W4 MUST NOT write scheduled segments, daily results, exceptions, calculation versions or close snapshots.

#### Scenario: Punch intent is narrow
- **WHEN** a punch is activated for one employee and W3 resolves one or two cross-midnight candidate dates
- **THEN** intents exist only for that employee and those dates and no unrelated employee/date receives an intent

#### Scenario: OA modification uses old/new union
- **WHEN** an OA document interval changes
- **THEN** intents cover the union of old and new affected employee/date ranges

#### Scenario: Static W5 boundary is clean
- **WHEN** W4 changed files, migrations, Mappers and API are scanned
- **THEN** they do not create or mutate W5 daily result, exception, recalculation execution, freeze or close aggregates

### Requirement: Evidence mutations use durable idempotency and deterministic locks
Publication, reversal and duplicate resolution SHALL require CSRF, `Idempotency-Key`, `X-Change-Reason`, strong `If-Match` where applicable, and deterministic lock order with a locked second check.

#### Scenario: Same-key committed resolution replays
- **WHEN** the identical reviewer request is repeated after committed success
- **THEN** status, headers and body replay exactly with no additional resolution, event or success audit

#### Scenario: Different-key race preserves invariant
- **WHEN** two keys concurrently attempt incompatible resolutions or exact-event creation
- **THEN** only one invariant-compatible result commits and the loser writes no success audit

#### Scenario: Failure rolls back all business effects
- **WHEN** event/link/intent persistence fails in a transaction
- **THEN** no partial event, lifecycle, link, intent or success audit remains; a separate failure audit may remain

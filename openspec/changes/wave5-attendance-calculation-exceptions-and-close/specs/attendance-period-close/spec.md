## ADDED Requirements

### Requirement: Attendance period lifecycle is versioned and append-only
An attendance period SHALL derive state from append-only transitions using `OPEN`, `FROZEN_FOR_CLOSE`, `CLOSED`, `REOPENED` and fail-closed `UNKNOWN` semantics. State or version history MUST NOT be overwritten.

#### Scenario: First close lifecycle
- **WHEN** an open period starts close processing and successfully closes
- **THEN** transitions record `OPEN → FROZEN_FOR_CLOSE → CLOSED` with actor, reason, request and monotonically newer token/version

#### Scenario: Close attempt fails
- **WHEN** close preconditions fail after temporary freezing
- **THEN** no close snapshot is created and a controlled transition releases/returns the period to the valid open state without hiding the failed attempt

#### Scenario: Invalid transition
- **WHEN** an ordinary command tries to move directly from `CLOSED` to `OPEN` or close `UNKNOWN`
- **THEN** the transition is rejected with zero period/snapshot mutation

### Requirement: Close precheck validates all blocking dependencies
A close precheck SHALL bind one immutable precheck token to period scope/version and exact W2/W3/W4/configuration/evidence/adjustment/result dependency digests. It MUST block unresolved hard errors, running imports/recalculations, stale watermarks, coverage gaps and version conflicts.

#### Scenario: Blocking exception exists
- **WHEN** any in-scope unresolved case has `blockingClose=true`
- **THEN** precheck fails with the exact case count/reason category and close cannot start

#### Scenario: Source or calculation is running
- **WHEN** W4 source/import work, W5 recalculation or adjustment publication is running for the scope
- **THEN** precheck is blocked and identifies the dependency class without exposing unauthorized details

#### Scenario: Calculation coverage is incomplete
- **WHEN** an employee/date lacks exactly one current successful calculation version
- **THEN** precheck fails rather than silently excluding or duplicating the member

#### Scenario: Watermark or provider token is stale
- **WHEN** W4 freshness is below the controlled threshold or a dependency token changes
- **THEN** the precheck token is invalid and a new precheck is required

#### Scenario: Control totals differ
- **WHEN** summary totals cannot be reproduced from the same calculation-version result items
- **THEN** precheck fails with a result-integrity blocker

### Requirement: Close atomically creates an immutable complete snapshot
Close SHALL lock the period, revalidate the precheck token and atomically append a snapshot containing period/scope version, organization/employment/configuration/evidence/adjustment/result references and digests, exact employee/date calculation members, control totals, actor/reason/request and closed time.

#### Scenario: Valid close
- **WHEN** every bound dependency is unchanged and precheck is green
- **THEN** exactly one immutable close snapshot and close transition commit together

#### Scenario: Dependency changes after precheck
- **WHEN** evidence, rule, adjustment, calculation or period token changes before close commit
- **THEN** close returns stale conflict and commits no snapshot or success transition

#### Scenario: Same idempotent close is retried
- **WHEN** the exact close command repeats after committed success
- **THEN** the existing snapshot/response is replayed and no second snapshot is created

#### Scenario: Same key has a different request
- **WHEN** the close idempotency key is reused with a changed reason/scope/token
- **THEN** the command conflicts and preserves the original snapshot

### Requirement: Closed snapshots are immutable and ordinary mutations are blocked
After close, ordinary source publication, effective correction, adjustment, reversal, recalculation and current-result mutation for that period MUST return conflict. Snapshot content and member calculations MUST remain byte-identical.

#### Scenario: Late evidence arrives
- **WHEN** W4 stages late raw evidence for a closed business date
- **THEN** the close snapshot and closed calculation versions remain unchanged and only protected/post-close metadata allowed by the upstream contract may be appended

#### Scenario: Ordinary recalculation is requested
- **WHEN** a caller requests normal recalculation against `CLOSED`
- **THEN** the frozen-period guard rejects it before version mutation

#### Scenario: Direct snapshot mutation is attempted
- **WHEN** application code/store is asked to replace/delete a snapshot member or digest
- **THEN** the operation is unavailable or rejected as `ATTENDANCE_CLOSE_SNAPSHOT_IMMUTABLE`

### Requirement: Reopen requires independent authority and creates a new period version
Reopen SHALL require independent `ATTENDANCE_PERIOD:REOPEN` capability, in-scope authorization, reason, strong expected version, idempotency and an authoritative approval fact. It MUST append a transition and create a new period version/token without editing the old snapshot.

#### Scenario: Authorized reopen
- **WHEN** an authorized supervisor reopens a closed period with current version and reason
- **THEN** state becomes `REOPENED`, the new version/token is returned and the old snapshot remains queryable

#### Scenario: Close capability only
- **WHEN** a caller can close but lacks reopen capability
- **THEN** reopen is denied before any transition or success audit

#### Scenario: Stale reopen
- **WHEN** the expected closed version is stale or another reopen already committed
- **THEN** the request conflicts and no extra period version is created

#### Scenario: Reopened mutation uses old token
- **WHEN** a source/adjustment/recalculation command still presents the prior closed token
- **THEN** it is rejected; only the new reopened token can authorize new-version work

### Requirement: Post-close changes generate differences, not history rewrites
New evidence, rules or adjustments processed after reopen SHALL create later calculation versions and post-close differences against the selected prior close snapshot. Old snapshot, calculations and audit history MUST remain unchanged.

#### Scenario: Reopen and correct one employee
- **WHEN** a correction after reopen changes one employee/date
- **THEN** a post-close difference identifies only that member and the prior snapshot member remains byte-identical

#### Scenario: No semantic change after reopen
- **WHEN** recalculation produces a semantic result identical to the prior close member
- **THEN** the post-close comparison records no metric/item delta while preserving version/provenance as required

### Requirement: Close snapshot reporting reconciles to its own detail
Any report or downstream consumer of a close snapshot SHALL use only that snapshot's exact calculation member set. Snapshot aggregate totals MUST equal recomputation from those members with a difference of zero.

#### Scenario: Current results later change
- **WHEN** a reopened period has newer current calculations
- **THEN** a report for the old snapshot continues to use old snapshot members, not current pointers

#### Scenario: Aggregate reconciliation
- **WHEN** all snapshot result items are summed by the canonical rules
- **THEN** every stored control total reconciles exactly

### Requirement: Period protection checks state and token twice
Every high-risk attendance mutation SHALL verify authoritative period state/token at command entry and again while holding the transactional aggregate lock. Client-reported state MUST NOT be authoritative.

#### Scenario: Period closes during mutation
- **WHEN** entry validation sees `OPEN` but the locked second check sees `FROZEN_FOR_CLOSE` or `CLOSED`
- **THEN** the mutation rolls back with zero business and success-audit delta

#### Scenario: Provider is unavailable
- **WHEN** authoritative state cannot be determined
- **THEN** the mutation fails closed as `ATTENDANCE_PERIOD_UNKNOWN`

#### Scenario: Current open token
- **WHEN** both checks return the same `OPEN/REOPENED` identity, version and token
- **THEN** the period guard permits the domain mutation subject to all other checks

### Requirement: Period actions are independently authorized and audited
Read, preclose, close, reopen and snapshot read SHALL use independent capabilities plus scoped authorization. Close/reopen attempts and snapshot access SHALL retain minimized audit records.

#### Scenario: Preclose does not imply close
- **WHEN** a user can run precheck but lacks close capability
- **THEN** they can view permitted blockers but cannot create a snapshot

#### Scenario: Snapshot read is separately controlled
- **WHEN** a caller lacks snapshot read or field-level evidence permission
- **THEN** the request is denied/redacted according to the non-disclosure policy without changing the snapshot

#### Scenario: Successful reopen audit
- **WHEN** reopen commits
- **THEN** audit links actor, authorization/scope digest, reason, old/new period version, snapshot reference and request/correlation ID without sensitive source payload

## ADDED Requirements

### Requirement: Location and group history uses immutable revisions and timelines
The system SHALL manage stable location and attendance-group identities, immutable business-content revisions and append-only effective timeline facts within company/data scope. Historical rows SHALL NOT be deleted or have business `effective_to/status/timezone/references` updated in place.

#### Scenario: Create a group with immutable content
- **WHEN** an authorized actor creates a valid future group
- **THEN** one stable identity, immutable content revision and timeline fact are created with strong row version, reason and digest

#### Scenario: Future deactivate appends history
- **WHEN** an authorized actor schedules a future group deactivation
- **THEN** an INACTIVE successor/timeline fact is appended and past dates keep the original group revision/digest

#### Scenario: Reject current or historical deactivate
- **WHEN** deactivation would affect today, history, a frozen period or a month-closed period
- **THEN** the operation fails closed without changing old content or writing success audit

#### Scenario: Preserve historical location timezone
- **WHEN** a location receives a future timezone revision
- **THEN** matching group successors are atomically created or the rollover is rejected; old-date resolution retains the prior immutable timezone

### Requirement: Group revision references stable families
Each group revision SHALL reference an immutable location revision plus stable shift and calendar families. It SHALL NOT permanently reference concrete shift/calendar versions. Resolver SHALL derive exactly one business-date-effective PUBLISHED version for each family.

#### Scenario: Different groups resolve different configuration
- **WHEN** two groups share company/location/timezone/date but reference different shift or calendar families
- **THEN** each resolves its own immutable versions and distinct correct full-configuration digest

#### Scenario: Preserve old digest after future rollover
- **WHEN** location/group/shift/calendar receives a future successor
- **THEN** recomputing any earlier date returns byte-identical location/group/configuration digest

### Requirement: Group create and rollover coordinate assignments and bindings atomically
Group create/enable SHALL atomically provision all three default policy bindings as stable families uniquely keyed by group identity + kind, whose immutable revisions reference the created group revision. Group rollover/shorten/deactivate SHALL use lock order `group identity -> current revision -> affected employee identities in binary order -> assignment timelines in binary order -> binding families in fixed policy-kind order`, execute freeze/impact checks, and atomically create successor group, assignment and binding revisions under the existing families.

Every group create/change/rollover/deactivate path that references a location SHALL first lock `location identity -> current location revision`, then use the established group lock order. Location rollover SHALL hold those outer locks while it enumerates the complete referencing group set, sorts group identities by UUID 16-byte binary order, locks them, and re-runs the same complete-set query. A set mismatch SHALL fail and roll back. Only then SHALL it execute each group's established lock order and commit location/group/assignment/binding successors, idempotency completion and success audits in one all-or-nothing transaction.

#### Scenario: Group creation is all or nothing
- **WHEN** any default policy binding cannot be provisioned
- **THEN** no group revision, timeline, binding, idempotency completion or success audit commits

#### Scenario: Rollover creates coordinated successors
- **WHEN** assignments and three bindings cross a valid future rollover boundary
- **THEN** successor assignments and binding revisions point to the successor group revision in the same transaction, and each side resolves exactly once

#### Scenario: Never fork a binding family during rollover
- **WHEN** a valid rollover appends successors for three default bindings
- **THEN** there remains exactly one family per group identity + kind and each family has an unbranched revision chain

#### Scenario: Reject an unsafe contraction
- **WHEN** a contraction cannot produce compatible assignment/binding successors or fails freeze/impact rules
- **THEN** 409 is returned, prior timeline remains unchanged, and only failure audit persists

#### Scenario: Concurrent rollover has one winner
- **WHEN** different keys race to roll the same group expected version
- **THEN** at most one successor chain commits; no timeline branch or ambiguous ownership exists

#### Scenario: Location rollover is globally atomic
- **WHEN** any affected group fails freeze/month-close, impact, configuration, concurrency or successor validation
- **THEN** the location and every affected group remain unchanged and no success audit commits

#### Scenario: A group cannot enter after the location snapshot
- **GIVEN** location rollover holds the location identity and current-revision locks while enumerating referencing groups
- **WHEN** a concurrent group create or change attempts to reference that current location revision
- **THEN** it waits, then resolves the successor revision or conflicts; it cannot commit an unenumerated reference to the old revision

#### Scenario: Complete group set changes before mutation
- **WHEN** the locked second complete-set query differs byte-for-byte from the first sorted identity set
- **THEN** the transaction returns conflict and writes no successor or success audit

### Requirement: Employee assignments are immutable and half-open
The system SHALL assign an existing W2 employee using immutable assignment content and append-only timeline with a non-empty `[effectiveFrom,effectiveTo)` derived interval. It SHALL reject same-employee overlap under a stable employee lock and SHALL allow adjacent periods.

#### Scenario: Include start and exclude end
- **WHEN** `asOf` equals assignment start or exclusive end
- **THEN** start is included and end is excluded

#### Scenario: Permit an adjacent group change
- **WHEN** one assignment ends exactly when its successor begins
- **THEN** both remain in history and each boundary side resolves exactly one group

#### Scenario: Reject overlap
- **WHEN** a new assignment overlaps an existing derived interval
- **THEN** stable 409 is returned and no assignment/success audit commits

#### Scenario: Reject inactive or cross-entity group
- **WHEN** assignment targets an inactive revision or another company
- **THEN** the safe 404/409 contract is returned with no assignment

#### Scenario: Validate actual effective configuration
- **WHEN** the assignment interval cannot resolve a compatible location, complete calendar/day, default shift or explicit override
- **THEN** persistence is rejected with a stable setup-incompatible conflict

### Requirement: Assignments preserve W2 people authority and monthly identity
Attendance SHALL reference W2 employee identity read-only and SHALL NOT copy or overwrite W2 master data. Monthly exemption identity SHALL be employee + natural month and SHALL NOT reset on group change.

#### Scenario: Unknown employee is hidden safely
- **WHEN** a caller assigns unknown or out-of-scope employee ID
- **THEN** the resource-not-available response is returned and no attendance row commits

#### Scenario: Monthly identity spans group change
- **WHEN** an employee changes groups within a month
- **THEN** both configuration snapshots expose the same employee/month usage key

### Requirement: Group and assignment idempotency is durable
Create/rollover/deactivate/assignment operations SHALL bind actor, operation, resource, key and canonical request digest including reason/expected version. The stable business lock SHALL be acquired before the second lookup.

#### Scenario: Concurrent same-key assignment replays exactly
- **GIVEN** the winning request commits `COMPLETED_SUCCESS`
- **WHEN** two requests use the same actor/resource/key/digest
- **THEN** both receive exact first status/business headers/body and only one assignment/success audit exists

#### Scenario: Retry assignment after rollback
- **GIVEN** an earlier attempt rolled back mutation, success audit and completion and retained only an independent failure audit
- **WHEN** the same key/digest is retried
- **THEN** it executes safely and does not exact-replay the failed response

#### Scenario: Changed reason conflicts
- **WHEN** the same key is reused with only reason changed
- **THEN** stable idempotency conflict is returned without mutation

#### Scenario: Different-key overlap has one winner
- **WHEN** different keys concurrently create overlapping assignments
- **THEN** one at most succeeds; the loser has stable failure audit and no success audit

### Requirement: Attendance group operations enforce capability, scope and safe disclosure
Every service operation SHALL enforce capability and company/object scope. HR_ADMIN/SYSTEM_ADMIN may manage authorized data; AUDITOR is read-only; cross-scope and unknown identities use the same safe response.

#### Scenario: Auditor cannot mutate
- **WHEN** AUDITOR reads an authorized group then attempts a write
- **THEN** read succeeds, write returns 403, business state and success audit remain unchanged

#### Scenario: Cross-scope identity is indistinguishable
- **WHEN** a principal requests another company’s group
- **THEN** response matches unknown-group 404 and reveals no metadata

### Requirement: Group/location/revision/assignment lists are stably paginated
Every non-catalog list SHALL have bounded pagination and deterministic sort with immutable ID tie-breaker.

#### Scenario: Stable traversal
- **WHEN** an unchanged authorized result is traversed page by page
- **THEN** every row appears exactly once without drift, omission or reordering

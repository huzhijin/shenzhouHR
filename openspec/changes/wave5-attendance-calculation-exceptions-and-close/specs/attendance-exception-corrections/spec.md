## ADDED Requirements

### Requirement: Exceptions are stable cases with append-only transitions
The system SHALL represent each attendance exception as a stable case plus append-only observations and transitions. It MUST NOT treat an editable status column on a calculation result as the authoritative exception history.

#### Scenario: New blocking finding
- **WHEN** a calculation first observes a blocking missing-punch, evidence-conflict or configuration finding
- **THEN** one stable case is opened with the first calculation version, segment/slice fingerprint, severity and `blockingClose=true`

#### Scenario: Recalculation observes the same finding
- **WHEN** the same stable exception fingerprint remains in a later calculation version
- **THEN** the existing case receives a new observation and no duplicate open case is created

#### Scenario: Finding disappears
- **WHEN** later valid evidence or adjustment removes the finding
- **THEN** an append-only `RESOLVED_BY_RECALCULATION` transition references the resolving calculation version

#### Scenario: Resolved finding recurs
- **WHEN** a later evidence reversal causes the same finding to return
- **THEN** an append-only `REOPENED_BY_RECALCULATION` transition preserves the prior resolution history

### Requirement: Exception types and close severity are controlled
Exception type, severity, current derived state and close-blocking behavior SHALL use closed versioned enums. Unknown or integrity-related types MUST fail safe and MUST NOT be silently treated as non-blocking.

#### Scenario: Supported domain types
- **WHEN** missing punch, evidence conflict, missing group/shift/calendar, ambiguous punch, cross-midnight review, late overtime document, early return candidate or input-integrity error is detected
- **THEN** the case uses the matching controlled code and policy-derived severity/blocking flag

#### Scenario: Unknown code is loaded
- **WHEN** persistence or an upstream adapter supplies an unregistered exception type
- **THEN** the object is rejected as an integrity error and close cannot treat it as resolved or non-blocking

### Requirement: Corrections append evidence and trigger recalculation
An employee/OA correction SHALL enter W5 only as later immutable evidence from the W4 evidence boundary. It MUST NOT directly edit a daily result, exception observation, selected punch or close snapshot.

#### Scenario: Approved timely punch correction arrives
- **WHEN** W4 later supplies approved correction evidence for the affected employee/date
- **THEN** W5 requests or consumes a narrow recalculation target and the old calculation version remains unchanged

#### Scenario: Source correction is pending or rejected
- **WHEN** the evidence snapshot contains a pending, draft, rejected or quarantined correction
- **THEN** it does not become selected evidence and the explanation records only its permitted non-effective status

#### Scenario: Evidence is reversed
- **WHEN** a correction or business document is later reversed by append-only evidence
- **THEN** W5 recalculates from the new knowledge snapshot without deleting the old evidence, result or exception history

### Requirement: HR adjustments are immutable, authorized and bounded
An HR adjustment SHALL identify one employee, business date and half-open interval, a controlled conclusion, reason, actor, capability/scope decision, approval, request/correlation ID, period token and optimistic version. An applied adjustment MUST NOT be edited in place.

#### Scenario: Authorized approved adjustment
- **WHEN** a caller with explicit in-scope create/approve capabilities submits a valid reason and current period token
- **THEN** one immutable adjustment fact is appended and only its employee/date is targeted for recalculation

#### Scenario: Missing reason or authorization
- **WHEN** reason, approval fact, exact capability or data scope is absent
- **THEN** the request fails before adjustment or success-audit creation

#### Scenario: Client tries to change source punch
- **WHEN** an adjustment command contains an instruction to update/delete an original or effective source event
- **THEN** the request is rejected because adjustments only add higher-priority evidence

#### Scenario: Stale optimistic version
- **WHEN** a concurrent adjustment or reversal changes the aggregate before submission
- **THEN** the stale command returns conflict and appends no adjustment or success audit

### Requirement: Adjustment reversal is another fact
An applied adjustment MAY be reversed only by appending an authorized reversal referencing the original adjustment. The original adjustment and every calculation that consumed it SHALL remain queryable.

#### Scenario: Valid reversal in an open period
- **WHEN** an authorized caller reverses an adjustment with a reason and current token in `OPEN/REOPENED`
- **THEN** a reversal fact is appended and a narrow recalculation produces a later version

#### Scenario: Second incompatible reversal
- **WHEN** a fully reversed adjustment is reversed again with an incompatible request
- **THEN** the server/domain command conflicts and preserves the original reversal

### Requirement: Protected periods reject correction and adjustment mutation
New effective corrections, adjustments, reversals and their recalculation mutations MUST be rejected for `FROZEN/CLOSED/UNKNOWN` periods and stale period versions. Raw staging allowed by W4 does not authorize W5 result mutation.

#### Scenario: Adjustment targets closed period
- **WHEN** an otherwise authorized adjustment targets `CLOSED`
- **THEN** it returns the stable closed-period conflict and leaves calculation, exception and snapshot history unchanged

#### Scenario: State changes between precheck and commit
- **WHEN** the period token becomes frozen or closed after command validation but before commit
- **THEN** the locked second check rejects the mutation with zero business delta

#### Scenario: Period was reopened
- **WHEN** the old closed version is reopened with a new period token
- **THEN** a new adjustment may target only the reopened version and cannot mutate the old close snapshot

### Requirement: Exception actions are independently authorized and scoped
Reading, assigning, adjusting, approving, reversing and resolving attendance exceptions SHALL use independent capabilities plus company/location/attendance-group/organization scope. Technical administrator status MUST NOT grant attendance detail or adjustment authority by default.

#### Scenario: Read does not imply adjust
- **WHEN** an auditor can read an in-scope exception but lacks adjustment capability
- **THEN** the exception may be returned with permitted fields while any adjustment command is denied

#### Scenario: Out-of-scope exception
- **WHEN** a caller requests an exception outside authorized scope
- **THEN** the non-disclosing path returns 404 before selecting protected evidence details

#### Scenario: Assignment does not imply approval
- **WHEN** a caller can assign a case but lacks approval capability
- **THEN** assignment may be audited while adjustment approval remains denied

### Requirement: Exception and adjustment audit is complete and minimized
Every exception assignment, adjustment create/approve/reverse, conflict, denied attempt and resulting recalculation reference SHALL be auditable by actor, scope digest, reason, request/correlation ID, before/after digest and result. Audit/log output MUST omit credentials, complete raw rows, local paths, exact unauthorized location and unnecessary sensitive document details.

#### Scenario: Successful adjustment trace
- **WHEN** an adjustment resolves an exception
- **THEN** an authorized trace links case → transition → adjustment → calculation version → difference → request/audit references

#### Scenario: Failure is safe
- **WHEN** authorization, validation or persistence fails
- **THEN** failure evidence contains a stable reason and correlation ID without a partial adjustment, success audit or sensitive payload leak

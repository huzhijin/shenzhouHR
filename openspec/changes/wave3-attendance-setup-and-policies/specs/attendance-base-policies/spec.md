## ADDED Requirements

### Requirement: W3 policies use an independent company-scoped aggregate
The system SHALL keep the W2 generic policy schema, Java model, repository, lifecycle, DTO and OpenAPI contract unchanged. W3 SHALL store attendance-specific templates, company scopes, scoped versions, lifecycle events and bindings only in `attendance_policy_*` aggregates. An attendance binding SHALL reference `attendance_policy_scoped_version`, never W2 `policy_version`.

#### Scenario: Preserve the W2 policy contract
- **GIVEN** a V6 database and existing W2 policy rows/API fixtures
- **WHEN** V7 and W3 application code are applied
- **THEN** W2 table columns, indexes, constraints, DTO schemas and full-row hashes are byte-for-byte retained

#### Scenario: Reject cross-entity scoped lifecycle access
- **WHEN** a capable actor queries or mutates a W3 scoped version outside their company data scope
- **THEN** the safe resource-not-available response is returned, no W3 mutation occurs, and no success audit is written

### Requirement: W3 provides exactly three controlled policy kinds
The W3 template catalog SHALL contain exactly `MEAL_DEDUCTION`, `LATE_GRACE`, and `MONTHLY_LATE_EXEMPTION`. Templates SHALL expose controlled typed fields only and SHALL prohibit scripts, expressions, commands and person-specific hardcoding.

#### Scenario: Reject an unsupported parameter
- **WHEN** an immutable draft version contains an undeclared field, enum value or value type
- **THEN** validation produces a field-level issue and publication is blocked

#### Scenario: Seed only the fixed oracle baselines
- **GIVEN** the explicit baseline company exists
- **WHEN** V7 is applied
- **THEN** exactly three W3 templates, three scopes and three PUBLISHED v1 scoped versions with oracle IDs/JSON/digests exist and no extra W3 baseline exists

### Requirement: Scoped versions and lifecycle facts are immutable
Every W3 scoped version business-content row and lifecycle fact SHALL be append-only. `attendance_policy_scoped_version` SHALL NOT contain a `status` column; lifecycle state SHALL be derived from append-only lifecycle facts at the requested business/knowledge time. Draft edit, validation, publication, future deactivation and rollback SHALL create new immutable content or lifecycle facts and SHALL NOT update historical parameters, effective period, snapshot or digest in place.

#### Scenario: Published content remains immutable
- **WHEN** a scoped version has a PUBLISHED lifecycle fact
- **THEN** its parameters, interval, snapshot and digest cannot be updated or deleted

#### Scenario: Derive PUBLISHED without mutable version status
- **WHEN** a resolver sees a valid PUBLISHED lifecycle fact and no effective deactivation at the requested times
- **THEN** it derives PUBLISHED without reading or updating a scoped-version status column

#### Scenario: Future deactivation preserves past resolution
- **WHEN** a PUBLISHED scoped version is scheduled for future deactivation
- **THEN** a new lifecycle fact is appended, past business dates still resolve the same version/digest, and dates at or after the boundary resolve the successor or fail closed

#### Scenario: Reject current or historical lifecycle mutation
- **WHEN** month-close authority is unavailable and publication/deactivation/rollback would affect today or a historical date
- **THEN** the operation returns a stable freeze conflict, writes no success fact/audit, and retains all prior content

### Requirement: Group creation provisions three default bindings atomically
Creating or enabling an attendance-group revision SHALL, in the same transaction, provision one valid default binding for each W3 policy kind. Each scoped version SHALL match the group company and business interval and SHALL be uniquely PUBLISHED. Meal deduction SHALL default to enabled.

#### Scenario: Provision all default bindings
- **WHEN** a valid future group revision is created and each scoped baseline resolves exactly once
- **THEN** the group revision and exactly three binding families/revisions commit together

#### Scenario: Roll back when one policy is missing
- **WHEN** any required kind has zero valid PUBLISHED scoped versions
- **THEN** creation fails with `POLICY_MISSING` and no group revision, binding or success audit commits

#### Scenario: Roll back when a policy is ambiguous
- **WHEN** any required kind has multiple equally valid PUBLISHED scoped versions
- **THEN** creation fails with `POLICY_AMBIGUOUS`; mapper single-value coercion or `LIMIT 1` is forbidden

### Requirement: Binding resolution is deterministic and history safe
Each binding family SHALL be uniquely and stably owned by `(attendanceGroupId, policyKind)`. An immutable binding revision SHALL reference exactly one group revision and one PUBLISHED W3 scoped version. Group rollover SHALL append a successor revision under the same family, never replace the family or create another family for the kind. Its derived interval SHALL be contained by both group and scoped-version intervals. Resolution SHALL evaluate company, kind, business date, lifecycle publication and half-open intervals and SHALL yield exactly one revision per kind. Priority SHALL NOT be part of the W3 binding model.

#### Scenario: Rollover retains the stable binding family
- **WHEN** a group revision rolls over with all three default policies
- **THEN** the same three family IDs remain, each receives one successor revision referencing the new group revision, and both boundary sides resolve exactly once

#### Scenario: Resolve exactly one stable-family revision
- **GIVEN** exactly one stable `(group identity, kind)` family
- **WHEN** one revision references the business-date-effective group revision and scoped version
- **THEN** that revision is returned with scoped-version ID and digest without priority selection

#### Scenario: Reject family or revision cardinality violation
- **WHEN** a kind/group has zero or multiple families, or its business date resolves zero or multiple revisions
- **THEN** resolution returns `POLICY_MISSING` or `POLICY_AMBIGUOUS` and no configuration is marked resolved

#### Scenario: Reject an out-of-period binding
- **WHEN** a proposed binding extends outside the group revision or scoped-version derived interval
- **THEN** mutation returns 409 and persists neither binding successor nor success audit

#### Scenario: Require current impact preview
- **WHEN** a binding successor changes effective scope
- **THEN** the mutation requires a real-scope impact token bound to the identical canonical request digest

### Requirement: Policy lifecycle uses durable locked idempotency
Draft, validate, publish, future deactivate and rollback SHALL use actor+operation+resource+key and a canonical request digest including reason, company and expected version. The service SHALL acquire the stable scope lock, perform a second idempotency lookup, then check `If-Match` in the same transaction. Exact replay SHALL apply only to committed `COMPLETED_SUCCESS` results. Failed transactions SHALL roll back mutation, success audit and completion; a separately committed failure audit SHALL NOT make the failed response replayable, and the same key/digest MAY retry after rollback or provably stale STARTED takeover.

#### Scenario: Replay an identical lifecycle response
- **GIVEN** the first request committed `COMPLETED_SUCCESS`
- **WHEN** the same actor repeats the same lifecycle request and digest with one key
- **THEN** exact status, business headers and body are replayed without another business row or success audit

#### Scenario: Retry after rolled-back failure
- **GIVEN** the prior attempt rolled back mutation, success audit and completion while retaining only an independent failure audit
- **WHEN** the same key and digest is retried
- **THEN** the operation executes again and does not exact-replay the failed response

#### Scenario: Reject changed reason under the same key
- **WHEN** the actor reuses a key but changes only the reason
- **THEN** the digest differs and a stable idempotency conflict is returned

#### Scenario: Reject stale validate
- **WHEN** validate supplies an obsolete strong `If-Match`
- **THEN** 409 is returned, current state is preserved, and only the independent failure audit remains

#### Scenario: Concurrent deactivate has one winner
- **WHEN** two future-deactivate requests use different keys and one expected version
- **THEN** at most one commits; the loser receives stable 409 and has no success audit

#### Scenario: Failure audit survives outer rollback
- **WHEN** authorization, stale, freeze or conflict rolls back the outer transaction
- **THEN** the reason-safe failure audit committed on an independent transaction/connection remains queryable

### Requirement: Simulation is server authoritative
Simulation SHALL accept only employee ID, business date, correction-as-of and typed punch instants/directions/segment associations. The client SHALL NOT supply policy kind/version, shift, calendar, fixed offset, meal match, `lateMinutes` or monthly usage. The server SHALL resolve authorized immutable configuration and query a read-only authoritative monthly-usage provider.

Typed punches SHALL be hypothetical request inputs without `recordedAt`; all supplied punches SHALL participate unchanged and correctionAsOf SHALL NOT filter or correct them. `correctionAsOf` SHALL be an offset RFC3339 knowledge-time instant between the resolved local business-day start and request processing time. Every server configuration and authoritative usage projection read SHALL have `recordedAt <= correctionAsOf`; later-known configuration/usage SHALL be invisible. Repeating identical authoritative inputs at the same knowledge time SHALL return the same digest/result.

Late resolution SHALL first classify `rawLateMinutes` through enabled `LATE_GRACE`, then apply enabled `MONTHLY_LATE_EXEMPTION`; monthly exemption SHALL NOT override a grace miss. The two maximum-minute thresholds SHALL agree, `monthlyUses` SHALL equal one and `resetOnGroupChange` SHALL be false, otherwise simulation SHALL fail closed with issues. For grace/max=15: 0 is ON_TIME/no consume; 1..15 with unused authoritative usage is EXEMPTED/predicted consume one; 1..15 with used usage is LATE/no consume; 16+ is LATE/no consume regardless of usage. W3 SHALL return predicted consumption but SHALL NOT persist official usage.

#### Scenario: Derive late minutes from schedule and punches
- **WHEN** the resolved WORK start and ENTRY punch differ by 0, 1, 15 or 16 minutes
- **THEN** the server derives those values itself; 0 and 16 do not consume exemption, while 1 and 15 may consume it if authoritative usage is unused

#### Scenario: Respect correction knowledge time
- **GIVEN** configuration or authoritative usage knowledge is recorded after `correctionAsOf`
- **WHEN** simulation runs for that knowledge time
- **THEN** the later fact is excluded; advancing correctionAsOf may include it without mutating the earlier snapshot

#### Scenario: Keep hypothetical punches independent of knowledge filtering
- **GIVEN** request typed punches have no recordedAt
- **WHEN** simulation runs at any valid correctionAsOf
- **THEN** all supplied punches participate and only server configuration/usage knowledge is time-filtered

#### Scenario: Reject inconsistent late policies
- **WHEN** LATE_GRACE and MONTHLY_LATE_EXEMPTION are missing, disabled, disagree on the minute threshold, allow uses other than one or reset on group change
- **THEN** simulation fails closed with real issues and writes no formal or success-audit state

#### Scenario: Group change does not reset usage
- **WHEN** the authoritative provider reports the employee/month exemption already used before a group change
- **THEN** the later group simulation remains not matched under the same employee/natural-month key

#### Scenario: Use actual next-day EXIT
- **WHEN** a resolved IANA-timezone shift crosses midnight and EXIT occurs next local day
- **THEN** simulation uses the actual instant/next-day local date and no hardcoded `+08:00`

#### Scenario: Meal calculation uses resolved policy and calendar
- **WHEN** typed punches cover the resolved meal window/threshold on an applicable resolved day type
- **THEN** the result returns matched, configured deduction, scoped-version ID and full configuration digest

#### Scenario: Simulation writes no formal state
- **WHEN** any simulation succeeds or returns not matched
- **THEN** raw facts, formal day/month results, official usage and success-audit counts have database delta zero

### Requirement: Policy UI respects scope and read-only roles
The UI SHALL render server lifecycle status, validation issues, conflicts, impact and authoritative simulation without duplicating the algorithm. AUDITOR SHALL see authorized history only and SHALL not see or invoke simulation, impact or manage actions.

#### Scenario: Read-only direct route
- **WHEN** an AUDITOR opens the W3 policy route directly
- **THEN** history loads, manage/simulation/impact controls are absent, and direct mutation endpoints remain denied

#### Scenario: Demo does not replace normal acceptance
- **WHEN** policy pages run in demo mode
- **THEN** they issue zero business network/MySQL activity; normal-mode role acceptance still runs against the real backend/database

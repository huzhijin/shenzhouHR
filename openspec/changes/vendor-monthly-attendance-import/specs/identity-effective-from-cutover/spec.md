## ADDED Requirements

### Requirement: Current identity clocks cover 2026-01-01
A one-time authorized cutover SHALL move the current open-ended employee version, employment assignment, organization version and attendance-group assignment so each covers `2026-01-01`. The cutover MUST move only the earliest version of each object so the chain covers `2026-01-01`. Later versions stay in place. It MUST NOT create a second version that also starts on `2026-01-01`, MUST NOT rewrite later historical versions onto that date, and MUST NOT invent department-transfer history. After cutover, a realtime report query for any day in January through July 2026 MUST be able to resolve at most one identity combination per employee-day.

#### Scenario: Single current version is backdated
- **WHEN** an employee, assignment, organization or attendance-group assignment has exactly one current open-ended version whose `effective_from` is after `2026-01-01`
- **THEN** that version's `effective_from` becomes `2026-01-01`, later days stay covered, and no additional version row is inserted

#### Scenario: Multiple versions are not collapsed
- **WHEN** an object already has more than one version
- **THEN** only the earliest version's `effective_from` may move to `2026-01-01`, later versions stay unchanged, and intervals remain non-overlapping so the calculator does not see two valid identities on the same day

#### Scenario: Overlap is a hard stop
- **WHEN** moving the current version to `2026-01-01` would overlap another version or violate a unique `(id, effective_from)` key
- **THEN** that object is refused, the cutover transaction does not commit a partial identity for that object, and the operator receives a durable exception list

### Requirement: Cutover is audited and separate from punch import
The identity cutover SHALL run as its own authorized, idempotent operation with a recorded actor, reason, before/after `effective_from`, object ids and a request id. Punch-import publication MUST NOT silently change identity or attendance-group dates. A second successful run against already-cutover objects SHALL report already-applied and MUST NOT rewrite row versions unnecessarily.

#### Scenario: First run writes an audit trail
- **WHEN** an authorized operator executes the cutover
- **THEN** each updated version is accompanied by an audit record that includes old `effective_from`, new `effective_from`, object type and request id

#### Scenario: Punch import does not move dates
- **WHEN** a vendor monthly batch is prechecked or published
- **THEN** no employee, assignment, organization or attendance-group `effective_from` changes as a side effect

#### Scenario: Re-run is idempotent
- **WHEN** the same cutover is executed after the current versions already start on `2026-01-01`
- **THEN** the operation reports already-applied and leaves those versions unchanged

### Requirement: January identity is enough for live calculation
After a successful cutover, a person who has a unique employee number and a current assignment MUST have an unambiguous identity on each day from `2026-01-01` through the current assignment end. Historical department transfers are out of scope: the current organization is the organization used for January through July. OA documents already stored for January through July MUST remain usable on their occurrence dates once identity covers those dates.

#### Scenario: January report can resolve the person
- **WHEN** cutover has committed for an active employee and a realtime report is queried for `2026-01`
- **THEN** the calculator finds exactly one identity for that employee on each in-period day and does not skip the person solely because versions previously started in August

#### Scenario: OA occurrence dates remain valid
- **WHEN** an approved OA document exists with an occurrence date in March 2026 and the employee's identity now covers that date
- **THEN** the realtime calculation MAY include that document and MUST NOT fail solely because identity previously started after the occurrence date

## ADDED Requirements

### Requirement: W6 dependency evidence is explicit
Before persistence or callable APIs are implemented, verification SHALL record the accepted W2 employee/employment/prior-service contract and an exact W5 FINAL commit/manifest containing period status, close snapshot, reopen version, recalculation intent and highest migration version. Historical, partial or assumed W5 state MUST be rejected.

#### Scenario: W5 FINAL is unavailable
- **WHEN** only W2 and a pre-FINAL W5 workspace are available
- **THEN** verification MAY pass pure-domain tasks but SHALL keep migration, persistence, API, UI, MySQL and report-wiring tasks blocked

#### Scenario: W5 FINAL is synchronized
- **WHEN** an exact upstream commit and manifest are supplied
- **THEN** W6 SHALL rerun retained contract checks before selecting a migration number or implementing adapters

### Requirement: Domain TDD names every calendar and ledger boundary
Review-owned tests SHALL separately cover qualification/tier separation, anniversary eve/day/expiry, 10-year eve/day, 20-year day, prior service, rehire, default and alternative leap-day policies, future dates, negative prior service, grant ordering, replay, expiry, use, exact reversal, duplicate reversal and negative balance. Tests SHALL use fixed dates and synthetic IDs.

#### Scenario: Pure-domain implementation is reviewed
- **WHEN** the current independent W6 slice is submitted
- **THEN** each named boundary test SHALL pass without Spring context, database, network, filesystem, system clock or W5 classes

### Requirement: Final W6 closes contract, database and UI gates
After dependency acceptance, W6 SHALL close OpenAPI/Controller/Java/TypeScript/database enums in both directions, add forward-only migration and MySQL tests, verify capability/scope/state/audit negatives, implement real normal-mode UI states, and pass backend/frontend builds. Unit tests MUST NOT be reported as MySQL, browser or external verification.

#### Scenario: Final completion is claimed
- **WHEN** W6 is reported complete
- **THEN** the report SHALL include exact source SHA, migration versions/checksums, MySQL version and final database state, named test/build commands, browser states, OpenSpec progress and all `NOT_VERIFIED` external conditions

### Requirement: Migration and historical data are protected
W6 SHALL not alter existing migration files/checksums. The actual next migration number SHALL be selected only after W5 FINAL synchronization. Ledger, grant, opening-import and audit records SHALL be append-only; rollback SHALL use feature disablement, policy deactivation, reversal and new projections rather than physical deletion.

#### Scenario: Candidate migration number is already occupied
- **WHEN** the synchronized upstream highest migration conflicts with a draft W6 number
- **THEN** W6 SHALL select the next available forward version and SHALL not rename or overwrite the upstream migration

### Requirement: Regression and data-safety boundaries remain intact
W6 verification SHALL keep W1～W5 retained behavior, PAYROLL frontend discoverability at zero, organization continuous-sync discoverability at zero, and production/demo isolation. Fixtures and evidence MUST NOT contain real employee, leave reason, attachment, location, payroll or credential data.

#### Scenario: Repository safety scan runs
- **WHEN** W6 source, fixtures and generated reports are scanned
- **THEN** no real personal/sensitive data, database secret, PAYROLL frontend entry or organization-sync route/job SHALL be present

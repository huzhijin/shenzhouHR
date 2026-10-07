## ADDED Requirements

### Requirement: Payroll reservation is disabled by default
The system SHALL define one explicit payroll-reservation feature flag whose checked-in default and environment-default value are `false`. Every payroll reservation use case SHALL check this flag before capability evaluation, adapter lookup, or snapshot access and SHALL fail as resource unavailable when disabled.

#### Scenario: Default configuration rejects a reservation call
- **WHEN** the application starts without a payroll-reservation environment override and a caller invokes a payroll reservation use case
- **THEN** the call fails as resource unavailable, no snapshot port is invoked, and no capability or integration state is disclosed

#### Scenario: Explicit false override rejects a reservation call
- **WHEN** the payroll-reservation environment override is explicitly `false`
- **THEN** the service follows the same fail-closed behavior as the checked-in default

### Requirement: PAYROLL capability is isolated and deny-by-default
The system SHALL define `PAYROLL:RESERVATION_READ` as a server-internal capability, SHALL seed or grant it to no role or account in W8, and SHALL require it after the feature gate for every reservation use case. Ordinary authentication, current-capability, menu, account, and role responses SHALL omit all `PAYROLL:*` codes even if persistence contains an accidental grant.

#### Scenario: Feature enabled without an explicit PAYROLL grant
- **WHEN** the feature is enabled and the current principal has only non-PAYROLL capabilities
- **THEN** the reservation call fails with access denied and no snapshot port is invoked

#### Scenario: Accidental grant does not become frontend capability
- **WHEN** persistence returns a `PAYROLL:*` code while building an ordinary user capability or menu response
- **THEN** the response contains neither that code nor a menu item derived from it

### Requirement: Reservation models contain boundaries and references only
The payroll domain SHALL model payroll periods, item definitions, versioned employee payroll-profile references, frozen attendance-snapshot references, and calculation-result references with validated precise identifiers, lifecycle states, time boundaries, positive versions, and integrity digests. These models SHALL NOT contain salary amounts, tax, social-insurance, housing-fund, bank-account, payment-file, payslip, or calculation-formula fields or behavior.

#### Scenario: Construct a valid reservation aggregate
- **WHEN** a caller provides valid opaque IDs, a non-empty half-open period, unique item codes, positive versions, permitted states, and SHA-256 digests
- **THEN** the reservation types are constructed without computing, storing, or returning any payroll value

#### Scenario: Reject invalid reservation metadata
- **WHEN** a caller provides a reversed/empty period, malformed ID or digest, duplicate item code, non-positive version, or unsupported state
- **THEN** construction fails before any snapshot port or persistence action

### Requirement: W5 close snapshots are consumed through a read-only port
The payroll application SHALL own a `FrozenAttendanceSnapshotPort` that provides lookup only and exposes no attendance create, close, reopen, update, delete, raw-punch, exception, or recalculation operation. A usable returned snapshot SHALL be closed, immutable-versioned, integrity-bound, and cover the exact payroll period.

#### Scenario: Real closed snapshot satisfies the reservation
- **WHEN** the feature is enabled, the principal has the PAYROLL capability, and a real W5 adapter returns a closed integrity-bound snapshot covering the requested period
- **THEN** the service returns only a validated frozen-snapshot reference suitable for later payroll reservation

#### Scenario: Snapshot is not closed or does not match the period
- **WHEN** an adapter returns a non-closed, invalid-digest, non-positive-version, or period-mismatched snapshot
- **THEN** the service rejects it and records an audit-safe invalid-snapshot denial

### Requirement: W8 ships no fabricated W5 integration
Until W5 FINAL is synchronized, W8 SHALL register no production, development, test-fallback, in-memory, empty, or synthetic implementation of the frozen attendance-snapshot port. If the feature and capability are both enabled without a real adapter, the service SHALL fail closed with a stable integration-unavailable result.

#### Scenario: Enabled service has no W5 adapter
- **WHEN** the feature is enabled, the principal has the PAYROLL capability, and no frozen attendance-snapshot adapter is registered
- **THEN** the call fails as integration unavailable and no synthetic snapshot or result is returned

### Requirement: Reservation denials are audit-safe
Each feature-off, missing-capability, missing-adapter, and invalid-snapshot rejection SHALL emit one payroll reservation denial audit event using stable action, resource, result, and reason codes. The event SHALL NOT contain request payloads, employee/profile contents, snapshot contents, capability lists, salary-related values, exception messages, secrets, or raw identifiers beyond the opaque resource reference required for correlation.

#### Scenario: Denied use case writes a safe audit event
- **WHEN** any guarded reservation use case is denied
- **THEN** exactly one failure audit event identifies the stable denial reason and contains no sensitive payload or exception text

### Requirement: W8 has no user-facing or public API surface
W8 SHALL add no frontend file, route, menu, card, search entry, copy, notification, ordinary-user API, REST controller/DTO, or public OpenAPI path. It SHALL add no payslip, tax/statutory calculation, export, payment, or bank-file function.

#### Scenario: Inspect the W8 production surface
- **WHEN** source, compiled frontend assets, controller mappings, DTOs, and the OpenAPI document are inspected
- **THEN** no W8 payroll surface is discoverable and the existing attendance/public contracts remain unchanged

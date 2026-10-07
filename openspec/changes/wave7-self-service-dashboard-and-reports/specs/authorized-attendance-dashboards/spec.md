## ADDED Requirements

### Requirement: Dashboard route authorization
The `/workbench` route SHALL be locally allowlisted and SHALL require `ATTENDANCE_DASHBOARD:READ`; server menu presence, role labels, query parameters, or prototype context SHALL NOT satisfy the route guard.

#### Scenario: Authorized dashboard route
- **WHEN** the server menu contains `/workbench` and the session contains `ATTENDANCE_DASHBOARD:READ`
- **THEN** the menu item and direct route are available

#### Scenario: Menu without capability
- **WHEN** the server menu contains `/workbench` but the session lacks `ATTENDANCE_DASHBOARD:READ`
- **THEN** the menu item is filtered out and direct navigation renders the generic 403 state

### Requirement: Authorized aggregate projection
The dashboard SHALL render only server-authorized aggregates for a `COMPANY`, `ORGANIZATION`, `ATTENDANCE_GROUP`, or `SELF` scope and SHALL NOT expand scope or calculate cross-scope totals in the browser.

#### Scenario: Department owner views an authorized scope
- **WHEN** a dashboard projection is returned for an authorized organization scope
- **THEN** the UI renders only the supplied aggregate and drill-down references for that scope

#### Scenario: Scope changes
- **WHEN** the user selects another authorized scope or period
- **THEN** prior projection data is cleared before loading and no previous-scope metric flashes while authorization is being re-evaluated

### Requirement: Dashboard metric boundaries
The dashboard projection SHALL support attendance rate, exception rate, confirmed work, recognized overtime, leave, unsettled-period, and freshness metrics while excluding precise coordinates, raw punch identifiers, devices, sensitive leave reasons, and every PAYROLL field.

#### Scenario: Safe aggregate metrics render
- **WHEN** an authorized aggregate contains supported attendance metrics
- **THEN** the dashboard renders the metric values, labels, period, scope, version, and data-as-of time

#### Scenario: Forbidden field enters a fixture
- **WHEN** a dashboard contract fixture includes a precise location, sensitive leave reason, raw punch identifier, device value, or PAYROLL field
- **THEN** contract/source verification fails

### Requirement: Small-sample suppression
The dashboard SHALL render server-provided small-sample suppression as a text state and SHALL NOT infer, reverse, or estimate suppressed values from other displayed metrics.

#### Scenario: Metric is suppressed
- **WHEN** an authorized metric is marked suppressed
- **THEN** the UI shows the supplied suppression label instead of a number and preserves an accessible text explanation

### Requirement: Dashboard version and frozen state
Every dashboard projection SHALL identify its projection version, source/close version, data-as-of time, scope, and period state. Frozen or closed aggregates SHALL remain readable as immutable history.

#### Scenario: Closed dashboard period
- **WHEN** the dashboard projection period state is `CLOSED`
- **THEN** the UI renders the metrics with a frozen-history notice and does not imply that live data will modify that version

### Requirement: Dashboard drill-down contract
Dashboard drill-down references SHALL bind the target report/detail request to the dashboard projection version and authorized scope.

#### Scenario: User drills into an exception metric
- **WHEN** the user activates an allowed exception metric drill-down
- **THEN** the target request carries the opaque scope reference and projection version supplied by the dashboard without constructing a wider scope

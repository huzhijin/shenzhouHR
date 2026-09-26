## ADDED Requirements

### Requirement: Wave 7 route allowlist
The frontend SHALL recognize only the declared Wave 7 routes and exact capability contracts and SHALL reject unknown, prototype-only, PAYROLL, or server-invented routes.

#### Scenario: Known Wave 7 menu routes
- **WHEN** the server supplies a declared Wave 7 menu path with all required capabilities
- **THEN** the path is included in authorized navigation

#### Scenario: Prototype or excluded path
- **WHEN** the server supplies a demo-ID path, `/qa/handoff`, a prototype recalculation path, or any PAYROLL path
- **THEN** the path is excluded from authorized navigation even when the server supplies unrelated or PAYROLL capabilities

### Requirement: Complete page states
Each Wave 7 asynchronous route SHALL have testable loading, empty, error, 401/session-expired, 403, and ready states; versioned routes SHALL also have a frozen/closed presentation and report export SHALL have processing lifecycle states.

#### Scenario: Loading clears protected content
- **WHEN** a Wave 7 route starts loading or changes scope, employee context, or period
- **THEN** protected projection content from the prior context is removed before the loading state is shown

#### Scenario: Empty differs from forbidden
- **WHEN** an authorized projection has no rows
- **THEN** the page renders an empty state distinct from the non-leaking 403 state

#### Scenario: Frozen projection is ready and immutable
- **WHEN** an authorized ready projection is frozen or closed
- **THEN** readable content and a frozen notice are both rendered, with actions controlled only by `allowedActions`

### Requirement: Fixture isolation
Synthetic Wave 7 contract fixtures SHALL be importable by tests but SHALL NOT be selected or returned by production runtime code, normal Vite mode, production API adapters, localStorage, or runtime environment flags.

#### Scenario: Component test uses fixtures
- **WHEN** a component test injects a Wave 7 fixture gateway
- **THEN** the page renders deterministic synthetic projection data

#### Scenario: Normal runtime loads Wave 7
- **WHEN** a normal production or development build opens a Wave 7 route before upstream wiring is complete
- **THEN** the fail-closed `WAVE7_UPSTREAM_PENDING` state is rendered and no fixture or synthetic success response is used

### Requirement: Responsive Wave 7 layouts
Wave 7 routes SHALL be usable at 360, 390, 430, 768, 1024, 1366, 1440, and 1920 CSS pixels without unintended page-level horizontal scrolling, overlap, clipping, or action loss.

#### Scenario: Employee page at 360px
- **WHEN** a self-service page is rendered at 360px
- **THEN** content uses a single-column layout, primary targets are at least 44 by 44 CSS pixels, and navigation/actions remain reachable without horizontal scrolling

#### Scenario: Report at mobile width
- **WHEN** a report projection is rendered at 430px or narrower
- **THEN** the desktop table is replaced by labeled record cards or grouped lists preserving the same authorized fields and semantic order

#### Scenario: Dashboard at desktop widths
- **WHEN** the dashboard is rendered at 1366px through 1920px
- **THEN** title, context, freshness, metrics, filters, and actions do not overlap and readable content width remains bounded

### Requirement: Keyboard and assistive technology access
All Wave 7 interactions SHALL be operable by keyboard with visible focus, semantic headings and landmarks, accessible names, associated form errors, and appropriate polite or assertive live-region behavior.

#### Scenario: Keyboard-only report interaction
- **WHEN** a keyboard user navigates filters, drill-down controls, and export controls
- **THEN** each control receives visible focus in logical order and exposes an accessible name and state

#### Scenario: Async state changes
- **WHEN** a route changes from loading to ready, error, or frozen
- **THEN** the state is announced with appropriate ARIA semantics without repeatedly announcing decorative content

### Requirement: State and charts do not rely on color
Metric, status, frozen, suppressed, error, and export lifecycle presentations SHALL include text and/or icon/shape semantics in addition to color.

#### Scenario: High contrast or color unavailable
- **WHEN** a user cannot perceive the intended colors
- **THEN** labels, values, status text, and focus indicators still communicate the complete state

### Requirement: Reduced motion
Wave 7 layout and state transitions SHALL respect `prefers-reduced-motion` and SHALL NOT require animation to understand state or complete an action.

#### Scenario: Reduced motion is enabled
- **WHEN** the operating system requests reduced motion
- **THEN** Wave 7 reveal and transition animations are disabled or reduced to effectively immediate changes

### Requirement: PAYROLL front-end discoverability remains zero
Wave 7 frontend routes, navigation, projection DTOs, fixtures, rendered copy, report/export columns, search, notification, telemetry, local storage, and build output SHALL contain no discoverable PAYROLL feature, field, or placeholder.

#### Scenario: PAYROLL capability is injected
- **WHEN** a session contains PAYROLL capabilities and menu entries
- **THEN** Wave 7 authorization still exposes no PAYROLL route or navigation

#### Scenario: Source and build scan runs
- **WHEN** Wave 7 verification scans frontend sources and the production build for excluded PAYROLL discovery terms
- **THEN** the scan reports zero Wave 7 discoveries

### Requirement: Verification truthfulness
Wave 7 verification SHALL separately report contract/UI evidence completed on fixtures and upstream API, MySQL, export worker, browser, performance, and external-integration evidence that remains `NOT_VERIFIED`.

#### Scenario: Upstream branches are not finalized
- **WHEN** Wave 5 and Wave 6 FINAL commits are absent from the Wave 7 baseline
- **THEN** no test or report claims real API/MySQL integration and the waiting interface list is preserved

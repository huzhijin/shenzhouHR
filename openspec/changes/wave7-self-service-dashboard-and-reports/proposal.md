## Why

Wave 7 turns the closed attendance and time-account facts produced by Waves 5 and 6 into employee self-service, authorized management insight, and controlled reporting experiences. The upstream implementations are not yet available on the synchronized baseline, so this change must first freeze the route, permission, projection, page-state, responsive, accessibility, and fixture contracts without presenting fixture-backed screens as real API integration.

## What Changes

- Add employee-only self-service routes for today, attendance records, leave/time-account balances, and feedback, with the employee identity derived from the authenticated session rather than a client-supplied employee identifier.
- Add capability- and scope-gated workbench/dashboard/report routes with authorized aggregates, freshness/version metadata, same-version drill-down contracts, and explicit frozen-period presentation.
- Add controlled report export contracts that reuse the visible query scope, filters, and field allowlist; reauthorize both creation and download; use synchronous delivery through 50,000 rows and an asynchronous job above that threshold.
- Add projection DTOs and synthetic contract fixtures for independently implementable UI work. Fixtures remain test-only and cannot be selected by the production API path.
- Add loading, empty, error, 403, frozen, and export-processing states; responsive behavior from 360px upward; keyboard, focus, live-region, reduced-motion, and non-color-only state requirements.
- Add source-level and component tests that preserve PAYROLL front-end discoverability at zero and prevent prototype role strings, demo IDs, localStorage, payroll fields, precise coordinates, or sensitive leave reasons from entering Wave 7 production paths.
- Leave real Wave 5/Wave 6 API wiring, end-to-end authorization, MySQL evidence, and live export generation explicitly pending until those upstream changes are finalized and synchronized.

## Capabilities

### New Capabilities

- `employee-self-service-projections`: Session-bound today, records, leave/time-account, and feedback projection contracts and employee-facing states.
- `authorized-attendance-dashboards`: Capability/scope-gated workbench and dashboard aggregates, freshness/version metadata, drill-down, and frozen-period behavior.
- `controlled-report-exports`: Authorized report queries, same-version detail contracts, field allowlists, synchronous/asynchronous export lifecycle, and dual authorization.
- `wave7-responsive-accessible-experience`: Route authorization, complete page states, fixture isolation, responsive behavior, accessibility, and PAYROLL zero-discoverability verification.

### Modified Capabilities

None.

## Impact

- Frontend route registry, navigation authorization, shared page-state presentation, feature modules, responsive styles, accessibility tests, and contract fixtures.
- Future API integration points for Wave 5 attendance/close projections and Wave 6 leave/time-account projections; no live endpoint is claimed or added to the production path in this independent phase.
- Open Design V19-OD-02, V19-OD-12, and V19-OD-13 are converged into production-safe React contracts; prototype role routing, demo persistence, and localStorage behavior are excluded.
- PAYROLL remains unregistered and absent from menu, route, card, search, notification, telemetry, DTO, fixture, and export-field contracts.

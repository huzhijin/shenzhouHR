## 1. Route and projection contracts

- [ ] 1.1 Extend the local route authorization matrix and unit tests for `/workbench`, `/attendance/reports`, `/me/today`, `/me/records`, `/me/leave`, and `/me/feedback`, including direct-route 403 behavior and PAYROLL/unknown-route rejection.
- [ ] 1.2 Define Wave 7 projection DTOs, allowed-action unions, period/version/freshness metadata, export lifecycle types, and runtime contract guards without importing raw Wave 5/Wave 6 entities.
- [ ] 1.3 Add deterministic synthetic contract fixtures in test-only modules and a production `WAVE7_UPSTREAM_PENDING` gateway that performs no fixture lookup and no network request.

## 2. Fixture-driven presentation

- [ ] 2.1 Add shared Wave 7 projection metadata, frozen-history notice, metric, responsive record-list, and safe async/error state components using the checked-in design tokens.
- [ ] 2.2 Implement fixture-driven employee today, records, leave/time-account, and feedback presentation with session-bound copy, safe plain text, allowed-action gating, and loading/empty/error/403/frozen states.
- [ ] 2.3 Implement the authorized management dashboard presentation with freshness/version/scope metadata, suppression labels, immutable frozen history, and version-bound drill-down references.
- [ ] 2.4 Implement the report and export presentation with bound filters/fields/version, export-purpose confirmation, synchronous/asynchronous lifecycle states, and separate creation/download action gating.
- [ ] 2.5 Register the Wave 7 route containers lazily with the fail-closed gateway and add authorized menu icons/selection without registering any prototype-only or PAYROLL route.

## 3. Responsive, accessibility, and isolation verification

- [ ] 3.1 Add responsive styles for desktop, tablet, and 360–430px employee/report layouts, including mobile cards/bottom navigation, 44px targets, no page-level horizontal scroll, and reduced-motion behavior.
- [ ] 3.2 Add component tests for ready/loading/empty/error/403/frozen/export-processing states, scope-change content clearing, keyboard-accessible names, live regions, safe text rendering, and responsive table-to-card semantics.
- [ ] 3.3 Add mechanical source/contract tests that forbid Wave 7 production imports of fixtures, demo/localStorage adapters, prototype IDs/role authorization, sensitive excluded fields, and PAYROLL discovery terms.

## 4. Independent phase gates

- [ ] 4.1 Run and pass frontend lint, unit tests, typecheck, production build, and existing PAYROLL zero-discoverability checks.
- [ ] 4.2 Validate the OpenSpec change and record an evidence note that separates completed fixture/contract UI evidence from real API, MySQL, export-worker, browser, screenshot, and performance evidence marked `NOT_VERIFIED`.

## 5. Upstream-dependent integration

- [ ] 5.1 Synchronize the finalized Wave 5 and Wave 6 commits, compare their accepted schemas/capability catalog with the Wave 7 projections, and update adapters or artifacts through an explicit reviewed delta.
- [ ] 5.2 Replace the upstream-pending gateway with real same-origin API wiring for employee, dashboard, report, feedback, export creation/status/download, and server reauthorization flows.
- [ ] 5.3 Run real API/MySQL authorization, same-version drill-down, frozen-history, export threshold/audit, browser responsive/accessibility, screenshot, performance, and production PAYROLL zero-discoverability verification.

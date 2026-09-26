## Context

The synchronized Wave 7 worktree starts at `0c09fc375b1d2973915234e50a9aac10900446ca`. It has a React/Vite application with server-supplied menu filtering, explicit route guards, shared async state components, design tokens, 360–430px responsive rules, and a production/demo runtime split. It does not yet contain the finalized Wave 5 attendance/close projections or Wave 6 leave/time-account projections on which Wave 7 depends.

The V1.9 PRD, acceptance criteria, API migration plan, and Open Design artifacts define the Wave 7 surface:

- employee routes `/me/today`, `/me/records`, `/me/leave`, and `/me/feedback`;
- authorized `/workbench` and `/attendance/reports` routes;
- session-derived employee identity, capability + scope authorization, version/freshness metadata, frozen history protection, same-version report drill-down, and controlled export;
- normal, loading, empty, error, 401/session-expired, 403, and frozen states at 360px and wider;
- PAYROLL front-end discoverability fixed at zero.

Open Design files V19-OD-02, V19-OD-12, and V19-OD-13 are interaction references only. Their role strings, demo IDs, query context, prototype JavaScript, and localStorage state are not authorization or production data sources.

## Goals / Non-Goals

**Goals:**

- Freeze testable Wave 7 route and capability contracts without trusting the server menu alone.
- Define narrow projection DTOs that expose only authorized display fields and always carry source version, data-as-of, scope, and period-state metadata.
- Build production-shaped React presentation components against synthetic contract fixtures injected only by tests.
- Cover loading, empty, error, 403, and frozen states with non-leaking copy and explicit recovery or next actions.
- Converge the employee, dashboard, and reporting layouts on the checked-in design tokens at all required breakpoints with WCAG 2.2 AA-oriented keyboard, focus, live-region, and reduced-motion behavior.
- Keep all Wave 7 route, DTO, fixture, navigation, search, export-field, and rendered text sources free of PAYROLL concepts.
- Make the missing Wave 5/Wave 6 implementation visible as an integration gate rather than fabricating a successful backend connection.

**Non-Goals:**

- No Wave 5 or Wave 6 domain implementation, migration, MyBatis mapper, service, or API completion.
- No callable Wave 7 OpenAPI paths, MySQL V12/V13 migration, export file generation, background worker, signed download, or end-to-end authorization claim.
- No demo/localStorage adapter in the normal production build and no contract fixture selected by runtime environment variables.
- No ranking comments, policy center, precise location, or large-screen attendance display implementation in this independently completable slice.
- No PAYROLL route, component, DTO field, placeholder, search term, notification, telemetry, or export column.

## Decisions

### 1. Use an explicit local route contract in addition to the server menu

Wave 7 routes are registered locally with exact capability requirements:

| Route | Route-read capability | Action capability |
|---|---|---|
| `/workbench` | `ATTENDANCE_DASHBOARD:READ` | none |
| `/attendance/reports` | `ATTENDANCE_REPORT:READ` | `ATTENDANCE_REPORT:EXPORT_CREATE` for export creation |
| `/me/today` | `ATTENDANCE_SELF:READ` | none |
| `/me/records` | `ATTENDANCE_SELF:READ` | none |
| `/me/leave` | `LEAVE_SELF:READ` | none |
| `/me/feedback` | `ATTENDANCE_FEEDBACK:READ` | `ATTENDANCE_FEEDBACK:CREATE` for submission |

The server menu is filtered through this allowlist. Direct navigation uses the same local contract and returns the generic 403 state when capability checks fail. Server-side scope remains authoritative; the client never converts a role label, department query parameter, employee ID, or menu presence into permission.

Alternative considered: trust the server menu as the sole route registry. Rejected because a malformed or over-broad menu could expose unknown routes and because direct-route 403 behavior would be inconsistent.

### 2. Separate DTO contracts, presentation components, and data gateways

Wave 7 defines:

- discriminated TypeScript projection DTOs;
- pure presentation components receiving a projection and allowed actions;
- small route containers that load through a `Wave7ProjectionGateway`;
- synthetic contract fixtures exported only from test files or test-only modules.

The production gateway for this phase returns a typed `WAVE7_UPSTREAM_PENDING` error without issuing a network request. A later synchronization change will replace it with an API gateway only after the finalized Wave 5/Wave 6 paths and schemas are available and checked against the OpenAPI contract.

Alternative considered: call the planned non-callable OpenAPI endpoints immediately or return fixtures from production. Rejected because either would misrepresent upstream readiness or put synthetic data on a production path.

### 3. Carry authorization-relevant display metadata in every projection

Every projection includes:

- `projectionVersion` and the upstream source/close or ledger version references;
- `dataAsOf` with an explicit `Asia/Shanghai` display contract;
- a server-authored `scope` label and scope type, never a client-expanded scope;
- `periodState` (`OPEN`, `FROZEN`, `CLOSED`, or `REOPENED`);
- `allowedActions`, supplied by the authorized projection rather than inferred from role names;
- a safe correlation/reference value where a retryable error or drill-down needs one.

Employee projections do not accept an employee ID. Dashboard/report projections may carry opaque scope identifiers returned by the server, but the UI does not create or widen them from URL query values.

Alternative considered: compose a page from raw Wave 5/Wave 6 entities. Rejected because that leaks domain fields, couples UI calculations to backend internals, and makes version-consistent report drill-down harder to prove.

### 4. Treat frozen data as readable, immutable versioned history

`FROZEN` and `CLOSED` projections remain readable when the capability permits. The page renders a dedicated frozen notice containing period/version and a plain-language statement that the displayed result will not be overwritten. Buttons are enabled only when the projection's `allowedActions` includes the action; the client does not guess whether feedback, export, recalculation, or adjustment is legal.

Alternative considered: map frozen data to a generic 409/error screen. Rejected because authorized users must still inspect and report on closed history.

### 5. Make export a bound query contract

An export request is a value object containing the current report projection version, scope reference, normalized filters, ordered allowed columns, purpose, and query fingerprint. The UI cannot add hidden fields. Creation requires `ATTENDANCE_REPORT:EXPORT_CREATE`; download requires a fresh server check for the matching download permission and current scope.

Row counts at or below 50,000 produce a synchronous-ready result; larger results enter an async job lifecycle. UI states are `PREPARING`, `QUEUED`, `RUNNING`, `READY`, `FAILED`, `EXPIRED`, and `REVOKED`. No file is saved until the server response supplies the authorized file.

Alternative considered: export the current DOM table. Rejected because it bypasses server scope, version consistency, field allowlists, audit, and large-result controls.

### 6. Use one semantic state layer with feature-specific detail

Shared states cover loading, empty, network/general error, 401, 403, and frozen. Feature components provide safe descriptions and actions:

- 403 does not reveal record existence, counts, scope names, or fields;
- 401 is handled by the existing session invalidation flow;
- loading clears prior sensitive projection data before showing a busy state;
- empty distinguishes “authorized but no rows” from “no permission”;
- errors show only safe correlation identifiers;
- frozen is an informational state layered with readable content, not a substitute for it.

The state layer uses headings, text, icons, and ARIA semantics; color is never the only signal.

### 7. Converge responsive behavior at the component level

- `>=1025px`: sidebar shell, multi-column metric grid, compact report table, visible filter/action region.
- `768–1024px`: navigation/filter drawers, two-column metrics, full-width detail.
- `360–430px`: employee bottom navigation with at most four items, single-column summaries, report rows rendered as labeled cards, no desktop table scaling, 44px targets, safe wrapping, and no horizontal page scroll.
- `1366`, `1440`, and `1920px`: title, context, filters, and actions do not overlap; line lengths remain bounded.

All values use the existing design-token CSS variables. Component CSS adds no raw product color. Motion is disabled under `prefers-reduced-motion`.

### 8. Enforce boundaries with source and behavior tests

Tests cover route capability matrices, direct-route 403s, fixture-only module reachability, async/frozen state rendering, mobile card/table switching, accessible names/live regions/focusability, export field binding, and a case-insensitive forbidden-term scan for PAYROLL-related front-end discovery.

The verification report distinguishes:

- contract/UI unit evidence available now;
- real API/MySQL/browser integration evidence waiting on Wave 5/Wave 6 FINAL;
- screenshot or external-environment evidence not run.

## Risks / Trade-offs

- [Upstream DTOs diverge from these projections] → Keep an adapter boundary and replace only the production gateway after synchronizing finalized Wave 5/Wave 6 schemas; do not let presentation components depend on raw upstream entities.
- [Route names or capability identifiers need backend alignment] → Treat this OpenSpec as the Wave 7 proposal contract and reconcile explicitly during upstream wiring; never silently alias a role to a capability.
- [Fixture-backed UI is mistaken for integration evidence] → Keep fixtures test-only, make the production gateway fail closed with `WAVE7_UPSTREAM_PENDING`, and label real API/MySQL evidence `NOT_VERIFIED`.
- [Frozen behavior is over-inferred by the client] → Render server-provided `allowedActions` and period metadata; do not derive action permission from status or role.
- [Responsive report density harms mobile usability] → Use labeled record cards and progressive disclosure below 430px while preserving the same fields and semantic reading order.
- [Sensitive fields leak through generic mapping] → Use explicit DTO and export allowlists; add forbidden-field and PAYROLL zero-discoverability tests.

## Migration Plan

1. Land OpenSpec artifacts and independent frontend contracts/tests on the Wave 7 branch.
2. Register route/capability guards and presentation components with the fail-closed upstream-pending gateway.
3. Run frontend lint, unit tests, typecheck/build, OpenSpec validation, responsive DOM assertions, accessibility assertions, and PAYROLL source scans.
4. After Wave 5 and Wave 6 FINAL commits are available, synchronize them into this branch and compare their accepted API/OpenAPI schemas with the projection contracts.
5. Implement the production gateway, remove only the upstream-pending adapter, and add API/MySQL/browser integration evidence without changing fixture isolation.
6. Enable routes through server-provided menus only after backend authorization and scope tests pass.

Rollback is route/feature removal plus restoration of the upstream-pending gateway. No backend data is deleted.

## Open Questions

- What exact finalized Wave 5 close-snapshot and Wave 6 ledger version identifiers will be exposed to Wave 7 projections?
- Will export download use a direct authenticated response or a short-lived same-origin token endpoint?
- What small-sample suppression threshold and label will management dashboards return?
- Can feedback against a frozen period be created as “pending reopen,” or must creation be rejected until the period is reopened?
- Which finalized server capability catalog names will be accepted for the six route/action contracts above?

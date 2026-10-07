## Why

V1.9 reserves payroll as an optional P0-B server-side branch, but the synchronized W3–W9 baseline has no enforceable PAYROLL boundary. W8 needs a narrow, independently testable reservation now so later payroll work cannot leak into ordinary capabilities or the frontend, while remaining unable to consume attendance data until W5 supplies a real closed-period snapshot.

## What Changes

- Add a standalone PAYROLL capability namespace whose authorization result is deny-by-default and whose feature flag is off by default.
- Add the server-side payroll reservation model for periods, items, employee payroll profiles, immutable attendance close-snapshot references, and calculation-result references; do not implement calculations or persist salary values.
- Define a read-only frozen-attendance-snapshot port that can accept only a real W5 closed snapshot adapter in a later integration; ship no fabricated adapter or fallback data.
- Add a guarded internal reservation service that rejects all calls while the feature is disabled and still requires explicit PAYROLL authorization when enabled.
- Add audit-safe denial semantics and negative verification for feature-off, missing capability, snapshot-integrity, Unicode-normalized discoverability, and sensitive-data/log leakage.
- Keep all frontend menus, routes, cards, search entries, copy, ordinary-user APIs, payslips, tax/social-insurance calculation, exports, and bank files out of scope.

## Capabilities

### New Capabilities

- `payroll-backend-reservation`: The isolated domain boundary, default-off/default-deny access gate, reservation service, and read-only W5 close-snapshot port.
- `wave8-verification`: Security/audit negative contracts, architecture isolation, and Unicode-normalized zero frontend discoverability for the W8 reservation.

### Modified Capabilities

None.

## Impact

- Backend-only Java packages under a new isolated `payroll` domain, application boundary, and infrastructure configuration.
- Backend configuration gains one explicit payroll reservation flag whose default is `false`.
- Authorization gains PAYROLL code definitions without granting them to any role, account, capability response, or menu.
- Tests and QA scripts gain W8 negative/security/discoverability coverage.
- No frontend source, public OpenAPI path, database migration, or W5 adapter is introduced in this independent phase. W5 close-snapshot integration remains blocked until W5 FINAL is synchronized.

## Context

W8 is an explicitly authorized, optional P0-B branch from the V1.9 roadmap. It depends on W5 for a real attendance close snapshot but is not a prerequisite for W9. The synchronized baseline is currently at W3 and already strips `PAYROLL:*` from authentication role/capability responses; it has no payroll domain package, grants, endpoint, migration, or frontend surface.

This phase must therefore establish an enforceable server-side seam without pretending that W5 has landed. The seam must be useful for later integration, safe while dormant, and impossible to discover from production/demo frontend source or ordinary capability/menu responses.

## Goals / Non-Goals

**Goals:**

- Establish a standalone `payroll` package with domain-only reservation types for payroll periods, item definitions, employee profile references, attendance close-snapshot mappings, and calculation-result references.
- Define an application-owned, read-only port for consuming an immutable W5 close snapshot.
- Fail closed in this order: feature enabled, explicit PAYROLL capability, real port available, snapshot closed and integrity-bound.
- Keep PAYROLL capabilities server-internal and ungranted by default.
- Produce deterministic security, audit, architecture, and Unicode-normalized discoverability tests.
- Allow feature-flag rollback without deleting or changing attendance data.

**Non-Goals:**

- No frontend menu, route, page, card, search term, notification, employee API, or public OpenAPI path.
- No salary amount, formula, payroll run calculation, payslip, tax, social-insurance, housing-fund, cost allocation, export, payment, or bank-file behavior.
- No database migration or seeded PAYROLL role/capability grant in this independent phase.
- No adapter, fixture, fallback, or fabricated W5 close snapshot.
- No claim that W5 integration, MySQL V13 migration, or production readiness is complete.

## Decisions

### 1. Reserve a hexagonal payroll boundary without a REST adapter

The new package follows the existing domain/application/infrastructure layering, but this phase adds no `interfaces.rest` package. Application callers enter through `PayrollReservationService`; later internal orchestration can call it without turning the reservation into an ordinary-user API.

Alternative considered: add a disabled controller. Rejected because a mapped route remains discoverable in handler metadata and expands the public contract even when every call is rejected.

### 2. Use one default-off configuration property and an explicit ordered access gate

`shenzhouhr.payroll-reservation.enabled` is a boolean whose checked-in default and environment-variable default are both `false`. The service checks the flag before authorization and integration lookup. Disabled access returns a resource-unavailable failure, records only a stable reason code, and reveals neither capability state nor integration state.

When enabled, the gate requires a server-internal `PAYROLL:RESERVATION_READ` capability. No migration or seed grants it. Ordinary capability and menu responses continue to filter every `PAYROLL:*` code even if a database row is accidentally present.

Alternative considered: rely only on capability absence. Rejected because an accidental future grant would activate dormant code without an operational feature decision.

### 3. Keep the W5 dependency as an application-owned read-only port

`FrozenAttendanceSnapshotPort` exposes lookup only; it has no create, close, reopen, update, or delete operation. The returned snapshot carries opaque IDs, the covered period, close version/time, and an integrity digest—never raw punches, exceptions, employee facts, or salary inputs.

No bean implements the port in W8. The reservation service accepts an optional adapter and fails with a stable integration-unavailable error after flag/capability checks. W5 integration will add the adapter only after W5 FINAL, with a separate integration commit and tests against W5’s actual snapshot contract.

Alternative considered: add an in-memory or zero snapshot adapter. Rejected because it would fabricate the dependency and could let an enabled service operate on non-closed attendance data.

### 4. Model references and invariants, not payroll computation or storage

Domain records validate opaque precise IDs, half-open period boundaries, unique item codes, positive immutable versions, allowed lifecycle states, SHA-256 integrity digests, and exact mapping between payroll and attendance periods. Employee profiles reference a versioned employee identity and configuration identifiers but contain no amount or statutory fields. Calculation results are opaque references plus lifecycle/digest metadata, not line items.

Alternative considered: create V13 tables now. Rejected because the baseline lacks W5/V10 and inserting a nominal V13 migration would create a false dependency chain and block independent waves.

### 5. Audit denials through a narrow payroll audit port

The application depends on `PayrollReservationAudit`, not directly on audit persistence. The infrastructure adapter records stable action/resource/result/reason values through the existing audit service. It never records commands, profile values, snapshot contents, exception text, or capability lists. Feature-off, missing-capability, missing-W5-adapter, and invalid-snapshot failures are auditable.

Alternative considered: log denials only. Rejected because logs are not the application’s durable audit ledger and are more prone to leaking request details.

### 6. Add an independent Unicode-normalized discoverability gate

A dependency-free QA script walks production/demo frontend source and built assets, normalizes text with Unicode NFKC then full case-fold, and rejects payroll/payslip/薪资/工资 patterns. Exact test-only deny vectors are outside product scan scope. It also asserts that no payroll REST controller/DTO or OpenAPI path exists and checks that W8 source contains none of the explicitly excluded payslip/tax/bank-file behavior.

The script reports only relative paths and line numbers, never matched sensitive content. Unit tests prove compatibility-width and separator variants are caught.

## Risks / Trade-offs

- [W5’s final snapshot shape differs from the reserved port] → Keep the port minimal and opaque; adapt only after W5 FINAL in a separate commit, without weakening close/integrity checks.
- [A PAYROLL capability is accidentally granted] → The feature flag still blocks access and public capability/menu serialization filters the entire namespace.
- [An operator enables the flag before W5 integration] → The service fails closed with an audited integration-unavailable result; no fallback adapter exists.
- [The zero-discoverability scanner has false negatives] → Normalize with NFKC plus case-fold, test compatibility/separator variants, scan built assets as well as source, and fail if an expected scan root is missing.
- [The scanner flags historical or test references] → Scope it to product frontend source/dist and public runtime contracts; keep exact negative vectors in test-only files.
- [Reservation models are mistaken for delivered payroll] → Names, documentation, OpenSpec progress, and unchecked W5 integration tasks explicitly retain `BLOCKED_BY_W5_FINAL`; no persistence/API/UI exists.

## Migration Plan

1. Land OpenSpec artifacts and baseline-independent tests.
2. Add the isolated domain/application/configuration/audit boundary with the flag off and no grants or adapters.
3. Run backend unit/architecture/security tests and the Unicode discoverability scan.
4. Keep W5 adapter, public contract, persistence migration, and end-to-end snapshot test incomplete until W5 FINAL is synchronized.
5. Roll back operationally by leaving or restoring `SHENZHOUHR_PAYROLL_RESERVATION_ENABLED=false`; no attendance or payroll data migration needs reversal in this phase.

## Open Questions

- What exact W5 close-snapshot identifier, close-version, reopen/supersession, and integrity-digest contract will be FINAL?
- Which later migration number is available after W5–W7 have landed? W8 must not reserve a number from the current W3-only baseline.
- Which internal caller, if any, will be authorized to use the reservation after a separately approved enablement phase?

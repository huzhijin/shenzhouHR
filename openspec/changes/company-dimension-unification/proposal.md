## Why

The legacy implementation exposes an obsolete top-level boundary vocabulary
even though the business operates and authorizes attendance data by company.
Keeping two terms would create ambiguous scope grants, misleading report
filters, and a serious cross-company authorization risk, so the platform must
adopt one company dimension end to end.

## What Changes

- **BREAKING**: Replace public legacy boundary identifiers such as
  `legalEntityId`, `LegalEntity*`, and `LEGAL_ENTITY`, plus all old
  user-visible labels, with `companyId`, `Company*`, `COMPANY`, and “公司”
  across APIs, frontend routes, DTOs, exports, audit metadata, and integration
  configuration.
- Introduce an append-only forward database migration that preserves every
  existing identifier and relationship while moving the authoritative boundary
  to company-named tables, columns, foreign keys, indexes, and scope values.
- Apply company scope before count, pagination, aggregation, detail lookup,
  mutation, export creation/status/download, and source synchronization.
- Bind employees, organizations, attendance configuration, Deli sources, OA
  evidence, calculations, report projections, and export artifacts to exactly
  one company.
- Remove implicit or mixed scope semantics: company, organization, and self are
  the only attendance data-scope types; unknown or legacy scope input fails
  closed.
- Preserve migration compatibility for stored data, but do not expose two
  parallel authorization vocabularies or let legacy aliases expand access.
- Replace current product documentation and verification contracts with
  company terminology while retaining historical evidence documents as
  historical records.

## Capabilities

### New Capabilities

- `company-dimension-boundary`: Defines the authoritative company identity,
  company-scoped authorization, cross-layer API and persistence vocabulary,
  integration binding, migration invariants, and cross-company negative
  verification.

### Modified Capabilities

None. There is no published base spec under `openspec/specs`; this change adds
the unifying boundary consumed by the existing wave capabilities.

## Impact

- Database: new forward Flyway migration after V10, H2 test schema, fixtures,
  constraints, indexes, scope catalog values, and retained-data verification.
- Backend: identity/access, organization, people, attendance setup,
  evidence/OA/Deli ingestion, calculation, reporting, exports, MyBatis rows and
  SQL.
- Contract: `api/openapi.yaml`, request/response DTOs, validation errors, audit
  metadata, idempotency and digest inputs.
- Frontend: types, gateways, account role scopes, company selectors, report
  queries/exports, setup/source/import pages, copy, and tests.
- Operations and documentation: environment variable descriptions, local
  bootstrap naming, handoff/sign-off documents, and release source contracts.

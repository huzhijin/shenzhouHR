## 1. Contract and migration

- [x] 1.1 Add V11 forward migration that renames the authoritative table, all current foreign-key columns, and top-level scope values to company terminology without changing stable IDs or rows.
- [x] 1.2 Update the latest H2 schema and retained migration/schema contract tests for empty-to-latest and populated V10-to-V11 company migration invariants.
- [x] 1.3 Update OpenAPI parameters, schemas, examples, errors, and strict-unknown-field tests from legal-entity to company contracts.

## 2. Backend company boundary

- [x] 2.1 Rename identity/access, organization, people, and role-scope runtime models and services to `Company`, `companyId`, and `COMPANY`.
- [x] 2.2 Rename attendance setup, source/import, Deli/OA evidence, and calculation runtime contracts to the company boundary.
- [x] 2.3 Rename reporting projection, directory, query, export, digest, and persistence contracts to the company boundary.
- [x] 2.4 Update every active MyBatis mapper and SQL predicate to use the latest company schema and apply company filtering before count, pagination, aggregation, selection, and mutation.

## 3. Authorization verification

- [x] 3.1 Update the role-scope matrix so company HR uses `COMPANY`, department roles use `ORGANIZATION`, employees use `SELF`, and legacy/unknown scope values fail closed.
- [x] 3.2 Add cross-company negative tests for account grants, employees, setup, ingestion, reporting, and export create/status/download with no existence or count disclosure.

## 4. Frontend company contract

- [x] 4.1 Rename production TypeScript DTOs, gateways, filters, role-scope policy, and request parameters to `companyId` and company directory contracts.
- [x] 4.2 Replace active UI labels, selectors, validation, empty states, and accessibility names with company terminology and reject legacy response/request shapes.
- [x] 4.3 Update frontend unit and contract fixtures for single-company, multi-company explicit selection, and cross-company fail-closed behavior.

## 5. Current documentation and residual control

- [x] 5.1 Update README, current V1.9 handoff, formula, OA/Deli, exception, and projection-wiring documents to the company boundary while preserving immutable historical evidence.
- [x] 5.2 Add a bounded residual vocabulary test that allows only pinned historical migrations (including the exact V9 compatibility correction) and historical evidence/oracle paths.

## 6. Verification and delivery

- [x] 6.1 Run strict OpenSpec validation, backend targeted/full tests, frontend typecheck/lint/full tests/build, migration/source scans, and W8/W9 retained regression.
- [x] 6.2 Review the final diff for cross-company authorization regressions, document any genuine external blockers, and create an intentional Git commit without staging user-owned files.

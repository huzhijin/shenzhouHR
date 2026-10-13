## Context

The current implementation uses `legal_entity` as the top-level tenant,
configuration, employee, source, authorization, report, and export boundary.
For this product those rows already represent companies, but the legal-entity
name leaks through SQL, Java, OpenAPI, TypeScript, UI copy, tests, and current
handoff documents. The business has now explicitly selected company as the
only top-level dimension.

The change is security-sensitive because a superficial label replacement would
leave two scope vocabularies that could be combined or interpreted differently.
Published Flyway V1–V8 and V10 files are immutable and the current stable
identifiers must be retained. Real MySQL 8.4 verification proved that V9 could
not parse its unquoted `row_number` identifier; V9 therefore has one explicit,
pinned quoting correction and requires controlled checksum reconciliation for
any database that already records V9.

## Goals / Non-Goals

**Goals:**

- Make company the only public and runtime top-level business dimension.
- Preserve every existing identifier, row relationship, and historical fact
  through an append-only V11 migration.
- Rename the physical latest schema, backend model, API, frontend, and active
  documentation consistently.
- Keep company, organization, and self scopes fail-closed and prevent
  cross-company reads, mutations, counts, exports, or source ingestion.
- Prove migration and authorization behavior with H2 contracts, retained
  Flyway checks, backend integration tests, and frontend contract tests.

**Non-Goals:**

- Rewriting or changing checksums of V1–V8 or V10 migrations, or changing V9
  beyond the exact MySQL 8.4 reserved-identifier quoting correction.
- Merging companies, changing stable IDs, or introducing group/subsidiary
  consolidation.
- Treating an organization as a company or inferring company from a display
  name.
- Maintaining two live authorization vocabularies.
- Completing unrelated OA business-contract or automatic-settlement blockers.

## Decisions

### 1. Company is a semantic and physical rename, not an alias

V11 renames `legal_entity` to `company`, its identifier to `company_id`, every
current foreign-key column to `company_id`, and `LEGAL_ENTITY` scope values to
`COMPANY`. Application SQL after V11 uses only the company names.

An alternative was to expose `companyId` while retaining `legal_entity_id`
internally. It was rejected because it would leave security reviews, SQL
filters, diagnostics, and future migrations with two meanings for one boundary.

### 2. Stable identifiers and relationships are retained one-to-one

Every old identifier becomes the company identifier byte-for-byte. V11 performs
only table/column/value renames and constraint replacement; it does not copy,
merge, trim, regenerate, or delete business data. Row counts and ordered ID
digests before and after migration must match.

The exact boundary topology is 29 boundary tables and 29 boundary columns, with
28 dependent tables contributing 29 foreign-key relationship edges. Some
company columns participate in composite foreign keys to an already
company-bound parent (for example, a report fact to its projection), so
preflight verifies the exact table-to-parent relationship set rather than
incorrectly requiring every edge to point directly to the company table.

An alternative new company directory with mapping rows was rejected because it
would introduce nullable or ambiguous mappings and allow mixed-scope access.

### 3. The public contract is intentionally breaking

OpenAPI and frontend use `companyId`, `companyName`, company directory types,
and `COMPANY`. Legacy JSON properties, query parameters, and scope values are
rejected as unknown input rather than accepted as aliases.

This avoids ambiguous requests containing both old and new properties. Stored
data compatibility is provided by V11, not by parallel API semantics.

### 4. Scope enforcement remains server-side and precedes observation

The authorization model has exactly three attendance data-scope types:
`COMPANY`, `ORGANIZATION`, and `SELF`. Company scope applies to the exact
company; organization scope is first proven to belong to one company and then
applies to its allowed current closure; self uses the session-bound employee.

All list counts, pagination, aggregation, object lookup, mutation, export, and
integration ingestion include the same company predicate. Role names never
expand company access.

### 5. Historical artifacts are not rewritten

Published V1–V8 and V10 migrations plus frozen historical verification
artifacts retain their original terms and hashes. V9 retains its schema and
data semantics but pins the corrected quoted `row_number` identifier and its
new checksum; an existing V9 history entry cannot be silently repaired.
Runtime source, latest test schema, OpenAPI, frontend, current handoff/sign-off
documents, and new verification artifacts must not expose the old boundary
vocabulary. Residual scans use an explicit historical allowlist.

### 6. Deployment is forward-only

The release sequence is backup and authority proof, migrate through V11,
validate retained IDs and scope values, then deploy the company-contract
application. After V11, an older application is schema-incompatible; rollback
uses a forward repair/candidate replacement, never reverse SQL or Flyway clean.
Application-startup Flyway is disabled by default so an existing database
cannot bypass the exact V10 company cutover preflight. Approved migration
harnesses stop at V10, verify the complete boundary topology and absence of
in-flight work, then explicitly target V11.

Frozen policy snapshots, completed idempotency payloads, and stored report
digests are historical evidence and are not rewritten just to change a label.
Before cutover, operations must prove that no setup or ingestion idempotency
row remains in a started state. A pre-V11 request replay conflicts with the new
canonical request digest (or otherwise fails closed), and a pre-V11 report
export is denied once its freshly calculated company authorization/content
digests differ; the caller must create a new export. No compatibility path may
relax digest comparison or return an old artifact.

## Risks / Trade-offs

- [Breaking API clients] → Version the release, fail legacy properties clearly,
  and update the only checked-in frontend atomically.
- [Missed SQL column causes runtime failure] → Exact source scans, Mapper/XML
  tests, empty-to-latest and V10-to-V11 migration tests.
- [Scope literal changed but role assignment not migrated] → Drop the old shape
  check, update every old value in one migration, add the new exact check, and
  assert zero legacy values.
- [Cross-company leakage during mixed deployment] → Do not support mixed old/new
  application versions; migration and application deploy form one maintenance
  window.
- [Historical evidence scan noise] → Allow only pinned migration/history
  locations and fail on runtime/API/frontend/current-doc occurrences.
- [V9 checksum differs on an existing database] → Stop before migration,
  compare the actual V9 schema and old/current checksums, and require an
  approved reconciliation; never run an automatic Flyway repair.
- [Physical constraint names retain old wording after column rename] → V11
  replaces security-relevant scope constraints; other historical constraint
  names may remain only as non-semantic database metadata unless a safe exact
  rename is supported.
- [Started idempotency work crosses the maintenance window] → Require zero
  started setup/ingestion rows before V11; do not replay or silently translate
  an in-flight old request after cutover.
- [A stored export was created with the pre-V11 authorization digest] → Keep
  the stored evidence unchanged and fail status/build/download closed; require
  a new export under the current company scope.

## Migration Plan

1. Freeze source, verify the pinned V9 checksum policy, and capture a database
   backup plus server/environment identity.
2. Run V11 preflight against an exact V10 schema and verify no unexpected scope
   values, orphan company references, or started setup/ingestion idempotency
   rows.
3. Apply table and column renames, migrate `LEGAL_ENTITY` to `COMPANY`, and
   replace the scope shape constraint.
4. Validate row counts, stable ID digests, foreign keys, indexes, and zero
   legacy scope values.
5. Deploy the company-contract backend and frontend together.
6. Run cross-company read/mutation/export/source negative tests, prove old
   export artifacts fail closed, and run current vocabulary scans.
7. If validation fails, stop before cutover and issue a forward repair
   migration; do not reverse or clean the database.

## Open Questions

None for the dimension definition. The user explicitly selected company as the
authoritative boundary. Existing unrelated OA field semantics and calculation
formula decisions remain separately gated.

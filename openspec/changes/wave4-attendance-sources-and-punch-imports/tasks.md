## 1. Prerequisite, retained baseline and RED contracts

- [ ] 1.1 Verify a current W3 final manifest/integrity result, freeze its source hash and V1～V7 checksums as the W4 prerequisite, and reject every invalidated/historical/partial upstream run.
- [ ] 1.2 Create a review-owned W4 schema registry and retained canonical fixtures that are independent of V8/V9, H2, Mapper and live information_schema.
- [ ] 1.3 Add RED migration tests for empty→latest, same-DB V7→V8→V9→latest, V1～V7 checksum immutability, registry exactness and second-migrate no-op.
- [ ] 1.4 Add RED domain/HTTP tests for AC-PUNCH-01～15, AC-CALC-01/02/04 and AC-SOURCE-01 before implementing W4 behavior.
- [ ] 1.5 Freeze W4 OpenAPI paths, closed DTOs, state/reason enums, multipart parts, headers, errors, pagination and correlation contracts with bidirectional-closure RED tests.
- [ ] 1.6 Add module/ArchUnit RED tests enforcing controller→application→domain/port→adapter direction and forbidding vendor DTO, POI row, MultipartFile or W5 aggregate dependencies in W4 domain.

## 2. V8 source and shared evidence persistence

- [ ] 2.1 Add `V8__attendance_source_and_evidence.sql` with stable source identities, immutable configuration revisions, jobs/pages, committed watermarks and required named constraints/indexes.
- [ ] 2.2 Add V8 device identities/revisions and effective-dated device-person/external-person bindings with overlap, legal-entity and location integrity tests.
- [ ] 2.3 Add append-only raw fact, immutable normalization revision and employee-match-decision tables with exact string external IDs, canonical digests and query indexes.
- [ ] 2.4 Add immutable effective event, append-only lifecycle, evidence link, OA document and source reversal tables with cardinality/integrity constraints.
- [ ] 2.5 Add duplicate review group/member/resolution tables that can prove pending=0 active, same=1 active and distinct=N active without deleting raw facts.
- [ ] 2.6 Add evidence interval slice, recalculation intent, evidence-subject lock and durable ingestion-idempotency tables with deterministic key/index contracts.
- [ ] 2.7 Mirror the exact V8 registry in H2 test schema, Java rows/domain records, MyBatis Mapper/XML and repository ports; fail on unknown/missing/reordered columns or multi-row single-result queries.
- [ ] 2.8 Add runtime-account/Mapper guards proving raw facts, event lifecycle, reversals, resolutions, slices and intents have no UPDATE/DELETE business path.

## 3. Source ports, adapters, jobs and watermarks

- [ ] 3.1 Implement stable source/configuration aggregates and service ports using repository-external secret references without storing or returning credential values.
- [ ] 3.2 Implement `DeliPunchSourcePort` canonical pages and a deterministic synthetic adapter covering long IDs, timezone, device/location evidence and forbidden biometric payloads.
- [ ] 3.3 Implement `OaAttendanceDocumentSourcePort` canonical pages and versioned status/type mapping for approved, draft, rejected, unknown, modified, supplemented and revoked documents.
- [ ] 3.4 Implement page-transaction ingestion that locks source/watermark, commits raw→quarantine/effective outcomes and advances watermark only with the complete page.
- [ ] 3.5 Implement job states, page counts/digests, freshness, safe error summaries and bounded stable list/detail pagination.
- [ ] 3.6 Implement durable start/retry idempotency, backoff/rate-limit behavior, cursor regression/loop rejection and safe retry from the last committed watermark.
- [ ] 3.7 Implement quarantined-row handling that can advance a valid page while transport/whole-page parse/database failures cannot advance it.
- [ ] 3.8 Prove adapters expose no organization sync, upstream write-back, source delete or biometric-template persistence operation.
- [ ] 3.9 Emit separate contract-stub and live-integration status fields so fixture PASS can never be relabeled as real 得力/OA联调 PASS.

## 4. Unified normalization, matching, events and evidence

- [ ] 4.1 Implement point-in-time employee/employment resolution: employee number first, then effective confirmed binding, with name/department comparison-only and zero/multiple fail closed.
- [ ] 4.2 Implement authoritative W3 timezone/group/shift resolution for punch instants and candidate business dates, including cross-midnight and UNKNOWN/no-group quarantine.
- [ ] 4.3 Implement canonical point/interval normalization preserving source time/timezone, UTC instant, `[start,end)` semantics, schema version and supersession.
- [ ] 4.4 Implement source-key/version and no-source-ID stable-fingerprint idempotency with exact canonical byte/digest tests.
- [ ] 4.5 Implement order-independent Excel/得力 exact duplicate merging that retains both raw facts and produces exactly one active event with both evidence links.
- [ ] 4.6 Implement 1～60-second near-duplicate grouping, including 0/1/60/61 boundaries and atomic retraction of an already active member event.
- [ ] 4.7 Implement immutable `SAME_FACT`/`DISTINCT_FACTS` review with If-Match, durable idempotency, reason/audit and exact 1/N event cardinality.
- [ ] 4.8 Implement OA source-version append, current-knowledge ordering, modification/revocation/reversal lifecycle and out-of-order-version behavior.
- [ ] 4.9 Implement deterministic OA boundary splitting and fixed evidence-priority slices; same-level mutual exclusion becomes `EVIDENCE_CONFLICT` with no last-write winner.
- [ ] 4.10 Implement permission-aware event evidence trace from raw fact through normalization, match, lifecycle, duplicate/reversal, slice and intent with field redaction.
- [ ] 4.11 Implement fail-closed period-protection provider and narrow recalculation-intent port; protected/unknown periods cannot activate import evidence or request calculation.
- [ ] 4.12 Implement fixed lock order and locked second checks for source, employee subject, source key, duplicate group and intent mutations.
- [ ] 4.13 Add real-MySQL double-thread tests for exact/near duplicates, incompatible reviews, source replay, reversal, deadlock retry and all-or-nothing rollback.

## 5. V9 XLSX template, mapping and import lifecycle

- [ ] 5.1 Add `V9__attendance_punch_import.sql` with immutable mapping versions, batch/file/row/attempt/issue/precheck/publication/state/error-report objects and the independent registry's constraints/indexes.
- [ ] 5.2 Produce the versioned six-sheet template with 14 punch fields, 9 device-mapping fields, synthetic examples, machine-readable template version and canonical field-contract digest.
- [ ] 5.3 Implement `.xlsx` file policy and streaming parser for extension/MIME/content parity, OOXML allowlist, 20 MiB/50,000-row limits, ZIP bounds and formula/macro/external/OLE/DDE rejection.
- [ ] 5.4 Implement immutable mapping profile versions with only registered column/date/timezone/trim/enum transforms and no script, formula, arbitrary expression or name-only identity rule.
- [ ] 5.5 Implement upload/DRAFT creation, opaque stored-object metadata, legal-entity/source/location scope and filename-independent SHA-256 identity.
- [ ] 5.6 Implement immutable normalization attempts and row staging for all rows, including rows outside workbook style/table ranges.
- [ ] 5.7 Implement precheck matching, issues, exact/near duplicate preview, period/config checks, counts, affected employee/date preview and downloadable error-report generation.
- [ ] 5.8 Implement precheck token digest binding batch/file/mapping/window/resolver/period/row-result versions, and invalidate it on any relevant change.
- [ ] 5.9 Implement the exact append-only batch state machine with distinct `VALIDATION_FAILED`, `BLOCKED_BY_FROZEN_PERIOD` and `PUBLISH_FAILED` transitions and retry prerequisites.
- [ ] 5.10 Implement strict one-transaction publication that rejects every blocker and commits publication/raw/events/intents/success audit together.
- [ ] 5.11 Implement separately authorized, explicitly confirmed partial publication with `PARTIALLY_PUBLISHED`, exact reconciled counts and retained invalid rows.
- [ ] 5.12 Implement frozen/closed publication protection and mandatory new precheck after a period-version change or reopen.
- [ ] 5.13 Implement published-batch void/reverse as append-only reversal/lifecycle/intents/state/audit with no physical delete or restoration.
- [ ] 5.14 Implement batch/row/file/error-report list/detail/download APIs with stable pagination, no-store downloads, separate permissions and access audit.
- [ ] 5.15 Add exact boundary/hostile workbook tests for size/row limit±1, formula cached values, macro/external links, ZIP abuse, forbidden result columns and empty/misleading MIME.

## 6. Authorization, audit, storage and API closure

- [ ] 6.1 Add the exact `ATTENDANCE_SOURCE:*` and `ATTENDANCE_PUNCH_IMPORT:*` capability catalog without granting raw attendance access through SYSTEM_ADMIN or another technical role by default.
- [ ] 6.2 Implement SQL-level legal-entity/location/attendance-group/organization scope in every W4 list, detail, download and mutation before pagination/count/data selection.
- [ ] 6.3 Implement source/config/device/device-person-binding/document/quarantine/source-job endpoints with strict DTOs, state, ETag, idempotency, stable pagination and safe failure responses.
- [ ] 6.4 Implement mapping/template/import batch/mapping/precheck/preview/error/error-report/file/row/publish/void/recalculation-intent endpoints and exact multipart/status/header behavior.
- [ ] 6.5 Implement event-evidence and duplicate-review list/detail/resolve endpoints with field-level raw/location authorization.
- [ ] 6.6 Implement CSRF, `Idempotency-Key`, `X-Change-Reason` and strong `If-Match` enforcement with committed-response replay and changed-digest 409.
- [ ] 6.7 Implement success/failure/access audits for source config/jobs, upload/precheck/publish/partial/void, duplicate review, raw file/row, error report and intents without sensitive payload.
- [ ] 6.8 Implement `StoredObjectPort` and `MalwareScanPort` contracts plus fail-closed local/test adapters using opaque refs, safe paths and repository-external storage.
- [ ] 6.9 Close Controller/OpenAPI/Java/TypeScript/database enums in both directions, including unknown fields, nullability, 401/403/404/409/413/415 and correlation headers.

## 7. W4 React routes and runtime modes

- [ ] 7.1 Generate/implement typed W4 clients from the final OpenAPI, including ETag/idempotency/multipart, stable pagination and exact source/job/import/review state unions.
- [ ] 7.2 Register `/sources/online`, `/sources/oa`, `/sources/jobs`, `/sources/attendance-excel` and `/sources/attendance-excel/:batchId` with capability-based route/menu/breadcrumb/redirect and real 404 behavior.
- [ ] 7.3 Build source overview, OA metadata and jobs/watermark/retry pages using real normal-mode APIs and safe read-only/detail boundaries.
- [ ] 7.4 Build import list, template download and upload/DRAFT flow with file policy guidance and separate action capabilities.
- [ ] 7.5 Build mapping editor, precheck progress, counts, impact preview, issues and confirmation UI driven only by server state/tokens.
- [ ] 7.6 Build batch/row/evidence/error-report detail plus strict/partial publish and void/reverse high-risk dialogs with stale/frozen failure recovery.
- [ ] 7.7 Build pending duplicate-review list/detail and immutable SAME/DISTINCT resolution UI with reason, If-Match and exact resulting cardinality.
- [ ] 7.8 Cover loading, empty, network/error, session-expired, 403, 404, stale, validating, validation-failed, frozen, publishing, partial, publish-failed, voided and success states.
- [ ] 7.9 Enforce role×capability×scope behavior for HR_ADMIN, AUDITOR and SYSTEM_ADMIN, with no read-only mutation affordance or client role-string authorization.
- [ ] 7.10 Pass keyboard/focus/axe/semantic-token/Tabler-2px checks and 360/390/430 responsive mapping/table/dialog behavior without page-level overflow.
- [ ] 7.11 Add an explicit synthetic demo adapter that short-circuits before API, separate prod/demo builds, visible demo labeling and no production import of demo datasets.

## 8. Regression, MySQL, browser and final evidence

- [ ] 8.1 Pass W4 unit/domain/Mapper/HTTP/security/state/idempotency/file/evidence/source-adapter tests for every named AC-PUNCH/CALC/SOURCE scenario.
- [ ] 8.2 Pass backend full plus separately recorded W1, W2 and W3 regression suites with unchanged retained public/schema/API behavior.
- [ ] 8.3 Pass frontend typecheck, lint and tests as separate commands, including route, API, state, permission, accessibility and runtime-mode tests.
- [ ] 8.4 Pass separate production and demo builds with newer-than-source inventories and production demo-dataset absence.
- [ ] 8.5 Pass the actual served XLSX/template/parser contract and 得力/OA synthetic consumer contract matrices.
- [ ] 8.6 On isolated exact MySQL 8.4.10, pass empty→latest, V7→V8→V9→latest, validate, no-op, W1～W3 retained and registry/schema checks without changing existing MySQL 8.0 state.
- [ ] 8.7 On that 8.4.10 instance, pass app-DML/denied-DDL/cross-schema, FK/CHECK/unique/index, action/scope negatives, append-only, concurrency, rollback and query-plan tests.
- [ ] 8.8 Run normal browser acceptance against real backend/MySQL at 360×800, 390×844, 430×932, 768×1024, 1024×768, 1366×768, 1440×900 and 1920×1080 for HR_ADMIN, AUDITOR and SYSTEM_ADMIN boundaries.
- [ ] 8.9 Prove demo business-network count and DB delta are zero, while normal mode shows current real API/DB markers and no demo data.
- [ ] 8.10 Pass Unicode-normalized PAYROLL/organization-sync zero discoverability, secret/sensitive-data scan, W5-scope-zero and safe error/log scans.
- [ ] 8.11 Freeze source, create a fresh runId and execute the exact 24 required pre-review leaf gates with current source/DB identity and non-empty raw evidence.
- [ ] 8.12 Build the one-way artifact registry/matrix/report and obtain a distinct read-only `W4-VER-INDEPENDENT-REVIEW` over exactly those 24 leaf gates.
- [ ] 8.13 Create the final manifest plus read-only `W4-VER-EVIDENCE-INTEGRITY`, derive PASS only from the exact 26-ID set, apply detached checkbox completion and prove normalized source hash unchanged.
- [ ] 8.14 Report contract-stub PASS separately from `DELI_LIVE/OA_LIVE/PRODUCTION_FILE_STORAGE=NOT_VERIFIED`, and hand off W5 without implementing W5 aggregates in this change.

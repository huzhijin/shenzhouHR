## 1. Contract and persistence foundation

- [ ] 1.1 Restore the exact W2 V4 generic-policy Java/H2/OpenAPI contract with no company dimension field, original unique/effective constraints and unchanged V1-V6 source/checksums; add independently derived W2 schema/API/full-row retained red tests.
- [ ] 1.2 Start with RED tests proving current `Wave3MigrationContractTest` ALTER/seed-W2 assertions invalid, then rewrite the never-persisted exact file `backend/src/main/resources/db/migration/V7__attendance_setup_and_base_policies.sql` to create only W3 identity/revision/timeline and independent scoped-policy aggregates; never ALTER W2 `policy_version`, never add scoped-version `status`, and derive lifecycle state from facts.
- [ ] 1.3 Start with RED tests proving current V7/H2 single `attendance_policy_binding` invalid, then mirror the fixed review-owned all-W3 registry including `attendance_policy_binding_family/revision`, every timeline, FK/CHECK/index in H2, Rows, Mapper/XML and domain DTOs.
- [ ] 1.4 Revise OpenAPI for final independent W3 routes and strict DTOs; implement exact bidirectional Controller closure for method/path/query/header/status/request/response/nullability/unknown fields and all resolved refs.
- [ ] 1.5 Implement the independent fixed seed oracle with exact three kinds/IDs/scopes/PUBLISHED v1 canonical JSON/golden digests and no aggregate-selected company or V7-self-derived expected values.

## 2. Attendance groups, locations and assignments

- [ ] 2.1 Implement stable location/group identities, immutable content revisions and append-only timelines with historical timezone snapshots and exactly-one business-date resolution.
- [ ] 2.2 Implement atomic group create/enable that provisions exactly one stable `(group identity, kind)` family and one group-revision-referencing revision for MEAL_DEDUCTION, LATE_GRACE and MONTHLY_LATE_EXEMPTION in one transaction or rolls all state/success audit back.
- [ ] 2.3 Implement group/location future rollover/deactivate with every location-referencing group path taking location identity/current-revision locks first; location rollover holds them across complete group enumeration, binary sort, locks and exact second-set validation before the established per-group lock order, then atomically coordinates all successors or rolls everything back.
- [ ] 2.4 Implement immutable assignments with cross-entity/inactive/configuration rejection, adjacent support, ambiguity detection, stable pagination and employee-month continuity.
- [ ] 2.5 Implement durable assignment/group idempotency and H2/MySQL same-key exact replay only for committed success, safe retry after failed rollback, changed-reason conflict and different-key single-winner tests with loser-only failure audit.

## 3. Shift versions and work calendars

- [ ] 3.1 Implement stable shift families, immutable versions and append-only publication timeline with locked version allocation, overlap/gap rejection and history-safe future deactivation.
- [ ] 3.2 Implement IANA `startDayOffset/endDayOffset` multi-segment validation and 20:00→04:00 golden plus zero-length/order/overlap/WORK-required negatives without hardcoded offset.
- [ ] 3.3 Implement stable calendar families, immutable effective/year versions/days and append-only publication timeline with complete-date, overlap/gap and atomic same-year rollover checks.
- [ ] 3.4 Implement immutable PATCH/upsert draft days and optional compatible explicit shift override; default working days resolve exactly one group-family PUBLISHED shift.
- [ ] 3.5 Prove two groups can resolve distinct calendar/shift families on one date, year boundaries are independent, and all old-date content/digests remain byte-identical after future publications.
- [ ] 3.6 Add bounded stable pagination to every shift/calendar/version/day list and exact route/schema contracts.

## 4. Attendance scoped policies and authoritative simulation

- [ ] 4.1 Begin with RED tests proving current `AttendancePolicyMapper`/XML and lifecycle Controller/OpenAPI W2 `policy_*`/`PolicyDtos` reuse invalid; implement an attendance-only repository/service/facade with independent strict DTOs.
- [ ] 4.2 Implement exact-three stable `(group identity,kind)` binding families and exactly-one business-date revision resolution with no priority winner model, plus group/version containment and canonical impact-preview binding.
- [ ] 4.3 Implement durable draft/edit/validate/publish/future-deactivate/rollback idempotency with locked If-Match, exact status/headers/body replay only for committed success, safe retry after failed rollback, changed reason conflict and independent failure audit.
- [ ] 4.4 Add stale/replay/same-key/different-key lifecycle and binding concurrency tests in HTTP/H2 and real MySQL current-read/lock tests; loser writes no success audit.
- [ ] 4.5 Implement server-authoritative simulation request containing only employee/date/correctionAsOf/typed punches; reject client lateMinutes/usage/policy/shift/calendar/offset assertions.
- [ ] 4.6 Treat typed punches as hypothetical non-recordedAt inputs; apply correctionAsOf only to server configuration/authoritative usage knowledge, implement IANA cross-midnight/meal calculation and the complete LATE_GRACE→MONTHLY_LATE_EXEMPTION truth table/consistency/order, derive 0/1/15/16 boundaries and preserve employee-month usage across group changes.
- [ ] 4.7 Prove every simulation transaction has database delta zero for raw facts, formal day/month results, official usage and formal success audit.

## 5. Attendance setup user interface and runtime

- [ ] 5.1 Align typed clients with final strict OpenAPI routes, company lifecycle, pagination, impact token and authoritative simulation; normal mode uses real backend/proxy and demo short-circuits before network.
- [ ] 5.2 Build group/location immutable history and atomic rollover/assignment UI with loading/empty/403/404/409/stale/replay/success states and no read-only manage affordance.
- [ ] 5.3 Build shift/calendar family/version/timeline UI with dual offsets, gap/overlap/completeness, PATCH days, override issues and byte-stable history visibility.
- [ ] 5.4 Build scoped-policy lifecycle/binding UI displaying real status/issues/conflicts/impact and server simulation; AUDITOR has no simulation/impact/manage action.
- [ ] 5.5 Close `/`, `/login`, `/rules*`, four W3 routes, menu/breadcrumb/longest-prefix/redirect/real-404 behavior for role×capability matrix; authorized menu never filters empty.
- [ ] 5.6 Semantically review governance edits, real a11y/keyboard/axe and upload MIME/content parity; remove mechanical `Object.is`/`Array.from`/string-splitting evasions.

## 6. Verification, regression and evidence

- [ ] 6.1 Pass the independently fixed W2 Java public signature/model/repository/lifecycle/DTO/OpenAPI/Mapper/H2 schema oracle/checksum, W2 schema/API/full-row retained gates and all W1/W2 backend regression before treating any W3 narrow test as compatible.
- [ ] 6.2 Pass exact Controller/OpenAPI closure, strict schema instances, W3 unit/mapper/HTTP/auth/history/idempotency/concurrency/failure-audit/simulation tests.
- [ ] 6.3.1 Verify the independently versioned review-owned W2 structured parser/export/exact-diff fixture/extractor and all-W3 table/PK/ordered-column/logical-type registry; implement multi-table/multi-row length-prefixed NULL/empty/delimiter/Unicode/JSON/decimal/time/boolean/binary golden field/row/line/final hashes under distinct locale/timezone settings without deriving expected from actual schema.
- [ ] 6.3.2 Make outer W3 orchestration runId/DB-identity bound, capture W2 before plus W3 target7 snapshots before any W2 child migrates latest, and prove target7→latest remains ordered with V8+.
- [ ] 6.3.3 Execute actual inherited W2 static/runtime/evidence/smoke gates; reject historical/invalidated/null evidence and syntax-only wrappers.
- [ ] 6.3.4 Enforce Unicode-normalized PAYROLL zero-discoverability only across product/user-discoverable source/dist/OpenAPI/REST controller/runtime scopes, with a strict minimum QA marker/report allowlist and real-role normal runtime probes.
- [ ] 6.4.1 Verify empty→latest and same-DB V6→target7→latest with V8+ tolerance, immutable V1-V6 checksums, target7 oracle and repeat/no-op fingerprints.
- [ ] 6.4.2 Build/install/start official MySQL exactly 8.4.10 in isolation on 127.0.0.1:13306 after tarball SHA256 verification; reject every other 8.4.x and record existing 8.0.34 PID/version/socket/datadir/service before/after byte-identical.
- [ ] 6.4.3 On that 8.4 instance verify every W3 FK/CHECK/index/ENFORCED/chosen key/current lock, app transactional DML and denied DDL/GRANT/cross-schema/AUDITOR manage grants.
- [ ] 6.5.1 Produce `W3-VER-BACKEND-FULL`, `W3-VER-W1-REGRESSION`, `W3-VER-W2-REGRESSION` and `W3-VER-W3-REGRESSION` from separate frozen-source executions with their unique markers/artifacts/failure semantics.
- [ ] 6.5.2 Produce `W3-VER-FRONTEND-TYPECHECK`, `W3-VER-FRONTEND-LINT` and `W3-VER-FRONTEND-TESTS` from separate frozen-source executions.
- [ ] 6.5.3 Produce separate newer-than-source `W3-VER-PROD-BUILD` and `W3-VER-DEMO-BUILD` inventories and `W3-VER-DEMO-ISOLATION` proof of zero business network/MySQL.
- [ ] 6.5.4 Produce current-run/current-source/current-DB `W3-VER-W2-CURRENT-SMOKE`; historical wrapper evidence is forbidden.
- [ ] 6.5.5 Run `W3-VER-NORMAL-BROWSER` against real backend/database at exactly 390x844, 768x1024, 1024x768, 1366x768, 1440x900 and 1920x1080 for only V2 real roles SYSTEM_ADMIN, HR_ADMIN and mandatory read-only AUDITOR, covering route/menu/breadcrumb/redirect/HTTP/UI/keyboard/axe and real API/DB markers.
- [ ] 6.6.1 Freeze spec/source/migration, compute bounded symlink-safe source tree hash with tasks checkbox-token normalization, create a fresh non-reused runId and detached completion metadata; any other later source change invalidates the run.
- [ ] 6.6.2 Produce the exact DAG: 20 pre-review leaf gates/artifacts → registry → mutually non-hashing matrix/report → review → detached manifest hashing registry/matrix/report/review/metadata → post-manifest integrity; reject cycles, backward hash, NOT_VERIFIED or null.
- [ ] 6.6.3 Produce `W3-VER-INDEPENDENT-REVIEW` by a read-only process distinct from implementation, challenging exactly the fixed 20 pre-review IDs and raw evidence, never itself/manifest/integrity/FINAL, without waiver authority.
- [ ] 6.6.4 Derive `W3_FINAL_INDEPENDENT_ACCEPTANCE=PASS` only from the exact 22-ID dependency set in verification spec, then apply detached checkbox completion and prove normalized source hash unchanged.
- [ ] 6.6.5 Keep `w3-20260726-1917` permanently `INVALIDATED_NON_FINAL`; after true W3 PASS do not modify/archive into W4 here, but automatically hand off the next W4 change.

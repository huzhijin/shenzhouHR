## 1. Reusable Calculation Core

- [x] 1.1 Make the existing full calculation orchestrator contract explicitly side-effect-free and reuse it as the realtime and optional publication calculation core
- [x] 1.2 Return daily facts, OA report facts, time-account facts, source versions, data cutoff, and deterministic complete-output digest without creating a report projection
- [ ] 1.3 Keep the optional publication path on the same calculation service and prove realtime and publication calculations have identical business values for the same snapshot
- [ ] 1.4 Preserve fail-closed behavior for ambiguous identity, attendance group, shift, calendar, policy, OA status, and unresolved leave revocation

## 2. Authorized Batch Inputs

- [x] 2.1 Resolve the current principal's report capability and effective COMPANY, ORGANIZATION, descendant, and SELF scope before loading calculation inputs
- [x] 2.2 Keep bounded company-period batch queries for employee identities, assignments, attendance groups, shifts, calendars, Deli punches, punch corrections, exemption roles, and OA documents
- [x] 2.3 Ensure every source query is constrained by company with no per-employee or per-day query loop, and filter the immutable facts by the freshly resolved employee/organization scope before aggregation
- [ ] 2.4 Add a lightweight input-version query covering Deli/OA committed watermarks and all personnel/configuration versions used by the calculation
- [x] 2.5 Generate a deterministic snapshot token and expose the Deli/OA cutoffs and calculation data-as-of time

## 3. Realtime Report Service

- [x] 3.1 Implement a realtime report query service that calculates without reading or writing `attendance_report_projection`
- [x] 3.2 Implement a bounded company-month calculation cache with a 30-second active bucket and retain exact first-page tokens for at most five minutes; never cache authorization results
- [ ] 3.3 Preserve page, aggregate, drill-down, and expected-version consistency using the input snapshot token
- [ ] 3.4 Return safe retryable errors for source staleness, snapshot change, unsafe documents, and ambiguous inputs instead of zero-valued reports
- [ ] 3.5 Record cache hit/miss/eviction, input-load duration, calculation duration, row count, and safe failure metrics without sensitive evidence content

## 4. API and Frontend Cutover

- [x] 4.1 Route the existing attendance report company-options and GET endpoints to the realtime service while preserving the established response shape
- [x] 4.2 Interpret the existing `projectionVersion` response/request field as the realtime input snapshot token and document the compatibility behavior
- [x] 4.3 Remove publication/approval/not-ready prerequisites from the report-center user flow and add an explicit refresh action
- [x] 4.4 Display calculation time and Deli/OA source cutoffs so users can see data freshness
- [x] 4.5 Keep legacy projection default zeroes from being presented as measured day counts or classified overtime
- [x] 4.6 Update OpenAPI for realtime query behavior, snapshot-change errors, freshness metadata, and the absence of a publication prerequisite

## 5. Dependent Consumers

- [ ] 5.1 Bind report export creation and download to the same realtime snapshot token and authorization digest
- [ ] 5.2 Switch dashboard summary and drill-down reads to the realtime snapshot service or mark them explicitly unavailable until the same snapshot can be used
- [x] 5.3 Retain the old publication endpoint only as a compatibility/internal operation and ensure it is not linked from the default UI

## 6. Verification and Delivery

- [ ] 6.1 Add no-projection end-to-end tests using Deli punches, OA leave/overtime/outing/exemption/correction evidence, shifts, attendance groups, and organization scope
- [ ] 6.2 Add cross-company, organization-descendant, SELF, expired-scope, and unauthorized negative tests proving calculation is never performed outside scope
- [ ] 6.3 Add snapshot-change, source failure, quarantine, stale watermark, ambiguous input, and unresolved leave-revocation fail-closed tests
- [ ] 6.4 Add a 5,000-employee company-month cold/warm performance test proving bounded query counts and the 5-second/1-second budgets
- [ ] 6.5 Run backend, frontend, OpenAPI, OpenSpec strict, migration, and Baota release gates from one clean commit
- [ ] 6.6 Perform a customer-environment single-company manual Deli/OA sync, compare sample employees against source records, and capture cold/warm report timings
- [ ] 6.7 Build a clean immutable Baota release package with manifest and SHA only after the realtime report acceptance evidence passes

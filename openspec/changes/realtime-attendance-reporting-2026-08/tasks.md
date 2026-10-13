## 1. Calculation core

- [x] 1.1 Side-effect-free full-calc orchestrator reused by realtime query
- [x] 1.2 Return daily/OA/time-account facts, source versions, cutoff, digest; no projection write
- [x] 1.3 Prove realtime query and optional publication yield identical business values for the same snapshot
- [x] 1.4 Fail closed on ambiguous identity, attendance group, shift, calendar, policy, or OA status
- [x] 1.5 Approved leave with no revocation uses the original leave start/end
- [x] 1.6 Approved leave with revocation(s) uses 销假 actual start/end (formmain_0370 field0086/87), not the original leave interval
- [x] 1.7 Multiple approved revocations for one leave: union actual intervals and merge overlaps; do not fail the whole company-month
- [x] 1.8 Persist leave serial (field0097) and original-leave serial (field0099) on ingest; match by serial, fall back to same employee number
- [x] 1.9 Project the real shift template name into daily/matrix facts; stop hardcoding 「计算班次」

## 2. Authorized batch inputs

- [x] 2.1 Resolve capability and COMPANY/ORGANIZATION/SELF before loading inputs
- [x] 2.2 Company-period batch queries (no per-employee/day loops)
- [x] 2.3 Filter facts by freshly resolved scope before aggregation
- [ ] 2.4 (deferred this round) Lightweight input-version catalog over all people/config versions — token remains Deli/OA watermark + calc digest, not a full catalog
- [x] 2.5 LIVE snapshot token + Deli/OA cutoffs + dataAsOf

## 3. Realtime report service

- [x] 3.1 Query without reading/writing attendance_report_projection
- [x] 3.2 30s company-month cache, 5-minute token retain; never cache authorization
- [x] 3.3 Page, aggregate, drill-down, and CustomerReportCenter URL params stay on one LIVE token
- [x] 3.4 Safe retryable errors for snapshot change, source failure, quarantine, ambiguous inputs (no fake zeros)
- [ ] 3.5 (deferred) Complete cache/calc metrics acceptance

## 4. Official report UI (CustomerReportCenter only)

- [x] 4.1 Route report GET/company-options to realtime while preserving response shape
- [x] 4.2 Interpret projectionVersion as the LIVE snapshot token
- [x] 4.3 Remove publication prerequisite from report-center and add refresh
- [x] 4.4 Display calculation time and Deli/OA source cutoffs
- [x] 4.5 Do not present legacy projection zeroes as measured days or classified overtime
- [x] 4.6 Update OpenAPI for realtime query, snapshot-change, freshness, no publish gate
- [x] 4.7 Read reportType/period/companyId/expectedProjectionVersion from the URL (dashboard deep link)
- [x] 4.8 Show 暂算/OPEN on the official report header

## 5. Same LIVE snapshot for export and dashboard

- [x] 5.1 Bind export create/download to the LIVE token; restore REPORT_EXPORT actions on the realtime path
- [x] 5.2 Switch dashboard (and self today if kept) to the same realtime snapshot; no PROJECTION_NOT_READY
- [x] 5.3 Keep publication API internal; not linked from default UI

## 6. Sync, self-service, environment

- [x] 6.1 Diagnose and fix customer Deli job failures using Deli v3 OA integration (`doc.delicloud.com/v3/integration/oa.html`): employee/department APIs supply employee codes used to bind local employee ids
- [x] 6.2 Incremental next_id sync of the full punch set; do not advance cursor on failed pages
- [x] 6.3 Show last sync failure reason and offer manual retry from the operator UI
- [x] 6.4 Deploy env defaults: Deli/OA auto-sync on after cutover (Java property default stays false)
- [x] 6.5 Employee /me/records, /me/leave use LIVE facts; /me/feedback menu hidden (no fake page)
- [ ] 6.6 Customer DB: backup then forward-migrate V36–V49 on shenzhou_hr (now at V35) — 由客户在宝塔执行，见 2026-08-18 部署手册
- [ ] 6.7 Single-company Deli+OA sync and sample-employee reconciliation (Q13 acceptance) — 部署并同步后由客户对账
- [x] 6.8 Tests: no-projection query path; scope negatives; 销假 replace+union (not company-month fail-closed)

## 7. Deferred after this round

- [ ] 7.1 5,000-employee cold/warm performance gate
- [ ] 7.2 Full backend/frontend/OpenAPI/migration/Baota gates on one clean commit
- [ ] 7.3 Immutable Baota release package after 6.7 passes
- [ ] 7.4 business-rules 12.9–12.12 staging/production window (Q16=B)

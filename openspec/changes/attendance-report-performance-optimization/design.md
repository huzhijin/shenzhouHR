## Context

Customer production already pins company-month reports. GET paths are supposed to be warm reads of `attendance_report_*_fact`. The pain is the **write/recalculate** path:

- `findEffectiveOaDocuments` ranks every `oa_attendance_document` with `created_at <= dataAsOf` using `ROW_NUMBER()` partitioned by `(attendance_source_id, source_business_key)` **before** the effective-status filter. A 89s run examined ~630k rows to return ~1.8k.
- V59 already added `ix_oa_document_source_rank_created (attendance_source_id, knowledge_rank, created_at, oa_attendance_document_id)`. It does not include `source_business_key` and does not match a status-leading lookup.
- `AttendanceReportProjectionPublisher` creates a new projection, `copyFactsOutsideRange` for narrow windows, then `appendDailyFact` / `appendExceptionFact` / `appendOaDocumentFact` one row at a time.
- Live `attendance_report_daily_fact` is ~1.3M rows across ~175 projections (~7.5k rows each). Repeated full-month copies are the growth engine.
- Mapper input queries use `timeout="3600"`. Interactive Hikari pool is shared with recalculate.

Pin and query-page specs still require: GET does not calculate; explicit/auto recalculate updates the latest pin; warm query p95 ≤ 1s; last-3-days keeps days outside the window.

## Goals / Non-Goals

**Goals:**

- Cut a production full-month recalculate from 20–30 minutes to ≤ 5 minutes in phase one, then to 1–2 minutes after the latest-pin UPSERT model.
- Stop live fact tables from growing on every recalculate of the same month.
- Keep GET / query-page / export contracts: latest pin only, authorization re-resolved per request, `ATTENDANCE_REPORT:REFRESH` for recalculate.
- Keep OA version ranking semantics (newer pending/revoked suppresses former approved).
- Keep last-3-days / last-7-days merge semantics without copying the rest of the month.

**Non-Goals:**

- Deleting latest pins older than three months in this change.
- Long-lived read of superseded snapshot tokens after a successor pin exists.
- Changing Deli/OA cron (00:00 / 12:00) or making GET calculate again.
- MySQL RANGE partitioning of fact tables (phase three, after unique keys exist).
- Shipping true context-window incremental loading as a required feature; only a feasibility probe.
- Changing attendance calculation formulas.

## Decisions

### D1. Measure the OA query, then index or rewrite — do not ship the guessed status-leading index

The draft task proposed `ix_oa_document_status_type_created (source_status, document_type, created_at, id)`. The inner query is:

```sql
SELECT ... ROW_NUMBER() OVER (
  PARTITION BY attendance_source_id, source_business_key
  ORDER BY status_case, knowledge_rank DESC, created_at DESC, id DESC
)
FROM oa_attendance_document
WHERE created_at <= #{dataAsOf}
```

Status and document type are applied **after** `version_rank = 1`. A status-leading index will not prune that window.

Working sequence:

1. On a production-scale copy, `EXPLAIN ANALYZE` `findEffectiveOaDocuments` and `findReportableOaDocuments`.
2. Prefer an index that matches partition + cutoff, for example `(attendance_source_id, source_business_key, created_at, knowledge_rank, oa_attendance_document_id)`.
3. If the window still scans the whole cutoff set, rewrite to a latest-row subquery or join that can use that index, still ranking all statuses.
4. Add the index via Flyway (next unused version after V66) only with the recorded plan.

Alternatives considered: add the guessed index anyway (low confidence, extra write cost); drop ranking of non-approved rows (changes evidence semantics; rejected).

### D2. Batch writes first, schema change second

Batch `INSERT` of 500 rows in the current append publisher is a behavior-preserving speedup and does not require unique keys. Ship it before the UPSERT model so phase one can move persist time without waiting on projection unique constraints.

`max_allowed_packet` on customer MySQL must be checked; if a 500-row rowset is too large, lower the batch size rather than raising packet size in the dark.

### D3. One live pin per `(company_id, data_year, data_month)`

Replace “append a projection every recalculate” with find-or-create the month pin, then `INSERT ... ON DUPLICATE KEY UPDATE` on natural fact keys:

- daily: `(projection_id, employee_id, business_date)` (confirm against current identity columns; use the same employee/version keys the writer already stores)
- OA: `(projection_id, source_business_key)` plus type if needed
- exception: `(projection_id, employee_id, business_date, exception_code)`
- time account: `(projection_id, employee_id, account_type)`

Narrow windows upsert only intersecting facts; they never `copyDailyFactsOutsideRange` into a new id.

This **breaks** “old snapshot token stays readable”. Default and token-bearing reads use the latest pin; stale tokens get `ATTENDANCE_REPORT_SNAPSHOT_CHANGED`.

Alternatives considered: keep all versions but stop copying unchanged days (still N projections and N times the indexes); dual-write old+new for a week (safer, more code; optional if rehearsal fails).

### D4. Archive superseded projections, not old business dates

After the unique month pin exists, move non-latest projections (and their facts) to `*_archive` tables or delete them in a maintenance window. Keep every **latest** company-month pin that queries still need, including months older than 90 days.

The original “45万行 = 500×30×30 months” target is a capacity ceiling, not a 3-month retention policy. 3-month online retention is an open product question and out of this change.

### D5. Timeouts move last among the query fixes

Leave `timeout="3600"` until each input query p95 < 60s on production-scale data. Then set 300s. Doing 1.2 before 1.1 can abort jobs that today finish.

### D6. Punch and shift SQL after OA, driven by the same duration log

`findActivatedPunchEvents` uses `OR` plus a correlated employee-number subquery and a correlated lifecycle subquery. After OA is no longer 89s, if punches still dominate the duration log, rewrite with a roster/event employee map CTE (or temp table) and a latest-lifecycle join. Do not rewrite on speculation.

`findScheduledWorkSegments` joining `audit_event` with `ROW_NUMBER` is P2. Denormalizing `shift_template_name` onto `scheduled_work_segment` is allowed only if the duration log shows it; backfill must match the current ranking query.

### D7. Separate Hikari pools after writes are batched

Two DataSources: `web` (short timeout, sized for HTTP) and `batch` (recalculate, auto-recalc, sync-driven persist). Recalculate orchestrator and projection publisher use `batch`. Report/query mappers stay on `web`. Do this after batch writes so pool sizing is not hiding per-row round trips.

### D8. Partitioning and true incremental load are gated

Partition live fact tables only after unique keys and latest-pin writes are in production. Incremental “load only window ± lookback” is a research probe against `DeterministicAttendanceCalculator` dependencies (overnight punch, monthly late grace, week overtime). Probe success is a written feasibility note, not a phase-two exit criterion.

## Risks / Trade-offs

- [Wrong index on a large OA table] → Require `EXPLAIN ANALYZE` evidence in the PR; refuse status-leading index without that plan.
- [DDL lock on production] → Create indexes in a low-traffic window; on MySQL 8 use `ALGORITHM=INPLACE, LOCK=NONE` when the server allows it.
- [UPSERT unique keys fail because duplicate facts already exist inside one projection] → Deduplicate before adding unique keys; add keys in a rehearsal database first.
- [Concurrent recalculate deadlocks] → `SELECT ... FOR UPDATE` on the month pin row; one writer at a time per company-month.
- [Stale snapshot token clients] → Map to existing `ATTENDANCE_REPORT_SNAPSHOT_CHANGED` / retry; no mixed rows.
- [Timeout 300s aborts a remaining slow query] → Gate on measured p95; keep 3600 until then.
- [Batch larger than `max_allowed_packet`] → Configurable batch size starting at 500.
- [Archive job deletes a latest pin] → Archive only projections that are not the unique month pin; dry-run counts before delete.

## Migration Plan

1. Flyway index (or rewritten SQL) for OA load; deploy; capture three full-month durations.
2. Batch append (still append-only projections); deploy; compare persist time.
3. Rehearse unique keys + year/month columns + find-or-create on a restored copy; compare person-day digests with the append-only publisher.
4. Production maintenance window: add nullable year/month, backfill from `data_as_of` / period, add unique keys, switch publisher, archive non-latest projections.
5. Dual Hikari pools.
6. Lower mapper timeouts only after p95 evidence.
7. Rollback: keep previous JAR + do not drop new columns; append-only publisher can run if unique keys are not yet added. After unique keys exist, rollback is “write latest pin but disable archive”, not “restore 175 live projections”.

## Open Questions

- Confirm no in-production client needs to open a **specific old** projection token after a later recalculate (audit export of a superseded pin). Assumption: no.
- Exact natural keys for OA and exception facts once we list current writer columns.
- Customer MySQL version and `max_allowed_packet` / innodb online DDL support.
- Whether auto-recalculate last-3-days and manual 重新计算本月 must serialize on the same pin lock (assumption: yes).

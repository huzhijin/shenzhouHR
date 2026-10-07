## Context

Official 考勤报表 (`GET /api/v1/attendance-reports/month-matrix`) and independent 查询报表 (`GET /api/v1/attendance-report-queries/matrix`) already share a pinned projection. Query pages SQL-page `attendance_report_daily_fact` (~0.5s / 50–200 rows). The official matrix loads the entire company-month snapshot in `MyBatisAttendanceReportSourceRepository.loadAuthorizedSnapshot` (daily + OA + exception + time-account), with `reportFactVisibility` EXISTS and a recursive org walk on every fact even when `organizationId` is null. The browser then requests `size=50` and **stops**, setting `truncated`. Operators only use the official report; telling them to open 查询报表 is rejected.

Deli identity cleanup already prefers confirmed snowflake bindings. Remaining gaps are: roster empno vs Deli name on the same number; intern device empnos (`SZSTSX*`) absent from the roster; people with OA but no daily facts (霍岩); rest-day punches dropped from facts; frontend `primaryStatusOrder` ranking REST_DAY above RECOGNIZED_OVERTIME.

Constraints from the operator: Deli cron stays 00:00/12:00; do not rewrite Excel or HR empnos; do not change punch-exemption rules; do not import 07xx or the skipped names in this change.

## Goals / Non-Goals

**Goals:**

- First official matrix page (50 people, 江苏神州 2026-08, no employee filter) interactive in 3–5 seconds.
- Keep appending pages until the authorized company-month is complete.
- Bind Deli punches by unique roster empno; rule B for unlisted Deli empno + unique roster name; rematch same-empno name conflicts to HR.
- Show OA-only roster people (霍岩); OA color beats 漏刷 and rest-day; rest-day punches visible; attach clocks for system-exempt people without changing exemption.
- After identity/replay, recalculate 江苏神州 2026-08 so the pin matches.

**Non-Goals:**

- Changing 查询报表 (already fast).
- Redirecting operators to 查询报表.
- Changing standing/executive punch-exemption flags or OA forms.
- Importing 07xx, 邵怀芳/刘倩/望天荷, 徐利民, 黄兆隆.
- Rewriting Excel or roster empnos.
- Replacing the pin model or the twice-daily Deli/OA cron.

## Decisions

### 1. Official matrix GET copies the query-page SQL shape, not the in-memory snapshot

**Choice:** Resolve authorization and pin id once. `listMatrixEmployees` with `LIMIT/OFFSET`. `listDailyCells` / OA / exceptions **IN** that page's employee ids. Assemble only those rows.

**Rejected:** Keep loading the full snapshot then slice — that is the 40s GET.  
**Rejected:** Wait on `RealtimeAttendanceReportSnapshotService` calculation for GET when a pin exists.

`totalEmployees` still comes from `countMatrixEmployees` (and a union with OA-only employees, decision 3).

### 2. Progressive fill is client paging, not a new websocket

**Choice:** `collectMatrixPages` already loops; remove the “no employeeId → break after first page” shortcut. Show page 0 immediately, then request page 1..N with `expectedProjectionVersion`. If the pin changes mid-loop, restart from page 0 (existing SNAPSHOT_CHANGED).

**Rejected:** Server-sent events / chunked HTTP — extra infra, same SQL still required.  
**Rejected:** Raise first page to 551 — first byte would wait on the whole company.

KPI cards use page-0 counts only as preview; full counts after the last page.

### 3. Matrix population is daily-fact employees ∪ OA-in-month roster employees

**Choice:** Employee universe for the official matrix = distinct `employee_id` on pinned daily facts **union** distinct employees with effective OA overlapping the month, both filtered to the authorized company roster. Days without a daily fact: OA-covered dates get document color; other weekdays 漏刷; rest days follow rest-day-overtime rules.

This is how 霍岩 gets a row while query-page `listMatrixEmployees` (facts only) stays unchanged unless we later want the same union there. This change's acceptance is the **official** report.

### 4. Binding order

1. Confirmed snowflake binding (unchanged).  
2. Device/Deli empno equals exactly one roster empno → that employee (HR name wins).  
3. Else Deli empno **not** on roster **and** Deli name equals exactly one roster employee → rule B.  
4. Else quarantine / no authoritative match (unchanged).

Same-empno name conflict: after (2), Deli directory name that does not equal the HR name is rematched by unique HR name to the correct empno; punches follow the Deli person, not the printed empno.

Replay remaining mis-attached raw rows for 2026-08 after bindings, then one company-month recalculate. Cron unchanged.

### 5. Cell color rank

Frontend `primaryStatusOrder` MUST list document colors (leave, outing, trip, overtime) **before** `REST_DAY`. Rest-day punches must be stored on daily facts (`first_punch_at`/`last_punch_at`) even when scheduled minutes are 0.

### 6. Skip list is explicit

Deferred: 邵怀芳, 刘倩, 望天荷, 徐利民 `SZST0674`, 黄兆隆 `SZST0662`. 07xx keep raw Deli rows so a later roster empno matches by decision 4.2.

## Risks / Trade-offs

- **[Risk]** Pin SQL without indexes still misses 5s → **Mitigation:** reuse query-page indexes; `EXPLAIN` the new official-page statements on 江苏神州 2026-08 before ship.  
- **[Risk]** Progressive fill races a recalculate → **Mitigation:** bind later pages to `expectedProjectionVersion`; on conflict, reload page 0.  
- **[Risk]** Rule B unique-name false attach → **Mitigation:** only when Deli empno is absent from roster and name cardinality is 1; never override an existing roster empno.  
- **[Risk]** OA-only rows explode people not in any group → **Mitigation:** union is roster ∩ effective OA in month, not all OA names in the raw OA database.  
- **[Risk]** Recalculate after replay is slow (known 15min engine) → **Mitigation:** replay+recalculate is an ops step, not the GET path; GET stays on the old pin until the new pin is published.  
- **[Trade-off]** Query-page matrix may still omit 霍岩 until a later change; operators were required to keep using 考勤报表.

## Migration Plan

1. Ship backend paged official matrix + frontend progressive fill (can go live with old identity).  
2. Apply binding SQL / seed for rule B and name conflicts; replay 2026-08 Deli window; recalculate 江苏神州 2026-08.  
3. Hard-refresh 考勤报表; first page timed; 霍岩 / 季佳男 / 张海兰 / intern-B seven / 彭伟 8/8 sampled.  
4. Rollback: revert jar+web; pin table unchanged; bindings are additive and can stay.

## Open Questions

None blocking implementation. Items 14–15 remain operator todos outside this change.

## Context

Live workbench (`AttendanceDashboardWorkbenchAssembler`) classifies today's roster as ERROR missing punches when there are zero punches, and labels ERROR as 阻断. DAY and MONTH windows both classify only `businessDate` (today). HR drill-down goes to `/attendance/reports`.

Self pages (`/me/today`, `/me/leave`) both call `/api/v1/me/attendance-dashboard`. Leave is a fake account from dashboard minutes. Self SQL requires `data_scope.company_id IS NULL`, but EMPLOYEE_SELF assignments store a company id. EMPLOYEE_SELF currently includes `ATTENDANCE_REPORT:READ` and `ATTENDANCE_REPORT_QUERY:READ`.

吴根银得力原件是 **10 日 07:28 上班 / 漏刷**，不是过夜班。入库只存 `point_instant`（`2026-08-09T23:28:59Z` = 上海 10 日 07:28）。核算 `calculateDay` 证据窗：休息日到次日 `overnightCut`（班次开始），工作日从当天 `overnightCut` 起。周日窗盖住周一 07:28，周一窗从 08:30 才开始，卡被记在 9 日、10 日变缺卡。得力 06:00 入库切点和 UTC 显示都不是根因。

OA 补签 is ingested (`formmain_0203` / `formson_0204` → `PUNCH_CORRECTION`) but query sheets are only leave/overtime. Calculator maps `PUNCH_CORRECTION` to `EXEMPT_WORK`. In-app punch-correction rows inject synthetic punches; OA makeup does not. OA document list GET currently returns 409 `DATA_CONFLICT` (route overlap with source jobs).

Deli cron stays 00:00 and 12:00 Asia/Shanghai: yesterday is complete after midnight; today's 迟到/早上漏签 after 12:00 uses the noon sync.

## Goals / Non-Goals

**Goals:**

- Workbench: yesterday exceptions; after 12:00 add today's 迟到 and 早上漏签; no 阻断
- HR row → `/attendance/queries/exceptions`
- Employee: 我的考勤明细 + 本人异常 + 真实假期账户; no company-wide report menu
- Rest-day early arrival stays on the work day
- OA 补签 listed and used as a punch at makeup time
- Compare script against 得力考勤月报 and live matrix API

**Non-Goals:**

- More frequent Deli sync
- Changing monthly 补卡 quota
- OA plugin (`szsc`) form validation
- Rebuilding official published report center
- Fixing every quarantined Deli raw row in this change

## Decisions

1. **Yesterday from calculated exception facts; today-after-noon from punches vs shift**
   - Yesterday: `exceptionFacts` (same types as 异常总览), not the 2-punch classifier.
   - Today after 12:00: first punch vs shift start → 迟到; no on-duty punch → 早上漏签. No off-duty miss today.
   - Alternative: encrypt Deli sync and classify today all day — rejected; user asked to wait until cards are due.

2. **HR navigation**
   - `DashboardPage` 查看异常报表 / row click → `/attendance/queries/exceptions?employeeNumber=&fromDate=&toDate=` (and company).
   - Keep `ATTENDANCE_REPORT_QUERY:READ` on HR_ADMIN / DEPARTMENT_HEAD.

3. **EMPLOYEE_SELF capabilities**
   - Remove `ATTENDANCE_REPORT:READ` and `ATTENDANCE_REPORT_QUERY:READ` from EMPLOYEE_SELF (Flyway + role seed).
   - Keep `ATTENDANCE_SELF:READ`, `LEAVE_SELF:READ`.
   - Self workbench uses the same yesterday/noon rules, filtered to the bound employee.
   - 我的考勤 reads self daily facts / matrix-equivalent API, not `loadSelfDashboard` parse-as-records.
   - 我的假期: `/api/v1/me/leave-accounts` wrapping annual-leave + time-off for the bound employee, authorized by `LEAVE_SELF:READ`.
   - Fix `SelfAttendanceDashboardMapper` SELF join: allow `scope_type = SELF` with null **or** matching company id.

4. **Rest-day vs next-morning early clock-in (two-sided window)**
   - Rest day: `[D 00:00, D+1 00:00)` so Saturday daytime OT stays Saturday and Monday 07:28 is not Sunday.
   - Work day after a rest day: start at `D 00:00` so early clock-in is today's on-duty. Only changing rest-day end leaves 07:28 in a gap.
   - Work day after a work day: keep `overnightCut` so 05:50 stays previous work day's leaving punch.
   - Recalc pinned August after deploy.

5. **OA 补签 as punch**
   - Query: add sheet `makeup` (`PUNCH_CORRECTION`) beside leave/overtime; menu 补签.
   - Calc: convert approved OA `PUNCH_CORRECTION` to `PunchEvent` at interval start (补签时间). Infer ENTRY if T is before noon / in arrival window, else EXIT; honor 补卡类型 when mapped.
   - Stop mapping `PUNCH_CORRECTION` → `EXEMPT_WORK`.
   - Matrix already has 补签 badge; keep it once the fact exists.
   - Fix OA documents GET 409 (split job-list vs document-list mappings).

6. **Compare script**
   - `deploy/baota/scripts/compare-deli-month-report.sh`: login, parse 考勤月报 xlsx, call matrix per employee (or paged), print 错日 / 得力有我们无 / 漏刷对不上.
   - 月度汇总表 is optional; skip empty Deli summary rows.

## Risks / Trade-offs

- [Removing QUERY_READ from EMPLOYEE_SELF] → mixed accounts like 黄金鑫 keep reports via HR_ADMIN. Pure employees lose 异常总览 menu; they use 我的考勤. Mitigate in release notes.
- [Today after 12:00 uses noon sync] → a 11:50 punch might miss the 12:00 job. Accept; next midnight lands it on yesterday.
- [补卡类型 enum unused] → wrong ENTRY/EXIT if we only use noon. Mitigate: map `field0134` when catalog knows the value; else noon heuristic + tests on 吴根银-style morning makeup.
- [August already pinned] → HR must 重新计算. Script should say so.
- [OA list 409] → if root cause is not routing, still ship a working 补签 query sheet from report facts so HR is not blocked on `/sources/oa`.

## Migration Plan

1. Deploy backend + frontend + Flyway role capability update.
2. HR 重新计算 2026-08 (and any other OPEN month).
3. Run compare script against 考勤月报.
4. Rollback: revert role_capability insert/delete; previous workbench remains noisy but usable.

## Open Questions

- None blocking. 补卡类型 display values to be taken from live OA enum if present; otherwise noon heuristic.

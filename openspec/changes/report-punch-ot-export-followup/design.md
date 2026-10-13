## Context

`overnight-return-ot-display-and-exceptions` moved the overnight cut to next shift start minus 30 minutes, so 07:xx early cards became the previous day's last punch. `overtime-hours-from-snapped-oa-form` counts OA interval minus meals and ignores punches. `report-export-ot-leave-exceptions` promised live xlsx export; the button still labels 「演示文件已导出」 and matrix export binds `ATTENDANCE_DETAIL` allowlists. Finance overtime still splits at midnight. Live 2026-08: 吴根银 `08:11 次日 07:56` then next-day 漏刷; 李尹 8/8 09:30–20:00 listed twice while last punch is 18:16; 晟州 32 people in the matrix, most 漏刷 despite earlier Deli matches.

人事 2026-08-29 锁定：两天都显示；未报加班 18:30 后无单；OA 结束晚于末卡要异常并截到末卡；跨夜小时跟开始日（含周末节假日）；加班单据去重。

## Goals / Non-Goals

**Goals:**

- Split overnight leave (`< 06:00`) from next-morning arrival (`>= 06:00`); both days keep their own punches.
- Cap OA overtime to last punch and flag when the form ends later.
- Fold all overnight (including into weekend/holiday) hours onto the start date for human sheets and finance overtime.
- Hide 加班费 or 转调休 columns from the treatment filter; default shows both.
- Deduplicate identical employee+interval overtime facts.
- 未报加班 only after 18:30 without covering form.
- Live export downloads xlsx without demo copy; matrix does not depend on empty ATTENDANCE_DETAIL allowlists.
- Shanghai OPEN month shows committed punches for roster people; rest days are not 漏刷.

**Non-Goals:**

- Not changing Deli/OA cron schedules.
- Not auto-reopening closed months.
- Not introducing night-shift templates.
- Not changing OA plugin fill rules.
- Not matching punches across unrelated employee numbers.
- Not hiding 未报加班 entirely.

## Decisions

### 1. Overnight boundary is 06:00, not next shift start

`selectDayClocks` afternoon/overnight membership for day D is: same-day after noon, or D+1 strictly before 06:00. D+1 punches from 06:00 inclusive are D+1 上班. `overnightCut` evidence window follows the same 06:00 handoff so 07:xx is in D+1's punch set.

Alternative: next shift start minus 2 hours. Rejected; 07:22 with 08:30 start is still overnight under a 2-hour cut, which is the live bug.

Alternative: keep shift-start cut and also copy the punch to D+1. Rejected; one physical punch must not be both 下班 and 上班.

### 2. Last punch for overtime cap is the start day's selected last punch

After `selectDayClocks`, cap each overtime form that starts on D using D's `lastPunchAt`. Snap the form first, then `end = min(snappedEnd, lastPunch)`. Meal windows use the capped interval. New exception type `OVERTIME_FORM_BEYOND_LAST_PUNCH` (中文：加班结束晚于打卡), WARNING, not month-close blocking. No last punch → 0 hours + exception.

Alternative: cap per calendar slice. Rejected; hours now live on the start day.

### 3. Start-day fold applies to daily facts used by finance

`OvertimeMealDeductions.minutesByDay` (or the writer that fills `recognized/paid/compensatory`) places the whole capped interval on the start date. `OvernightOvertimeFold` becomes identity for display once facts already sit on the start day. Finance overtime calendar cells read those facts; Saturday morning continuation of a Friday form MUST NOT appear as Saturday weekend hours.

**BREAKING** vs previous finance midnight split. OPEN months recalc; closed months stay on old pins.

### 4. Dedup key is employee number + start + end instants

When projecting OA overtime facts, if two effective documents share employee + start + end, keep one (prefer the lower source business key / earlier ingested document id). Query pages group the same way so a leftover pair still renders once. Do not merge different intervals (李尹 8/4 3h and 8/8 9h stay two rows).

### 5. Undeclared overtime threshold is 18:30 clock time

Replace `last.isAfter(shiftOff)` with `last` after 18:30 Shanghai. Coverage check is `[18:30, lastPunch]` against effective overtime. Overnight last punches (00:14) are after 18:30 of the start day, so still undeclared if uncovered.

### 6. Export: matrix local workbook first, honest copy

`handleExport` for `attendance-detail` writes `downloadCustomerReportWorkbook` from the loaded matrix (already in memory, paged-assembled). Server job may still run for other tabs. Remove the hard-coded 「演示文件已导出」 heading; use the existing live/screen sentences only. `exportCustomerReport` MUST NOT treat empty `exportFieldAllowlist` as a hard failure that then swallows ExcelJS errors; fallback errors surface in `exportFeedback`.

### 7. Treatment filter is column visibility

Frontend options are 加班费 / 转调休. Selecting one hides the other column on 每日加班 and 每日加班查询 (and finance-overtime paid/compensatory columns). Do not filter people out. Query-page SQL `dailyOvertimeTreatment` row filters are not required for this behaviour.

### 8. Shanghai punches: reuse employee_number join, repair events, rest-day paint

Keep `findActivatedPunchEvents` join on employee_number + roster company. If bindings exist without effective PUNCH_POINT events, run identity replay for those source employees then recalc 晟州/昇州 only. Matrix rest-day cells follow published calendar/shift, same as 江苏; empty WORK on Saturday MUST NOT default to 漏刷.

## Risks / Trade-offs

- [Risk] 05:50 overnight leave stays on D; 06:05 is D+1 上班 → Mitigation: 06:00 is the locked cut; golden 金玉亮 00:14 and 吴根银 07:56.
- [Risk] Finance weekend hours drop for Friday-night work → Mitigation: specified; payroll informed via start-day weekday type.
- [Risk] Capping Saturday 09:30–20:00 at 18:16 removes dinner meal if dinner no longer covered → Mitigation: meals on capped interval only.
- [Risk] Dedup hides a genuine second form with same times → Mitigation: same interval is the same claim; different end times stay.
- [Risk] Large Jiangsu matrix ExcelJS still heavy → Mitigation: matrix uses already-loaded rows; if write fails, show the error, do not claim success.
- [Risk] Shanghai still empty after code if events never committed → Mitigation: replay script in tasks; no cron change.

## Migration Plan

1. Deploy backend + frontend.
2. Recalculate OPEN 2026-08 for 江苏神州, then 晟州, then 昇州 (replay Deli identity for Shanghai if events missing).
3. Spot-check: 吴根银 07:56 on next morning; 金玉亮 `次日 00:14`; 李尹 one 8/8 row capped to 18:16; export xlsx; 未报加班 not on 18:05; 晟州 周步新 not all 漏刷 if events exist.
4. Rollback: revert artifacts, read previous pin. Closed months unchanged.

## Open Questions

- Exception code display label locked as 加班结束晚于打卡 unless copy review changes it.
- Dedup survivor: lowest `oa_attendance_document_id` among effective duplicates.

## Context

Query pages read the latest `PUBLISHED` pin (`formula_catalog_version = FULL_CALCULATION_OA_FORM_HOURS_V8`). HR matrix save writes `attendance_hr_punch_adjustment` then calls `AttendanceReportQueryService.recalculate(..., RecalcWindow.MONTH)`, which rebuilds **the whole company-month**. Jiangsu Shenzhou is ~550 × 31 days, so the dialog hangs, then `query()` sets `detailIndex = null` and toasts「报表将按新打卡重算」. The calendar the user was editing disappears.

Work-hours aggregates pinned daily facts. If `employment.effective_to` is null, leavers keep scheduled days after they left. Identity already skips days that fail `validOn` (half-open `[from, to)`). Closing assignment `effective_to` to the day after 离职日期 plus a month recalc is enough.

OA sync is already `0 0 * * * ?` Asia/Shanghai (clock hours, not one hour after process start). Auto-recalc still only at 00:00/12:00 after both OA and Deli succeed. Do not change cron.

张海兰：花名册一直是 `SZST0303`。人事已把**得力工号改成 0303**。新增量按工号即可认人。8 月已经按旧号 `SZST0302` 入库的卡仍可能挂在姜长波或隔离区，需要回放后再重算，而不是再写一条永久的 0302 姓名特例。

## Goals / Non-Goals

**Goals:**

- Quiet 异常总览 (two types gone, 虚假加班 renamed, Wuhan/Dalian mute for 2026-08).
- Query group second, directly under 报表中心; work-hours labeled 月度工时统计表.
- Person-day save is fast, applied, and visible on the same cell.
- Paper outing via day-type checkboxes; overtime beats outing on the cell.
- 旷工统计表 and 请假统计表 clone 每日加班查询.
- 迟到/补签/旷工统计表 omit people with no matching data.
- 张海兰 8 月旧 0302 卡回放到 0303，新卡走 0303 工号。
- Listed 2026-08 leavers prorated on work hours after employment close + recalc.

**Non-Goals:**

- Changing OA or Deli cron, or auto-recalc hours.
- Deducting leave quota from HR day-type 年假/事假.
- Muting 漏刷 on 考勤明细 for Wuhan/Dalian.
- Replacing 请假统计 document list.
- Company-wide person-day recalc API for the 考勤报表中心 buttons (those stay 近3天/近一周/本月).
- Closing employment for people not on the supplied list.
- A standing 张海兰 0302→0303 name override now that Deli prints 0303.

## Decisions

### 1. Hide two exception types at query time; still persist them

**Choice:** Remove `OVERTIME_DOCUMENT_MISSING_OR_LATE` and `LONG_PUNCH_SPAN_REVIEW` from `ACTIONABLE` SQL, `AttendanceReportCalculator.actionableException`, frontend filters, and labels. Do not stop the calculator from writing them.

**Why:** 8 月 pin 不必为「不显示」全量重算。Wuhan/Dalian mute is a query/export filter on department path for `2026-08` only.

**Alternative:** Stop generating them. Rejected for this round; would force a full-company recalc just to hide rows, and 取消异常 still needs the codes.

**Rename:** Label map only (`FAKE_OVERTIME` → 加班异常). Code stays.

### 2. Person-day publish copies everyone else from the previous pin

**Choice:** New recalc target: `employeeId` + `[businessDate - 1, businessDate + 1]`. Reuse `publisher.publish(command, copyBefore, copyFromExclusive)` and add copy-other-employees (facts whose `employee_id` is not the target). Digest must include HR adjustments so idempotent publish cannot reuse the old pin when only one person changed.

**Why:** Overnight clocks touch the previous/next day. Copying other people avoids 550-person rebuilds.

**Alternative:** `RecalcWindow.LAST_3_DAYS` for the whole company. Rejected: still slow, and editing 8/12 would recalc the wrong window (relative to *today*).

**dataAsOf:** `HrPunchAdjustmentService` SHALL pass the inserted row's `created_at` (or `created_at + 1µs`) as recalc `dataAsOf`, so `created_at <= dataAsOf` cannot drop the row just written. Do not truncate `dataAsOf` below `created_at`.

**Capability:** Saving with `ATTENDANCE_ADJUST:MANAGE` is enough to run this scoped recalc. Do not require a separate click of 重新计算.

**UI:** Keep `detailIndex`. Toast `已更新该日考勤`. Expand cleared-type allow-list to the checkboxes already on the dialog.

### 3. Day types are extra columns on the same adjustment row

**Choice:** Add `day_types` (comma-separated codes) on `attendance_hr_punch_adjustment`. Orchestrator turns them into interval evidence for that calendar day (outing/trip/leave-like) before calculation. Display priority in `AttendanceMonthMatrixAssembler.composeSlot`: if `overtimeTone()` then show 加班 even when the slot label is 外出.

**Why:** Same audit trail as punch edits. No second paper-outing OCR product.

**Alternative:** New OA-like document table. Rejected; paper overtime already has a document UI, outing here is a cell correction.

Leave types do not write `time_account` movements.

**Hire day:** when `businessDate.equals(identity.employmentFrom())` and there is no morning punch, inject an on-duty punch at 08:29 Asia/Shanghai, tag `shiftLabel` with `入职`, and drop 上班缺卡. Matrix `composeSlot` also paints `08:29` instead of 漏刷 when the day is marked 入职 and morning is empty. Real morning punches win.

### 4. New wide sheets clone finance-overtime SQL shape

**Choice:** `absence-stat` sums `absence_minutes` and lists only people with `SUM(absence_minutes) > 0`. `leave-stat` sums `leave_or_time_off_minutes` (people with leave > 0) and carries `leave_type` for cell color. 迟到统计 HAVING `SUM(late_minutes) > 0`. 补签 stays document-only (`PUNCH_CORRECTION`). Empty cell = blank.

**Why:** HR asked to copy 每日加班. Existing 请假统计 stays `oaPage`.

### 5. Leavers: close employment, then month recalc

**Choice:** Set current `employment_assignment.effective_to` to 离职日期 + 1 day (half-open, 当天计入). Match by roster display name + company when empno is already known from seed (`SZST0641` 陈柏宇, `SZST0662` 李恩琪, `SZST0598` 范康搏, `SZST0674` 徐利民, `SZST0638` 刘梓轩, `SZST0652` 江梦圆, `SZSTSX91` 刘至宽, `SZSTSX95` 姚韩, `SZST0501` 艾兵洁, `SZST0645` 张泽, `SZST0138` 杜超, `SZST0216` 王文睿, `SZST0531` 杨旭涛). Resolve 崔雨 / 张自豪 by unique active name on Jiangsu Shenzhou at apply time. Then `recalculate(2026-08, company, MONTH)` once.

**Why:** Calculator already skips `!validOn`. Full-month hours mean `effective_to` is still null (or after August).

**Alternative:** Filter work-hours SQL by employment without closing assignments. Rejected; 考勤明细 would still 漏刷 after leave.

### 6. Menu order is AppShell group order only

`navigationGroups`: first group labeled 报表中心 (`workspace`, 含考勤工作台与考勤报表), `report-queries` second, then people / attendance / sources / administration. Backend `AuthenticationController.menu` order can stay; grouping is the sidebar.

### 7. 张海兰: replay old 0302 rows; new Deli is 0303

**Choice:** Do not add a permanent 张海兰 0302 override. Replay 2026-08 Deli evidence still stored as `SZST0302` + 张海兰 onto `SZST0303`. Fresh syncs use empno `SZST0303`. Same August month recalc as leavers.

**Why:** Device empno is already corrected. Remaining gap is historical bindings, not live matching.

## Risks / Trade-offs

- [Risk] Copy-other-employees publish misses a column and one person looks right while neighbours go blank → Mitigation: copyFacts uses the same writer as date-window copy; test pin row counts for a second employee stay byte-identical.
- [Risk] 崔雨 / 张自豪 not unique on roster → Mitigation: apply-time lookup; if 0 or >1 matches, leave a task and do not guess.
- [Risk] Wuhan/Dalian mute by path misses people whose org name has no 武汉/大连 → Mitigation: document the rule; do not use attendance group (Wuhan has none).
- [Risk] Hiding 未报加班 while still generating it surprises month-close dashboards → Mitigation: workbench/export use the same actionable filter.
- [Risk] Person-day copy + digest collision still returns old pin → Mitigation: include adjustment id in source digest; test that a late-clear changes projection id.
- [Risk] 8 月得力已改 0303 但旧 Excel/隔离区仍是 0302，只跑新同步看不见卡 → Mitigation: replay 0302+张海兰 rows before the August recalc.

## Migration Plan

1. Deploy code (query filters, menu, wide sheets, adjustment columns, scoped recalc, labels).
2. Data: close listed employments; replay 张海兰 8 月旧 0302 卡；**一次**江苏神州 2026-08 整月重算（离职截断、张海兰、异常过滤共用这次）。
3. Rollback: revert jars/frontend; employment `effective_to` rollback script; pins remain append-only (old pin still readable if formula version pinned).

## Open Questions

- None blocking apply. 外出超时未报加班 stays visible (not requested). OA cron stays hourly on the clock.

## 1. Punch split 06:00

- [x] 1.1 Change `selectDayClocks` so D+1 punches before 06:00 can be day D last punch, and D+1 punches at/after 06:00 are day D+1 first punch
- [x] 1.2 Align `overnightCut` / evidence window handoff with 06:00, not next shift start minus 30 minutes
- [x] 1.3 Tests: 金玉亮 00:14 stays on previous day; 吴根银 07:56 is next-day 上班 and previous off-duty is 漏刷 if no evening punch; 21:00 then 07:56 both display

## 2. Overtime cap and exception

- [x] 2.1 After snap, cap OA overtime end to the start day's `lastPunchAt`; meals use the capped interval; no punch → 0 minutes
- [x] 2.2 Emit `OVERTIME_FORM_BEYOND_LAST_PUNCH` when snapped form end is after last punch; add overview label 加班结束晚于打卡
- [x] 2.3 Tests: 李尹 8/8 09:30–20:00 last punch 18:16 exception + capped hours; 8/4 18:30–21:30 last punch 22:12 no exception

## 3. Start-day hours including weekend

- [x] 3.1 Write recognised/paid/compensatory/voluntary minutes for a crossing form entirely on the start date
- [x] 3.2 Finance overtime and 每日加班 read those facts so Saturday continuation of a Friday form is not weekend hours on Saturday
- [x] 3.3 Tests: Friday 21:00–Saturday 02:00 all on Friday in 日报 and 财务加班; Saturday 22:00–Sunday 02:00 all on Saturday

## 4. Undeclared overtime 18:30

- [x] 4.1 `OffScheduleAttendanceExceptions` only when last punch is after 18:30 and no effective overtime covers `[18:30, lastPunch]`
- [x] 4.2 Tests: 18:05 no form → no 未报加班; 18:31 no form → 未报加班; 00:14 overnight no form → 未报加班

## 5. Overtime dedupe

- [x] 5.1 When projecting OA overtime, keep one effective fact per employee number + start + end (lowest document id)
- [x] 5.2 Query 加班单据明细 groups the same key so leftover duplicates still render once
- [x] 5.3 Tests: two 李尹 8/8 09:30–20:00 rows → one row and hours counted once; 8/4 3h remains a second row

## 6. Daily overtime columns

- [x] 6.1 Report center and query 每日加班 filter options are 加班费 / 转调休
- [x] 6.2 Selecting 加班费 hides 转调休 column; selecting 转调休 hides 加班费; cleared shows both; do not drop rows
- [x] 6.3 Tests for column visibility on both pages

## 7. Report center export

- [x] 7.1 Remove 「演示文件已导出」; status is live snapshot or 当前屏幕导出 only
- [x] 7.2 Month matrix export writes the loaded matrix workbook even if `ATTENDANCE_DETAIL` allowlist is empty
- [x] 7.3 Surface fallback/ExcelJS failures instead of claiming success
- [x] 7.4 Tests: demo heading absent; empty allowlist still saves PK xlsx; failure message on write error

## 8. Shanghai punches and rest days

- [x] 8.1 Confirm `findActivatedPunchEvents` employee_number join returns 晟州/昇州 roster punches; fix ingest/replay if bindings exist without PUNCH_POINT events
- [x] 8.2 Rest day without WORK shift paints rest, not 漏刷 漏刷
- [x] 8.3 Recalc OPEN 2026-08 for 晟州 then 昇州 after events exist; spot-check 周步新 / 赵俊君

## 9. Recalc and live check

- [x] 9.1 Recalculate OPEN 江苏神州 2026-08 after punch/OT changes
- [x] 9.2 Browser: export matrix; 吴根银 07:56; 李尹 one 8/8 row; 未报加班 18:30; 每日加班 column filter

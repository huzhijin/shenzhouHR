## 1. Identity effective-from cutover

- [x] 1.1 Add a read-only diagnostic that lists current open-ended `employee_version`, `employment_assignment`, `organization_version`, `attendance_group_assignment`, and current attendance-group/calendar/policy revisions whose `effective_from` is after `2026-01-01`
- [x] 1.2 Implement the authorized idempotent cutover that moves only the current open-ended version of each object to `2026-01-01`, refuses overlap or unique-key collisions, and writes before/after audit
- [x] 1.3 Cover parent organization versions needed for `validOn`, and current attendance-group revision / calendar / policy when those clocks also start after `2026-01-01`; any unsafe object fails the whole cutover
- [x] 1.4 Prove a second run reports already-applied and that punch-import publish does not change any of these dates

## 2. Vendor monthly workbook adapter

- [x] 2.1 Keep existing ZIP, envelope, formula and size checks, then classify the workbook by headers as official template, `月度汇总表`, or 得力 `考勤月报`
- [x] 2.2 Unpivot `月度汇总表` person blocks into canonical punch rows (in then out, day headers like `26-07-01`, skip `-` / empty)
- [x] 2.3 Unpivot 得力 `考勤月报` 签到/签退 pairs (day headers like `01/三`, parse `HH:MM` from `08:30 迟到`, skip `漏刷` / empty)
- [x] 2.4 Reject unrecognized layouts; leave the official six-sheet template path unchanged; count unpivoted rows against the 50,000 limit
- [x] 2.5 Prove conclusion columns and status text are not persisted as facts

## 3. Match, precheck and publication

- [x] 3.1 Match vendor monthly rows only by employee number inside the selected company; treat missing or non-unique numbers as blocking unmatched issues
- [x] 3.2 Deduplicate every extracted punch against all existing active events (Deli, prior Excel, SQL backfill, other file in the same month) by employee + instant + direction; union complementary files; create new events only for missing times
- [x] 3.3 Require operator confirmation before valid-row-only publish when any blocker exists; cancel leaves the batch with no facts written
- [x] 3.4 Ensure batch publish writes punch evidence only: no report projection, no period close, no company-month settlement job

## 4. Import UI

- [x] 4.1 Allow the existing spreadsheet import page to upload a vendor monthly `.xlsx` without asking the operator to map columns
- [x] 4.2 Show precheck counts plus unmatched / no-number / exact-duplicate issues, and a confirmation dialog before publishing valid rows
- [x] 4.3 Keep the batch on the import task list after publish or cancel, including filename, month range, counts and issue rows

## 5. Verification

- [x] 5.1 Fixture tests from the two sample layouts covering extract, skip, match, same-month union, and exact-duplicate against Deli / prior Excel / SQL / re-import
- [x] 5.2 Regression: official template upload, forbidden-result-column rejection, and name-only match still fail as today
- [ ] 5.3 After cutover and a January two-file import, selecting `2026-01` in the report center returns a live calculation that includes punches and existing OA documents without a report publication

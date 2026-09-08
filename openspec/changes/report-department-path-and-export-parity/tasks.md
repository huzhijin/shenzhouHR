## 1. Department path

- [x] 1.1 Change `DepartmentPathNames` so `reportDepartment` joins every non-company short name from 一级 to the leaf, and expose the segment list for wrapping
- [x] 1.2 Update `DepartmentPathNamesTest` to expect `服务中心-工程二部-RF-B组` and `服务中心-工程一部-MATCH组-MATCH1组`; keep first-level-only as a single name
- [x] 1.3 Confirm identity rewrite in `FullCalculationEngineOrchestrator` still feeds that path into identity rows (and therefore daily facts / month matrix) so every sheet reads one string

## 2. Report sheets and wrapping

- [x] 2.1 Collapse 年休假汇总 to a single 「部门」 column (page, demo rows, mapper, CSV/xlsx headers); stop rendering 「一级部门」「二级部门」 as data columns
- [x] 2.2 Add a shared department-path display helper that inserts wrap opportunities only between segments so `RF-B组` never splits
- [x] 2.3 Update `customerReports.css` so every customer-report 部门 cell can wrap; grow row height; keep month-matrix sticky 工号/姓名/部门 aligned; remove `overflow-wrap: anywhere` on that column
- [x] 2.4 Update frontend report tests for the full path and the annual-leave header/column change

## 3. Export matches the visible sheet

- [x] 3.1 Extract the daytime legend color map used by the month matrix so the Excel encoder can paint the same tones
- [x] 3.2 Replace projection-field xlsx layout with per-sheet customer columns for 个人月度工时, 请假, 加班, 异常, 迟到, 忘打卡, 出勤率, 年休假
- [x] 3.3 Add a month-matrix export path: one employee row, date columns, morning/afternoon or merged text, slot fills; do not export `ATTENDANCE_DETAIL` daily metric rows for that tab
- [x] 3.4 Force exported sheets onto the daytime white palette even when the UI is night
- [x] 3.5 Wrap exported 部门 cells between segments and auto-size row height
- [x] 3.6 Point LIVE and demo 「导出当前报表」 at the new workbook; stop shipping unstyled CSV as the customer file
- [x] 3.7 Cover encoder tests: work-hours headers for a named month, matrix half-day colors, night-theme still white, department wrap segments

## 4. Whole-site day / night

- [x] 4.1 Add `data-theme="day|night"` plus night token overrides on canvas, surface, text, and chrome; leave legend semantic colors on the shared map
- [x] 4.2 Put a day/night control in the app shell; persist in `localStorage`; default to day
- [x] 4.3 Drive Ant Design `ConfigProvider` algorithm from the same preference
- [x] 4.4 Apply the preference on login and the attendance big screen (day is not locked dark)
- [x] 4.5 Add tests for persistence, login/shell/big-screen theme, and a matrix cell keeping annual-leave fill after switching to night

## 5. Product name

- [x] 5.1 Set `app.name` / `app.shortName` and `index.html` title to `神州考勤系统`; leave `app.companyName` as the legal company name
- [x] 5.2 Update login brand text, mobile drawer title, and big-screen accessible name
- [x] 5.3 Update UI tests that still assert `神州 HR 管理系统` or `神州HR`

## 6. Cross-checks

- [x] 6.1 Verify one employee shows the same 部门 path on 月度工时, 请假, 考勤明细, and 年休假
- [x] 6.2 Verify export of 月度工时 and 考勤明细 against the on-screen columns/colors, then toggle night and confirm the file stays white

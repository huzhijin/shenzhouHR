## 1. Backend slot contract

- [x] 1.1 Add morning/afternoon slot display, merge flag, and hover text to `DayCell` while keeping `firstPunchAt`, `lastPunchAt`, and `badges`
- [x] 1.2 Bump month-matrix `formulaVersion` to `ATTENDANCE_MONTH_MATRIX_V2`
- [x] 1.3 Map OA leave types to Chinese labels (年假/事假/病假/调休/婚假/产假/陪产假/丧假/工伤假 and remaining catalog names); never emit `其他假别` for a known type
- [x] 1.4 Cover OA intervals with hardcoded Asia/Shanghai windows 08:30–12:00 and 13:00–17:30; project continuous multi-day intervals onto each date without breaking the span

## 2. Slot composition rules

- [x] 2.1 Compose each slot by spec priority: document label, 漏刷, 补签, 迟到/早退, then punch time
- [x] 2.2 Merge the cell when both slots share the same leave/document label; do not merge a half-day with a punch time
- [x] 2.3 Apply overtime tone only when an effective OA overtime document exists and `recognizedOvertimeMinutes > 0`; keep times on the grid
- [x] 2.4 Build hover text with 3.5 morning / 4.5 afternoon leave hours, 加班, and 补签; omit those hours from slot `text`
- [x] 2.5 Do not render 驻外, 不打卡, 离职, 入职, or 停职留薪 as slot labels

## 3. Backend tests

- [x] 3.1 Cover morning-only leave, afternoon-only leave, and full-day merge in `AttendanceMonthMatrixAssemblerTest`
- [x] 3.2 Cover continuous paternity leave across multiple dates as one uncolored `陪产假` label per date
- [x] 3.3 Cover overtime requiring both OA overtime document and recognized minutes; late checkout without a document stays uncolored
- [x] 3.4 Cover 漏刷 wording, 补签 visibility, stacked 补签+外出, and draft documents ignored
- [x] 3.5 Assert legacy punch instants and badges remain populated on the same day object

## 4. API and frontend contract

- [x] 4.1 Extend OpenAPI month-matrix day schema with slot/merge/hover fields
- [x] 4.2 Extend `AttendanceMonthMatrixDay` and REST mapping; keep Wave7 tests passing on old fields
- [x] 4.3 Map slot fields in `toAttendanceDetailRows` instead of deriving a single `primaryAttendanceStatus` color

## 5. Customer-report matrix UI

- [x] 5.1 Render one `td` with stacked slot bands and no 签到/签退 labels; merged full-day leave is a single centered label
- [x] 5.2 Apply legend colors per slot; other leave types show text with no required background
- [x] 5.3 Keep hover content in the existing tooltip; demo `applyAttendanceStatus` matches live composition
- [x] 5.4 Export the same cell text (merged one label, or two lines in one Excel cell)
- [x] 5.5 Add frontend tests for half-day bands, full-day merge, 陪产假, overtime hover, and 漏刷 wording

## 6. Verification

- [x] 6.1 Run the assembler and customer-report tests that cover this change
- [x] 6.2 Manually check the attendance-detail matrix: half-day two-tone inside one cell, full-day one label, 陪产假 visible, overtime needs an OA form

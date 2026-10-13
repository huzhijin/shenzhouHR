## ADDED Requirements

### Requirement: Export matches the currently visible report sheet
When an authorized user exports the current customer report, the workbook SHALL reproduce that sheet: the same title, column order and labels, filtered rows, department path, and cell text. The system MUST NOT substitute a different report type or a raw projection-field dump.

For 月度考勤明细矩阵, the export SHALL be the employee-by-date matrix with morning/afternoon slot text, not one row per employee-day of `ATTENDANCE_DETAIL` metric fields.

#### Scenario: Work-hours export uses the on-screen columns
- **WHEN** the user is viewing 个人月度工时 for 2026-06 and exports
- **THEN** the data sheet columns are 姓名, 部门, `6月应出勤工时`, 加班时数, 事假+病假+其他假期, 年假, 加班换调休, 实际调休, 个人实际出勤工时, and remarks when shown
- **AND** the file is not a projection dump whose organization header is `发生时组织`

#### Scenario: Month-matrix export keeps one row per employee
- **WHEN** the user is viewing 月度考勤明细矩阵 and exports
- **THEN** each employee occupies one row
- **AND** each calendar date is a column whose cell text matches the on-screen morning/afternoon or merged leave label
- **AND** the sheet is not a daily metric list of 应出勤工时 / 首次有效打卡

#### Scenario: Export follows the current filters
- **WHEN** the visible sheet is filtered to one organization node
- **THEN** the export contains only the rows shown for that filter
- **AND** it does not silently widen to the whole company

### Requirement: Export always uses daytime white background
Exported workbooks SHALL use the daytime white-background presentation regardless of whether the operator is in day or night theme. Chrome, fills, and text on the data sheet MUST be the light customer-report palette.

#### Scenario: Night theme still exports white
- **WHEN** the operator has night theme on and exports any customer report
- **THEN** the workbook data sheet has a white (daytime) background
- **AND** it does not use the night canvas or night text colors

### Requirement: Export carries the on-screen semantic cell colors
Where the visible sheet paints semantic colors (month-matrix legend colors for 迟到, 早退, 漏刷, 加班, 调休, 外出, 出差, 事假, 病假, 年假, 休息日, 补签, and slot fills), the export cells SHALL use those same colors. Sheets that have no semantic cell colors on screen MUST NOT invent extra fills beyond the daytime header/table chrome.

Department cells in the export SHALL wrap between hierarchy segments, matching the on-screen wrap rule.

#### Scenario: Month-matrix leave and overtime colors are in the file
- **WHEN** a date cell on screen shows afternoon 年假 with annual-leave color and morning punch time without leave color
- **THEN** the exported cell keeps that two-band text
- **AND** the afternoon band uses the annual-leave color
- **AND** the morning band is not painted as leave

#### Scenario: Work-hours numbers stay uncolored
- **WHEN** 个人月度工时 on screen has no per-cell status fills
- **THEN** the exported number cells have no status fills
- **AND** the sheet still uses daytime header chrome on white

#### Scenario: Department wraps in Excel at segment boundaries
- **WHEN** an exported 部门 cell contains `服务中心-工程二部-RF-B组` and the column is narrower than the full string
- **THEN** Excel wrap occurs between those three segments
- **AND** `RF-B组` is not split at its inner hyphen

## ADDED Requirements

### Requirement: Live export must download xlsx without demo wording

When an authorized principal clicks 「导出当前报表」 on `/attendance/reports` in live mode, the browser SHALL receive an `.xlsx` whose ZIP signature is `PK`. The status text MUST describe a live or on-screen export and MUST NOT contain 「演示文件已导出」. Month-matrix export SHALL encode the visible matrix workbook even if `ATTENDANCE_DETAIL` exportFieldAllowlist is empty. If the server job fails, the page SHALL still download the in-memory workbook and SHALL say the file is from the current screen.

#### Scenario: Jiangsu month matrix downloads
- **WHEN** the principal exports 月度考勤明细矩阵 for 江苏神州 2026-08
- **THEN** a file whose name includes the month and 月度考勤明细矩阵 is saved
- **AND** the first bytes are `PK`
- **AND** the status does not say 演示文件已导出

#### Scenario: Empty allowlist still falls back
- **WHEN** server export cannot bind because selectedFields is empty
- **THEN** the visible matrix is written locally as xlsx
- **AND** the operator is told it is 当前屏幕导出

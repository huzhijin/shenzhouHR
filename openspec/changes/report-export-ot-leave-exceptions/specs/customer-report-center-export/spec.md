## ADDED Requirements

### Requirement: Report center exports the currently visible workbook

When an authorized principal clicks 「导出当前报表」 on `/attendance/reports`, the system SHALL deliver an `.xlsx` file whose rows, columns, and fills match the currently filtered visible report. The download MUST succeed for every report tab the principal can view.

#### Scenario: Attendance detail downloads as xlsx
- **WHEN** the principal has `ATTENDANCE_REPORT:EXPORT_CREATE` and `ATTENDANCE_REPORT:EXPORT_DOWNLOAD` and exports the month matrix for a company-month they can read
- **THEN** the browser receives an `.xlsx` whose ZIP signature is `PK` and whose sheet shows sign-in / sign-out rows for the visible employees

#### Scenario: Content-Type charset does not fail the download
- **WHEN** the download response `Content-Type` is the xlsx media type with or without a `charset` parameter
- **THEN** the client still saves the file
- **AND** it MUST NOT show 「导出文件暂时无法使用」 solely because of that parameter

### Requirement: Failed server export falls back to the visible workbook

If the server export job cannot be created, bound, or downloaded, and the page already has the filtered report in memory, the system SHALL write that visible workbook locally instead of leaving the operator with no file.

#### Scenario: Binding stale still yields a file
- **WHEN** `POST /api/v1/attendance-reports/exports` returns `ATTENDANCE_REPORT_EXPORT_BINDING_STALE`
- **THEN** the page downloads the currently displayed report as xlsx
- **AND** it tells the operator the file is from the current screen, not a new server snapshot

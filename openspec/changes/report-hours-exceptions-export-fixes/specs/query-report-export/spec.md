## ADDED Requirements

### Requirement: Query-page export SHALL use the complete export contract

Clicking 「导出」 on a query report page SHALL create a server-side XLSX of the current sheet, company, period or date range, and applied filters against the same pin the page is reading. The request MUST include `reportType`, `projectionVersion`, `queryFingerprint`, `scopeReference`, `filters`, `selectedFields`, and `purpose`. The client SHALL poll until `READY` and download the file. It MUST NOT POST only `{ reportType, period, companyId }`.

#### Scenario: Work-hours export downloads an xlsx
- **WHEN** an authorized user on 月度工时 clicks 导出 with a pinned company-month
- **THEN** the browser receives an xlsx whose rows match the current query filters
- **AND** the UI does not navigate to an empty 「当前功能暂不可用」 page

#### Scenario: Incomplete export payload is not sent
- **WHEN** the query page builds the export request
- **THEN** `projectionVersion` and `queryFingerprint` equal the values from the page's last successful query
- **AND** `purpose` is a non-empty Chinese export purpose of 2 to 200 safe characters

### Requirement: Query roles SHALL export without official-report READ

A principal who has `ATTENDANCE_REPORT_QUERY:READ` and `ATTENDANCE_REPORT:EXPORT_CREATE` SHALL be able to create a query-page export for companies in scope. The export service MUST NOT also require `ATTENDANCE_REPORT:READ`. Download still requires `ATTENDANCE_REPORT:EXPORT_DOWNLOAD`. Failure SHALL surface a Chinese reason on the same page (`请求字段校验失败`, `无权执行此操作`, `报表已更新请刷新后重试`), not a full-page unavailable state.

#### Scenario: Query-only exporter succeeds
- **WHEN** the session has `ATTENDANCE_REPORT_QUERY:READ` and `ATTENDANCE_REPORT:EXPORT_CREATE` but not `ATTENDANCE_REPORT:READ`
- **AND** the user exports 加班统计
- **THEN** the export job is created and becomes READY

#### Scenario: Missing export capability hides the button
- **WHEN** the session lacks `ATTENDANCE_REPORT:EXPORT_CREATE`
- **THEN** the query page does not show 导出
- **AND** posting the export API is denied

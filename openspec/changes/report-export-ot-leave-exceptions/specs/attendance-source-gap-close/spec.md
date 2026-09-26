## ADDED Requirements

### Requirement: Shanghai companies can receive punch evidence via existing import

When a Shanghai company (上海昇州 or 上海晟州聚能) has no (or incomplete) punch facts for a requested month, an authorized punch-import principal SHALL be able to load vendor monthly workbooks or official punch templates through `/sources/attendance-excel`. After publish and recalculate, those punches MUST appear on that company's report-center matrix.

#### Scenario: Import then recalculate
- **WHEN** the operator publishes a vendor month workbook whose employee numbers match the Shanghai roster and then recalculates that company-month
- **THEN** matching employees have `firstPunchAt` / `lastPunchAt` on the days present in the file
- **AND** the company is no longer an empty matrix solely for lack of Deli API rows

### Requirement: OA leave gaps are listed by comparison

The operator MUST be able to run the existing OA-vs-HR comparison for a date window and obtain rows that are approved (or pending) in OA leave but absent from `oa_attendance_document`, and rows present in HR but not in OA. Unmapped `showvalue` labels SHALL be listed, not silently dropped.

#### Scenario: Approved OA leave missing in HR
- **WHEN** OA `col_summary.state = 3` leave for employee number SZST00xx is in the window and HR has no `LEAVE:<formId>` row
- **THEN** the comparison output includes that form id, employee number, and interval
- **AND** a subsequent OA sync (or targeted replay) plus recalculate makes 请假统计 contain it

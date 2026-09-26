## ADDED Requirements

### Requirement: HR can enter paper overtime on a master-detail page

The system SHALL provide a paper overtime entry page whose **master** holds attachments and the recognize action, and whose **detail** holds one overtime document per row. Principals with paper-overtime manage capability (granted to `HR_ADMIN`, not to ordinary query readers) MUST be able to upload, recognize, edit, and save. Principals with only overtime query read MUST NOT create or edit paper overtime. Hand-filled detail rows without attachments SHALL be allowed.

#### Scenario: HR opens the entry page
- **WHEN** an `HR_ADMIN` principal opens paper overtime entry
- **THEN** the page shows a master attachment area with 识别 and a detail table with 新增一行

#### Scenario: Query-only user cannot save paper overtime
- **WHEN** a principal has `ATTENDANCE_REPORT_QUERY:READ` but not paper-overtime manage
- **THEN** create, recognize, and save APIs deny the request

### Requirement: Master attachments can be recognized into detail rows

The master SHALL accept multiple files in one upload (PDF, JPEG, PNG, and other common photo types). Recognize SHALL run OCR on every file (PDF pages rasterized separately) in the same request batch. Each recognized form SHALL become one detail row by default (one image / one PDF page → one row). Multiple files on the same master SHALL NOT be auto-merged into one row. OCR SHALL fill name, department text, overtime date, start, end, reason, and overtime type from checkboxes when readable. Hours SHALL be derived later from confirmed start/end using the snapped overtime formula, not summed across files.

#### Scenario: Eight photos become eight draft rows
- **WHEN** HR uploads eight distinct overtime photos on the master and clicks 识别
- **THEN** the detail table has eight rows with OCR fields filled where readable

#### Scenario: Front and back of one slip are not auto-merged
- **WHEN** HR uploads two photos of the same physical form
- **THEN** recognize produces two draft rows
- **AND** HR may delete the duplicate row; the remaining row still lists both master attachments

#### Scenario: PDF pages are recognized
- **WHEN** HR uploads a three-page PDF of three overtime slips
- **THEN** recognize produces three draft rows

### Requirement: Duplicate names list candidates for HR to confirm

Employee resolution for paper overtime SHALL be interactive. Same display name with different departments MUST all appear as candidates (employee number, canonical department, employment status). The system MUST NOT quarantine or drop the row because the name is duplicate. HR MUST select a candidate or search-pick another employee before save. After a person is selected, department and employee number SHALL come from the HR roster, not from the OCR department string.

#### Scenario: Two 陈士庆 are both listed
- **WHEN** OCR name is `陈士庆` and two active employees share that display name in 设备工程部 and 财务部
- **THEN** the row shows both candidates with employee numbers and departments
- **AND** save is rejected until HR selects one

#### Scenario: HR switches the person
- **WHEN** HR picks a different employee from search
- **THEN** the row's employee number and department update from that employee's roster record

#### Scenario: Fuzzy department ranks the nearer homonym first
- **WHEN** OCR department is `设备部` and one 陈士庆 is in 设备工程部 and another is in 财务部
- **THEN** 设备工程部 is listed first as the recommendation
- **AND** HR can still select the other person

### Requirement: Unclear overtime-type checkboxes stay empty and block save

Overtime type SHALL be one of 加班费 (compensated / paid), 调休 (time-off in lieu), or 义务加班 (voluntary). OCR MUST set the type only when a single checkbox is clearly marked. Missing marks, multiple marks, or unreadable marks SHALL leave type empty. Empty type, unconfirmed person, or missing date/start/end SHALL cause save to fail. There is no default type and no force-save.

#### Scenario: Two boxes ticked
- **WHEN** OCR sees both 调休 and 加班费 marked
- **THEN** the detail type is empty
- **AND** save returns a validation error naming that row

#### Scenario: HR fills the empty type
- **WHEN** type was empty after OCR and HR selects 调休
- **THEN** save may proceed if other gates pass

### Requirement: Overlapping calendar dates for the same person reject save

Save SHALL reject a paper overtime row when the same employee already has an approved OA or paper overtime document whose snapped interval covers any Asia/Shanghai calendar day covered by the new row, including overnight spans. Overlap among unsaved detail rows in the same master SHALL also reject. The error MUST name the person, the overlapping date, and the existing document source and interval. The API MUST NOT provide a force-save or override flag.

#### Scenario: Second slip on the same date
- **WHEN** employee 10012 already has paper overtime on 8-18 17:40–22:00
- **AND** HR tries to save another row for 10012 on 8-18 18:00–21:00
- **THEN** save fails with an overlap error for 8-18
- **AND** no new evidence row is persisted

#### Scenario: Overnight occupies the next calendar day
- **WHEN** employee 10012 has overtime 8-18 22:00–8-19 06:00
- **AND** HR saves 8-19 08:00–12:00 for 10012
- **THEN** save fails because 8-19 overlaps

#### Scenario: Same name different employee numbers do not conflict
- **WHEN** 陈士庆 10012 has overtime on 8-18
- **AND** 陈士庆 10087 is saved on 8-18
- **THEN** save succeeds

#### Scenario: Two detail rows in one batch overlap
- **WHEN** the master detail contains two rows for 10012 both covering 8-20
- **THEN** save fails before ingesting either row

### Requirement: Paper overtime can be saved without waiting for calculation

HR MUST be able to save paper overtime whenever the row is valid, including when the company-month has no pin, an OPEN pin, or a stale pin. Save MUST NOT require a prior or in-flight recalculate. Saved evidence becomes visible on reports only after the next unified overtime calculation (manual window recalc or auto last-3-days). Overlapping calendar days with existing OA or paper overtime still reject save.

#### Scenario: Save succeeds before any pin exists
- **WHEN** HR saves a valid paper overtime row for an OPEN month that has never been calculated
- **THEN** the evidence row is persisted
- **AND** the save does not start month calculation

#### Scenario: Save is not blocked by an outdated pin
- **WHEN** a pin exists and HR saves a valid non-overlapping paper overtime row
- **THEN** save succeeds without requiring 重新计算 first

### Requirement: Saved paper overtime is approved evidence like OA overtime

A successfully saved paper overtime row SHALL enter the attendance evidence chain as document type `OVERTIME` with source origin paper, source status approved, and the confirmed employee, interval, and overtime type. Compensatory (调休) paper overtime SHALL credit TIME_OFF the same way approved OA 调休 overtime does. Query and calculation consumers MUST treat it as an overtime document, not as an attachment-only note.

#### Scenario: Saved 调休 paper overtime is queryable
- **WHEN** HR saves a confirmed 调休 row for 10012 from 8-18 17:40–22:00
- **THEN** 加班统计 lists that document with origin 纸质
- **AND** recognized hours use the snapped interval minus meals

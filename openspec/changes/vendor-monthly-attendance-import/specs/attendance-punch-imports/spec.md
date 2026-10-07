## MODIFIED Requirements

### Requirement: AC-PUNCH-02 only raw punch facts can be imported
The import SHALL persist only source metadata, person identifiers, punch time, direction, verification method, source record ID, event code, timezone and note fields. It MUST NOT persist calculated attendance conclusions and MUST NOT act as a punch-correction application. Official six-sheet templates and unclassified workbooks MUST reject mapped conclusion columns. A workbook classified as a vendor monthly report MAY contain conclusion columns; the system MUST ignore those cells and MUST NOT write late, early, absence, overtime, leave-hour or payroll values as facts.

#### Scenario: Calculated result column is rejected
- **WHEN** an official-template or unclassified workbook maps or contains columns such as late minutes, absence, recognized overtime, exception result or payroll hours
- **THEN** precheck reports a blocking forbidden-result-column issue and no raw fact can be published

#### Scenario: Vendor monthly conclusion columns are ignored
- **WHEN** a workbook is classified as `月度汇总表` or 得力 `考勤月报`
- **THEN** columns such as 请假时长, 应出勤天数, 迟到次数, 早退次数, 旷工天数 and cells such as `漏刷`, `-`, `08:30 迟到` or `17:59 早退` do not block the batch, only a parsed `HH:MM` becomes a punch fact, and no conclusion value is stored

#### Scenario: Manual punch correction is not disguised as a device row
- **WHEN** a user supplies a manually invented punch without an allowed device, standard-template or vendor-monthly source classification and required provenance
- **THEN** the server rejects it and directs the case to the later authorized correction/evidence workflow

## ADDED Requirements

### Requirement: Vendor monthly workbooks are recognized and unpivoted
The system SHALL classify a safe `.xlsx` as one of: official punch template, `月度汇总表`, or 得力 `考勤月报`. For the two vendor monthly formats it MUST unpivot the person × day matrix into canonical punch rows before the existing match, fingerprint and publication pipeline. Classification MUST use sheet headers, not filename alone. Month-to-month files that keep the same headers and only change the calendar columns MUST be accepted.

#### Scenario: Monthly summary workbook
- **WHEN** a caller uploads a workbook whose headers include `姓名`, `工号` and day columns such as `周三` plus `26-07-01`
- **THEN** each person occupies an in-row then out-row pair, each `HH:MM` cell becomes one punch with inferred direction and business date, and `-` or empty cells produce no row

#### Scenario: Deli monthly report workbook
- **WHEN** a caller uploads a workbook titled as a 考勤月报 whose headers include `帐号`, `工号`, `姓名`, `签到`/`签退` and day columns such as `01/三`
- **THEN** each `HH:MM` or `HH:MM` plus status text becomes one punch, `漏刷` and empty cells produce no row, and direction comes from the 签到/签退 row

#### Scenario: Official template is unchanged
- **WHEN** a caller uploads the current six-sheet official punch template
- **THEN** the existing template contract, mapping and validation apply and the vendor monthly adapter is not used

#### Scenario: Unrecognized layout is rejected
- **WHEN** a workbook is not the official template and does not match either vendor monthly header contract
- **THEN** precheck reports a blocking unrecognized-layout issue and no fact is published

### Requirement: Employee number is the only match key
Vendor monthly precheck SHALL match a punch to exactly one employee in the selected company by employee number. Name and department are comparison-only. The system MUST NOT guess by name, account id or department path. A row without an employee number, or with an employee number that is missing or not unique in that company at the punch instant, is a blocking unmatched issue.

#### Scenario: Unique employee number matches
- **WHEN** a vendor monthly cell yields `HH:MM` and the row's employee number resolves to exactly one employment covering that punch instant in the selected company
- **THEN** precheck records that employee as matched and the row is publishable

#### Scenario: Missing employee number is blocking
- **WHEN** a vendor monthly person block has no employee number
- **THEN** that person's extracted punches are unmatched, appear on the batch issue list and cannot be published

#### Scenario: Name-only match is forbidden
- **WHEN** a row contains only a name, or a name plus department or vendor account id
- **THEN** the row remains unmatched and no employee is selected

### Requirement: Already existing punches are always exact-duplicated
Every vendor monthly punch SHALL be compared to all already active effective punch events for the matched employee, regardless of how those events were created (Deli sync, official template import, vendor monthly import, one-off SQL backfill, or another file in the same month). An extracted row with the same employee, normalized instant and direction as an existing active event is an exact duplicate: the batch MUST record it, MUST retain the new raw fact when publication keeps source provenance, and MUST NOT create a second active event. Only times that do not already exist become new active events. Same-month complementary files therefore form a union. Re-importing any already loaded month MUST be safe and idempotent on effective cardinality.

#### Scenario: Complementary files in one month
- **WHEN** an operator publishes both a `月度汇总表` batch and a 得力 `考勤月报` batch for the same company-month
- **THEN** punches that appear in only one file become active events, and punches that appear in both become one active event with two raw facts

#### Scenario: Re-import of any already loaded month
- **WHEN** a vendor monthly file is published after any prior source already activated the same employee, time and direction
- **THEN** the batch records exact-duplicate rows, creates no additional active event and remains traceable

#### Scenario: Mix of new and existing times in one file
- **WHEN** a vendor monthly file contains both times already in the ledger and times that do not yet exist
- **THEN** only the missing times create new active events, existing times are counted as exact duplicates, and the batch counts reconcile

#### Scenario: Deli and Excel same instant
- **WHEN** a vendor monthly row matches an already synced Deli punch on employee, instant and direction
- **THEN** both raw facts may remain and exactly one active effective event exists after publish

### Requirement: Problems require confirmation before valid rows publish
A vendor monthly batch SHALL remain a durable record through upload, precheck, confirmation and publication. If the batch has blocking unmatched, ambiguous or empty-extraction issues, the operator MUST confirm before any valid row is published. Confirmation SHALL use the existing valid-row-only publication path: valid rows publish, issue rows stay on the batch, and the batch ends `PARTIALLY_PUBLISHED` or `PUBLISHED` with reconcilable counts. Strict publication remains available and MUST refuse the batch when any blocker exists.

#### Scenario: Confirm after unmatched numbers
- **WHEN** precheck finds publishable punches and at least one unmatched employee number
- **THEN** the UI shows the issue list and a confirmation dialog, and no fact is written until the operator confirms valid-row-only publication

#### Scenario: Cancel leaves the batch
- **WHEN** the operator cancels the confirmation
- **THEN** the batch, file, precheck issues and counts remain, and no raw fact or effective event is created

#### Scenario: Confirmed valid rows stay auditable
- **WHEN** the operator confirms valid-row-only publication
- **THEN** valid punches enter the evidence ledger, unmatched rows remain on the batch with issues, and an authorized reader can open the batch later and see filename, month, counts, actor and issues

### Requirement: Import does not publish reports or run month jobs
Publishing a vendor monthly punch batch SHALL NOT create or update an attendance report projection, SHALL NOT close or reopen a period, and SHALL NOT start a company-month recalculation or settlement job. Downstream attendance conclusions MUST be produced only when an authorized report query supplies a filter period and runs the existing realtime calculation over published punches and already ingested OA documents.

#### Scenario: Publish punches only
- **WHEN** a vendor monthly batch publishes valid rows for 2026-02
- **THEN** effective punch events exist for that month and no `attendance_report_projection` row or official publication record is written as a result of that publish

#### Scenario: Report filter calculates live
- **WHEN** an authorized user opens the report center and selects period `2026-02` after those punches are published
- **THEN** the realtime query uses the new punches together with existing OA documents for that period and does not require a prior report publication

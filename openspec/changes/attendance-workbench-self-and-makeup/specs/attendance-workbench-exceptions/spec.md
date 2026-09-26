## ADDED Requirements

### Requirement: Workbench SHALL list only attendance exceptions without blocking language

The attendance workbench SHALL list people who have an exception. Visible types are 迟到, 早退, 上班漏签, and 下班漏签. The workbench MUST NOT show the words 阻断, ERROR, or WARNING, MUST NOT show a blocking-count metric, and MUST NOT treat empty punch pairs as a month-end block.

#### Scenario: HR sees yesterday's late and missed punches
- **WHEN** an HR administrator opens the attendance workbench after 2026-08-11 00:00 Asia/Shanghai
- **THEN** the list is yesterday 2026-08-10 exceptions for the authorized roster
- **AND** a person with only a late punch appears as 迟到, not 阻断

#### Scenario: No blocking card
- **WHEN** the workbench summary is rendered
- **THEN** there is no 阻断异常 card and no blockingCount series in charts

### Requirement: Complete-day exceptions SHALL be yesterday; today SHALL wait until noon

The default complete window SHALL be the previous calendar day in Asia/Shanghai. Before 12:00 today the workbench MUST NOT raise 漏签 for today. After 12:00 today the workbench MAY add today's 迟到 and 早上漏签 only. Today's 下班漏签 MUST NOT appear until that day becomes yesterday.

#### Scenario: Morning does not show today's missed punch
- **WHEN** the clock is 2026-08-24 10:00 Asia/Shanghai and an employee has not punched yet
- **THEN** the workbench MUST NOT list that employee as 漏签 for 2026-08-24
- **AND** it still lists 2026-08-23 exceptions

#### Scenario: After noon, late and morning miss only
- **WHEN** the clock is 2026-08-24 13:00 Asia/Shanghai, employee A first punched 09:40, and employee B has no on-duty punch
- **THEN** A appears as 迟到 for today if late minutes exceed policy
- **AND** B appears as 早上漏签
- **AND** neither appears as 下班漏签 for today

### Requirement: HR exception rows SHALL open exception overview details

When an HR user (or other principal with `ATTENDANCE_REPORT_QUERY:READ` and org scope) clicks an exception row or 异常详情, the system SHALL navigate to `/attendance/queries/exceptions` with that employee and business date, not `/attendance/reports`.

#### Scenario: Drill-down goes to exception overview
- **WHEN** HR clicks 吴根银's 2026-08-10 上班漏签
- **THEN** the browser opens the exception overview query filtered to that employee and date
- **AND** the official report center is not the target

## ADDED Requirements

### Requirement: Exception overview SHALL list actionable attendance exceptions

The 考勤异常总览 SHALL include chargeable late, early departure, missing on-duty or off-duty punches on scheduled days, missing overnight leaving punches that block overtime filing, and fake overtime. Each fake-overtime row SHALL state the overnight punch time and the same-day on-duty punch or unleaved work overlap in the evidence summary.

#### Scenario: Fake overtime appears with an explanation
- **WHEN** overtime covers Tuesday 08:30–10:00 without morning leave
- **THEN** the exception overview contains a 虚假加班 row
- **AND** the evidence summary names the overlapping scheduled window

#### Scenario: Forgotten overnight leaving punch appears
- **WHEN** Monday has no off-duty punch before Tuesday shift start and Tuesday morning has no leave
- **THEN** the exception overview contains a missing-punch row for Monday off-duty
- **AND** the row prompts punch supplement

### Requirement: Exception overview SHALL omit non-actionable noise

The overview MUST NOT list late within unused monthly grace, rest-day absence of punches, punch-exempt scheduled days, already supplemented punches, or overnight overtime that already has a leaving punch before next shift start while the overtime document is still pending.

#### Scenario: Grace late is omitted
- **WHEN** an employee is 10 minutes late and monthly grace still applies so penalized late minutes are 0
- **THEN** the exception overview SHALL NOT contain a late row for that day

#### Scenario: Pending overtime document is not an exception
- **WHEN** the employee punched Tuesday 07:00 as Monday overtime off-duty and no overtime document is approved yet
- **THEN** the exception overview SHALL NOT raise missing punch or fake overtime for that leaving punch

### Requirement: Absence SHALL not be relabeled as overdue missing punch

When the daily result is absence, the exception overview SHALL show 旷工, not 缺卡超期, unless the only finding is a one-sided missing punch.

#### Scenario: Full-day absence is 旷工
- **WHEN** a scheduled day has no work, no leave, and no punches
- **THEN** the exception type shown is 旷工

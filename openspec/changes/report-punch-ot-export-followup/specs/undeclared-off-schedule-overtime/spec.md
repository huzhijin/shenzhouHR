## ADDED Requirements

### Requirement: Undeclared overtime only after 18:30

The system SHALL write `OVERTIME_DOCUMENT_MISSING_OR_LATE` (未报加班) only when the day's last punch is after 18:30 Asia/Shanghai and no effective overtime document covers `[18:30, lastPunch]`. A last punch at or before 18:30 MUST NOT create this exception, even if it is after published shift-off. Overnight leaving punches before 06:00 still count as last punches after 18:30 of the start day.

#### Scenario: 18:05 clock-out is not undeclared overtime
- **WHEN** shift-off is 18:00, last punch is 18:05, and there is no overtime form
- **THEN** 未报加班 MUST NOT appear

#### Scenario: 18:31 without a form is undeclared overtime
- **WHEN** last punch is 18:31 and no effective overtime form covers 18:30–18:31
- **THEN** 未报加班 appears
- **AND** recognised overtime hours remain 0

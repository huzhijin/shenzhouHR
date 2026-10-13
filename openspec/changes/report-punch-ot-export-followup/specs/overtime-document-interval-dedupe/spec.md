## ADDED Requirements

### Requirement: One overtime fact per employee and interval

For a given employee and identical overtime start and end instants, the report SHALL keep a single effective overtime document fact. Duplicate OA formson rows, two parent forms, or two projection rows with the same employee and interval MUST NOT appear twice on 加班单据明细, MUST NOT add hours twice, and MUST NOT paint the calendar twice.

#### Scenario: 李尹 8/8 appears once
- **WHEN** two approved OA overtime details for SZST0463 both start 2026-08-08 09:30 and end 2026-08-08 20:00
- **THEN** 加班单据明细 shows one row
- **AND** daily overtime hours for that date count the interval once

## ADDED Requirements

### Requirement: Makeup punches render as 补签 without late

A published punch-correction (POINT, `endExclusive` may be null) MUST appear on the matching morning or afternoon slot as `补签HH:mm` (or `补签` when the clock is missing). That slot MUST NOT also show 迟到. Ordinary clock-ins at the published shift start remain late.

#### Scenario: Makeup at 08:30 is 补签 only
- **WHEN** an approved 补签 is stored at 08:30 for the on-duty side and the shift starts at 08:30
- **THEN** the month-matrix morning text is `补签08:30` with tone `PUNCH_CORRECTION`
- **AND** the cell MUST NOT append `迟到` or use `LATE` as the slot tone

#### Scenario: Ordinary punch at 08:30 stays late
- **WHEN** the employee clocks in at 08:30:00 with no punch-correction covering that side
- **THEN** the morning slot shows `08:30 迟到` with tone `LATE`

#### Scenario: Point corrections are not dropped
- **WHEN** the OA fact for 补签 has `start` at 08:30 and `endExclusive` null
- **THEN** the assembler still treats it as a morning correction
- **AND** it MUST NOT require a non-null `endExclusive` to keep the badge

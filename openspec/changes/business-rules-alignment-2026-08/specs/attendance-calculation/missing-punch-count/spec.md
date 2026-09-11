# Missing Punch Count Specification Delta

## MODIFIED Requirements

### Requirement: Full day without punch SHALL count as 2 missing punches

When an employee has no punch records for an entire work day, and has no exemption request, and is not in an exemption role, the system SHALL count this as 2 missing punches (one for morning in, one for evening out).

**Clarification**: This explicitly confirms that full-day absence = 2 missing punches (not 1, not 0).

#### Scenario: No punch for entire day counts as 2
- **WHEN** employee has 0 punch records for 2026-08-15
- **AND** employee has no exemption request for that date
- **AND** employee is not in an exemption role
- **THEN** missingPunchCount SHALL increment by 2
- **AND** both morning and evening punches are counted as missing

#### Scenario: Only morning punch counts as 1 missing
- **WHEN** employee has only morning punch for 2026-08-15 (no evening punch)
- **THEN** missingPunchCount SHALL increment by 1
- **AND** only the evening punch is counted as missing

#### Scenario: Only evening punch counts as 1 missing
- **WHEN** employee has only evening punch for 2026-08-15 (no morning punch)
- **THEN** missingPunchCount SHALL increment by 1
- **AND** only the morning punch is counted as missing

#### Scenario: Both punches present counts as 0 missing
- **WHEN** employee has both morning and evening punches for 2026-08-15
- **THEN** missingPunchCount SHALL remain 0
- **AND** no missing punch penalties apply

### Requirement: Exemption request SHALL reduce expected punch count to 0

When an employee has an approved exemption request for a date, the system SHALL NOT count any missing punches for that date, regardless of whether punch records exist.

#### Scenario: Exemption request prevents missing punch count
- **WHEN** employee has approved exemption request for 2026-08-15
- **AND** employee has 0 punch records
- **THEN** missingPunchCount SHALL remain 0
- **AND** no punches are expected for that date

### Requirement: Exemption role SHALL never have missing punch count

Employees assigned to exemption roles (e.g., high-level executives) SHALL never have missing punch counts, as they are not required to punch at all.

#### Scenario: Exemption role never counts missing punches
- **WHEN** employee is in a no-punch-required role
- **AND** employee has 0 punch records for 2026-08-15
- **THEN** missingPunchCount SHALL remain 0
- **AND** attendance SHALL be granted automatically

### Requirement: Missing punch count SHALL be per work segment side

The system SHALL count missing punches per work segment side (morning in, evening out), not per work segment or per day.

For standard single-segment work days:
- 2 sides: morning in, evening out
- Maximum missing punch count per day = 2

For multi-segment work days (if any):
- Each segment has 2 sides (in, out)
- Maximum missing punch count = number of segments × 2

#### Scenario: Standard day has 2 sides
- **WHEN** employee has standard single-segment work day
- **THEN** expected punch sides = 2 (morning in, evening out)
- **AND** maximum possible missing punch count = 2

### Requirement: Partial exemption SHALL reduce missing punch count proportionally

When an employee has a partial-day exemption (e.g., morning only), the system SHALL only count missing punches for the non-exempt portion.

#### Scenario: Morning exemption reduces expected punches
- **WHEN** employee has morning exemption for 2026-08-15
- **AND** employee has no punch records
- **THEN** missingPunchCount SHALL increment by 1 (only evening expected)
- **AND** morning punch is not expected

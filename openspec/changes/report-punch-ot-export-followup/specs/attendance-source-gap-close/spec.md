## ADDED Requirements

### Requirement: Shanghai roster punches appear on Shanghai reports

When a Shanghai 昇州 or 晟州 roster employee has committed Deli punches whose employee number matches that roster row, recalculation of an OPEN month SHALL produce daily facts with those punches. Matched identity bindings without an effective punch event MUST be repaired by replay or rematch before the next pin. Rest days without a published WORK shift MUST NOT paint 漏刷 漏刷.

#### Scenario: 周步新 August punches on 晟州
- **WHEN** SZJN0002 has Deli punches in August and is on the 晟州 roster and attendance group
- **THEN** the 晟州 month matrix shows those punch times on the matching dates
- **AND** the row MUST NOT be 漏刷 漏刷 on every weekday solely because events were bound to another company id

#### Scenario: Shanghai rest day is not missed punch
- **WHEN** 2026-08-01 is Saturday and the employee's calendar has no WORK shift that day
- **THEN** the matrix cell is a rest day, not 漏刷 漏刷

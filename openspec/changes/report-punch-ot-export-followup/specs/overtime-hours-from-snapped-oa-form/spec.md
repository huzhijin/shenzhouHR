## ADDED Requirements

### Requirement: Recognised overtime cannot exceed last punch

Recognised, paid, compensatory, and voluntary overtime minutes taken from a snapped OA form SHALL NOT extend past the last punch attributed to the overtime start day. This cap applies after 30-minute snapping and before meal deductions that use the capped interval.

#### Scenario: Cap then meals
- **WHEN** a rest-day form is 09:30–20:00, last punch is 18:16, lunch 12:00–13:00 is covered, and dinner is not covered after the cap
- **THEN** minutes are counted on 09:30–18:16 minus the lunch hour
- **AND** the dinner 0.5 hour MUST NOT be deducted solely because the original form covered 18:00–18:30

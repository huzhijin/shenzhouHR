## ADDED Requirements

### Requirement: Overtime form past last punch is an exception and is capped

When an effective OA overtime interval's end is after the employee's last punch that belongs to that overtime start day (including an overnight leaving punch before 06:00), the system SHALL emit an exception and SHALL recognise overtime minutes only through that last punch. Snap-to-30-minute grid SHALL run first; the recognised end MUST then be the earlier of the snapped form end and the last punch. Hours after the last punch MUST be zero. If there is no last punch on that start day, recognised overtime minutes for that form SHALL be 0 and the exception SHALL still appear.

#### Scenario: Saturday form ends after the last punch
- **WHEN** 李尹 has an approved OA overtime 2026-08-08 09:30–20:00 and last punch 18:16
- **THEN** the overtime sheet shows an exception for form end after last punch
- **AND** recognised hours use 18:16 as the end, after meal windows that the capped interval still covers

#### Scenario: Last punch after form end does not cap
- **WHEN** approved OA overtime is 18:30–21:30 and last punch is 22:12
- **THEN** recognised hours follow the snapped form interval minus meals
- **AND** this form-beyond-punch exception MUST NOT appear

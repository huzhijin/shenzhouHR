## ADDED Requirements

### Requirement: Weekend and holiday lunch SHALL deduct one hour only when the snapped overtime interval covers 12:00–13:00

On Saturday, Sunday, and public holidays, if the snapped overtime interval fully covers `12:00–13:00` Asia/Shanghai, the system SHALL deduct 60 minutes. The window is the same at every location. The system MUST NOT deduct this hour merely because the day is a rest day.

#### Scenario: Saturday day shift covering noon
- **WHEN** a snapped overtime interval is `08:30–17:00` on Saturday
- **THEN** lunch deduction is 60 minutes

#### Scenario: Saturday evening overtime does not take lunch
- **WHEN** a snapped overtime interval is `18:00–21:00` on Saturday
- **THEN** lunch deduction is 0 minutes
- **AND** summer dinner still deducts 30 minutes if `18:00–18:30` is covered
- **AND** hours are `2.5`

#### Scenario: Public holiday uses the same noon window
- **WHEN** a snapped overtime interval is `09:00–17:00` on a public holiday
- **THEN** lunch deduction is 60 minutes

### Requirement: Dinner SHALL deduct half an hour only when the snapped overtime interval covers the dinner window

The system SHALL deduct 30 minutes of overtime only when the snapped overtime interval fully covers the dinner window. The dinner window is:

- summer: `18:00–18:30` Asia/Shanghai
- winter: 30 minutes starting at that day's published shift off time (typically `17:30–18:00`)

If the day has no published off time, winter dinner SHALL start at `17:30`. When the snapped overtime interval fully covers the dinner window, the system SHALL deduct 30 minutes. This applies on weekdays, Saturdays, Sundays, and public holidays.

#### Scenario: Summer weekday evening covering dinner
- **WHEN** a snapped overtime interval is `18:00–21:00` on a summer weekday
- **THEN** dinner deduction is 30 minutes
- **AND** hours are `2.5`

#### Scenario: Summer form starting at 18:30 does not take dinner
- **WHEN** a snapped overtime interval is `18:30–21:00` on a summer weekday
- **THEN** dinner deduction is 0 minutes
- **AND** hours are `2.5`

#### Scenario: Winter weekday uses shift-off plus 30 minutes
- **WHEN** the published shift off time is `17:30`
- **AND** a snapped overtime interval is `17:30–21:00` on a winter weekday
- **THEN** dinner deduction is 30 minutes
- **AND** hours are `3.0`

#### Scenario: Weekend overtime that also covers dinner
- **WHEN** a snapped overtime interval is `12:00–21:00` on a summer Saturday
- **THEN** lunch deduction is 60 minutes
- **AND** dinner deduction is 30 minutes
- **AND** hours are `7.5`

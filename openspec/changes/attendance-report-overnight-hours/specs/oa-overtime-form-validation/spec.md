## ADDED Requirements

### Requirement: OA overtime start SHALL reject without punches and SHALL NOT use 06:00

The OA overtime plugin SHALL keep requiring punches before filing and SHALL keep rejecting future intervals. Overnight attribution SHALL use the employee's published shift start on the end date. The plugin MUST NOT use a fixed 06:00 cutoff. A punch at or after that shift start MUST NOT prove overnight off-duty.

#### Scenario: Overtime until 07:00 is allowed when a 07:00 punch exists
- **WHEN** a Yangzhou employee files Monday 17:30–Tuesday 07:00, has Monday 08:30 and Tuesday 07:00 punches, and Tuesday shift start is 08:30
- **THEN** the plugin SHALL allow the form to start
- **AND** calculated hours SHALL use 17:30–07:00 minus applicable meal rest

#### Scenario: Morning on-duty punch cannot prove overnight off-duty
- **WHEN** the employee files Monday 17:30–Tuesday 07:00 but Tuesday's only punches are 08:32 and 17:28
- **THEN** the plugin SHALL reject with a message that there is no leaving punch before the next shift start and punch supplement is required

#### Scenario: Same-day evening overtime still needs an evening punch
- **WHEN** the employee files Monday 17:30–21:00 and Monday has only an 08:30 punch
- **THEN** the plugin SHALL reject for missing punches that day

### Requirement: OA overtime SHALL ignore scheduled work covered by leave

When checking that an overtime interval does not cover normal work, the plugin SHALL subtract intervals covered by effective leave documents. Overnight continuation until before shift start SHALL not be rejected as "only after scheduled off time".

#### Scenario: Overtime until 07:00 with Tuesday morning leave
- **WHEN** Tuesday 08:30–12:00 is covered by approved leave and overtime ends Tuesday 07:00
- **THEN** the plugin SHALL NOT reject the row for entering normal work time

#### Scenario: Overtime into unleaved morning work is rejected
- **WHEN** overtime covers Tuesday 08:30–10:00 and that morning has no leave
- **THEN** the plugin SHALL reject the row
- **AND** the message SHALL state that overtime entered scheduled work without leave

### Requirement: Weekend and holiday lunch rest SHALL always be 12:00–13:00

On Saturday, Sunday, and public holidays the lunch deduction window SHALL be 12:00–13:00 and SHALL deduct 60 minutes when the overtime interval overlaps that window. The plugin MUST NOT use the summer weekday lunch gap (for example 12:00–13:30). Dinner rest SHALL remain 30 minutes starting at that day's shift off time (winter 17:30–18:00, summer 18:00–18:30 when those are the published off times).

#### Scenario: Summer weekend lunch does not use 12:00–13:30
- **WHEN** Saturday overtime is 13:00–13:30 during a summer weekday shift whose lunch gap is 12:00–13:30
- **THEN** weekend lunch rest is 12:00–13:00
- **AND** 13:00–13:30 SHALL NOT deduct the lunch hour

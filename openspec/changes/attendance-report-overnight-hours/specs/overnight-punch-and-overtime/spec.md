## ADDED Requirements

### Requirement: Scheduled-day punches SHALL use earliest arrival and latest departure

On a scheduled work day the system SHALL select the earliest punch in the day's arrival window as the on-duty punch and the latest punch in the day's departure window as the off-duty punch. Late minutes, early-departure minutes, confirmed work, and the month-matrix morning/afternoon times SHALL use that pair. Employees SHALL NOT be required to punch at scheduled off time (for example 17:30) when they continue into overtime.

#### Scenario: Multiple punches keep earliest morning and latest evening
- **WHEN** an employee punches 07:52, 08:05, 12:00, 17:00 and 18:20 and the arrival/departure windows contain 07:52–08:05 and 17:00–18:20
- **THEN** the on-duty punch is 07:52 and the off-duty punch is 18:20
- **AND** late and work hours are calculated from that pair

#### Scenario: Continuing overtime does not require a 17:30 punch
- **WHEN** an employee punches 08:30 and does not punch at 17:30 because they stay for overtime
- **THEN** the system SHALL NOT treat 17:30 as a required off-duty punch for the scheduled pair

### Requirement: Overnight leave punches SHALL belong to the previous business day

A punch on calendar date D that occurs at or after 00:00 and strictly before that employee's published shift start on D SHALL be the previous day's overtime off-duty punch. It MUST NOT be the on-duty punch for D. The system MUST NOT use a fixed 06:00 cutoff.

#### Scenario: 07:00 punch is yesterday's overtime off-duty for a 08:30 shift
- **WHEN** Yangzhou shift start on Tuesday is 08:30 and the employee punches Tuesday 07:00
- **THEN** Tuesday 07:00 is Monday's overtime off-duty punch
- **AND** Tuesday's on-duty punch is the earliest punch in Tuesday's arrival window, not 07:00

#### Scenario: Dalian uses 07:30 not 08:30
- **WHEN** Dalian shift start on Tuesday is 07:30 and the employee punches Tuesday 07:00
- **THEN** Tuesday 07:00 is Monday's overtime off-duty punch

#### Scenario: Next-morning work after overnight overtime is allowed
- **WHEN** an employee punches Monday 08:30, Tuesday 05:50, then Tuesday 08:32 in the arrival window, and has no leave Tuesday morning
- **THEN** Tuesday on-duty is 08:32
- **AND** the day is not fake overtime solely because overnight overtime was followed by morning work
- **AND** the system SHALL NOT require a rest gap between 05:50 and 08:32

### Requirement: Overnight overtime plus morning leave SHALL be a valid combination

When overtime ends before the next day's shift start and the next morning is covered by an effective leave document, the system SHALL treat both as valid. The morning slot SHALL show the leave, not the overnight off-duty punch.

#### Scenario: Overtime until 07:00 with Tuesday morning leave
- **WHEN** the employee punches Monday 08:30 and Tuesday 07:00, has an approved morning leave covering Tuesday 08:30–12:00, and later has an approved overtime document Monday 17:30–Tuesday 07:00
- **THEN** Monday shows on-duty 08:30 and overtime off-duty 07:00
- **AND** Tuesday morning shows the leave label
- **AND** no missing-punch or fake-overtime exception is raised for that combination

### Requirement: Missing overnight off-duty punch SHALL be a supplement case

When the employee did not punch between 00:00 and the next day's shift start, has no effective morning leave that day, and overnight overtime is expected or an overtime document cannot be filed, the previous day SHALL record a missing off-duty punch and SHALL prompt punch supplement.

#### Scenario: Forgot the leaving punch
- **WHEN** the employee punches Monday 08:30, punches Tuesday 08:32 in the arrival window, has no Tuesday punch before shift start, and has no Tuesday morning leave
- **THEN** Monday records a missing off-duty punch with a supplement prompt
- **AND** Tuesday on-duty remains 08:32

### Requirement: Fake overtime SHALL be overtime covering unleaved scheduled work

The system SHALL raise fake overtime when an overtime interval overlaps a scheduled work interval that is not covered by effective leave. Overtime that ends before shift start is not fake overtime. Overtime that overlaps a morning covered by leave is not fake overtime.

#### Scenario: Overtime into the morning without leave
- **WHEN** an overtime interval covers Tuesday 08:30–10:00 and Tuesday morning has no effective leave
- **THEN** the system SHALL raise a fake-overtime exception
- **AND** recognized overtime minutes for that interval SHALL be 0

#### Scenario: Overtime until 07:00 is not fake
- **WHEN** overtime ends Tuesday 07:00 and Yangzhou shift start is 08:30
- **THEN** the system SHALL NOT raise fake overtime for that end time alone

## MODIFIED Requirements

### Requirement: Lateness and early departure retain raw and chargeable minutes

Late and early-departure decisions SHALL measure only uncovered scheduled minutes and SHALL retain original minutes, evidence-covered minutes, grace consumption and final chargeable minutes.

Arrival is late when the selected on-duty instant is **not before** the published WORK segment start in Asia/Shanghai. `08:29:59` is on time for an `08:30:00` start. `08:30:00` is late. Exact-on-start raw minutes MAY be 0; the day is still a late event. Monthly grace SHALL zero chargeable minutes inside the configured window but MUST NOT clear the late event used by the month matrix and 迟到统计.

#### Scenario: Last on-time second
- **WHEN** planned start is 08:30:00 Asia/Shanghai and valid arrival is 08:29:59
- **THEN** the arrival is not late
- **AND** raw late minutes are 0
- **AND** no grace opportunity is consumed

#### Scenario: Shift start is already late
- **WHEN** planned start is 08:30:00 Asia/Shanghai and valid arrival is 08:30:00
- **THEN** the day is a late event
- **AND** the month matrix morning slot text is `08:30 迟到` with late color
- **AND** raw late minutes are 0
- **AND** no grace opportunity is consumed

#### Scenario: Displayed minute 08:30 with seconds is late
- **WHEN** planned start is 08:30:00 and valid arrival is 08:30:30
- **THEN** the day is a late event
- **AND** slot text uses `HH:mm` so it shows `08:30 迟到`

#### Scenario: Grace zeros chargeable minutes but not the late mark
- **WHEN** raw late minutes are 1 or exactly the configured default 15 and an employee-month opportunity is available
- **THEN** original minutes remain visible
- **AND** chargeable minutes become 0
- **AND** one explicit grace consumption decision is returned
- **AND** the month matrix still shows `HH:mm 迟到` with late color

#### Scenario: Above grace maximum
- **WHEN** raw late minutes are 16 under the default maximum
- **THEN** the event is not exempted and does not consume the grace opportunity

#### Scenario: Interval evidence covers part of late or early time
- **WHEN** approved outing or leave covers part of the gap
- **THEN** only the uncovered minutes are late/early while the explanation identifies the covering evidence

#### Scenario: Changing attendance group does not reset monthly usage
- **WHEN** the employee changes attendance group during the same natural month
- **THEN** the employee-month grace snapshot remains shared and already-consumed opportunities stay consumed

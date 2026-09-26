## ADDED Requirements

### Requirement: Overnight leaving and next-morning arrival both display

Workday punch selection SHALL split at 06:00 Asia/Shanghai on the next calendar day. A punch in `[D+1 00:00, D+1 06:00)` SHALL be the previous work day's last punch and SHALL display as `次日 HH:mm` on day D. A punch in `[D+1 06:00, D+1 published shift start)` SHALL be day D+1's first punch (上班) and MUST NOT be used as day D's last punch. When day D has no evening punch after noon, day D's off-duty cell SHALL show 漏刷 rather than consuming D+1's 07:xx card.

#### Scenario: Early arrival stays on the next morning
- **WHEN** an employee punches 08:31 on D and 07:22 on D+1, and D+1 shift start is 08:30
- **THEN** day D last punch is not 07:22
- **AND** day D+1 first punch is 07:22
- **AND** if D has no punch after noon, day D off-duty is 漏刷

#### Scenario: True overnight leave still belongs to the start day
- **WHEN** an employee punches 08:26 on D and 00:14 on D+1
- **THEN** day D last punch displays as `次日 00:14`
- **AND** day D+1 first punch is not 00:14

#### Scenario: Evening leave and next-morning arrival both remain
- **WHEN** an employee punches 08:11 and 21:00 on D, then 07:56 and 18:27 on D+1
- **THEN** day D shows 08:11 and 21:00
- **AND** day D+1 shows 07:56 and 18:27

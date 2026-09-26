## MODIFIED Requirements

### Requirement: Rest-day punches SHALL appear as times

On Saturday, Sunday, or a public holiday, when the employee has at least one activated punch on that calendar date in Asia/Shanghai, the month-matrix cell SHALL show those punch times. The system MUST NOT blank the cell solely because the day is a rest day or because the punches were not consumed by weekday shift matching.

The two slots SHALL be the **earliest** punch and the **latest** punch in that calendar day's evidence window. The system MUST NOT keep only punches before 12:00 as 上班 and only the latest punch at or after 12:00 as 下班 on a rest day. A single punch SHALL occupy the morning band when its local time is at or before 12:00, otherwise the afternoon band. When more than two punches exist, the grid keeps first and last; intermediate times MAY appear only in hover.

#### Scenario: Saturday morning overtime shows both punches
- **WHEN** Saturday is a rest day and the employee has punches at 10:00 and 12:00 Asia/Shanghai
- **THEN** the cell shows `10:00` on the morning line and `12:00` on the afternoon line

#### Scenario: Saturday afternoon overtime shows both punches
- **WHEN** Saturday is a rest day and the employee has punches at 15:00 and 18:00 Asia/Shanghai
- **THEN** the cell shows `15:00` on the morning line and `18:00` on the afternoon line
- **AND** the 15:00 punch is not dropped because it is after noon

#### Scenario: Saturday overtime day with two punches spanning noon
- **WHEN** Saturday 15 August is a rest day and the employee has punches at 08:30 and 15:00 Asia/Shanghai
- **THEN** the cell shows `08:30` on the morning line and `15:00` on the afternoon line
- **AND** the cell is not an empty rest-day beige block

#### Scenario: Four rest-day punches keep first and last on the grid
- **WHEN** a rest day has punches at 10:00, 12:00, 15:00, and 18:00
- **THEN** the grid shows `10:00` and `18:00`
- **AND** 12:00 and 15:00 are not required on the grid
- **AND** hover MAY list the intermediate times

#### Scenario: Rest day with no punches and no documents stays empty
- **WHEN** Sunday has no punches and no effective attendance document
- **THEN** the cell stays empty with rest-day color

### Requirement: Effective rest-day overtime SHALL be visible

When a rest day has an effective approved OA overtime document covering that calendar date, the system SHALL make overtime visible:

- If punch times are shown, slots that are not already occupied by leave, 迟到, 早退, or 漏刷 SHALL use overtime legend color, and hover SHALL name 加班. Times stay on the grid; the word 加班 MUST NOT replace the times.
- If no punch times exist for that date, at least one slot SHALL show `加班` with overtime color. The cell MUST NOT remain an empty rest-day beige block.

Rest-day overtime color SHALL NOT require recognized overtime minutes. Rest-day visibility MUST NOT depend on weekday shift segments existing.

#### Scenario: Saturday overtime form with punches is green with times
- **WHEN** an employee has an approved overtime document 08:30–15:00 on Saturday and punches at those times
- **THEN** the cell shows those times
- **AND** overtime color is applied
- **AND** hover includes 加班

#### Scenario: Saturday overtime form without punches still marks 加班
- **WHEN** an approved overtime document covers Saturday and that date has no punches
- **THEN** the cell shows `加班` with overtime color
- **AND** the cell is not empty rest-day beige

#### Scenario: Rest-day overtime form with zero recognized minutes is still green
- **WHEN** an approved overtime document covers Saturday and recognized overtime minutes for that date are 0
- **THEN** the rest-day cell still uses overtime color when punches or the 加班 label are shown

#### Scenario: Rest-day late checkout without overtime document is not green
- **WHEN** Saturday has punches 08:26 and 21:38 and no effective overtime document
- **THEN** the times are shown
- **AND** the cell does not use overtime color

## ADDED Requirements

### Requirement: Overnight leaving punches SHALL use next-day shift start

For employee E on calendar date D, a punch at or after D+1 00:00 and strictly before E's published WORK shift start on D+1 SHALL belong to D as the off-duty / overtime leaving punch. The system MUST NOT use a fixed 06:00 cutoff. That punch MUST NOT be the on-duty punch for D+1.

#### Scenario: Leaving at 00:14 belongs to the previous work day
- **WHEN** 金玉亮 punches 2026-08-11 08:26 and 2026-08-12 00:14, and 08-12 shift start is 08:30
- **THEN** 08-11 last punch is 00:14
- **AND** 08-12 first punch is the earliest punch in the 08-12 arrival window, not 00:14

#### Scenario: 07:00 is yesterday's leaving punch for an 08:30 shift
- **WHEN** Yangzhou shift start on Tuesday is 08:30 and the employee punches Tuesday 07:00
- **THEN** Tuesday 07:00 is Monday's overtime off-duty punch

### Requirement: Cross-midnight leaving time SHALL display as 次日 HH:mm on the start day

Month matrix afternoon/off slot, 考勤日报 下班, hover, and self-service punch clocks SHALL render a leaving punch whose Shanghai calendar date is after the business date as `次日 HH:mm`. They MUST NOT render a bare `HH:mm` that can be read as the same afternoon.

#### Scenario: Matrix and journal show 次日 00:14
- **WHEN** 08-11 last punch is 2026-08-12 00:14
- **THEN** 08-11 下班 / 下午格 text is `次日 00:14`
- **AND** 08-12 morning slot does not show `00:14`

#### Scenario: Same-calendar evening off stays HH:mm
- **WHEN** 金玉亮 punches 2026-08-10 08:26 and 19:01
- **THEN** 08-10 下班 is `19:01` with no `次日` prefix

### Requirement: Middle punches SHALL NOT count as missing punches

On a scheduled work day the on-duty / off-duty pair remains earliest arrival-window punch and latest qualifying leaving punch (including overnight). Extra punches between them, including going home and returning, SHALL NOT increment missing-punch count.

#### Scenario: Four punches are not 缺卡
- **WHEN** an employee punches 08:30, 17:30, 21:00, and next-day 02:00
- **THEN** missing punch count is 0
- **AND** 考勤日报 备注 MAY name 白班 08:30–17:30 and 加班 21:00–次日 02:00 when those middle punches exist
- **AND** the remark MUST NOT invent middle punches that were not recorded

#### Scenario: Two punches overnight still pair
- **WHEN** an employee punches only 08:26 and next-day 00:14
- **THEN** missing punch count is 0
- **AND** 备注 does not claim a 17:30 or 21:00 punch

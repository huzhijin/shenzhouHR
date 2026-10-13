## ADDED Requirements

### Requirement: Exception overview names the day and the explicit type

Each 考勤异常总览 row SHALL be one employee, one business date, and one exception type. The type column SHALL use a closed Chinese label. Missing-punch findings SHALL distinguish on-duty from off-duty:

- 迟到
- 早退
- 上班缺卡
- 下班缺卡
- 旷工
- 虚假加班

Other existing calculation types that remain visible SHALL keep a one-to-one Chinese label and MUST NOT be collapsed into 未识别异常类型 when the backend code is known.

The business date of the row SHALL be the day the exception occurred. The system MUST NOT hide the date inside an evidence-summary paragraph.

#### Scenario: Missing off-duty punch is 下班缺卡 on that date
- **WHEN** 17 August is a scheduled day with an on-duty punch and no off-duty punch, and it is not covered by leave, outing, trip, or exemption
- **THEN** the overview contains a row whose date is 2026-08-17
- **AND** the type is 下班缺卡

#### Scenario: Missing on-duty punch is 上班缺卡
- **WHEN** a scheduled day has only an off-duty punch
- **THEN** the type is 上班缺卡
- **AND** the row date is that business date

#### Scenario: Late stays 迟到 with the date
- **WHEN** 6 August has penalized late minutes greater than zero
- **THEN** the overview contains a 迟到 row dated 2026-08-06

#### Scenario: Full-day absence is 旷工
- **WHEN** a scheduled day has no work, no leave, and no punches
- **THEN** the type is 旷工
- **AND** the type is not 缺卡超期 or 缺卡待补签

### Requirement: Details replace evidence summary on the main table

The exception overview main table SHALL show a 详情 column and MUST NOT show 证据摘要 as a default column. Details SHALL state the concrete fact in one short line, including which side of the day is wrong when that applies. Examples:

- 迟到: `上班 08:59，计罚 29 分钟`
- 早退: `下班 16:42，早退 48 分钟`
- 上班缺卡: `无上班卡，下班 18:26`
- 下班缺卡: `上班 08:24，无下班卡`
- 旷工: `应出勤，无打卡无单据`
- 虚假加班: `加班时段盖住未请假的上班时段`

Evidence summary text MAY remain in the payload for audit or an optional expand control. It MUST NOT be the primary readable column.

#### Scenario: Off-duty missing punch details name the missing side
- **WHEN** the finding is a missing afternoon punch on 17 August with morning punch 18:17 displayed as the only card
- **THEN** details state that the off-duty punch is missing on that date
- **AND** the main table has no 证据摘要 column

#### Scenario: Type labels stay stable
- **WHEN** the backend exception code is `LATE`, `EARLY_DEPARTURE`, or `ABSENCE`
- **THEN** the type column is 迟到, 早退, or 旷工 respectively
- **AND** details carry minutes or the empty-day explanation rather than replacing the type

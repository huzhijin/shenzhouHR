## ADDED Requirements

### Requirement: Each recognized leave type has a unique legend color

The month-matrix legend and date-cell slots SHALL assign one background color to each recognized leave type. That color MUST be distinct from every other leave type and from the existing non-leave legend colors for 迟到, 早退, 漏刷, 加班, 外出, 出差, 休息日, and 补签. Existing 调休, 事假, 病假, and 年假 colors stay as they are; newly colored leave types MUST NOT reuse those four hex values either.

Recognized leave labels and their independent colors:

- 年假
- 事假
- 病假
- 调休
- 婚假
- 产假
- 陪产假
- 丧假
- 工伤假
- 护理假
- 哺乳假
- 孕检假
- 计生假

The legend SHALL list every leave type that has a color. Excel export SHALL use the same colors as the on-screen matrix.

#### Scenario: Paternity leave uses its own color
- **WHEN** both slots of a date are covered by effective paternity leave
- **THEN** the cell shows a single centered `陪产假`
- **AND** the cell uses the 陪产假 legend color
- **AND** that color is not the 年假, 病假, 事假, 调休, 加班, 外出, or 出差 color

#### Scenario: Marriage leave is not uncolored
- **WHEN** an effective leave document of type marriage covers the afternoon slot
- **THEN** the afternoon text is `婚假`
- **AND** the afternoon band uses the 婚假 legend color

#### Scenario: Two different leave types on one date stay two colors
- **WHEN** the morning slot is annual leave and the afternoon slot is compensatory time-off
- **THEN** the morning band is `年假` with the 年假 color
- **AND** the afternoon band is `调休` with the 调休 color
- **AND** the cell is not merged

### Requirement: Compensatory leave stored as COMPENSATORY SHALL display as 调休

Leave documents whose stored type is `COMPENSATORY`, `TIME_OFF`, `调休`, or `调休假` SHALL use the label `调休` and the 调休 legend color. The system MUST NOT render those documents as generic `请假` with no tone.

#### Scenario: Afternoon OA 调休 stored as COMPENSATORY
- **WHEN** an approved leave document has leave type `COMPENSATORY` from 13:30 to 18:00 Asia/Shanghai and the employee has a morning punch at 07:59
- **THEN** the morning text is `07:59` without leave color
- **AND** the afternoon text is `调休` with the 调休 color
- **AND** neither slot text is `请假`
- **AND** hover names 调休 and the afternoon hours, not 请假

#### Scenario: Full-day 调休 merges
- **WHEN** both slots of a date are covered by an effective `COMPENSATORY` leave
- **THEN** the cell shows a single centered `调休`
- **AND** the 调休 color fills the whole cell

### Requirement: Known leave types MUST NOT collapse to 请假

The assembler MUST map every `LeaveType` enum name and every catalog leave code to the Chinese label above. Unknown non-Chinese values MAY show `请假` with a dedicated fallback color that is still distinct from other legend colors. Known types MUST NOT use that fallback.

#### Scenario: Enum name COMPENSATORY is not fallback
- **WHEN** the OA report fact leave type string is `COMPENSATORY`
- **THEN** the slot label is `调休`
- **AND** the slot tone is `TIME_OFF`

#### Scenario: Enum name PATERNITY is not fallback
- **WHEN** the OA report fact leave type string is `PATERNITY`
- **THEN** the slot label is `陪产假`
- **AND** the slot uses the 陪产假 tone, not null

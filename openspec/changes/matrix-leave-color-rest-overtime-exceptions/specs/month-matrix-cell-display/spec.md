## MODIFIED Requirements

### Requirement: Legend colors apply; other leave types still render

The system SHALL color slots with the customer legend for 迟到, 早退, 漏刷, 加班, 调休, 外出, 出差, 事假, 病假, 年假, 休息日, and 补签. Every other recognized leave type, including 陪产假, 婚假, 丧假, 产假, 工伤假, 护理假, 哺乳假, 孕检假, and 计生假, MUST appear as its Chinese leave name AND MUST use its own legend color defined by `leave-type-unique-colors`. Those types MUST NOT render as uncolored white cells.

The system MUST NOT collapse those leave types into a generic `其他假别` label on the main grid. The system MUST NOT paint 驻外, 不打卡, 离职, 入职, or 停职留薪 as matrix cell text.

#### Scenario: Paternity leave is visible with its legend color
- **WHEN** both slots of a date are covered by effective paternity leave
- **THEN** the cell shows `陪产假`
- **AND** the cell uses the 陪产假 legend color

#### Scenario: Marriage and bereavement leave are visible
- **WHEN** an effective leave document has leave type marriage or bereavement covering a date
- **THEN** the cell text is `婚假` or `丧假` respectively
- **AND** the text is not replaced by `其他假别`
- **AND** each uses its own legend color

#### Scenario: Employment lifecycle labels stay out of the grid
- **WHEN** an employee has hire, resign, stationed-out, no-punch, or unpaid-suspension facts
- **THEN** the matrix cell does not render `入职`, `离职`, `驻外`, `不打卡`, or `停职留薪` as the slot label

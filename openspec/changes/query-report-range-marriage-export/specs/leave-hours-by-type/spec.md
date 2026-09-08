## MODIFIED Requirements

### Requirement: Calendar leave types SHALL count weekend hours

Approved 计生假, 陪产假, 产假, 病假, 丧假, 孕检假, 哺乳假, and 其他 that span a Saturday or Sunday SHALL include those weekend days in recognized leave hours. Each included day SHALL use that employee's published shift work-segment minutes for the same attendance group (not a hardcoded eight hours). When the weekend has no published work segments, the system SHALL use that employee's weekday shift template minutes for the same season. 婚假 is not a calendar leave type for this rule.

#### Scenario: Maternity leave Friday through Monday includes the weekend
- **WHEN** approved 产假 covers Friday 08:30 through Monday 17:30 for a Yangzhou winter shift (3.5 + 4.5 hours per day)
- **THEN** recognized hours include Friday, Saturday, Sunday, and Monday
- **AND** Saturday and Sunday each contribute 8.0 hours from the weekday template

### Requirement: Workday-only leave types SHALL exclude weekends

Approved 年休假, 调休假, 事假, and 婚假 SHALL NOT include Saturday or Sunday hours. Public holidays SHALL be treated the same as weekends for this exclusion. Rest days SHALL follow the employee's attendance calendar, so an adjusted workday still counts and a public holiday does not. Query-page 请假统计 hours, leave-summary hours, monthly work-hour leave hours, daily-journal leave, and month-matrix cells SHALL use this rule. On a rest day or public holiday covered by 婚假, the matrix cell SHALL show 休息日 or 节假日, not 婚假.

#### Scenario: Personal leave Friday through Monday excludes the weekend
- **WHEN** approved 事假 covers Friday 08:30 through Monday 17:30
- **THEN** recognized hours include Friday and Monday only

#### Scenario: Marriage leave Friday through Monday excludes the weekend
- **WHEN** approved 婚假 covers Friday 08:30 through Monday 17:30 for a Yangzhou winter shift (3.5 + 4.5 hours per day)
- **THEN** recognized hours include Friday and Monday only (16.0 hours)
- **AND** Saturday and Sunday are omitted from 请假统计 hours
- **AND** Saturday and Sunday matrix cells are 休息日, not 婚假

#### Scenario: Marriage leave skips a public holiday
- **WHEN** approved 婚假 covers a published PUBLIC_HOLIDAY
- **THEN** recognized marriage-leave hours for that date are 0
- **AND** the matrix cell for that date is 节假日, not 婚假

#### Scenario: Marriage leave counts an adjusted workday
- **WHEN** a calendar day in a 婚假 interval is ADJUSTED_WORKDAY
- **THEN** recognized hours include that day's published WORK segments

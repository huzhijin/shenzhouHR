## ADDED Requirements

### Requirement: Calendar leave types SHALL count weekend hours

Approved 婚假, 计生假, 陪产假, 产假, 病假, 丧假, 孕检假, 哺乳假, and 其他 that span a Saturday or Sunday SHALL include those weekend days in recognized leave hours. Each included day SHALL use that employee's published shift work-segment minutes for the same attendance group (not a hardcoded eight hours). When the weekend has no published work segments, the system SHALL use that employee's weekday shift template minutes for the same season.

#### Scenario: Marriage leave Friday through Monday includes the weekend
- **WHEN** approved 婚假 covers Friday 08:30 through Monday 17:30 for a Yangzhou winter shift (3.5 + 4.5 hours per day)
- **THEN** recognized hours include Friday, Saturday, Sunday, and Monday
- **AND** Saturday and Sunday each contribute 8.0 hours from the weekday template

### Requirement: Workday-only leave types SHALL exclude weekends

Approved 年休假, 调休假, and 事假 SHALL NOT include Saturday or Sunday hours. Public holidays SHALL be treated the same as weekends for this exclusion.

#### Scenario: Personal leave Friday through Monday excludes the weekend
- **WHEN** approved 事假 covers Friday 08:30 through Monday 17:30
- **THEN** recognized hours include Friday and Monday only

### Requirement: Leave hours SHALL follow the employee's shift segments

Morning and afternoon leave hours SHALL equal the published WORK segments for that employee and date. Yangzhou winter morning is 08:30–12:00 (3.5h) and afternoon 13:00–17:30 (4.5h). Yangzhou summer afternoon is 13:30–18:00 (4.5h). Dalian morning is 07:30–12:00 (4.5h) and afternoon 13:00–16:30 (3.5h). Chengdu SHALL use its published segments. Month-matrix hover MUST NOT hardcode Yangzhou winter 3.5/4.5 for every location.

#### Scenario: Dalian morning annual leave is 4.5 hours
- **WHEN** a Dalian employee takes approved morning 年休假
- **THEN** recognized annual-leave hours for that day are 4.5
- **AND** the matrix hover reports 4.5 hours, not 3.5

#### Scenario: Yangzhou afternoon annual leave is 4.5 hours
- **WHEN** a Yangzhou winter employee takes approved afternoon 年休假
- **THEN** recognized annual-leave hours for that day are 4.5

### Requirement: Family-planning and other leave SHALL be classified

OA 计生假 and 其他 SHALL map to first-class leave types and SHALL enter leave statistics and weekend-inclusion rules. They MUST NOT be quarantined as unknown.

#### Scenario: Family-planning leave is recognized
- **WHEN** an OA leave document has showvalue 计生假
- **THEN** the leave report shows 计生假
- **AND** weekend days in the interval are included in hours

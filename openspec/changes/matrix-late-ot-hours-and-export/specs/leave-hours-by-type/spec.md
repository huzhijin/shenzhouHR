## MODIFIED Requirements

### Requirement: Leave hours SHALL follow the employee's shift segments

Morning and afternoon leave hours SHALL equal the published WORK segments for that employee and date, intersected with the snapped OA interval. Yangzhou winter morning is 08:30–12:00 (3.5h) and afternoon 13:00–17:30 (4.5h). Yangzhou summer afternoon is 13:30–18:00 (4.5h). Dalian morning is 07:30–12:00 (4.5h) and afternoon 13:00–16:30 (3.5h). Chengdu SHALL use its published segments.

Month-matrix hover MUST use the same intersection. It MUST NOT hardcode a 13:00–18:00 window that turns an afternoon 年假 into 5 hours, and MUST NOT hardcode Yangzhou 3.5/4.5 for every location. Query-page 年假小时 and hover for the same document and date SHALL match.

The main grid MUST NOT append those hours to slot text.

#### Scenario: Dalian morning annual leave is 4.5 hours
- **WHEN** a Dalian employee takes approved morning 年休假
- **THEN** recognized annual-leave hours for that day are 4.5
- **AND** the matrix hover reports 4.5 hours, not 3.5
- **AND** the grid text is `年假` without `4.5`

#### Scenario: Yangzhou afternoon annual leave is 4.5 hours
- **WHEN** a Yangzhou winter employee takes approved afternoon 年休假 covering 13:00–17:30
- **THEN** recognized annual-leave hours for that day are 4.5
- **AND** the matrix hover reports 4.5 hours

#### Scenario: Yangzhou summer OA 13:00–18:00 afternoon leave clips to 4.5
- **WHEN** published afternoon WORK is 13:30–18:00 and approved 年假 is 13:00–18:00 Asia/Shanghai
- **THEN** recognized hours and matrix hover are 4.5
- **AND** neither surface reports 5 hours

## ADDED Requirements

### Requirement: Missed-punch counts SHALL be unique on/off-duty sides

忘打卡查询、异常总览缺卡行、以及工作台类型分布中的缺卡次数 SHALL count distinct `(employeeId, businessDate, side)` where `side` is 上班 or 下班. A full scheduled day with no punch and no covering document counts as two. A one-sided miss counts as one. Duplicate exception facts MUST NOT increase the count.

#### Scenario: Duplicate exception rows do not inflate the dashboard
- **WHEN** the pin currently stores twenty-four 缺卡 facts that collapse to three unique person-day-sides
- **THEN** the workbench type distribution for that period shows 3, not 24

#### Scenario: Full day without punches counts two
- **WHEN** a scheduled weekday has no on-duty punch, no off-duty punch, and no covering leave, outing, trip, or exemption
- **THEN** 忘打卡 includes 上班缺卡 and 下班缺卡
- **AND** the person's missed-punch count for that day is 2

#### Scenario: Leave on one side is not a missed punch
- **WHEN** morning is covered by approved leave and the afternoon off-duty punch is missing
- **THEN** only 下班缺卡 is counted
- **AND** 上班缺卡 is not counted

### Requirement: 忘打卡 query SHALL match the missing-punch family

The 忘打卡 sheet SHALL select exception types in the missing-punch family (`MISSING_PUNCH`, `MISSING_PUNCH_PENDING`, `MISSING_PUNCH_OVERDUE`, `MISSING_ON_DUTY`, `MISSING_OFF_DUTY`) after they are projected to 上班缺卡 / 下班缺卡. It MUST NOT filter `exception_type = 'MISSING_PUNCH'` so strictly that all real rows disappear, and MUST NOT return every duplicate raw fact.

#### Scenario: 忘打卡 lists unique sides with details
- **WHEN** an authorized user opens 忘打卡 for a pinned month
- **THEN** each row is one person, one date, one side
- **AND** 详情 states which side is missing and the other punch time when present

## ADDED Requirements

### Requirement: 张海兰 current Deli empno is SZST0303; August leftover 0302 punches still attach to her

Live Deli now lists 张海兰 as `SZST0303`, matching the HR roster. New Deli syncs SHALL bind those punches by employee number to `SZST0303` without a name-over-empno special case. Punches already ingested for 2026-08 under device empno `SZST0302` with person name 张海兰 SHALL be replayed onto `SZST0303`. `SZST0302` remains 姜长波. After replay and 2026-08 recalculation, 考勤明细 and 月度工时统计表 for 张海兰 MUST show weekday clock times that exist in Deli.

#### Scenario: New Deli punches bind by 0303
- **WHEN** Deli sends 张海兰 with empno `SZST0303`
- **THEN** the punch attaches to roster `SZST0303`
- **AND** the matcher does not need a 张海兰-specific 0302 override

#### Scenario: August punches that landed on 0302 are replayed
- **WHEN** 2026-08 evidence already stored under `SZST0302` is named 张海兰
- **THEN** replay attaches those events to `SZST0303`
- **AND** 姜长波 keeps punches that belong to `SZST0302` 姜长波

#### Scenario: Matrix weekday cells have clocks after recalc
- **WHEN** 2026-08 Jiangsu Shenzhou 考勤明细 is queried for employee number `SZST0303`
- **THEN** 张海兰 has a row
- **AND** weekdays that have a clock time in Deli show that time, not an empty 漏刷-only month

## ADDED Requirements

### Requirement: Rest days SHALL NOT take the next morning's early on-duty punch

If calendar day D is a rest day (Saturday, Sunday, or public holiday) with no scheduled work segment and no effective overnight overtime covering into D+1, a punch on D+1 that is before D+1 shift start SHALL be D+1's on-duty (or early arrival) punch. It MUST NOT be stored as D's first or last punch. Overnight leaving punches still belong to the previous **work** day when that previous day had scheduled work or effective overnight overtime.

#### Scenario: Wu Genyin Monday 07:28 stays on Monday
- **WHEN** 2026-08-09 is Sunday rest, 2026-08-10 is a weekday, and 吴根银 punches 07:28 Asia/Shanghai on 2026-08-10 with no Sunday overtime
- **THEN** the month matrix first punch for 2026-08-10 is 07:28
- **AND** 2026-08-09 has no punch from that instant
- **AND** 2026-08-10 MUST NOT be all-day 上班漏签 solely because 07:28 was assigned to Sunday

#### Scenario: Early Monday punch after rest is not overnight leave
- **WHEN** the employee's Monday shift starts 08:30 and they punch 07:28 Monday with Sunday rest
- **THEN** 07:28 is Monday early on-duty
- **AND** the system MUST NOT treat it as Sunday's overtime off-duty

#### Scenario: Overnight leave after a work day is unchanged
- **WHEN** Monday is a work day, Tuesday shift starts 08:30, and the employee punches Tuesday 05:50 after Monday overtime
- **THEN** 05:50 remains Monday's overnight off-duty punch
- **AND** Tuesday on-duty is the earliest punch in Tuesday's arrival window

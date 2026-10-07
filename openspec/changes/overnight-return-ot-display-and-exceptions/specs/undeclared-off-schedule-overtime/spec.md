## ADDED Requirements

### Requirement: Off-schedule or overnight presence without a covering overtime document SHALL be 未报加班

When a scheduled work day's last punch is after published shift off, or is an overnight leaving punch before next shift start, and no effective overtime document covers that off-schedule interval, the system SHALL raise `OVERTIME_DOCUMENT_MISSING_OR_LATE` on the start business date. Recognized overtime minutes for that undeclared interval SHALL be 0. The exception MUST NOT block period close. 考勤异常总览 SHALL include it.

#### Scenario: Overnight leaving punch with no form
- **WHEN** 金玉亮 punches 08-11 08:26 and 08-12 00:14 and has no effective overtime document covering 08-11 after shift off through 00:14
- **THEN** 08-11 has a 未报加班 exception
- **AND** 08-11 recognized overtime minutes from that presence are 0

#### Scenario: Approved covering form clears undeclared overtime
- **WHEN** the same punches exist and an approved overtime document covers 08-11 after shift off through 00:14
- **THEN** the system SHALL NOT raise 未报加班 for that interval
- **AND** recognized hours follow the document

#### Scenario: Pending overtime with a leaving punch is still 未报加班 until effective
- **WHEN** a leaving punch exists but the overtime document is not yet effective (not approved / modified / supplemented)
- **THEN** 未报加班 remains
- **AND** recognized overtime minutes stay 0

### Requirement: Fake overtime SHALL stay a separate exception

`FAKE_OVERTIME` remains overtime covering unleaved scheduled work. Undeclared off-schedule presence MUST NOT be classified as fake overtime.

#### Scenario: Coming back after work is not fake overtime
- **WHEN** overtime is 21:00–next-day 02:00 and does not overlap unleaved WORK
- **THEN** the system SHALL NOT raise `FAKE_OVERTIME` for that interval alone

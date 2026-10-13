## ADDED Requirements

### Requirement: HR can rebuild annual-leave and time-off balances from OA

Principals with `ANNUAL_LEAVE:ADJUST` SHALL be able to run 「按 OA 重算」 for a company and calendar year. The system SHALL set each employee's annual-leave **used** hours to the sum of effective OA/paper annual-leave recognized hours in that year, and SHALL set time-off **used** hours to effective 调休 documents plus **overtime credit** from compensatory overtime documents. Differences append ledger entries (USE / OVERTIME_CREDIT / ADJUSTMENT) with source `OA_SYNC`; prior OA_SYNC entries for that year are reversed first. Then the OPEN attendance-report month intersecting today SHALL be recalculated so 年休假 / 调休额度 pages read the new pin.

#### Scenario: Annual leave used matches OA
- **WHEN** an employee has two approved 年假 documents totaling 8 hours in 2026
- **AND** HR runs 按 OA 重算 for that company and 2026
- **THEN** the annual-leave account used hours are 8
- **AND** remaining hours equal opening+grant+adjust−8

#### Scenario: Compensatory overtime becomes time-off credit
- **WHEN** approved overtime documents of type 调休 total 4 hours
- **THEN** the time-off account overtime-credit hours include 4 after sync

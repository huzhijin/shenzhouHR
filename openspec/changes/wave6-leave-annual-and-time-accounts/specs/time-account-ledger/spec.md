## ADDED Requirements

### Requirement: Time-account balance is an immutable ledger replay
The system SHALL represent opening, grant, overtime credit, return, adjustment, use, expiry and reversal as immutable ledger entries in hours. Balance SHALL equal the exact signed sum of entries in sequence order; the system MUST NOT persist an overwritable balance as the business fact.

#### Scenario: Balance replays from all entry categories
- **WHEN** a synthetic account has opening, grant, return, positive adjustment, use, expiry and reversal entries
- **THEN** replay SHALL return exactly `opening + grant + return + adjustment - use - expiry ± reversal`

#### Scenario: Expiry date alone does not change balance
- **WHEN** an entry reaches its `expiresOn` date but no `EXPIRY` ledger entry has been appended
- **THEN** replay SHALL retain the amount and SHALL expose that expiry processing is still required

### Requirement: Ledger entries carry complete provenance
Every ledger entry SHALL carry account ID, employment-period ID, type, signed amount and unit, source type/ID, business date, effective/expiry dates, policy version, period/close version where applicable, reversal target, request ID and unique strictly ordered sequence. IDs MUST be transmitted as strings and no entry may contain a real leave reason or sensitive attachment payload.

#### Scenario: Missing provenance is rejected
- **WHEN** an append command omits its account, employment period, source, business date, request ID or required version reference
- **THEN** domain validation SHALL reject it before persistence

#### Scenario: Duplicate entry identity or sequence is rejected
- **WHEN** replay contains duplicate entry IDs or duplicate sequences
- **THEN** replay SHALL fail rather than silently choose an entry

### Requirement: Entry amount direction and precision are controlled
Hours SHALL use decimal arithmetic with at most two fractional digits. `OPENING`, `GRANT`, `OVERTIME_CREDIT` and `RETURN` MUST be positive; `USE` and `EXPIRY` MUST be negative; `ADJUSTMENT` and `REVERSAL` MAY be positive or negative but MUST NOT be zero.

#### Scenario: Binary floating-point input is absent
- **WHEN** the domain constructs or sums hour amounts
- **THEN** it SHALL use exact decimal values and preserve a two-decimal result without float/double conversion

#### Scenario: Wrong sign is rejected
- **WHEN** a `USE` entry has a positive amount or a `GRANT` entry has a negative amount
- **THEN** domain validation SHALL reject the entry

### Requirement: Reversal is a single exact inverse append
A reversal SHALL be a new immutable entry that references one existing non-reversal entry on the same account and employment period and whose amount exactly negates the target. The original entry MUST remain queryable. A target MUST NOT be reversed twice, and reversing a reversal MUST be rejected.

#### Scenario: Grant is reversed
- **WHEN** a 40.00-hour grant is reversed
- **THEN** the system SHALL append a -40.00-hour `REVERSAL` referencing that grant and replay SHALL return the pre-grant balance

#### Scenario: Use is reversed
- **WHEN** a -8.00-hour use entry is reversed
- **THEN** the system SHALL append a +8.00-hour `REVERSAL` referencing that use and retain both entries

#### Scenario: Duplicate or malformed reversal is rejected
- **WHEN** two reversals reference the same target, a reversal references a missing/cross-account target, or its amount is not the exact inverse
- **THEN** replay SHALL fail and SHALL not produce a balance

### Requirement: Balance-controlled accounts reject negative prefixes
Replay and append validation for annual-leave and time-off accounts SHALL reject any sequence prefix whose balance is below zero. Concurrent application writes SHALL lock/version the account and recheck balance inside the append transaction.

#### Scenario: Use would overdraw account
- **WHEN** a 4-hour balance is followed by an 8-hour use
- **THEN** replay/append validation SHALL reject the sequence and no use entry SHALL commit

#### Scenario: Reversal restores available balance
- **WHEN** a valid use entry is followed by its exact reversal
- **THEN** replay SHALL restore the prior balance without deleting the use

### Requirement: Ledger replay is deterministic
Given the same immutable entries and policy controls, replay SHALL return the same final balance, per-type totals, last sequence and canonical replay digest regardless of input collection order. Business dates MAY be historical; fact sequence SHALL determine replay order.

#### Scenario: Input list order changes
- **WHEN** the same entries are supplied in a different collection order
- **THEN** replay SHALL sort by unique sequence and return the same totals and digest

#### Scenario: Historical business date is appended later
- **WHEN** a later sequence contains a correction with an earlier business date
- **THEN** replay SHALL preserve sequence semantics while retaining the earlier business date for reporting

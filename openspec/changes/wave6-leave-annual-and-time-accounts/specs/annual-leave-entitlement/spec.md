## ADDED Requirements

### Requirement: Qualification is separate from cumulative-service tier
The system SHALL first determine qualification from complete calendar months in the latest employment period, using a default threshold of 12 months. Only after qualification succeeds SHALL it choose a tier from `current-employment completed months + published prior-service months`. It MUST NOT allow prior service to bypass the current-employment qualification threshold.

#### Scenario: Anniversary eve is not qualified
- **WHEN** the latest employment starts on 2025-07-22 and the assessment date is 2026-07-21 under the default 12-month threshold
- **THEN** qualification SHALL be false and entitlement SHALL be 0 days / 0 hours

#### Scenario: First anniversary is qualified
- **WHEN** the latest employment starts on 2025-07-22 and the assessment date is 2026-07-22 under the default policy
- **THEN** qualification SHALL be true and the default first tier SHALL grant 5 days / 40 hours

#### Scenario: Prior service cannot bypass qualification
- **WHEN** current employment has 6 completed months and published prior service has 180 months
- **THEN** qualification SHALL remain false and entitlement SHALL be 0 days / 0 hours

#### Scenario: Configured 24-month threshold is honored
- **WHEN** the published qualification threshold is 24 months
- **THEN** the first anniversary SHALL produce no grant and the second anniversary SHALL use the cumulative-service tier

### Requirement: Cumulative-service tier boundaries are exact
The default published tiers SHALL be `[12,120)` months = 5 days / 40 hours, `[120,240)` months = 10 days / 80 hours, and `[240,+)` months = 15 days / 120 hours. Values below 12 cumulative months SHALL map to no tier. The range implementation MUST cover every non-negative month without fall-through.

#### Scenario: Day before ten-year boundary remains first tier
- **WHEN** an employment starts on 2016-07-21 and is assessed on 2026-07-20 with no prior service
- **THEN** cumulative completed service SHALL be 119 months and the entitlement SHALL be 5 days / 40 hours

#### Scenario: Ten-year boundary enters second tier
- **WHEN** an employment starts on 2016-07-21 and is assessed on 2026-07-21 with no prior service
- **THEN** cumulative completed service SHALL be 120 months and the entitlement SHALL be 10 days / 80 hours

#### Scenario: Twenty-year boundary enters third tier
- **WHEN** an employment starts on 2006-07-21 and is assessed on 2026-07-21 with no prior service
- **THEN** cumulative completed service SHALL be 240 months and the entitlement SHALL be 15 days / 120 hours

#### Scenario: Published prior service contributes only to tier
- **WHEN** current employment is qualified with 24 completed months and published prior service is 144 months
- **THEN** cumulative service SHALL be 168 months and the entitlement SHALL be 10 days / 80 hours

### Requirement: Anniversary cycles use half-open validity and ordered events
The system SHALL anchor an annual-leave cycle to the latest employment start date. On an eligible anniversary it SHALL expire the prior cycle's remaining amount before appending the new grant. The new grant SHALL be valid on `[anniversary,nextAnniversary)`, with the displayed expiry date equal to `nextAnniversary - 1 day`.

#### Scenario: Anniversary eve produces no new grant
- **WHEN** the business date is one day before an eligible anniversary
- **THEN** the system SHALL plan neither a new-cycle grant nor a new-cycle expiry

#### Scenario: Anniversary orders expiry before grant
- **WHEN** the business date is an eligible anniversary and a prior-cycle balance remains
- **THEN** the plan SHALL order the prior-cycle expiry before the new grant

#### Scenario: Grant expiry date is inclusive display boundary
- **WHEN** a grant begins on 2026-07-22
- **THEN** its `validUntilExclusive` SHALL be 2027-07-22 and its displayed `expiresOn` SHALL be 2027-07-21

### Requirement: Mid-cycle tier changes do not rewrite an existing grant
By default, reaching a higher cumulative-service tier during a grant cycle SHALL NOT append a supplemental grant. The next eligible anniversary SHALL resolve the tier and policy effective on that anniversary. Existing grants MUST retain their original tier and policy snapshot.

#### Scenario: Employee reaches ten years mid-cycle
- **WHEN** cumulative service reaches 120 months after the current cycle grant
- **THEN** no immediate difference SHALL be appended and the next anniversary SHALL use the 80-hour tier

#### Scenario: Policy changes mid-cycle
- **WHEN** a new annual policy version becomes effective after a grant was appended
- **THEN** the existing grant SHALL remain unchanged and the next anniversary SHALL resolve the then-effective version

### Requirement: Rehire creates a new qualification and anniversary anchor
Each rehire SHALL use the latest non-overlapping employment period and its start date for qualification, completed current service and anniversary cycles. Old employment grants, uses and expiries MUST remain bound to the old employment period and MUST NOT revive. Prior employment contributes to the new tier only when HR has published it through the prior-service contract.

#### Scenario: Rehire does not inherit old qualification
- **WHEN** a synthetic employee is rehired on 2026-07-21 with 144 published prior-service months
- **THEN** 2027-07-20 SHALL remain unqualified and 2027-07-21 SHALL qualify for 10 days / 80 hours

#### Scenario: Old grant remains historical
- **WHEN** a new employment period is opened after a prior period was terminated
- **THEN** the new account cycle SHALL use the new employment-period ID and no old grant/use/expiry entry SHALL become active again

### Requirement: Leap-day anniversaries follow a versioned policy
The default leap-day policy SHALL map a February 29 start to February 28 in non-leap years. A published policy MAY instead use March 1, but one grant cycle MUST use one policy consistently. Anniversary calculation SHALL use calendar dates, not elapsed-day division.

#### Scenario: Default February 29 anniversary
- **WHEN** employment starts on 2024-02-29 under the default leap-day policy
- **THEN** 2025-02-27 SHALL be before the anniversary and 2025-02-28 SHALL be the anniversary with the applicable grant

#### Scenario: March 1 alternative
- **WHEN** employment starts on 2024-02-29 under a published `MARCH_1` leap-day policy
- **THEN** 2025-02-28 SHALL be before the anniversary and 2025-03-01 SHALL be the anniversary

#### Scenario: Leap-year anniversary remains February 29
- **WHEN** a February 29 employment reaches an anniversary in a leap year
- **THEN** the anniversary SHALL be February 29 for either non-leap fallback policy

### Requirement: Employment end and period protection fail closed
The termination date SHALL remain inside `[startDate,endExclusive)`, while new leave requests on or after `endExclusive` SHALL be rejected. The configured termination policy SHALL append an expiry entry without deleting history. Anniversary, expiry, reconciliation and grant append MUST be blocked for `FROZEN`, `CLOSED` or `UNKNOWN` periods and may resume only against a new reopen version.

#### Scenario: Termination day remains eligible for existing balance use
- **WHEN** termination date is 2026-07-25 and `endExclusive` is 2026-07-26
- **THEN** an otherwise valid request on 2026-07-25 MAY use existing balance but a new request on 2026-07-26 SHALL be rejected

#### Scenario: Frozen anniversary does not auto-write
- **WHEN** an anniversary business date belongs to a frozen or closed W5 period
- **THEN** no expiry or grant SHALL be appended until reopen provides a new period version, after which the system SHALL record the new version, difference and audit

#### Scenario: Unknown period status is blocked
- **WHEN** the W5 period provider is missing or returns `UNKNOWN`
- **THEN** the system SHALL fail closed and append no ledger entry

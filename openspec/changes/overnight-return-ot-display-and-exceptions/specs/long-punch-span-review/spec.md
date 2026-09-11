## ADDED Requirements

### Requirement: First-to-last punch span of 14 hours or more SHALL be 长时在岗待审

On a business date, when last punch minus first punch is at least 14 hours (including an overnight last punch), the system SHALL raise a non-blocking `LONG_PUNCH_SPAN_REVIEW` exception on that date. 考勤异常总览 SHALL include it. Period close MUST NOT be blocked. An approved overtime document MUST NOT auto-suppress this exception; HR MAY clear it through the existing punch-adjustment exception-clear path.

#### Scenario: Overnight 08:26 to 次日 00:14 is long span
- **WHEN** first punch is 08-11 08:26 and last punch is 08-12 00:14
- **THEN** 08-11 has 长时在岗待审
- **AND** evidence summary names both punch times and the span hours

#### Scenario: Same-day 08:26–19:01 is not long span
- **WHEN** 金玉亮 punches 08-10 08:26 and 19:01
- **THEN** the system SHALL NOT raise 长时在岗待审 for that day

#### Scenario: Documented overnight still reviews long span
- **WHEN** the 08-11 overnight punches exist and an approved overtime form covers the overnight interval
- **THEN** 未报加班 is absent
- **AND** 长时在岗待审 is still present until HR clears it

#### Scenario: Undeclared overnight does not double as only long-span
- **WHEN** overnight punches exist and there is no covering overtime form
- **THEN** 未报加班 is present
- **AND** 长时在岗待审 is also present when the span is ≥ 14 hours

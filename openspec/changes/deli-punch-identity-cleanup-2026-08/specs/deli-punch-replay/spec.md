## ADDED Requirements

### Requirement: Historical punches replay by current identity
The system MUST be able to replay already-ingested Deli raw punches in a closed date range using the current identity policy. Replay MUST re-resolve each `source_record_id` once and MUST NOT insert a second effective event for the same source record.

#### Scenario: Quarantined punch becomes matched after binding
- **WHEN** a 2026-08-03 raw punch for 李扬 was stored as `QUARANTINED` with reason `NO_AUTHORITATIVE_MATCH` or wrong empno
- **AND** a confirmed snowflake binding to 李扬 `SZST0291` now exists
- **AND** replay runs for 2026-08-01 through 2026-08-13
- **THEN** that source record MUST become a valid effective punch on 李扬
- **AND** the original source_record_id MUST still appear exactly once

#### Scenario: Punch wrongly hung on another employee is moved
- **WHEN** 彭伟's 2026-08-01 off-duty punch was effective on 赵艺娴 because device empno was `SZST0289`
- **AND** replay resolves the snowflake id to 彭伟
- **THEN** the effective event MUST belong to 彭伟 on 2026-08-01
- **AND** 赵艺娴 MUST no longer own that source_record_id

#### Scenario: Replay is idempotent
- **WHEN** replay for the same range and source runs a second time
- **THEN** source_record_id counts MUST NOT increase
- **AND** employee-day first/last punches MUST stay the same as after the first successful replay

### Requirement: Replay then recalculate the open month
After a successful replay that changed any effective punch, the system MUST recalculate the affected company-month projections. Recalculation without replay MUST NOT be treated as sufficient to fill 漏刷 when evidence was missing or mis-attributed.

#### Scenario: 陆玉蕾 August after replay
- **WHEN** replay has reattached 陆玉蕾's 2026-08-01 through 2026-08-12 Deli punches
- **AND** 2026-08 for 江苏神州 is recalculated
- **THEN** the month matrix first/last times for those days MUST match the complementary Deli Excel times for 陆玉蕾
- **AND** those days MUST NOT remain `MISSING_ON_DUTY` / `MISSING_OFF_DUTY` solely for lack of punches

### Requirement: Acceptance uses complementary Deli Excel aligned by HR identity
Acceptance comparison MUST merge 考勤月报 and 月度汇总表, align people by HR employee number and name (黄凯 `SZST0667`, 张晨阳 `SZST0663`, 于跃 `SZST0671`), and treat a real clock time in either file as present. A person-day where Excel has a clock time and the matrix has neither first nor last punch is a failure.

#### Scenario: 李扬 8/4 after cleanup
- **WHEN** Deli complementary Excel shows 李扬 2026-08-04 `08:17` / `21:03`
- **AND** cleanup and recalculation have finished
- **THEN** 李扬's month-matrix cell for 2026-08-04 MUST show those punches (to the minute)
- **AND** MUST NOT display all-day 漏刷

#### Scenario: 黄凯 remains matched after name alignment
- **WHEN** Deli Excel prints 黄凯 as `SZST0663` but HR identity is `SZST0667`
- **THEN** acceptance MUST compare 黄凯's punches to `SZST0667`
- **AND** MUST NOT require rewriting the Excel file

## MODIFIED Requirements

### Requirement: Scheduled sync success plus delay triggers background recalculate

The system SHALL schedule a background recalculate after **either** a scheduled Deli punch sync **or** a scheduled OA document sync completes successfully. It MUST NOT wait for both sources to succeed in the same 12-hour slot. Multiple successes inside the configured delay (default 10 minutes) SHALL coalesce into one run. Auto-recalculate SHALL use the **last-3-days** window (today and the previous two Asia/Shanghai calendar days), not the full company-month engine, unless that window is empty. Recalculation SHALL run as SYSTEM, SHALL skip closed/frozen months, and SHALL write into the latest pin for each OPEN month it updates rather than appending a second live projection. Ordinary report and query GET requests MUST NOT start this job. Manual source-job runs SHALL NOT trigger auto-recalculate. Auto-recalculate MUST use the batch connection pool required by `attendance-report-recalc-performance`.

#### Scenario: Hourly OA success schedules a 3-day recalc without waiting for Deli
- **WHEN** the 09:00 scheduled OA sync succeeds and no Deli job ran in that hour
- **THEN** after the delay the system recalculates last 3 days for eligible OPEN months

#### Scenario: 18:00 Deli success does not wait for a 12-hour pair
- **WHEN** the 18:00 scheduled Deli sync succeeds
- **THEN** the system schedules last-3-days auto-recalculate even if the 12:00 slot already completed an earlier run

#### Scenario: Hourly OA bursts coalesce
- **WHEN** OA sync succeeds at 09:00 and again at 09:40 and the delay is 10 minutes
- **THEN** at most one auto-recalculate starts for that burst after the delay from the latest success

#### Scenario: Query does not start recalculate
- **WHEN** an authorized user opens a query page during the delay window after sync
- **THEN** the GET does not calculate, does not persist a pin, and continues to return the existing pin when one exists

#### Scenario: Auto-recalculate updates the latest pin
- **WHEN** auto-recalculate last-3-days succeeds for an OPEN month that already has a latest pin
- **THEN** the job upserts the window into that pin
- **AND** it does not leave a second live projection as current

### Requirement: Auto-recalculate uses the same merge rules as manual last-3-days

Background last-3-days recalculate SHALL keep daily facts outside the window in the same latest pin, rewrite facts inside the window, and re-sum month totals from the merged set, matching 重新计算近3天. It MUST skip closed months that intersect the window and MUST leave the previous latest pin in place when calculation fails. It MUST NOT copy outside-window facts into a new projection.

#### Scenario: Auto-recalculate on the 1st skips a closed July
- **WHEN** today is the 1st, July is closed, August is OPEN, and auto-recalculate runs last 3 days
- **THEN** only August days in the window are rewritten

#### Scenario: Failed auto-recalculate keeps the latest pin
- **WHEN** background last-3-days calculation fails closed
- **THEN** subsequent queries continue to return the previously latest pin

### Requirement: Readers keep the old pin until the new pin is stored
While auto-recalculate is running, ordinary GET and query pages SHALL return the existing latest pin. After a successful auto-recalculate, default queries SHALL use that same month pin with the updated facts and token. Requests that carry a superseded snapshot token SHALL receive a retryable snapshot-changed error and MUST NOT mix versions. The manual 「重新计算」 control SHALL remain available to principals with `ATTENDANCE_REPORT:REFRESH`.

#### Scenario: User opens reports during background recalculate
- **WHEN** a qualifying pin exists and auto-recalculate is still running
- **THEN** the query page returns the existing pinned rows without waiting for the new calculation

#### Scenario: After auto-recalculate completes
- **WHEN** the background job has persisted updates into the latest pin
- **THEN** a subsequent default query returns the new business values and snapshot token

#### Scenario: Manual recalculate still works
- **WHEN** a principal with `ATTENDANCE_REPORT:REFRESH` clicks 「重新计算」 after auto-recalculate has already run or while it is queued
- **THEN** the system performs the same latest-pin write path and does not require a publication step

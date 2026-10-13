## MODIFIED Requirements

### Requirement: Scheduled sync success plus delay triggers background recalculate

The system SHALL schedule a background recalculate after **either** a scheduled Deli punch sync **or** a scheduled OA document sync completes successfully. It MUST NOT wait for both sources to succeed in the same 12-hour slot. Multiple successes inside the configured delay (default 10 minutes) SHALL coalesce into one run. Auto-recalculate SHALL use the **last-3-days** window (today and the previous two Asia/Shanghai calendar days), not the full company-month engine, unless that window is empty. Recalculation SHALL run as SYSTEM, SHALL skip closed/frozen months, and SHALL append a new pin for each OPEN month it writes. Ordinary report and query GET requests MUST NOT start this job. Manual source-job runs SHALL NOT trigger auto-recalculate.

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

## ADDED Requirements

### Requirement: Auto-recalculate uses the same merge rules as manual last-3-days

Background last-3-days recalculate SHALL keep daily facts outside the window, rewrite facts inside the window, and re-sum month totals from the merged set, matching 重新计算近3天. It MUST skip closed months that intersect the window and MUST leave the previous pin in place when calculation fails.

#### Scenario: Auto-recalculate on the 1st skips a closed July
- **WHEN** today is the 1st, July is closed, August is OPEN, and auto-recalculate runs last 3 days
- **THEN** only August days in the window are rewritten

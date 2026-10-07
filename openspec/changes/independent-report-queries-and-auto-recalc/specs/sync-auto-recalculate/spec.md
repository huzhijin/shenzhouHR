## ADDED Requirements

### Requirement: Scheduled sync success plus delay triggers background recalculate
The system SHALL start a company-month recalculation only after both the scheduled Deli punch sync and the scheduled OA document sync for the same timetable slot have completed successfully, and only after a configured delay following that pair (default 30 minutes). The delay and an enable switch MUST be configurable. Recalculation SHALL run in the background as the SYSTEM principal, SHALL use the same complete company-month engine as the manual 「重新计算」 action, and SHALL append a new pin when the calculation completes. Ordinary report and query GET requests MUST NOT start this job.

#### Scenario: Both midnight syncs succeed then wait
- **WHEN** the 00:00 Deli scheduled sync and the 00:00 OA scheduled sync both succeed and the delay has elapsed
- **THEN** the system recalculates each eligible open company-month whose committed watermarks are newer than its pin

#### Scenario: Query does not start recalculate
- **WHEN** an authorized user opens a query page or the official report during the delay window after sync
- **THEN** the GET does not calculate, does not persist a pin, and continues to return the existing pin when one exists

#### Scenario: One scheduled sync fails
- **WHEN** the scheduled Deli sync for a slot fails and the OA sync for that slot succeeds
- **THEN** the system does not auto-recalculate for that slot

### Requirement: Auto-recalculate skips closed months and unchanged pins
Auto-recalculate SHALL cover each company with committed attendance sources for the current open month, and SHALL also include the previous open month when the local business date is the 1st or 2nd. It MUST skip frozen or closed months, MUST skip a company-month whose committed Deli and OA watermarks are not newer than the pin, and MUST leave the previous pin in place when calculation fails. Manual source-job runs SHALL NOT trigger auto-recalculate.

#### Scenario: Watermarks already match the pin
- **WHEN** the delay elapses and the latest committed Deli and OA watermarks are not newer than the current pin
- **THEN** the system does not run the month engine for that company-month

#### Scenario: Closed month is not auto-recalculated
- **WHEN** the previous month is closed or frozen
- **THEN** auto-recalculate does not replace that month's pin

#### Scenario: Failed auto-recalculate keeps numbers
- **WHEN** the background calculation fails closed
- **THEN** subsequent queries continue to return the previously pinned snapshot

#### Scenario: Manual sync does not auto-recalculate
- **WHEN** an operator starts a Deli or OA sync from the sync-jobs UI and it succeeds
- **THEN** the system does not schedule an auto-recalculate because of that run

### Requirement: Readers keep the old pin until the new pin is stored
While auto-recalculate is running, ordinary GET and query pages SHALL return the existing pin. After a successful auto-recalculate, default queries SHALL use the new pin. Requests that carry the previous snapshot token SHALL either return that exact token's facts or a retryable snapshot-changed error, and MUST NOT mix versions. The manual 「重新计算」 control SHALL remain available to principals with `ATTENDANCE_REPORT:REFRESH`.

#### Scenario: User opens reports during background recalculate
- **WHEN** a qualifying pin exists and auto-recalculate is still running
- **THEN** the query page returns the existing pinned rows without waiting for the new calculation

#### Scenario: After auto-recalculate completes
- **WHEN** the background job has persisted a newer complete pin
- **THEN** a subsequent default query returns the new business values and snapshot token

#### Scenario: Manual recalculate still works
- **WHEN** a principal with `ATTENDANCE_REPORT:REFRESH` clicks 「重新计算」 after auto-recalculate has already run or while it is queued
- **THEN** the system performs the same complete company-month recalculate path and does not require a publication step

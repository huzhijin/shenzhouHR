## MODIFIED Requirements

### Requirement: Report query calculates without publication
The system SHALL return an attendance report when an authorized report query is received and a complete pinned snapshot exists for the requested company-month, and SHALL NOT require a manual publication, approval, reopen, or republish operation before returning the report. Ordinary GET requests, query-page reads, page reloads, and dashboard reads SHALL NOT calculate from source evidence and SHALL NOT persist a pin. When no complete pinned snapshot exists, the system SHALL fail closed with a retryable not-ready error instead of calculating inside the request. When a complete pinned snapshot already exists, the system SHALL return that snapshot and SHALL NOT recalculate.

#### Scenario: First query for a company month
- **WHEN** an authorized user queries a company and month for which no complete pinned snapshot exists
- **THEN** the system does not calculate or persist a pin in that request and returns a retryable indication that calculation has not finished

#### Scenario: Repeat query uses the pinned snapshot
- **WHEN** an authorized user queries a company and month that already has a complete pinned snapshot
- **THEN** the system returns the pinned business values and snapshot token without recalculating from later committed inputs

### Requirement: Calculation uses the authoritative attendance inputs
The system SHALL calculate reports from the latest locally committed Deli punch evidence, OA attendance documents, employee identity and employment assignments, organization versions, attendance-group assignments, shift definitions, calendars, punch corrections, exemption roles, and signed attendance policies that are effective for the requested business dates at calculation time. A report query SHALL NOT call the Deli or OA external network directly. After a snapshot is pinned, later committed evidence SHALL NOT change displayed report values until an authorized explicit recalculate or a completed delayed auto-recalculate pins a successor snapshot.

#### Scenario: Deli punches and OA documents overlap a scheduled shift
- **WHEN** a scheduled employee has committed Deli punches and an approved OA document overlapping the same business date and the system is calculating a new snapshot
- **THEN** the system applies the confirmed attendance business rules to those inputs and returns one deterministic employee-day result

#### Scenario: Source data has not been committed
- **WHEN** an external source page is still in progress, failed, or quarantined and has not advanced its committed watermark
- **THEN** the calculation excludes that uncommitted page and exposes the latest committed source cutoff instead of treating the missing page as zero attendance

#### Scenario: Newly committed evidence waits for recalculate
- **WHEN** Deli or OA evidence is committed after a company-month snapshot has been pinned
- **THEN** subsequent ordinary queries continue to return the pinned values until an authorized explicit recalculate or a delayed auto-recalculate after both scheduled syncs succeed persists a successor snapshot

### Requirement: Realtime report queries meet the fast response budget
For a company-month containing no more than 5,000 active employees, the system SHALL avoid per-employee and per-day database queries for warm reads, SHALL page and filter pinned facts in bounded SQL, and SHALL meet a warm-query p95 response time of 1 second on the customer acceptance environment. A warm query is a paged read of an already pinned snapshot. Cold calculation MUST NOT run inside an ordinary GET; it runs only as manual recalculate or delayed auto-recalculate after scheduled sync.

#### Scenario: Warm repeated report query
- **WHEN** the same authorized company-month is queried after one successful pinned calculation
- **THEN** the paged report response is returned within the warm-query performance budget without repeating full source reads or daily calculations

#### Scenario: Cold company-month query
- **WHEN** no pinned snapshot exists for an authorized company-month
- **THEN** the GET does not load calculation inputs or persist a pin, and the client is told calculation has not finished

### Requirement: Source-newer-than-pin is visible without changing numbers
The system SHALL compare the latest committed Deli and OA watermarks with the pinned snapshot's recorded source cutoffs and SHALL expose whether sources are newer than the pin. Twice-daily source synchronization itself SHALL NOT recalculate. After both scheduled syncs of a slot succeed, a separate delayed job MAY replace the pin; until that job stores a successor, displayed rows stay on the current pin.

#### Scenario: Sync completes after the pin
- **WHEN** a scheduled Deli or OA sync commits a newer watermark than the pinned snapshot and the delayed auto-recalculate has not yet persisted a successor
- **THEN** the report response indicates that sources are newer than the displayed snapshot while the displayed rows stay unchanged, and principals with `ATTENDANCE_REPORT:REFRESH` are still offered the 「重新计算」 action

#### Scenario: Sources match the pin
- **WHEN** the latest committed Deli and OA watermarks are not newer than the pinned snapshot
- **THEN** the report does not claim that a recalculate is required because of source freshness

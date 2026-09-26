## ADDED Requirements

### Requirement: Recalculate input queries follow measured plans
The system SHALL load OA documents, punch events, scheduled work segments, calendars, and policies for a recalculate using SQL whose production `EXPLAIN` plan is recorded in the change evidence. New indexes MUST be justified by that plan. The system MUST NOT add an index whose leading columns are only `source_status` and `document_type` for `findEffectiveOaDocuments` unless a recorded plan shows those predicates are applied before the version window. Ranking of OA document versions MUST still consider pending and revoked rows so that a newer non-approved row can suppress a former approved row.

#### Scenario: OA effective-document query uses a matching index
- **WHEN** production `EXPLAIN` for `findEffectiveOaDocuments` is captured on a copy of production-scale data
- **THEN** the chosen index or rewritten SQL is the one that reduces examined rows for that plan
- **AND** the ranking still includes non-approved statuses before the effective-status filter

#### Scenario: Guessed status-leading index is rejected
- **WHEN** a candidate index starts with `source_status, document_type, created_at` and the inner query still ranks every `created_at <= dataAsOf` row before filtering status
- **THEN** that index is not treated as the OA query fix

### Requirement: Recalculate duration meets the phase-one budget
After the input-query and batch-write work of this change is deployed, a full open company-month recalculate for the customer production roster (on the order of 500–600 employees) MUST complete in 5 minutes or less on the customer database. Individual input queries used by that run MUST each complete in 10 seconds or less. Warm report and query-page reads of an existing latest pin MUST remain within the already specified 1 second p95 budget.

#### Scenario: Full-month recalculate after input and write fixes
- **WHEN** an authorized `ATTENDANCE_REPORT:REFRESH` principal requests 重新计算本月 for an OPEN company-month of the production roster size
- **THEN** the job finishes within 5 minutes
- **AND** no single input query in that run exceeds 10 seconds

#### Scenario: Warm query is unchanged
- **WHEN** a query page reads an already stored latest pin
- **THEN** first-page p95 remains 1 second or less

### Requirement: Mapper timeouts shrink only after queries are fast
MyBatis timeouts on recalculate input queries MUST stay at 3600 seconds until evidence shows each of those queries p95 is below 60 seconds on production-scale data. After that evidence exists, the timeout MAY be lowered to 300 seconds. The system MUST NOT ship a 300 second timeout that would abort a currently successful production recalculate.

#### Scenario: Timeout is not reduced while queries are still slow
- **WHEN** any recalculate input query still exceeds 60 seconds p95
- **THEN** the mapper timeout for that query remains 3600 seconds

#### Scenario: Timeout is reduced after the query is fast
- **WHEN** recorded production-scale runs show that query p95 below 60 seconds
- **THEN** the mapper timeout for that query is 300 seconds

### Requirement: Recalculate writes facts in bounded batches
The projection publisher MUST insert or upsert daily, exception, OA, and time-account facts in batches (default 500 rows, not exceeding the database `max_allowed_packet`). It MUST NOT issue one statement per fact row as the production write path. A failed batch MUST roll back that recalculate write and MUST leave the previous latest pin readable.

#### Scenario: Month facts are written in chunks
- **WHEN** a company-month recalculate produces more than 500 daily facts
- **THEN** the writer submits them in batches of at most 500 rows

#### Scenario: Failed batch keeps the previous pin
- **WHEN** a batch upsert fails closed
- **THEN** subsequent ordinary queries continue to return the previous latest pin

### Requirement: Recalculate does not starve interactive queries
Recalculate, auto-recalculate, and other attendance batch jobs MUST use a database connection pool distinct from the pool that serves HTTP report and query requests. Interactive GET requests MUST remain servable while a recalculate is running, within the warm-query budget, provided a latest pin already exists.

#### Scenario: Report GET during recalculate
- **WHEN** a latest pin exists and a full-month recalculate is using batch connections
- **THEN** an authorized report GET is served from the web pool without waiting for the recalculate to finish

### Requirement: Recalculate performance is measured
Each production or staging full-month recalculate used for this change MUST record start and end time, company, employee count, window type, total duration, and durations for OA load, punch load, shift load, in-memory calculate, and persist. Those samples are the baseline for later phases.

#### Scenario: A full-month run leaves a duration record
- **WHEN** a full-month recalculate completes or fails
- **THEN** the run's total duration and stage durations are stored or logged in a single structured record

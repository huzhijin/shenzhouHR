## ADDED Requirements

### Requirement: One latest pin per company-month
The system SHALL store at most one latest complete pin for each pair of company and calendar month. Recalculate MUST find or create that pin and write facts into it. The system MUST NOT create an additional live projection row for the same company-month as the default write path.

#### Scenario: Second recalculate reuses the month pin
- **WHEN** an OPEN company-month already has a latest pin and an authorized recalculate succeeds
- **THEN** default queries still read one pin for that company-month
- **AND** no second live projection remains current for that company-month

#### Scenario: First month creates the pin
- **WHEN** a company-month has no latest pin and a complete recalculate succeeds
- **THEN** the system creates exactly one latest pin for that company-month

### Requirement: Window facts are upserted without copying the rest of the month
When a recalculate window is narrower than the month, the writer MUST upsert only facts whose business dates (or document intervals) intersect the window, MUST leave stored facts outside the window in place, and MUST NOT copy those outside-window rows into a new projection. Whole-month recalculate MAY replace all facts of that month pin but MUST still write into the same latest pin.

#### Scenario: Last-three-days does not duplicate earlier days
- **WHEN** August already has a latest pin and HR recalculates 近3天 on 2026-08-26
- **THEN** daily facts for 8-01 through 8-23 stay in the same pin without being copied
- **AND** daily facts for 8-24 through 8-26 are upserted in that pin

#### Scenario: Full month still one pin
- **WHEN** HR requests 重新计算本月
- **THEN** the writer upserts the month's facts into the existing latest pin or the newly created one
- **AND** it does not publish a second live projection for that month

### Requirement: Superseded projections are archived
After a successor latest pin is stored for a company-month, previous live projections for that company-month MUST be archived or deleted from the live fact tables. Ordinary queries MUST NOT require those superseded rows. Live `attendance_report_daily_fact` SHALL not keep growing because of repeated recalculates of the same month.

#### Scenario: Repeated recalculate does not grow live daily facts by a full month each time
- **WHEN** the same OPEN company-month is fully recalculated twice
- **THEN** the live daily-fact row count for that company-month stays on the order of employees × days in the month, not 2 × employees × days

#### Scenario: Archived previous projection is not the default read
- **WHEN** a previous projection has been archived after a successful successor write
- **THEN** default report and query GET return the successor latest pin

### Requirement: Live table size is bounded by latest pins
After superseded projections are removed from live tables, live daily-fact cardinality MUST be explained by latest pins only: approximately active employees × days in each retained company-month pin. This change MUST NOT delete latest pins solely because the business date is older than three months.

#### Scenario: Latest pins for older months remain queryable
- **WHEN** a company still has a latest pin for a month more than three months ago and an authorized user queries that month
- **THEN** the system returns that latest pin rather than treating the month as archived-empty

### Requirement: Concurrent recalculates serialize on the month pin
Two recalculates for the same company-month MUST not leave two live latest pins. The writer MUST lock or otherwise serialize find-or-create of that month pin so that one latest pin remains.

#### Scenario: Overlapping full-month jobs
- **WHEN** auto-recalculate and a manual 重新计算本月 overlap for the same company-month
- **THEN** at most one live latest pin remains after both jobs finish or one fails
- **AND** readers never mix facts from two live projections for that month

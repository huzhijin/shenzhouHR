## ADDED Requirements

### Requirement: Shift families publish immutable versions through append-only timelines
The system SHALL manage stable shift identities, immutable content versions and append-only publication/deactivation timeline facts. Published business content, status and interval SHALL NOT be updated or deleted in place. Resolver SHALL derive exactly one PUBLISHED version for a business date or fail closed.

#### Scenario: Publish an adjacent seasonal version
- **WHEN** a validated future version begins exactly at the next timeline boundary
- **THEN** a publication fact is appended; old and new dates resolve their immutable versions without downtime

#### Scenario: Reject overlap
- **WHEN** a proposed publication creates two effective PUBLISHED versions for one family/date
- **THEN** 409 is returned and no publication/success audit commits

#### Scenario: Reject gap
- **WHEN** a required working timeline would have a date with no PUBLISHED version
- **THEN** publication/rollover fails with a stable gap issue

#### Scenario: Deactivate without erasing history
- **WHEN** a referenced version is future-deactivated
- **THEN** a timeline fact is appended; past dates retain the original version/digest

### Requirement: Shift versions support IANA multi-segment cross-midnight schedules
Each immutable version SHALL contain an IANA timezone snapshot and normalized ordered typed segments using local wall time plus nullable-wrapper `startDayOffset/endDayOffset` validated to 0/1. Types SHALL include WORK, BREAK and MEAL.

#### Scenario: Accept the golden cross-midnight schedule
- **WHEN** segments are `20:00 day0 WORK -> 01:00 day1`, `01:00 day1 BREAK -> 01:15 day1`, `01:15 day1 WORK -> 04:00 day1`
- **THEN** publication retains all times/offsets/timezone and produces the independent normalized digest

#### Scenario: Reject zero-length or overlap
- **WHEN** normalized segments overlap, reverse, or have zero duration
- **THEN** validation returns field-level issues and no publication fact

#### Scenario: Require WORK
- **WHEN** all segments are BREAK/MEAL
- **THEN** validation blocks publication with a WORK-required issue

### Requirement: Calendar families publish complete immutable year/effective versions
The system SHALL manage stable calendar identities, immutable content versions/days and append-only publication/deactivation timeline facts by company, location and IANA timezone. Published days SHALL never be deleted or replaced.

#### Scenario: Publish a complete interval
- **WHEN** every date in the declared half-open interval exists exactly once and lies in the declared year
- **THEN** one immutable version publication fact is appended

#### Scenario: Reject missing or duplicate date
- **WHEN** any interval date is absent, duplicated or outside the declared year
- **THEN** publication fails and the draft lineage remains available

#### Scenario: Patch draft days immutably
- **WHEN** an actor PATCH/upserts draft days
- **THEN** a new immutable draft content version is created; no published day is updated/deleted

#### Scenario: Reject calendar overlap or gap
- **WHEN** a family publication creates same-date overlap or required timeline gap
- **THEN** the whole publication/rollover transaction returns 409

#### Scenario: Same-year rollover is atomic
- **WHEN** a calendar changes mid-year
- **THEN** predecessor/successor timeline facts commit together at one boundary or neither commits

### Requirement: Calendar day uses group default shift unless explicit override exists
Each day SHALL store controlled `WORKDAY/WEEKEND/PUBLIC_HOLIDAY/SPECIAL_WORKDAY` plus optional `shiftVersionOverrideId`. Absence SHALL resolve the group shift family; presence SHALL require a compatible immutable PUBLISHED shift version.

#### Scenario: Use default family
- **WHEN** a working day has no override
- **THEN** resolver requires exactly one group-family PUBLISHED shift version on that date

#### Scenario: Validate explicit override
- **WHEN** a day supplies an override
- **THEN** it must match company, location, IANA timezone and business-date publication

#### Scenario: Permit non-working day without shift
- **WHEN** WEEKEND/PUBLIC_HOLIDAY has no override
- **THEN** the day resolves without inventing a work schedule

#### Scenario: Reject ambiguous default shift
- **WHEN** zero or multiple group-family PUBLISHED versions match a working date
- **THEN** configuration fails closed; mapper ordering cannot hide the error

### Requirement: Resolution remains deterministic across families and year boundaries
For employee+business date the resolver SHALL return exactly one group revision, location revision, calendar version/day and required shift version, with a complete digest; missing/ambiguous data SHALL fail closed.

#### Scenario: Two groups use different calendar families
- **WHEN** two groups in the same company/location/timezone/date reference different calendar families
- **THEN** each resolves its own correct immutable day/version and digest

#### Scenario: December 31 and January 1 resolve independently
- **WHEN** adjacent year timelines meet at January 1
- **THEN** each date resolves its own calendar version without inference from the other year

#### Scenario: Future publications do not alter old digest
- **WHEN** shift/calendar future facts are appended
- **THEN** old-date configuration content and digest remain byte-identical

### Requirement: Shift/calendar lifecycle is authorized, idempotent and audited
Every operation SHALL enforce attendance capability/object scope, strong expected version where applicable, durable idempotency, non-empty reason and independent failure audit.

#### Scenario: Unauthorized publish is denied
- **WHEN** a read-only actor attempts publication
- **THEN** 403 is returned, no timeline/success audit commits, and failure audit persists

#### Scenario: Concurrent publication has one winner
- **WHEN** different keys race to publish conflicting versions
- **THEN** at most one fact commits and the loser receives stable 409 with no success audit

### Requirement: Shift/calendar/version/day lists are stably paginated
Every list SHALL use bounded pagination and deterministic sort with immutable ID tie-breaker.

#### Scenario: Stable page traversal
- **WHEN** an unchanged list is traversed across pages
- **THEN** no row is omitted, repeated or reordered

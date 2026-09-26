## ADDED Requirements

### Requirement: Calculation consumes a closed immutable input snapshot
Each employee business-day calculation SHALL consume one immutable snapshot containing exact employee/employment identity, business date, business timezone, knowledge cutoff, scheduled work segments, configuration/rule references, evidence decisions, approved adjustments, period token and calculation algorithm version. The snapshot MUST have a canonical digest independent of database row order, map iteration order, process timezone or source arrival order.

#### Scenario: Equivalent inputs in different order
- **WHEN** the same scheduled segments, evidence and adjustments are supplied in different collection orders
- **THEN** the canonical input digest and resulting calculation digest are byte-identical

#### Scenario: Incomplete authoritative input
- **WHEN** employee/employment identity, timezone, configuration digest, evidence digest or period token is absent or ambiguous
- **THEN** calculation fails closed with a stable input-integrity reason and produces no successful calculation version

#### Scenario: Unknown period provider
- **WHEN** the authoritative period state is `UNKNOWN`
- **THEN** the snapshot is rejected before any daily result, exception transition or current-version mutation is committed

### Requirement: Business-day ownership supports cross-midnight segments
The calculator SHALL use the business date and instant intervals resolved from the authoritative shift/calendar snapshot. It MUST NOT derive business date by truncating UTC or a client-supplied natural date.

#### Scenario: Segment crosses midnight
- **WHEN** a scheduled segment starts on the business date and ends after local midnight
- **THEN** the entire segment remains owned by that business date and is not split merely at midnight

#### Scenario: Punch before cross-midnight cutoff
- **WHEN** a synthetic 02:00 departure event is authoritatively attributed to the previous business date before a 06:00 cutoff
- **THEN** it may close the previous business-day segment and its event ID is marked consumed for that use

#### Scenario: Cross-day punch cannot be reused
- **WHEN** a punch event was consumed as the previous business date's departure
- **THEN** the same event ID cannot also become the next business date's arrival; a distinct next-day punch is required

#### Scenario: Attribution is ambiguous
- **WHEN** configuration/evidence inputs present more than one authoritative business-date ownership for a segment or punch
- **THEN** the calculator emits an input-integrity or cross-midnight review blocker and does not guess

### Requirement: Evidence intervals are atomically split and resolved by fixed priority
The calculator SHALL split overlapping half-open evidence and adjustment intervals at every relevant boundary, then resolve each atomic slice with the fixed order: approved HR adjustment; approved revocation/reversal; approved business document; effective punch evidence; system-derived finding. It MUST NOT use last-write-wins.

#### Scenario: Overlap creates atomic slices
- **WHEN** evidence covers 09:00–12:00 and 10:00–11:00
- **THEN** calculation decisions cover 09:00–10:00, 10:00–11:00 and 11:00–12:00 with exact candidate references

#### Scenario: Higher priority wins without deleting lower evidence
- **WHEN** an approved adjustment and a lower-priority document cover the same slice
- **THEN** the adjustment is selected, the lower evidence remains a rejected candidate with a reason, and neither source record is modified

#### Scenario: Same-priority mutually exclusive evidence conflicts
- **WHEN** approved leave and approved outing conflict on the same atomic slice
- **THEN** the slice has no selected winner, produces `EVIDENCE_CONFLICT` and remains blocking

#### Scenario: Adjacent intervals do not overlap
- **WHEN** one valid document ends exactly when another begins
- **THEN** `[start,end)` semantics keep them adjacent without a false conflict

### Requirement: Punch matching is segment-specific, deterministic and single-use
Each scheduled work segment SHALL select arrival and departure from eligible effective punch events using stable candidate ordering, configured windows and explicit direction rules. One event MUST NOT be consumed more than once unless a versioned rule explicitly permits that use.

#### Scenario: One segment has valid endpoints
- **WHEN** exactly one eligible arrival and departure exist within the configured windows
- **THEN** the result item references both selected event IDs and records every rejected candidate and reason

#### Scenario: Candidate order changes
- **WHEN** equivalent eligible punch candidates arrive in a different source order
- **THEN** stable instant/direction/event-ID ordering yields the same selection and result digest

#### Scenario: Candidate selection is ambiguous
- **WHEN** multiple candidates remain equally valid under the configured rule
- **THEN** the segment produces `AMBIGUOUS_PUNCH_MATCH` rather than silently choosing one

#### Scenario: A punch is offered to two segments
- **WHEN** the same event ID would otherwise satisfy adjacent segment endpoints
- **THEN** deterministic consumption allows it only for the selected use and the other segment retains an explicit missing/ambiguous decision

### Requirement: Daily results preserve segment items and reproducible metrics
A daily attendance result SHALL consist of segment/slice result items, rule hits, evidence decisions and exceptions. All summaries MUST be reproducible from the same version's items using integer minutes.

#### Scenario: Mixed-status day
- **WHEN** a synthetic employee works the morning segment and has approved leave for the afternoon segment
- **THEN** the daily result preserves separate normal-work and leave items rather than collapsing to one status

#### Scenario: Core minute metrics
- **WHEN** the result is summarized
- **THEN** it separately reports scheduled minutes `S`, confirmed scheduled work `W_in`, extended presence `E`, recognized overtime `O`, leave/time-off `L`, absence `A`, and actual work as `W_in + O`

#### Scenario: Time-account value is not double-counted
- **WHEN** recognized overtime is tagged for future time-off credit
- **THEN** the credit tag is retained but is not added a second time to actual-work minutes

#### Scenario: Display rounding cannot change calculation
- **WHEN** minutes are displayed as decimal hours
- **THEN** rounding occurs only in the presentation and the stored/result digests retain integer-minute values

### Requirement: Lateness and early departure retain raw and chargeable minutes
Late and early-departure decisions SHALL measure only uncovered scheduled minutes and SHALL retain original minutes, evidence-covered minutes, grace consumption and final chargeable minutes.

#### Scenario: Late boundary is zero
- **WHEN** valid arrival is exactly at planned start
- **THEN** raw late minutes are 0 and no grace opportunity is consumed

#### Scenario: Grace boundaries are eligible
- **WHEN** raw late minutes are 1 or exactly the configured default 15 and an employee-month opportunity is available
- **THEN** original minutes remain visible, chargeable minutes become 0 and one explicit grace consumption decision is returned

#### Scenario: Above grace maximum
- **WHEN** raw late minutes are 16 under the default maximum
- **THEN** the event is not exempted and does not consume the grace opportunity

#### Scenario: Interval evidence covers part of late or early time
- **WHEN** approved outing or leave covers part of the gap
- **THEN** only the uncovered minutes are late/early while the explanation identifies the covering evidence

#### Scenario: Changing attendance group does not reset monthly usage
- **WHEN** the employee changes attendance group during the same natural month
- **THEN** the employee-month grace snapshot remains shared and already-consumed opportunities stay consumed

### Requirement: Missing punches remain pending until the configured deadline
For every scheduled work segment the calculator SHALL distinguish two-sided evidence, single-sided missing punch and no-punch conditions. It MUST NOT invent a missing endpoint or classify pending time as absence before the versioned deadline policy permits.

#### Scenario: Single-sided punch within deadline
- **WHEN** a segment has only an arrival or departure and the correction deadline has not expired
- **THEN** it produces `MISSING_PUNCH_PENDING`, retains the valid side and records zero finalized absence for the missing side

#### Scenario: Timely submission approved later
- **WHEN** a correction was first submitted within the default seventh natural day but approved on the ninth day
- **THEN** the timely submission prevents premature overdue finalization and approval triggers a new calculation version

#### Scenario: Default overdue single-side behavior
- **WHEN** the deadline expires without valid or timely pending evidence
- **THEN** only the uncovered missing work segment becomes absence by default and valid attendance in other segments is unchanged

#### Scenario: Entire day has no evidence
- **WHEN** every planned segment lacks punches and valid documents after the deadline
- **THEN** each uncovered segment is independently finalized according to the versioned missing-punch policy

### Requirement: Recognized overtime requires both actual evidence and valid authorization
Extended presence SHALL be reported separately from recognized overtime. Recognized overtime MUST equal eligible off-schedule actual evidence intersected with valid overtime authorization, after versioned meal deductions and deadline rules.

#### Scenario: Late departure without authorization
- **WHEN** a punch proves presence after shift end but no valid overtime document covers it
- **THEN** extended presence is retained while recognized, payable and time-off overtime minutes are 0

#### Scenario: Temporary overtime deadline
- **WHEN** first submission occurs 47 hours 59 minutes after actual overtime end under the default 48-hour rule
- **THEN** the eligible intersection may be recognized; submission at 48 hours 1 minute yields 0 recognized overtime

#### Scenario: Cross-midnight punch alone
- **WHEN** work evidence crosses midnight but no timely approved overtime document exists
- **THEN** the cross-midnight presence is not automatically recognized as overtime

#### Scenario: Meal window deduction
- **WHEN** recognized overtime fully covers an enabled meal window
- **THEN** that window is deducted at most once and the result cannot fall below 0

#### Scenario: Obligation overtime
- **WHEN** a valid policy classifies the eligible interval as obligation overtime
- **THEN** recognized work duration is retained but payable and time-off-credit minutes are both 0

### Requirement: Every result has a complete permission-neutral explanation graph
The core calculation SHALL produce an explanation graph linking result summaries to segment/slice items, rule hits, evidence decisions, adjustments, exceptions, snapshot references and request IDs. The graph MUST be complete before field-level authorization redacts its external representation.

#### Scenario: Drill down from summary
- **WHEN** a caller follows a synthetic daily metric into its explanation
- **THEN** the graph reaches the exact scheduled segment, rule snapshot, selected/rejected evidence IDs, calculation version and request/correlation references

#### Scenario: Conflict explanation
- **WHEN** a slice is `EVIDENCE_CONFLICT`
- **THEN** its explanation includes every same-priority candidate and states that no winner was selected

#### Scenario: Raw permission is absent
- **WHEN** a future interface adapter renders the graph for a caller without raw-row/file/location permission
- **THEN** protected raw fields may be redacted while stable reason codes, allowed references and calculation digests remain consistent

### Requirement: Identical snapshot replay is deterministic
For the same canonical input digest and algorithm version, calculation SHALL produce byte-identical semantic result, exception fingerprints, explanation graph and result digest regardless of wall-clock execution time or source collection order.

#### Scenario: Replay identical input
- **WHEN** an identical snapshot is calculated repeatedly with a deterministic clock value supplied in metadata
- **THEN** every semantic result and digest is identical

#### Scenario: Input changes
- **WHEN** evidence, adjustment, schedule, rule or algorithm version changes
- **THEN** the input digest changes and a later requirement can create an explainable new calculation version rather than overwriting the old one

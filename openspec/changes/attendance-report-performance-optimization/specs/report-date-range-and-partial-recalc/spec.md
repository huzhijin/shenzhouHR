## MODIFIED Requirements

### Requirement: Partial recalculate rewrites only the window and re-sums

For each calendar month overlapping the window, the engine SHALL recalculate daily facts only for dates in the intersection of that month and the window (plus one previous-day shift/punch context that is not written unless it is also in the window). Daily facts outside the window MUST remain as in the current latest pin and MUST NOT be copied into a new projection. Monthly aggregates, work-hours formula columns, and overtime document hours that depend on those daily facts SHALL be re-summed from the merged set (unchanged days + new window days). Monthly late grace MUST be reconstructed by counting consumption on unchanged earlier days of the month, then continuing through the window. The writer MUST upsert the window into the same latest month pin. Readers MUST NOT mix old and new window days; after a successful write the latest pin is the only live version for that month.

#### Scenario: Only the last three days are expensive
- **WHEN** today is 2026-08-26 and HR recalculates 近3天 for an OPEN August
- **THEN** daily facts for 8-01 through 8-23 stay in the same latest pin without being copied
- **AND** daily facts for 8-24 through 8-26 are replaced in that pin
- **AND** 月度工时 row totals equal the sum of the kept 8-01–8-23 facts plus the new 8-24–8-26 facts

#### Scenario: Late grace uses earlier days without recomputing their punches
- **WHEN** the employee already consumed monthly late grace on 8-05
- **AND** 近3天 recalculates 8-24–8-26
- **THEN** grace on 8-24 is evaluated as already consumed
- **AND** 8-05's stored daily fact is not rewritten

#### Scenario: Partial recalculate does not append a second live projection
- **WHEN** a latest pin already exists and 近3天 succeeds
- **THEN** live daily-fact rows for dates outside the window do not increase by a full extra copy of those dates

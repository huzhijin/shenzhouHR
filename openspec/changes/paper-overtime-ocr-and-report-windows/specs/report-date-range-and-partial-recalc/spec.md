## ADDED Requirements

### Requirement: Report center accepts a start and end date, not only a month

考勤报表中心 SHALL provide a start–end date filter in addition to company and organization scope. The system MUST NOT require the range to be a whole calendar month. Displayed daily cells, document rows, exception rows, and **sheet totals / overview cards** SHALL be computed from daily facts and documents that intersect the selected range. Days outside the range MUST keep their stored facts but MUST NOT be added into the displayed totals. A range spanning two calendar months SHALL read both months' pins when present.

#### Scenario: Mid-month range slices totals
- **WHEN** the user selects 2026-08-10 through 2026-08-20
- **THEN** 月度工时 displayed hours, overtime document rows, and overview cards include only facts and documents overlapping that range
- **AND** stored daily facts for 8-01–8-09 remain in the pin

#### Scenario: Range may cross months
- **WHEN** the user selects 2026-07-28 through 2026-08-05 and both months have qualifying pins
- **THEN** the view includes matching days and documents from both months

### Requirement: Recalculate offers last 3 days, last 7 days, and this month

Principals with `ATTENDANCE_REPORT:REFRESH` SHALL see three actions: 重新计算近3天, 重新计算近一周, and 重新计算本月. Windows are relative to **today** in Asia/Shanghai, inclusive of today: last 3 days = today and the previous two calendar days; last 7 days = today and the previous six calendar days; this month = the currently selected report month from day 1 through today or month end, whichever is earlier. The system MUST NOT treat 「近3天」 as the last three days of a past selected month when today is in a later month.

#### Scenario: Last 3 days from August 1
- **WHEN** today is 2026-08-01 and HR clicks 重新计算近3天
- **THEN** the window is 2026-07-30 through 2026-08-01

#### Scenario: Last 3 days does not follow a past month picker
- **WHEN** today is 2026-08-26, the month filter is 2026-07, and HR clicks 重新计算近3天
- **THEN** the window is 2026-08-24 through 2026-08-26, not 2026-07-29 through 2026-07-31

### Requirement: Partial recalculate rewrites only the window and re-sums

For each calendar month overlapping the window, the engine SHALL recalculate daily facts only for dates in the intersection of that month and the window (plus one previous-day shift/punch context that is not written unless it is also in the window). Daily facts outside the window MUST remain as in the current pin. Monthly aggregates, work-hours formula columns, and overtime document hours that depend on those daily facts SHALL be re-summed from the merged set (unchanged days + new window days). Monthly late grace MUST be reconstructed by counting consumption on unchanged earlier days of the month, then continuing through the window. The new pin MUST replace the month projection as a whole version; readers MUST NOT mix old and new window days across versions.

#### Scenario: Only the last three days are expensive
- **WHEN** today is 2026-08-26 and HR recalculates 近3天 for an OPEN August
- **THEN** daily facts for 8-01 through 8-23 stay
- **AND** daily facts for 8-24 through 8-26 are replaced
- **AND** 月度工时 row totals equal the sum of the kept 8-01–8-23 facts plus the new 8-24–8-26 facts

#### Scenario: Late grace uses earlier days without recomputing their punches
- **WHEN** the employee already consumed monthly late grace on 8-05
- **AND** 近3天 recalculates 8-24–8-26
- **THEN** grace on 8-24 is evaluated as already consumed
- **AND** 8-05's stored daily fact is not rewritten

### Requirement: Closed months in a cross-month window are skipped

When a recalc window covers a previous month, the system SHALL update that month only if its period state is OPEN (not closed, frozen, or confirmed-closed). Skipped months MUST be reported to the operator. OPEN months in the window MUST still recalculate.

#### Scenario: July closed, August open
- **WHEN** today is 2026-08-01, July is closed, August is OPEN, and HR clicks 近3天
- **THEN** 7-30 and 7-31 are not rewritten
- **AND** 8-01 is recalculated
- **AND** the response explains that July was skipped because it is closed

#### Scenario: This-month button on a closed month
- **WHEN** the selected month is closed and HR clicks 重新计算本月
- **THEN** the system refuses and does not replace the pin

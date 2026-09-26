## ADDED Requirements

### Requirement: Distinct last punch is off-duty even before noon

When `firstPunchAt` and `lastPunchAt` on a person-day are both present and not equal, the system SHALL treat the last punch as the off-duty clock regardless of whether it is before 12:00. Month matrix afternoon text, daily journal 下班, and hover MUST show that clock. They MUST NOT write 「漏刷」 or 「下班漏刷」 for lack of a post-noon punch.

#### Scenario: Weekend 08:17 and 11:36
- **WHEN** a Saturday has punches at 08:17 and 11:36 and an approved overtime document
- **THEN** the matrix morning slot shows 08:17
- **AND** the afternoon slot shows 11:36 (overtime fill allowed)
- **AND** neither slot is 「漏刷」

#### Scenario: Same single punch is still one-sided
- **WHEN** the only punch of the day is 08:17 (`firstPunchAt` equals `lastPunchAt`)
- **THEN** off-duty may still be 漏刷 if the day requires an off-duty clock and no covering document exists

### Requirement: Overtime document does not invent a missing off-duty when the last punch exists

`composeSlot` for the afternoon MUST NOT return 「漏刷」 solely because `overtimeDocument` is true and the last punch is before noon.

#### Scenario: Rest-day overtime with both clocks before noon
- **WHEN** rest-day overtime exists and last punch is 11:36
- **THEN** afternoon tone may be overtime
- **AND** afternoon text is the formatted 11:36, not 漏刷

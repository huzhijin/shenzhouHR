## ADDED Requirements

### Requirement: All overnight overtime hours stay on the start day

Human-facing sheets and the finance overtime calendar SHALL attribute an overtime interval that crosses midnight to the start date, including when the next calendar day is Saturday, Sunday, or a public holiday. Daily facts used by those sheets MUST NOT keep a positive overtime remainder on D+1 solely because the form crossed 00:00. Weekend and holiday multipliers, if any, follow the start day's calendar type.

#### Scenario: Friday night into Saturday morning
- **WHEN** approved overtime is Friday 21:00 to Saturday 02:00
- **THEN** 考勤日报, 加班日报, 每日加班, and 财务加班 all show the recognised hours on Friday
- **AND** Saturday shows 0 for that form's continuation

#### Scenario: Saturday into Sunday
- **WHEN** approved overtime is Saturday 22:00 to Sunday 02:00
- **THEN** the hours appear on Saturday
- **AND** Sunday does not receive the continuation slice

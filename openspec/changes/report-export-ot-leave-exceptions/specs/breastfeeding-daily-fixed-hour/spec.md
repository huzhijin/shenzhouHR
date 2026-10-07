## ADDED Requirements

### Requirement: Breastfeeding leave is one hour from the document start clock every workday

For leave type `BREASTFEEDING_TIME` (OA labels 哺乳时间 / 哺乳假), the system SHALL ignore the multi-day wall-clock span as a single interval. Instead, each workday from the document start date through the document end date SHALL be credited as exactly one hour beginning at the document's start local time in `Asia/Shanghai`. Saturday, Sunday, and public holidays MUST NOT receive a slice.

#### Scenario: Month-long form is still one hour per workday
- **WHEN** an approved 哺乳假 starts 2026-08-03 08:30 and ends 2026-08-31 09:30
- **THEN** Monday 08-03 is covered 08:30–09:30 only
- **AND** Tuesday 08-04 is covered 08:30–09:30 only
- **AND** Saturday 08-08 has no breastfeeding minutes

#### Scenario: Start clock is taken from the document, not a hardcoded 08:30
- **WHEN** the form starts at 09:00
- **THEN** each workday window is 09:00–10:00
- **AND** the system MUST NOT force 08:30–09:30

### Requirement: Matrix paints only the breastfeeding hour

The month matrix MUST show 哺乳假 on the slot that contains that hour (typically morning) and MUST NOT paint the whole morning or the whole day as 哺乳假 solely because the stored OA interval spans midnight-to-midnight.

#### Scenario: Afternoon remains a punch or empty
- **WHEN** the daily window is 08:30–09:30 and the employee punches out at 17:35 with no afternoon leave
- **THEN** the morning slot is 哺乳假
- **AND** the afternoon slot shows the off-duty time, not 哺乳假

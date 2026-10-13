## MODIFIED Requirements

### Requirement: Overtime color requires an effective OA overtime document

A slot MAY use overtime legend color when that business date has an effective OA overtime document (source status approved, modified, or supplemented). Recognized overtime minutes MUST NOT be required. Late checkout alone MUST NOT receive overtime color.

Overtime color SHALL be applied per slot. A morning late slot MUST keep late color even when the afternoon slot is overtime. Leave, 漏刷, 迟到, and 早退 on a slot MUST NOT be replaced by overtime color on that same slot.

The main grid SHALL keep the punch time visible; the overtime label SHALL appear in the hover text, not as a replacement for the time, except on a rest day with an overtime document and no punches, which MAY show `加班`.

#### Scenario: Approved overtime with punches is green with times
- **WHEN** an employee has punches 08:26 and 21:38 on a date with an effective OA overtime document
- **THEN** the cell shows those times
- **AND** overtime color is applied to slots that are not late, early, missing, or leave
- **AND** the hover text includes `加班`
- **AND** the main grid does not replace the time with the word `加班`

#### Scenario: Overtime document without recognized minutes is still painted
- **WHEN** an OA overtime document exists for the date and recognized overtime minutes are zero
- **THEN** slots that would otherwise show punch times use overtime color
- **AND** hover includes `加班`

#### Scenario: Late checkout without an overtime document stays normal
- **WHEN** an employee clocks out at 21:38 and there is no effective OA overtime document for that date
- **THEN** the afternoon text is `21:38` without overtime color
- **AND** the hover text does not present that date as overtime

#### Scenario: Morning late and afternoon overtime keep both colors
- **WHEN** morning arrival is a late event at 08:32 and the date has an effective overtime document with afternoon punch 21:38
- **THEN** the morning slot is `08:32 迟到` with late color
- **AND** the afternoon slot is `21:38` with overtime color
- **AND** the morning slot is not painted overtime green

### Requirement: Hover carries hours and secondary explanations

The main grid MUST NOT show leave hours such as `3.5` or `4.5`. Slot text stays `年假` (or the leave name) without a numeric suffix.

Hover text for a date cell SHALL include the slot times or replacement labels. When a half-day leave covers only one slot, hover SHALL report that slot's hours as the overlap between the OA interval and that employee's **published WORK segments** for the date, in hours with at most one decimal. Those hours MUST NOT be hardcoded as Yangzhou 3.5/4.5 for every location. Hover for overtime SHALL name 加班. Hover for punch correction SHALL name 补签.

#### Scenario: Morning half-day hours appear only on hover
- **WHEN** annual leave covers only the morning slot
- **THEN** the main grid shows `年假` without `3.5`
- **AND** hover text includes the recognized morning leave hours for that employee

#### Scenario: Afternoon Yangzhou summer leave is 4.5 on hover not 5
- **WHEN** a Yangzhou summer employee has published afternoon WORK 13:30–18:00 and an approved afternoon 年假 OA interval 13:00–18:00
- **THEN** the main grid afternoon text is `年假` without `4.5` or `5`
- **AND** hover text includes `4.5` hours
- **AND** hover does not report `5` hours

#### Scenario: Afternoon Dalian leave is 3.5 on hover
- **WHEN** a Dalian employee has published afternoon WORK 13:00–16:30 and approved afternoon 年假 covering that window
- **THEN** the main grid shows `年假` without hours
- **AND** hover text includes `3.5` hours
- **AND** hover does not report `4.5` hours

#### Scenario: Full-day leave hover does not invent half-day hours
- **WHEN** both slots are covered by the same leave type
- **THEN** hover does not describe the day as a morning half plus an afternoon half unless the OA document itself is two half-day intervals

## ADDED Requirements

### Requirement: Visual late follows shift start not chargeable minutes

The morning slot SHALL show `HH:mm 迟到` and late color when the day's selected on-duty punch is not before the published morning WORK start, including exact `08:30:00` and late minutes still inside monthly grace. Chargeable minutes MUST NOT be the only signal for matrix late.

#### Scenario: Exact 08:30 is marked late on the grid
- **WHEN** morning WORK starts at 08:30 and first punch is 08:30:00
- **THEN** morning text is `08:30 迟到`
- **AND** the slot uses late color

#### Scenario: Grace late remains visible next to overtime
- **WHEN** morning punch is 08:32 inside monthly grace and afternoon has overtime color
- **THEN** morning still shows `08:32 迟到` with late color

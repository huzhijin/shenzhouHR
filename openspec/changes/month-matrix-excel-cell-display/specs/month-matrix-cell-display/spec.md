## ADDED Requirements

### Requirement: Month matrix keeps one visual date cell per employee-day
The customer-report attendance-detail matrix SHALL keep one table row per employee and one table cell per calendar date. The cell MUST NOT show 「签到」 or 「签退」 labels. The cell outer border SHALL read as a single date cell.

#### Scenario: Employee row still has two punch lines inside one date cell
- **WHEN** an authorized user opens the monthly attendance-detail matrix
- **THEN** each employee occupies one row
- **AND** each date column is a single cell whose first line is the morning/in slot and whose second line is the afternoon/out slot

#### Scenario: Sign-in and sign-out column is absent
- **WHEN** the matrix header is rendered
- **THEN** date headers remain `dd/weekday` only
- **AND** no 签到/签退 identity column is added

### Requirement: Each slot has independent text and color
The system SHALL compose morning and afternoon slots independently. A document, exception, or punch that covers only one slot MUST NOT paint or replace the other slot.

Slot text on the main grid SHALL follow this priority for the covered slot, first match wins:
1. Leave, time-off, outing, or trip covering the slot → leave/document Chinese label without hours
2. Missing punch on that slot → `漏刷`
3. Punch correction on that slot → `补签` or `补签HH:mm` when a correction time exists
4. Late on the morning slot → `HH:mm 迟到`
5. Early departure on the afternoon slot → `HH:mm 早退`
6. Otherwise a punch time `HH:mm` when present, else empty

#### Scenario: Afternoon annual leave keeps the morning punch
- **WHEN** an effective OA leave covers only the afternoon slot and the employee has a morning punch at 08:14
- **THEN** the cell shows `08:14` on the morning line without leave color
- **AND** the afternoon line shows `年假` with annual-leave color
- **AND** the main grid does not show `年假4.5`

#### Scenario: Morning annual leave keeps the afternoon punch
- **WHEN** an effective OA leave covers only the morning slot and the employee has an afternoon punch at 18:01
- **THEN** the cell shows `年假` with annual-leave color on the morning line
- **AND** the afternoon line shows `18:01` without leave color
- **AND** the main grid does not show `年假3.5`

#### Scenario: Missing punch is 漏刷 not 漏签
- **WHEN** a scheduled slot has no effective punch and is not covered by leave, outing, trip, time-off, or punch exemption
- **THEN** that slot text is `漏刷`
- **AND** that slot uses the missed-punch legend color

#### Scenario: Punch correction remains visible
- **WHEN** an effective punch-correction document supplies the afternoon punch at 16:30
- **THEN** the afternoon slot text includes `补签` and `16:30`
- **AND** the correction uses the legend correction style (red text)

#### Scenario: Correction stacked with a document on the same slot
- **WHEN** the same slot is covered by an effective outing document and a punch correction
- **THEN** the slot text includes both `补签` and `外出`
- **AND** the slot uses outing color with correction red text

#### Scenario: Late annotates the morning time
- **WHEN** the morning slot is a penalized late punch at 08:32 and is not replaced by a leave/document label
- **THEN** the morning text is `08:32 迟到`
- **AND** the morning slot uses late color
- **AND** the afternoon slot is unchanged by that late fact

### Requirement: Full-day identical status merges to one centered label
When both slots of a date resolve to the same leave or attendance-document label (年假, 出差, 外出, 调休, 事假, 病假, 陪产假, or any other leave label), the cell SHALL merge visually into one centered label. The system MUST NOT draw two copies of that label. The merge MUST stay inside that date cell and MUST NOT span other dates.

#### Scenario: Full-day annual leave is a single 年假
- **WHEN** both morning and afternoon slots of a date are covered by annual leave
- **THEN** the cell shows a single centered `年假`
- **AND** the annual-leave color fills the whole cell
- **AND** punch times are not shown in the main grid for that date

#### Scenario: Multi-day paternity leave stays one cell per date
- **WHEN** an effective paternity-leave document covers 27 through 31 as one continuous interval
- **THEN** each of those dates shows a single centered `陪产假`
- **AND** those date cells are not col-span merged across columns

#### Scenario: Half-day leave is not merged
- **WHEN** only one slot of the date is covered by leave and the other slot is a punch time
- **THEN** the cell keeps two stacked bands
- **AND** the leave label is not vertically centered as a full-cell merge

### Requirement: Legend colors apply; other leave types still render
The system SHALL color slots with the customer legend for 迟到, 早退, 漏刷, 加班, 调休, 外出, 出差, 事假, 病假, 年假, 休息日, and 补签. Leave types that are not in that legend, including 陪产假, 婚假, 丧假, 产假, and 工伤假, MUST still appear as their Chinese leave name. Those other leave types MAY have no background color.

The system MUST NOT collapse those other leave types into a generic `其他假别` label on the main grid. The system MUST NOT paint 驻外, 不打卡, 离职, 入职, or 停职留薪 as matrix cell text in this change.

#### Scenario: Paternity leave is visible without legend color
- **WHEN** both slots of a date are covered by effective paternity leave
- **THEN** the cell shows `陪产假`
- **AND** the cell is allowed to have no leave background color

#### Scenario: Marriage and bereavement leave are visible
- **WHEN** an effective leave document has leave type marriage or bereavement covering a date
- **THEN** the cell text is `婚假` or `丧假` respectively
- **AND** the text is not replaced by `其他假别`

#### Scenario: Employment lifecycle labels stay out of the grid
- **WHEN** an employee has hire, resign, stationed-out, no-punch, or unpaid-suspension facts
- **THEN** the matrix cell does not render `入职`, `离职`, `驻外`, `不打卡`, or `停职留薪` as the slot label

### Requirement: Overtime color requires an effective OA overtime document
A slot MAY use overtime legend color only when that business date has an effective OA overtime document and recognized overtime for that date. Late checkout alone MUST NOT receive overtime color. The main grid SHALL keep the punch time visible; the overtime label SHALL appear in the hover text, not as a replacement for the time.

#### Scenario: Approved overtime with punches is green with times
- **WHEN** an employee has punches 08:26 and 21:38 on a date with an effective OA overtime document and recognized overtime
- **THEN** the cell shows those times
- **AND** overtime color is applied
- **AND** the hover text includes `加班`
- **AND** the main grid does not replace the time with the word `加班`

#### Scenario: Late checkout without an overtime document stays normal
- **WHEN** an employee clocks out at 21:38 and there is no effective OA overtime document for that date
- **THEN** the afternoon text is `21:38` without overtime color
- **AND** the hover text does not present that date as overtime

#### Scenario: Overtime document without recognized overtime is not painted
- **WHEN** an OA overtime document exists for the date but recognized overtime minutes are zero
- **THEN** the cell does not use overtime color

### Requirement: Hover carries hours and secondary explanations
The main grid MUST NOT show leave hours such as `3.5` or `4.5`. Hover text for a date cell SHALL include the slot times or replacement labels, and when a half-day leave covers only the morning slot it SHALL report 3.5 hours, and when it covers only the afternoon slot it SHALL report 4.5 hours. Those two hour values are fixed for this change. Hover for overtime SHALL name 加班. Hover for punch correction SHALL name 补签.

#### Scenario: Morning half-day hours appear only on hover
- **WHEN** annual leave covers only the morning slot
- **THEN** the main grid shows `年假` without `3.5`
- **AND** hover text includes `3.5` hours for that morning leave

#### Scenario: Afternoon half-day hours appear only on hover
- **WHEN** annual leave covers only the afternoon slot
- **THEN** the main grid shows `年假` without `4.5`
- **AND** hover text includes `4.5` hours for that afternoon leave

#### Scenario: Full-day leave hover does not invent half-day hours
- **WHEN** both slots are covered by the same leave type
- **THEN** hover does not describe the day as a 3.5-hour morning half plus a 4.5-hour afternoon half unless the OA document itself is two half-day intervals

### Requirement: OA intervals decide morning, afternoon, or full-day coverage
The system SHALL use the OA document start and exclusive end, interpreted in Asia/Shanghai, to decide which slots of each business date are covered. A half-day leave is a single interval whose start and end fall on one business date and cover only one slot. A full-day or multi-day leave is one continuous unbroken interval; the system MUST NOT split it into disconnected dates.

Coverage on a business date:
- Morning slot is covered when the interval overlaps 08:30–12:00 Asia/Shanghai on that date
- Afternoon slot is covered when the interval overlaps 13:00–17:30 Asia/Shanghai on that date
- Both slots covered → full-day merge for that date
- Neither slot overlapped → that document does not label that date

Only documents in an effective source status already used by the month matrix (approved, modified, supplemented) SHALL label cells.

#### Scenario: OA half-day afternoon interval paints only the out slot
- **WHEN** an effective leave document starts at 13:00 and ends at 17:30 on 2026-07-22 Asia/Shanghai
- **THEN** only the afternoon slot of 2026-07-22 receives that leave label
- **AND** 2026-07-21 and 2026-07-23 are not labeled by that document

#### Scenario: OA half-day morning interval paints only the in slot
- **WHEN** an effective leave document starts at 08:30 and ends at 12:00 on 2026-07-07 Asia/Shanghai
- **THEN** only the morning slot of 2026-07-07 receives that leave label

#### Scenario: Continuous multi-day leave is not broken
- **WHEN** an effective paternity-leave document starts at 2026-07-27 08:30 and ends after 2026-07-31 17:30 as one interval
- **THEN** each date from 27 through 31 is a full-day `陪产假` cell
- **AND** the system does not insert an uncovered work day inside that span

#### Scenario: Draft OA documents do not label cells
- **WHEN** a leave document covering a date is not in an effective status
- **THEN** that document does not replace punch times on that date

### Requirement: Month-matrix payload exposes slot display without dropping punch instants
The month-matrix API SHALL add morning-slot and afternoon-slot display fields (text, tone, merge flag, hover material) so the customer-report page does not re-derive OA day coverage. Existing `firstPunchAt`, `lastPunchAt`, and `badges` fields SHALL remain populated so current Wave7 matrix clients keep compiling.

#### Scenario: Customer report can render a half-day cell from the payload
- **WHEN** the frontend maps a live month-matrix row that has morning punch time and afternoon annual-leave display
- **THEN** it can draw the two-band cell from the new slot fields without inspecting raw OA intervals

#### Scenario: Legacy punch fields remain
- **WHEN** a month-matrix day has first punch 08:14 and last punch 18:01
- **THEN** `firstPunchAt` and `lastPunchAt` are still present on that day object

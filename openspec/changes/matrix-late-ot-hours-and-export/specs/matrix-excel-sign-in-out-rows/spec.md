## ADDED Requirements

### Requirement: Attendance-detail Excel SHALL use sign-in and sign-out rows

Exporting the customer-report 考勤明细 matrix SHALL write two physical Excel rows per employee, matching the structure of `工作簿11111.xlsx`:

- Identity columns (工号 or 姓名, 部门, 职位 when present) SHALL be vertically merged across the pair.
- A label column SHALL contain `签到` on the first row of the pair and `签退` on the second.
- Each calendar date is two cells, one per row, each with its own text and fill.
- Date headers remain `dd/weekday` and MAY span the two header rows.

The on-screen matrix SHALL stay one table row per employee and one `td` per date, with two stacked bands inside the cell. The page MUST NOT add a 签到/签退 identity column.

#### Scenario: Late morning and overtime afternoon export as two colored cells
- **WHEN** an authorized user exports 考勤明细 for a day whose morning slot is `08:32 迟到` and afternoon slot is `21:38` with overtime color
- **THEN** the workbook has a 签到 cell `08:32 迟到` filled with late color
- **AND** the 签退 cell on the next row is `21:38` filled with overtime color
- **AND** those two fills are not the same overtime-only fill

#### Scenario: Full-day annual leave writes the label on both rows
- **WHEN** both slots of a date are 年假
- **THEN** 签到 and 签退 cells both contain `年假` with annual-leave fill
- **AND** neither cell text contains `4.5` or `5`

#### Scenario: Rest-day afternoon overtime exports both punch times
- **WHEN** Saturday punches are 15:00 and 18:00
- **THEN** the 签到 cell is `15:00` and the 签退 cell is `18:00`

#### Scenario: Screen layout is unchanged
- **WHEN** an authorized user opens the monthly attendance-detail matrix
- **THEN** each employee still occupies one HTML row
- **AND** no 签到/签退 column is added to the page

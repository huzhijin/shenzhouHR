## ADDED Requirements

### Requirement: Overtime-fee and time-off filters hide columns

On 每日加班 in the report center and on 每日加班查询, the treatment filter values SHALL be `加班费` and `转调休`. When `加班费` is selected, the 加班费 column SHALL be visible and the 转调休 column SHALL be hidden. When `转调休` is selected, the reverse SHALL hold. When the filter is cleared, both columns SHALL be visible. The filter MUST NOT be a no-op; it MUST NOT require the mismatched labels `计薪加班` / `转调休加班` to take effect.

#### Scenario: Filter overtime fee hides time-off column
- **WHEN** the operator selects 加班费 on 每日加班
- **THEN** the table shows the 加班费 column
- **AND** the 转调休 column is not rendered
- **AND** rows remain the people in the current company-month scope (column visibility, not emptying the sheet)

#### Scenario: No treatment selected shows both columns
- **WHEN** the treatment filter is empty
- **THEN** both 加班费 and 转调休 columns are visible

# Overtime Classification Specification

## Purpose

Defines how overtime records from OA are classified into three types (paid overtime, compensatory leave overtime, and voluntary overtime) based on OA field values, with separate tracking and reporting for each type plus an aggregate total.

## ADDED Requirements

### Requirement: System SHALL classify overtime into three types

The system SHALL classify each overtime record from OA into one of three mutually exclusive types:

1. **Paid overtime (计薪加班)**: Overtime that will be compensated with overtime pay
2. **Compensatory leave overtime (转调休加班)**: Overtime that will be converted to compensatory leave hours
3. **Voluntary overtime (义务加班)**: Overtime with no compensation (neither pay nor leave)

#### Scenario: Paid overtime is classified
- **WHEN** OA overtime record has field0096 = -6539634143789166714
- **THEN** system SHALL classify as paid overtime
- **AND** paidOvertimeMinutes SHALL be incremented by overtime duration

#### Scenario: Compensatory leave overtime is classified
- **WHEN** OA overtime record has field0096 = 5912806790045781226
- **THEN** system SHALL classify as compensatory leave overtime
- **AND** compensatoryOvertimeMinutes SHALL be incremented by overtime duration

#### Scenario: Voluntary overtime is classified
- **WHEN** OA overtime record has field0096 = 4337518111002608138
- **THEN** system SHALL classify as voluntary overtime
- **AND** voluntaryOvertimeMinutes SHALL be incremented by overtime duration

### Requirement: System SHALL read overtime type from OA field0096

The system SHALL read the overtime type from OA table `formson_0172` field `field0096` (bigint type), which contains an enum ID that maps to overtime type via `oa_dic_item` table.

Verified mapping (from docs/verification/oa-live/2026-08-10/EVIDENCE.md, OA-B5-03C):

| field0096 Enum ID         | Display Value (SHOWVALUE) | Classification        | Record Count |
|---------------------------|---------------------------|-----------------------|--------------|
| -6539634143789166714      | 加班费                    | Paid overtime         | 112,022      |
| 5912806790045781226       | 调休                      | Compensatory leave    | 5,066        |
| 4337518111002608138       | 义务加班                  | Voluntary overtime    | 89           |
| NULL                      | (empty)                   | Isolation (no class)  | 30           |

#### Scenario: Mapping reads from verified OA field
- **WHEN** system processes OA overtime record
- **THEN** system SHALL read field0096 value
- **AND** system SHALL map enum ID to overtime type using the table above

### Requirement: System SHALL isolate NULL overtime type values

When OA overtime record has field0096 = NULL, the system SHALL isolate the record and NOT classify it into any of the three types.

Isolated records SHALL be logged but SHALL NOT contribute to any overtime minutes count.

#### Scenario: NULL type is isolated
- **WHEN** OA overtime record has field0096 = NULL
- **THEN** system SHALL log warning "加班记录类型为空，已隔离: recordId={id}"
- **AND** paidOvertimeMinutes SHALL NOT be incremented
- **AND** compensatoryOvertimeMinutes SHALL NOT be incremented
- **AND** voluntaryOvertimeMinutes SHALL NOT be incremented
- **AND** totalOvertimeMinutes SHALL NOT be incremented

### Requirement: System SHALL isolate unknown overtime type values

When OA overtime record has field0096 value that does not match any of the three known enum IDs, the system SHALL isolate the record and log an error.

#### Scenario: Unknown enum ID is isolated
- **WHEN** OA overtime record has field0096 = 9999999999999999999 (unknown value)
- **THEN** system SHALL log error "未知加班类型: enumId=9999999999999999999"
- **AND** record SHALL NOT contribute to any overtime minutes count

### Requirement: System SHALL calculate total overtime as sum of three types

The system SHALL calculate total overtime minutes as the sum of paid, compensatory, and voluntary overtime minutes.

`totalOvertimeMinutes = paidOvertimeMinutes + compensatoryOvertimeMinutes + voluntaryOvertimeMinutes`

Isolated records (NULL or unknown types) are NOT included in the total.

#### Scenario: Total is sum of three types
- **WHEN** employee has 120 minutes paid overtime, 60 minutes compensatory overtime, and 0 minutes voluntary overtime
- **THEN** totalOvertimeMinutes SHALL be 120 + 60 + 0 = 180

#### Scenario: Isolated records do not affect total
- **WHEN** employee has 100 minutes paid overtime and 1 record with NULL type (60 minutes)
- **THEN** paidOvertimeMinutes = 100
- **AND** totalOvertimeMinutes = 100 (NULL record not included)

### Requirement: System SHALL store four overtime fields in daily fact table

The system SHALL store four overtime minute fields in the `attendance_report_daily_fact` table:

- `paid_overtime_minutes` (BIGINT, NOT NULL, DEFAULT 0): Paid overtime minutes
- `compensatory_overtime_minutes` (BIGINT, NOT NULL, DEFAULT 0): Compensatory leave overtime minutes
- `voluntary_overtime_minutes` (BIGINT, NOT NULL, DEFAULT 0): Voluntary overtime minutes
- `total_overtime_minutes` (BIGINT, NOT NULL, DEFAULT 0): Sum of the above three

#### Scenario: All four fields are persisted
- **WHEN** daily attendance calculation runs
- **THEN** system SHALL persist all four overtime fields to database
- **AND** each field SHALL have BIGINT type with NOT NULL constraint

### Requirement: Reports SHALL display all four overtime metrics

Attendance reports SHALL display all four overtime metrics separately plus the total:

- Individual columns for paid, compensatory, and voluntary overtime
- One column for total overtime (sum)

#### Scenario: Daily report shows four overtime columns
- **WHEN** user views daily attendance report
- **THEN** report SHALL show columns: "计薪加班", "转调休加班", "义务加班", "汇总加班"
- **AND** each column SHALL display minutes or hours

#### Scenario: Department aggregate shows four overtime columns
- **WHEN** user views department attendance report
- **THEN** report SHALL show SUM of each overtime type across all employees
- **AND** total overtime = SUM(paid) + SUM(compensatory) + SUM(voluntary)

### Requirement: System SHALL calculate overtime duration from OA time fields

The system SHALL calculate overtime duration in minutes from OA fields:
- `field0100`: Overtime start time (datetime)
- `field0099`: Overtime end time (datetime)

Duration in minutes = (field0099 - field0100) converted to minutes.

#### Scenario: Calculate duration from OA timestamps
- **WHEN** OA record has field0100 = "2026-08-15 18:00:00" and field0099 = "2026-08-15 20:30:00"
- **THEN** system SHALL calculate duration = 150 minutes
- **AND** this duration SHALL be added to the appropriate overtime type

### Requirement: Only approved overtime SHALL be included

The system SHALL only include overtime records with approval state = 3 (approved).

Records with state = 0 (pending), 2 (cancelled), or NULL (draft) SHALL be excluded.

#### Scenario: Only approved overtime counts
- **WHEN** processing OA overtime records for an employee
- **THEN** system SHALL filter by col_summary.state = 3
- **AND** system SHALL exclude state = 0, 2, or NULL

#### Scenario: Pending overtime does not appear in reports
- **WHEN** employee has 1 approved overtime (120 minutes) and 1 pending overtime (60 minutes)
- **THEN** totalOvertimeMinutes SHALL be 120 (only approved)
- **AND** pending overtime SHALL NOT affect any overtime count

### Requirement: System SHALL handle existing unexcused/excused overtime fields

The existing fields `unexcused_overtime_minutes` and `excused_overtime_minutes` SHALL be renamed or deprecated:

- `unexcused_overtime_minutes` → `unapproved_overtime_minutes` (overtime not yet approved)
- `excused_overtime_minutes` → deprecated or renamed to `approved_overtime_minutes` (legacy field)

The new three-type classification replaces the binary unexcused/excused model.

#### Scenario: Legacy fields are not used for new classification
- **WHEN** system calculates overtime from OA
- **THEN** system SHALL populate paid/compensatory/voluntary fields
- **AND** system SHALL NOT use the binary excused/unexcused model

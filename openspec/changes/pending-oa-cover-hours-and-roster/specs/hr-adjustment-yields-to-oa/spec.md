## ADDED Requirements

### Requirement: OA of the same kind on the same person-day SHALL replace HR pre-add

When an OA document (pending or approved) covers a person-day for a kind (outing, trip, leave/time-off, overtime, punch correction), calculation and reports SHALL use the OA evidence for that kind. An HR punch-adjustment of the same kind on that person-day MUST NOT add a second outing label, a second overtime hour total, or a second leave block.

Kinds that the OA does not cover on that day KEEP the HR adjustment. HR rows that have no matching OA MUST remain in force, including 张衡 `SZSZ0003` outing on `2026-08-11` and `2026-08-26`.

The 2026-09-02 import reason `未结束OA预入账-20260902` follows this rule. The system MUST NOT reverse every HR row of that reason; only the kinds that now have OA yield.

#### Scenario: Pending outing OA displaces imported outing HR on that day
- **WHEN** a person-day has HR `dayTypes` containing `OUTING` from the pending-OA pre-add
- **AND** a pending or approved OA outing covers that calendar day
- **THEN** the matrix shows one `外出` from the OA interval
- **AND** overtime hours that day, if any, come from OA overtime documents, not a doubled HR+OA total

#### Scenario: Zhang Heng outing without OA stays
- **WHEN** 张衡 `SZSZ0003` has HR outing on `2026-08-11` and `2026-08-26`
- **AND** no OA outing covers those dates
- **THEN** both dates still display `外出`
- **AND** those HR rows are not reversed or rewritten by the yield rule

#### Scenario: Overtime OA replaces HR overtime hours, other HR fields stay
- **WHEN** a person-day has HR `overtimeHours` 4.0 and an OA overtime document of 2.5 hours
- **AND** the HR row also marks outing and there is no OA outing
- **THEN** recognized overtime is `2.5` from OA
- **AND** the outing label from HR remains

## ADDED Requirements

### Requirement: Standing no-punch list is distinct from the EXECUTIVE role
The system SHALL persist a standing punch-exemption interval per employee number on `/Users/huzhijin/Downloads/不打卡人员名单。.xlsx` (duplicate rows collapsed). Publishing that list MUST NOT create or extend an `EXECUTIVE` role assignment. Calculation for a day SHALL treat the employee as punch-exempt when **either** a standing interval covers the day **or** an effective `EXECUTIVE` role assignment covers the day. OA approved 免打卡 documents remain a third, date-bounded source and are unchanged.

#### Scenario: List member without EXECUTIVE is still exempt
- **WHEN** `SZST0571` 王哲 is on the standing list from `2026-01-01`
- **AND** that employee has no `EXECUTIVE` role
- **AND** `2026-03-10` is a scheduled work day with no punches
- **THEN** the day is punch-exempt
- **AND** no `EXECUTIVE` role assignment exists for that principal

#### Scenario: EXECUTIVE without the list is still exempt
- **WHEN** an employee has an open-ended `EXECUTIVE` role covering `2026-03-10`
- **AND** that employee number is not on the standing list
- **AND** `2026-03-10` is a scheduled work day with no punches
- **THEN** the day is punch-exempt

#### Scenario: Standing list does not grant executive permissions
- **WHEN** a standing exemption is published for a field engineer on the list
- **THEN** that principal SHALL NOT gain `EXECUTIVE` capabilities
- **AND** report-read and other role grants stay as previously assigned

### Requirement: Ye Jian exemption is stored on SZSZ0000
The standing-list row whose number is `SZST0567` 叶剑 SHALL be stored against employee `SZSZ0000`. Matching MUST use the same identity-correction table as roster sync.

#### Scenario: List number SZST0567 exempts SZSZ0000
- **WHEN** the standing list contains `SZST0567` 叶剑
- **THEN** punch exemption from `2026-01-01` is attached to `SZSZ0000`
- **AND** no exemption interval is created for a non-existent `SZST0567` employee

### Requirement: Exempt scheduled work without punches displays as normal
On a scheduled work day covered by standing exemption or `EXECUTIVE`, with no effective punches and no leave/outing/trip/time-off covering the slots, the month matrix and customer attendance-detail cell SHALL display as normal attendance. The system MUST NOT show `漏刷`, `旷工`, or `无打卡` for those slots. Missing-punch counts and absence for that day SHALL be zero.

#### Scenario: No-punch work day looks normal
- **WHEN** `SZST0004` 丁书龙 is standing-exempt on a scheduled Wednesday
- **AND** there are no punches and no leave documents that day
- **THEN** the date cell status is normal attendance
- **AND** slot text is not `漏刷`
- **AND** tooltip text is not `无打卡`

#### Scenario: OA leave still outranks exemption display
- **WHEN** a standing-exempt employee has approved annual leave covering the whole day
- **THEN** the cell shows `年假`
- **AND** it does not show as normal punch-in

### Requirement: Rest days stay rest days under exemption
A calendar rest day for a punch-exempt employee SHALL still display as 休息日. Standing exemption MUST NOT paint a rest day as normal work attendance.

#### Scenario: Weekend remains rest
- **WHEN** `2026-03-08` is a rest day for a standing-exempt employee
- **AND** the employee has no overtime document that day
- **THEN** the date cell shows 休息日
- **AND** it is not counted as a scheduled work day of normal attendance

### Requirement: Overtime for exempt people requires an approved overtime document
Punch-exempt employees SHALL NOT receive overtime credit or overtime cell coloring from the presence or absence of punches. Overtime SHALL be recognized only when an approved overtime document covers the interval, identical to non-exempt employees.

#### Scenario: Punches without an overtime form are not overtime
- **WHEN** a standing-exempt employee punches outside shift hours
- **AND** no approved overtime document exists for that interval
- **THEN** the day SHALL NOT be classified as 加班

#### Scenario: Approved overtime form is honored
- **WHEN** a standing-exempt employee has an approved overtime document on a rest day
- **THEN** overtime minutes follow the existing overtime classification rules
- **AND** the rest-day label remains unless those rules already replace it

### Requirement: Standing exemption starts on 2026-01-01
Standing-list intervals SHALL start on `2026-01-01` and remain open-ended unless later ended. Days before `2026-01-01` MUST NOT become exempt solely because the person is on this list.

#### Scenario: 2025 is not rewritten by the list
- **WHEN** a listed employee had missing punches on `2025-12-31`
- **THEN** that day's historical calculation result SHALL stay as it was
- **AND** standing exemption does not apply to that date

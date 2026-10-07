## ADDED Requirements

### Requirement: Query reports SHALL list approved OA punch corrections as 补签

The independent query reports SHALL include a 补签 sheet (or an OA document sheet that can filter `PUNCH_CORRECTION`). Each row SHALL show employee, department, 补签 time, and approval state. The leave and overtime sheets MUST NOT be the only OA document lists. The OA source document list for type 补签 MUST return documents instead of `DATA_CONFLICT`.

#### Scenario: August makeup punches appear on the 补签 query
- **WHEN** HR opens 查询报表 → 补签 for 2026-08 and company 江苏神州半导体
- **THEN** approved OA formmain_0203 / formson_0204 rows for that month appear as document type 补签
- **AND** 请假统计 and 加班统计 still list only leave and overtime

#### Scenario: OA source 补签 filter loads
- **WHEN** an authorized user lists OA documents with documentType PUNCH_CORRECTION
- **THEN** the response is a page of 补签 documents
- **AND** the status is not 409 DATA_CONFLICT

### Requirement: Approved OA 补签 SHALL count as a punch at the makeup time

An effective approved OA `PUNCH_CORRECTION` at instant T SHALL enter calculation as a punch event at T (direction from 补卡类型 when present, otherwise inferred from T vs the shift). It MUST NOT be classified as 免打卡 / EXEMPT_WORK. After recalculation, that side's 漏签 SHALL clear when T covers the missing side, the month matrix SHALL show 补签, and late / work minutes SHALL use T when it is the effective on or off punch.

#### Scenario: Makeup on-duty punch clears morning miss
- **WHEN** 吴根银 has no device on-duty punch on 2026-08-10 and an approved OA 补签 at 08:30 that day
- **THEN** calculation treats 08:30 as the on-duty punch
- **AND** 上班漏签 is not open for that day
- **AND** the month-matrix slot text includes 补签

#### Scenario: Makeup is not exemption
- **WHEN** the only OA document that day is PUNCH_CORRECTION
- **THEN** scheduled work is not marked 免打卡 solely because of that document

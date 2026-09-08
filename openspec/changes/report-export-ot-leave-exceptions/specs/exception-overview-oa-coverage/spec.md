## ADDED Requirements

### Requirement: Exception overview hides rows already covered by an in-HR OA document

异常总览 is the work queue. If `oa_attendance_document` already holds a document that covers that person-day and exception type, the row MUST NOT appear, including when `source_status` is still pending (`UNKNOWN` / `DRAFT`). Rejected and revoked documents MUST NOT hide rows.

Coverage:

| Document | Hides |
|---|---|
| 请假 / 调休 | 漏刷 (both sides in the interval), 迟到, 早退, 旷工 |
| 补签 | 漏刷 on the corrected side; 迟到 on that side when the correction is on-duty |
| 外出 / 出差 / 免打卡 | 漏刷, 迟到, 早退, 旷工 on covered slots |
| 加班单 | only rest-day 下班漏刷 that was invented for that overtime day |
| 销假 | does not hide; may restore punch exceptions on the revoked interval |

#### Scenario: Pending leave hides 漏刷
- **WHEN** 李四 has 上班漏刷 on 2026-08-10 and HR already stored a 请假 covering that morning with status `UNKNOWN`
- **THEN** 异常总览 has no 上班缺卡 / 漏刷 row for 李四 on 2026-08-10

#### Scenario: Pending makeup hides that side only
- **WHEN** 王五 is missing the off-duty punch and a 补签 for 17:35 is stored as `UNKNOWN`
- **THEN** 异常总览 has no 下班缺卡 for that date
- **AND** an uncovered 上班缺卡 on the same date still appears

### Requirement: Approved coverage removes exception facts on recalculate

After a covering document becomes `APPROVED` (or `MODIFIED` / `SUPPLEMENTED`) and the company-month is recalculated, the new pin MUST NOT persist the suppressed exception facts. Query pages, the report-center exception tab, and the month matrix MUST all omit those punch exceptions.

#### Scenario: Leave approved then recalculated
- **WHEN** 2026-08-10 was 漏刷, the leave is later `APPROVED`, and an authorized principal recalculates August
- **THEN** the new pin has no `MISSING_*` / `ABSENCE` / `LATE` / `EARLY_DEPARTURE` fact caused by that gap
- **AND** 请假统计 still lists the leave

#### Scenario: Revoked leave can show 漏刷 again
- **WHEN** the covering leave is `REVOKED` or a matching 销假 is approved and the month is recalculated
- **THEN** uncovered missing punches for that day MAY appear again on 异常总览

### Requirement: Documents not yet in HR cannot hide rows

If the form exists only in OA and has not been ingested, 异常总览 MAY still list the exception until the next successful OA sync. The system MUST ingest pending leave/makeup/outing/trip/exempt/overtime (not only approved) so that scenario 「pending leave hides 漏刷」 can hold after sync, not only after approval.

#### Scenario: OA pending but not synced
- **WHEN** OA has a state=0 leave that HR has never stored
- **THEN** 异常总览 may still show 漏刷
- **AND** after OA sync writes that row as `UNKNOWN`, the next 异常总览 query hides it without waiting for approval

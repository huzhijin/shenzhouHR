## ADDED Requirements

### Requirement: Pending outing and covering documents SHALL hide missed punches

The latest version of an OA outing, trip, leave, time-off, or exempt-punch document whose source status is pending (`UNKNOWN`) or approved (`APPROVED`, `MODIFIED`, `SUPPLEMENTED`) SHALL cover the calendar days of its interval. Month-matrix slots covered by that interval MUST display the document label (外出, 出差, or the leave name) and MUST NOT display `漏刷`. Exception overview MUST NOT list missed-punch, missing on-duty, missing off-duty, or absence cases for those covered slots.

Revoked documents MUST NOT cover. Drafts without a stable state MUST NOT cover. Overnight outing intervals MUST cover every Asia/Shanghai calendar day they overlap, including both the start morning slot and the end afternoon slot.

#### Scenario: Ju Jun overnight pending outing covers both days
- **WHEN** 居军 `SZST0017` has an OA outing `2026-08-19 08:30`–`2026-08-20 18:00` that is still pending or already approved
- **AND** those days have no punches
- **THEN** 考勤明细 19 日 and 20 日 show `外出` on both morning and afternoon slots
- **AND** neither day shows `漏刷`
- **AND** 异常总览 has no 漏刷 / 上班缺卡 / 下班缺卡 / 旷工 for those two days

#### Scenario: Pending outing on one workday covers that day only
- **WHEN** an employee has a pending outing `08:30`–`18:00` on a scheduled weekday
- **THEN** that date cell is `外出`
- **AND** adjacent days without a covering document still follow ordinary punch rules

### Requirement: Pending overtime SHALL count in finance totals and overtime detail

A pending or approved OA overtime document SHALL contribute recognized hours to 财务加班 / 每日加班 totals and SHALL appear as a row on 加班统计. Hours follow `overtime-hours-from-snapped-oa-form` (snapped interval minus scheduled work and meals). When a later version of the same `source_business_key` is approved, that version SHALL replace the pending hours. The system MUST NOT add pending hours and approved hours for the same form.

#### Scenario: Pending weekday overtime appears in finance and detail
- **WHEN** an employee has a pending OA overtime that snaps to `18:00–21:00` on a summer weekday
- **THEN** 加班统计 includes that row with hours `2.5`
- **AND** 财务加班 平时加班 and 加班费 include `2.5` for that person-day

#### Scenario: Approval replaces pending hours
- **WHEN** that same form later syncs as `APPROVED` with the same interval
- **THEN** 加班统计 still has one row for that form
- **AND** 财务加班 still includes `2.5` once, not `5.0`

#### Scenario: Revoked pending overtime drops out
- **WHEN** the latest version of an overtime form is revoked
- **THEN** 加班统计 does not list it
- **AND** 财务加班 does not include its hours

## 1. Pin store and read path

- [x] 1.1 Load the latest PUBLISHED company-month projection only when its formula catalog version matches the current calculator
- [x] 1.2 Reconstruct the authorized report snapshot from stored daily/OA/account facts and re-resolve scope on every request
- [x] 1.3 On GET miss, run the existing complete orchestrator, persist via the projection publisher, and return that pin
- [x] 1.4 Do not persist provisional, timed-out, or preview calculation output
- [x] 1.5 Keep expectedProjectionVersion bound to one stored token; mismatch returns ATTENDANCE_REPORT_SNAPSHOT_CHANGED without mixing rows

## 2. Explicit recalculate and freshness

- [x] 2.1 Add POST /api/v1/attendance-reports/recalculate for companyId+period requiring ATTENDANCE_REPORT:REFRESH in addition to READ
- [x] 2.2 Keep ATTENDANCE_REPORT:REFRESH only on SYSTEM_ADMIN and HR_ADMIN; do not grant EXECUTIVE, DEPARTMENT_HEAD, EMPLOYEE_SELF, or MANUFACTURING_CENTER_SUPERVISOR
- [x] 2.3 Recalculate covers the whole company-month, appends a successor pin, and is idempotent when the digest matches the latest pin
- [x] 2.4 Ordinary GET after newer Deli/OA watermarks keeps the pinned numbers and does not write a new projection
- [x] 2.5 Compare current committed Deli/OA watermarks with the pin cutoffs and expose a source-newer-than-pin flag without failing the report
- [x] 2.6 Failed recalculate leaves the previous pin readable
- [x] 2.7 Update OpenAPI for recalculate (x-capability ATTENDANCE_REPORT:REFRESH), pinned token, and source-newer-than-pin; do not present publication as a view gate
- [x] 2.8 Expose REPORT_RECALCULATE in allowedActions only when the principal has ATTENDANCE_REPORT:REFRESH

## 3. Shared consumers and UI

- [x] 3.1 Route month-matrix, dashboard, and export create/download through the same latest-pin loader as report GET
- [x] 3.2 Leave self-service today/me records on committed live evidence, not the pin
- [x] 3.3 Add a distinct 「重新计算」 button that POSTs recalculate; do not use 「刷新数据」 for month recalculation; show the button only when REPORT_RECALCULATE is allowed
- [x] 3.4 Show calculation time from the pin; show 「来源已更新，可重新计算」 only to REFRESH holders; keep rows unchanged until recalculate succeeds
- [x] 3.5 Prove two GET queries after restart return the same business values and token until recalculate

## 4. Tests and regression guards

- [x] 4.1 Tests: first GET materializes; second GET does not recalculate; recalculate after new evidence changes values
- [x] 4.2 Tests: organization scope cannot see out-of-scope employees from a company pin
- [x] 4.3 Tests: preview is not published; recalculate failure keeps prior pin
- [x] 4.4 Tests: sync/watermark advance without recalculate does not change report rows
- [x] 4.5 Confirm Deli/OA auto-sync cron stays 00:00 and 12:00 and no sync job calls recalculate
- [x] 4.6 Tests: EMPLOYEE_SELF, DEPARTMENT_HEAD, and EXECUTIVE receive 403 on recalculate and no REPORT_RECALCULATE action; HR_ADMIN and SYSTEM_ADMIN can recalculate

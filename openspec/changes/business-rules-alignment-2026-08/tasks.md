# Implementation Tasks: Business Rules Alignment 2026-08

## 1. Database Schema Changes (V48 Migration)

- [x] 1.1 Create migration script V48 with nullable, indexed `leave_type VARCHAR(50)` on `attendance_report_daily_fact`
- [x] 1.2 Create `punch_correction_request` table with employee, month, date, punch side, review state, actors, timestamps, and reason fields
- [x] 1.3 Create `oa_enum_mapping` configuration table with physical OA field, enum ID, business meaning, display label, and active state
- [x] 1.4 Create `deli_sync_log` table with columns: sync_time, status, record_count, error_message
- [x] 1.5 Add `overtime_type VARCHAR(20)` column to overtime evidence tables
- [x] 1.6 Insert initial data into `oa_enum_mapping` for overtime classification enum IDs
- [x] 1.7 Run migration script on development environment and verify schema changes
- [x] 1.8 Write the destructive V48 reference rollback script for disposable rehearsal/last-resort DBA review; keep production Flyway recovery forward-only by default

## 2. Attendance Rate Formula Change (Day-based)

- [x] 2.1 Update `AttendanceRate` class to remove minute-based calculation methods
- [x] 2.2 Fix implementation of `ATTENDANCE_RATE_ACTUAL_DAYS_OVER_SCHEDULED_DAYS_V2` to use day fields instead of minute fields
- [x] 2.3 Update `AttendanceReportPublication.attendanceRate()` to use `value.actualAttendanceDays` and `value.scheduledAttendanceDays`
- [x] 2.4 Update department-level rate calculation to use `SUM(actual_attendance_days) / SUM(scheduled_attendance_days)`
- [x] 2.5 Update all unit tests for `AttendanceRate` to use day-based assertions
- [x] 2.6 Update integration tests in `AttendanceReportMultiScopePersistenceIntegrationTest` to verify day-based rates
- [x] 2.7 Add test case: employee with 22 scheduled days, 20 actual days → 90.91% rate
- [x] 2.8 Add test case: employee with paid leave (20 punch days + 2 annual leave days) → 100% rate

## 3. Leave Type Preservation and Sick Leave Reporting

- [x] 3.1 Create `LeaveType` enum with values: ANNUAL, SICK, MARRIAGE, MATERNITY, PATERNITY, BEREAVEMENT, WORK_INJURY, PRENATAL_NURSING, PERSONAL, COMPENSATORY
- [x] 3.2 Update `DailyAttendanceProcessor` to store `leaveType` in daily records when processing leave evidence
- [x] 3.3 Update `OaLeaveAdapter` to map OA leave category to `LeaveType` enum
- [x] 3.4 Ensure sick leave increments `actualAttendanceDays` (counts as attendance)
- [x] 3.5 Update `AttendanceReportPublication` to add separate column for sick leave days
- [x] 3.6 Add query method to count sick leave days per employee per month
- [x] 3.7 Update report DTOs to include `sickLeaveDays` field
- [x] 3.8 Add test case: employee with 2 days sick leave → actualDays +2, sickLeaveDays = 2
- [x] 3.9 Add test case: department report aggregates sick leave days across employees

## 4. Late-to-Absent Conversion (≥30 minutes)

- [x] 4.1 Update `DailyAttendanceProcessor` to check `lateMinutes ≥ 30` after calculating late time
- [x] 4.2 If late ≥30min, set `attendanceStatus = ABSENT` and do NOT increment `actualAttendanceDays`
- [x] 4.3 If late <30min, process as normal late (increment `actualAttendanceDays` but record late event)
- [x] 4.4 Update late event recording to preserve original late minutes even when converted to absent
- [x] 4.5 Add test case: employee late 29 minutes → status = LATE, actualDays +1
- [x] 4.6 Add test case: employee late 30 minutes → status = ABSENT, actualDays +0
- [x] 4.7 Add test case: employee late 45 minutes → status = ABSENT, actualDays +0
- [x] 4.8 Update integration tests to verify late-to-absent conversion in monthly summary

## 5. Punch Correction Quota (1 per month per employee)

- [x] 5.1 Create `PunchCorrectionRequest` entity class mapping to `punch_correction_request` table
- [x] 5.2 Create `PunchCorrectionQuotaService` with method `canRequestCorrection(employeeId, month)`
- [x] 5.3 Implement quota check: count approved corrections for (employee, month), return false if ≥1
- [x] 5.4 Create `PunchCorrectionRequestRepository` for database access
- [x] 5.5 Add REST endpoint `POST /api/attendance/punch-corrections` to submit correction request
- [x] 5.6 Add REST endpoint `GET /api/attendance/punch-corrections/quota/{employeeId}/{month}` to check quota
- [x] 5.7 Add REST endpoint `PUT /api/attendance/punch-corrections/{id}/approve` for HR approval
- [x] 5.8 Add validation: reject request if quota exceeded
- [x] 5.9 Add test case: first correction request in month → allowed
- [x] 5.10 Add test case: second correction request in same month → rejected
- [x] 5.11 Add test case: correction request in new month → allowed (quota resets)
- [x] 5.12 Document HR manual override process (for exceptional cases)

## 6. Overtime Classification (Paid/Compensatory/Voluntary)

- [x] 6.1 Create `OvertimeType` enum with values: PAID, COMPENSATORY, VOLUNTARY
- [x] 6.2 Update `OaOvertimeAdapter` to read `field0096` from `formson_0172` table
- [x] 6.3 Implement enum ID mapping: -6539634143789166714 → PAID, 5912806790045781226 → COMPENSATORY, 4337518111002608138 → VOLUNTARY
- [x] 6.4 Handle NULL `field0096` values: log warning and isolate record
- [x] 6.5 Add `overtimeType` field to `OvertimeEvidence` class
- [x] 6.6 Update `DailyAttendanceProcessor` to preserve overtime type in daily records
- [x] 6.7 Update `AttendanceReportPublication` to add 3 separate overtime columns (paid hours, compensatory hours, voluntary hours) plus total
- [x] 6.8 Add query methods to aggregate overtime hours by type
- [x] 6.9 Add test case: overtime with type PAID → displays in paid overtime column
- [x] 6.10 Add test case: overtime with type COMPENSATORY → displays in compensatory column
- [x] 6.11 Add test case: overtime with NULL type → isolated and logged
- [x] 6.12 Verify against OA evidence: 112,022 PAID records, 5,066 COMPENSATORY records, 89 VOLUNTARY records

## 7. Exemption and Outing Request Handling

- [x] 7.1 Do not query the signed business-trip form `formmain_0265` from `OaMysqlAttendanceDocumentAdapter`
- [x] 7.2 For this rule, query only signed exemption (`formmain_0201` + `formson_0202`) and outing (`formmain_0251` + `formson_0252`) forms
- [x] 7.3 Update exemption date range query to use inclusive end date: `date <= end_date` (not `date < end_date`)
- [x] 7.4 Update outing request processing: only grant attendance if BOTH (approved request exists AND punch record exists)
- [x] 7.5 Add logic: if outing request exists but NO punch → mark as absent
- [x] 7.6 Add logic: exemption request grants attendance without punch requirement
- [x] 7.7 Add precedence logic: if both exemption and outing exist for same date, exemption takes precedence
- [x] 7.8 Update OA approval state filter: only use state = 3 (approved), ignore state 0/2/NULL
- [x] 7.9 Add test case: approved exemption + no punch → actualDays +1, no missing punch
- [x] 7.10 Add test case: approved outing + valid punch → actualDays +1
- [x] 7.11 Add test case: approved outing + no punch → actualDays +0, marked absent
- [x] 7.12 Add test case: exemption end_date = 2026-08-05, query on 08-05 → matches
- [x] 7.13 Update tests to NOT expect business trip evidence

## 8. Missing Punch Count Clarification

- [x] 8.1 Verify `DailyAttendanceProcessor` logic: full day with no punch → missingPunchCount +2
- [x] 8.2 Verify logic: only morning punch present → missingPunchCount +1 (evening missing)
- [x] 8.3 Verify logic: only evening punch present → missingPunchCount +1 (morning missing)
- [x] 8.4 Verify logic: both punches present → missingPunchCount = 0
- [x] 8.5 Verify logic: exemption request prevents missing punch count (expected punches = 0)
- [x] 8.6 Verify logic: exemption role never has missing punch count
- [x] 8.7 Add explicit test case: full day no punch, no exemption → missingPunchCount = 2
- [x] 8.8 Add test case: partial exemption (morning only) + no punch → missingPunchCount = 1 (evening only)

## 9. Deli Sync Schedule Configuration

- [x] 9.1 Update `DeliSyncJob` with Spring `@Scheduled(cron = "0 0 * * * ?")` annotation (hourly at top of hour)
- [x] 9.2 Create `DeliSyncLogRepository` for database access to `deli_sync_log` table
- [x] 9.3 Update `DeliSyncJob.run()` to log sync execution to `deli_sync_log` table
- [x] 9.4 Log fields: sync_time, status (success/failure), record_count, error_message
- [x] 9.5 Implement incremental sync: retrieve records with timestamp > last successful sync time
- [x] 9.6 On sync failure, do NOT update "last successful sync time" marker (allows retry to catch missed records)
- [x] 9.7 Create REST endpoint `GET /api/attendance/deli-sync/status` to retrieve last sync log entry
- [x] 9.8 Add DTO for sync status display: lastSyncTime, recordCount, status, errorMessage
- [x] 9.9 Configure log retention: delete entries older than 30 days (via scheduled cleanup job)
- [x] 9.10 Remove any existing alert threshold logic (no yellow/red status)
- [x] 9.11 Add test: verify cron schedule triggers at top of hour
- [x] 9.12 Add test: sync failure does not advance last sync time marker

## 10. Department Aggregation and Job Transfer Allocation

- [x] 10.1 Update department aggregation query to use day-based weighted average: `SUM(actualDays) / SUM(scheduledDays)`
- [x] 10.2 Ensure department aggregation excludes employees with scheduledDays = 0
- [x] 10.3 Ensure department aggregation respects company boundaries (filter by company_id)
- [x] 10.4 Update job transfer allocation logic to use assignment effective date for department determination
- [x] 10.5 Implement report row splitting: employee who transfers mid-month appears as separate rows per department period
- [x] 10.6 Each row shows: Employee | Department | Period (date range) | Actual Days | Scheduled Days | Rate
- [x] 10.7 Verify no duplicate attendance days: sum of split rows = full month total
- [x] 10.8 Update department filter to only include days employee was in that department
- [x] 10.9 Add test case: employee transfers Aug 15, Dept A gets Aug 1-14 (10 days), Dept B gets Aug 15-31 (12 days)
- [x] 10.10 Add test case: department filter for Dept A shows only Aug 1-14 attendance, not Aug 15-31
- [x] 10.11 Add test case: department rate includes partial-month employees (transferring in/out)

## 11. Testing and Validation

- [x] 11.1 Run full test suite and fix any failures caused by formula changes
- [x] 11.2 Update `AttendanceReportMultiScopePersistenceIntegrationTest` for all new business rules
- [x] 11.3 Create an offline read-only validation script to compare old vs new attendance rate calculation on sample data (not runtime dual calculation)
- [x] 11.4 Test sick leave display: verify separate column appears and counts correctly
- [x] 11.5 Test late-to-absent conversion: verify 30-minute threshold works across time zones
- [x] 11.6 Test punch correction quota: verify monthly reset behavior
- [x] 11.7 Test overtime classification: verify 3 types display correctly and sum to total
- [x] 11.8 Test exemption/outing logic: verify combined logic with various scenarios
- [x] 11.9 Test Deli sync: manually trigger and verify log entry creation
- [x] 11.10 Test job transfer: verify split rows and correct allocation
- [x] 11.11 Run performance test on department aggregation with large dataset
- [x] 11.12 Validate against OA evidence files in `docs/verification/oa-live/2026-08-10/`

## 12. Documentation and Deployment

- [x] 12.1 Update API documentation (OpenAPI spec) with new endpoints for punch correction
- [x] 12.2 Update database schema documentation to include V48 changes
- [x] 12.3 Create release notes documenting formula change and new business rules
- [x] 12.4 Document HR communication plan for late-to-absent policy announcement
- [x] 12.5 Document punch correction request workflow (submission → approval → application)
- [x] 12.6 Update `CLAUDE.md` or `README.md` with references to signed business decision document
- [x] 12.7 Create BaoTa deployment checklist: verified backup → staged-JAR forward migration → artifact cutover → Deli/OA source-specific enablement
- [x] 12.8 Document artifact rollback and database restore/forward-fix boundaries for each phase; do not rely on feature flags or automatic reverse Flyway
- [ ] 12.9 Schedule deployment window and notify stakeholders
- [ ] 12.10 Deploy to staging environment and run smoke tests
- [ ] 12.11 Deploy to production and monitor for 24 hours
- [ ] 12.12 After 1 month stable operation, complete the post-deployment review and retire previous application/frontend artifacts plus temporary offline comparison evidence according to the approved retention policy

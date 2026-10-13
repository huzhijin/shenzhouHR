# Design: Business Rules Alignment 2026-08

## Context

At change inception, the attendance calculation implementation had diverged from the confirmed business requirements in multiple areas:

1. **Attendance Rate Formula**: Code uses minute-based formula (`confirmedMinutes ÷ scheduledMinutes`) with a constant name that claims day-based calculation. Business has now confirmed to use actual day-based formula (`actualDays ÷ scheduledDays`).

2. **OA Integration**: Current code queries 3 types of OA documents (exemption, business trip, outing). Business confirmed that business trips should NOT be queried - employees on business trips are expected to punch normally.

3. **Leave Type Handling**: Code does not explicitly handle sick leave as attendance, and does not preserve leave types for separate reporting.

4. **Missing Punch Logic**: Full-day absence counting (1 vs 2 missing punches) needs explicit confirmation.

5. **New Rules**: Three business rules have no code implementation:
   - Late ≥30 minutes → absent
   - Punch correction limit: 1 per month per employee
   - Overtime classification: paid/compensatory/voluntary

6. **Sync Monitoring**: Original plan included complex alert thresholds. Business simplified to display-only.

See `proposal.md` for business motivation and stakeholder decisions.

Implemented architecture:
- Deterministic calculation engine: `DeterministicAttendanceCalculator`
- Calculation orchestration: `CalculationEngineOrchestrator` / `FullCalculationEngineOrchestrator`
- Report calculation/publication: `AttendanceReportCalculator` and `AttendanceReportPublicationApplicationService`
- Evidence sources: Deli punch adapters, `OaMysqlAttendanceDocumentAdapter`, and approved synthetic/offline corrections

## Goals / Non-Goals

**Goals:**
- Align attendance rate calculation to day-based formula per business decision Q1
- Implement strict late-to-absent conversion (≥30min) per Q7b
- Add punch correction quota (1/month/employee) per Q7c
- Classify overtime into 3 types (paid/compensatory/voluntary) per Q13
- Simplify OA queries to exclude business trips per Q2
- Preserve leave types for separate sick leave reporting per Q4
- Configure Deli sync to hourly schedule per Q10
- Update all affected tests and validation logic
- Connect to the confirmed production OA source through a SELECT-only account and stage scheduled ingestion only after an authorized manual sample succeeds

**Non-Goals:**
- Changing the overall attendance calculation architecture (daily → monthly remains)
- Migrating historical data with new formulas (new rules apply from deployment forward)
- Building a UI for punch correction requests (manual HR workflow for now)
- Real-time sync from Deli (hourly is sufficient per business)
- Automatic alerts on sync delays (display-only per Q10)
- A `businessRules2026_08` runtime feature flag, production dual calculation, or configuration-only rollback to the old formula
- Any write operation against the OA production database, or automatic OA scheduled-sync enablement during first deployment

## Decisions

### Decision 1: Use day-based formula throughout, remove minute-based

**Choice**: Replace all minute-based attendance rate calculations with day-based (`actualDays ÷ scheduledDays`).

**Rationale**:
- Business confirmed formula B (day-based) in Q1
- Simpler for HR to understand and explain
- Eliminates complexity of partial-day minute tracking for rate calculation
- Minutes are still tracked for other purposes (late time, work hours)

**Alternatives considered**:
- Keep minute-based formula: Rejected because business explicitly chose day-based
- Hybrid (individuals use days, departments use minutes): Rejected for consistency

**Impact**:
- `AttendanceRate` class: remove minute-based methods, keep day-based only
- `AttendanceReportPublication`: use day fields for rate calculation
- `DailyAttendanceProcessor`: ensure `actualAttendanceDays` correctly increments for paid leave
- All tests using minute-based rates need updates

**Implementation note**: The existing constant `ATTENDANCE_RATE_ACTUAL_DAYS_OVER_SCHEDULED_DAYS_V2` has the right name but wrong implementation - fix the implementation, keep the name.

---

### Decision 2: Late ≥30min converts to absent at daily calculation time

**Choice**: In `DailyAttendanceProcessor`, when processing late events, check if `lateMinutes ≥ 30`. If true, mark the day as absent and do NOT count as attended.

**Rationale**:
- Business rule Q7b: "Late ≥30min = absent"
- Must affect attendance rate (actualDays should not increment)
- Simplest to handle at daily level before aggregation

**Alternatives considered**:
- Apply at monthly aggregation: Would require re-reading daily records; less efficient
- Separate "severe late" flag: Over-engineering; absent is sufficient
- Configurable threshold: Not needed; 30min is fixed per business

**Impact**:
- `DailyAttendanceProcessor`: Add check after late time calculation
- If `lateMinutes ≥ 30`: set `attendanceStatus = ABSENT`, do not increment `actualDays`
- Else: process as normal late (increment `actualDays` but record late)
- Test cases for 29min (late), 30min (absent), 31min (absent)

---

### Decision 3: Punch correction quota enforced at application layer

**Choice**: Add a `PunchCorrectionQuota` service that checks and enforces "1 correction per employee per month."

**Rationale**:
- Business rule Q7c: 1 correction allowed per month
- Correction is a separate action (HR approves correction requests)
- Not part of core calculation; enforced when correction is submitted
- No UI implementation yet, but backend logic must exist

**Alternatives considered**:
- Database constraint: Cannot express "per month" easily in DB
- Calculate at reporting time: Too late; must block at submission
- No enforcement: Violates business rule

**Implementation**:
- New table: `punch_correction_request` with employee/month/date, punch side,
  status, request/review actors and timestamps, and reason fields
- Service method: `canRequestCorrection(employeeId, month)` returns boolean
- Count approved corrections for (employee, month); if ≥1, return false
- HR workflow: submit request → check quota → approve/reject → apply correction

**Open question**: What happens if employee exceeds quota? (Resolved: reject the request, inform HR)

---

### Decision 4: Overtime classification uses OA field directly

**Choice**: Read `field0096` from OA `formson_0172` table and map enum IDs to overtime types:
- `-6539634143789166714` → 加班费 (paid overtime)
- `5912806790045781226` → 调休 (compensatory leave)
- `4337518111002608138` → 义务加班 (voluntary)
- `NULL` → isolate and alert

**Rationale**:
- Business confirmed Q13: OA has a field at application time
- Field verified in `docs/verification/oa-live/2026-08-10/EVIDENCE.md` (OA-B5-03C)
- 117k+ records exist with these enum values
- Direct mapping is simplest and matches source data

**Alternatives considered**:
- Infer type from other fields: Not reliable, OA has explicit field
- Ask HR to classify later: Business wants it from source
- Three separate OA tables: Verified it's one table with one classification field

**Impact**:
- `OaOvertimeAdapter`: Add mapping logic for `field0096`
- New enum: `OvertimeType { PAID, COMPENSATORY, VOLUNTARY }`
- `OvertimeEvidence`: Add `overtimeType` field
- Reports: Display 3 separate columns + total

---

### Decision 5: Remove business trip from OA queries

**Choice**: Do NOT query the signed business-trip form `formmain_0265` from OA. Business trips do not grant exemption from punch requirements.

**Rationale**:
- Business decision Q2: "出差单不用查，只需要查外出单"
- Employees on business trips are expected to punch (mobile punch or at destination)
- Reduces OA query complexity by 33%

**Alternatives considered**:
- Keep querying but ignore results: Wastes resources
- Make it configurable: Not needed; business is firm

**Impact**:
- `OaMysqlAttendanceDocumentAdapter`: Do not add a business-trip query
- For exemption/outing evidence, query only the signed forms: exemption
  (`formmain_0201` + `formson_0202`) and outing
  (`formmain_0251` + `formson_0252`)
- Update tests to NOT expect business trip evidence
- Documentation: clarify that business trips require normal punch

---

### Decision 6: Preserve leave type in daily records, aggregate for reports

**Choice**:
1. Store `leaveType` (enum) in `attendance_report_daily_fact` daily records
2. Reports query and aggregate by leave type for separate display
3. Sick leave counts as attendance (increment `actualDays`) but displays separately

**Rationale**:
- Business rule Q4: "病假算出勤，但报表需要显示是病假"
- Need granular data to show sick leave trends
- Aggregation at report time keeps daily calculation simple

**Alternatives considered**:
- Only store "has leave" boolean: Loses type information
- Separate sick leave table: Over-normalization
- Infer leave type from OA at report time: Inefficient

**Implementation**:
- Daily table: Add `leave_type VARCHAR(50)` column (nullable)
- Enum values: `ANNUAL`, `SICK`, `MARRIAGE`, `MATERNITY`, `PATERNITY`, `BEREAVEMENT`, `WORK_INJURY`, `PRENATAL_NURSING`, `PERSONAL`, `COMPENSATORY`
- Daily processor: When processing leave evidence, store type
- Reports aggregate `attendance_report_daily_fact` rows where
  `leave_type = 'SICK'`, scoped by immutable projection/company/month

---

### Decision 7: Deli sync hourly via cron, no alert logic

**Choice**:
- Cron schedule: `0 0 * * * ?` (top of every hour)
- Log sync status: time, record count, success/failure, error message
- Display last sync info on monitoring page
- NO yellow/red alerts, NO automatic month-close blocking

**Rationale**:
- Business decision Q10: "得力每小时同步一次够了，只需要显示同步记录"
- Simplifies implementation (no alert threshold logic)
- HR will manually check if data seems stale

**Alternatives considered**:
- More frequent sync (every 15min): Not needed per business
- Manual trigger only: Too slow for daily operations
- Complex alerting: Rejected by business as over-engineering

**Implementation**:
- Spring `@Scheduled(cron = "0 0 * * * ?")` on `DeliSyncJob`
- Log table: `deli_sync_log` with columns: `sync_time`, `status`, `record_count`, `error_message`
- Incremental ingestion uses each source's provider `next_id` in
  `attendance_sync_watermark`; sync-log times are observational only and never
  filter punches, so late-arriving records and new sources are not skipped
- UI: Display last row from log table
- Retain 30 days of logs for troubleshooting

---

### Decision 8: Department aggregation uses day-based weighted average

**Choice**: Department attendance rate = `SUM(actualDays) ÷ SUM(scheduledDays)` across all employees.

**Rationale**:
- Aligns with individual formula change (Decision 1)
- Business confirmed Q16: "按分钟加权" → now "按天数加权" (same principle, different unit)
- Consistent aggregation method

**Implementation**:
- `AttendanceReportPublication`: When calculating department rates, use day fields
- SQL: `SELECT department_id, SUM(actual_attendance_days) / SUM(scheduled_attendance_days) ...`

---

### Decision 9: Job transfer splits report rows by occurrence date

**Choice**: When employee transfers mid-month, create separate report rows for each department period. Attendance days allocated to department active at time of occurrence.

**Rationale**:
- Business decision Q17: "拆成两行"
- Most accurate allocation (department gets credit for days employee was actually there)
- Prevents gaming (transferring at month-end to inflate one department's numbers)

**Alternatives considered**:
- Entire month to ending department: Inaccurate
- Entire month to starting department: Inaccurate
- Prorate by calendar days: Business wants by actual occurrence date

**Implementation**:
- Query allocates immutable `attendance_report_daily_fact` rows by captured
  `employment_period_id` and the effective `employment_assignment`
- Each daily record already has occurrence date
- Group by (employee, department, month) and filter by assignment effective dates
- Report displays: Employee Name | Department | Period | Actual Days | Scheduled Days | Rate

---

### Decision 10: Business rules cut over with the release artifact

**Choice**: The confirmed 2026-08 rules are compiled as the single runtime path. There is no `businessRules2026_08` feature flag and no production request performs old/new dual calculation.

**Rationale**:
- The customer confirmed every rule in the signed decision artifact; there is no unresolved cohort or alternate policy to route at runtime.
- The old minute formula has already been removed from the runtime calculation path.
- Keeping an unimplemented flag in the deployment design would create a false rollback instruction.

**Implementation**:
- Use `deploy/mysql/sql/compare-attendance-rate-formulas.sql` only as an offline, read-only validation tool; it is not a shadow calculation service.
- Stage and checksum the backend/frontend artifacts, run the staged JAR's forward Flyway migrations, then switch the application artifacts through the BaoTa upgrade path.
- Preserve the previous signed artifacts and the pre-upgrade database backup. Reverting business logic uses those artifacts only after the recovery owner has confirmed database compatibility or restored the verified backup.
- Once a Flyway migration has been attempted, the upgrade failure handler does not authorize starting the old JAR. Keep the application stopped and choose a verified backup restore or a higher-version forward repair; never use automatic reverse migration, `flyway clean`, or an unsupported `repair`.

---

### Decision 11: OA production ingestion uses read-only staged enablement

**Choice**: The system may read the confirmed OA production source directly, but source access is least-privilege and scheduled ingestion is enabled only after a controlled manual validation.

**Rationale**:
- The OA source is production authority and the integration only needs `SELECT`.
- The dedicated Hikari pool and every acquired connection enforce read-only mode; the source account is independently restricted to `SELECT` by the OA DBA.
- Separating reader enablement from scheduler enablement prevents an unvalidated deployment from immediately ingesting every active OA source.

**Implementation**:
1. Keep checked-in and provisioned defaults `OA_MYSQL_ENABLED=false` and `SHENZHOUHR_OA_AUTO_SYNC_ENABLED=false`.
2. Configure the OA JDBC URL and dedicated read-only credentials outside the release artifact, retain `Asia/Shanghai` as both source and scheduler zone, set `OA_MYSQL_ENABLED=true`, and keep automatic sync `false`.
3. Restart the BaoTa Java project and use the authenticated attendance-source job API as a principal with `ATTENDANCE_SOURCE:RUN` and the required company data scope to run one `OA_ATTENDANCE` source.
4. Verify the job result, quarantine count, audit trail, record totals, and representative leave/overtime/exemption/outing samples.
5. Only after acceptance, set `SHENZHOUHR_OA_AUTO_SYNC_ENABLED=true` and restart. The default schedule is every 30 minutes in `Asia/Shanghai`.

**Disablement**: Set automatic sync back to `false` first; disable the OA reader as well if source access must stop. This prevents new reads but does not delete already imported evidence or sync jobs. Any bad target-side evidence is isolated and reconciled through audited forward correction, not by writing to OA or silently deleting history.

---

## Risks / Trade-offs

### Risk: Attendance rate formula change affects historical comparisons

**Description**: After deployment, attendance rates calculated with new formula (days) will not be comparable to historical rates (minutes).

**Mitigation**:
- Apply new formula from deployment date forward only (no backfill)
- Document the change in release notes
- HR should note "rates before 2026-09 used different formula" in reports

---

### Risk: Late-to-absent conversion may surprise employees

**Description**: Employees used to late penalties may not expect that ≥30min late = full day absent.

**Mitigation**:
- Communication plan: HR announces new policy before enforcement
- There is no warning-only runtime switch; if communication is incomplete, postpone the artifact cutover rather than running a different hidden rule
- Clear policy document: "Late ≥30min = absent, affects attendance rate"

---

### Risk: Punch correction quota might be too strict

**Description**: 1 correction per month might not be enough for employees with genuine issues (broken devices, system outages).

**Mitigation**:
- HR can override quota for special circumstances (manual approval flow)
- Monitor correction request rejection rate for first 3 months
- Review policy if rejection rate >10%

---

### Risk: Sick leave display might reveal private health info

**Description**: Displaying sick leave days separately might expose sensitive health information.

**Mitigation**:
- Sick leave column visible only to HR and direct managers (permission check)
- Aggregate department sick leave doesn't show individual names
- Compliance: Check with legal if additional privacy controls needed

---

### Risk: Hourly Deli sync might miss rapid changes

**Description**: If employee punches in at 08:01 and sync runs at 08:00, data won't appear until 09:00 sync.

**Mitigation**:
- Acceptable per business decision (hourly is sufficient)
- HR understands ~1 hour delay in real-time viewing
- Critical corrections can be applied manually via offline correction

---

### Risk: OA field enum values might change over time

**Description**: Overtime classification enum IDs (e.g., `-6539634143789166714`) might change if OA system is upgraded.

**Mitigation**:
- Configuration table: `oa_enum_mapping` with physical OA table/field,
  enum ID, business meaning, display label, and active state
- If OA changes, update config table without code changes
- Alert on unmapped enum values (log warning, isolate records)

---

### Risk: Day-based formula loses precision for partial work arrangements

**Description**: Employees with partial-day schedules (e.g., 4-hour shifts) will be treated as full days in attendance rate.

**Mitigation**:
- Business accepted this trade-off (Q1 decision)
- Alternative: use scheduled work minutes for weighting (rejected by business)
- Document limitation: "Attendance rate is day-based; does not reflect partial-day arrangements"

---

## Deployment and Recovery Plan

### Phase 1: Preflight and recoverable backup

- Freeze the integrated commit and verify release checksums, exact MySQL/Flyway history, and the target BaoTa site contract.
- Stop the Java project and create verified backups of the database, active JAR, frontend, environment files, and BaoTa Nginx configuration.
- Keep Deli scheduling, the OA reader, and OA automatic scheduling disabled until their respective source checks are complete.

**Recovery boundary**: Before any migration attempt, the upgrade failure handler can restore staged file/config state. The database backup remains the authority for database recovery.

### Phase 2: Forward migration and artifact cutover

- Stage the new JAR and frontend without replacing the active artifacts.
- Use the migration account to run the staged JAR's forward Flyway chain through V48 and verify the required schema/routine contract.
- Only after migration succeeds, replace the backend/frontend artifacts and start the BaoTa Java project for health, authorization, and business sample checks.
- Preserve existing environment values; the release sample never overwrites OA/Deli credentials or prints them.

**Recovery boundary**: There is no feature-flag rollback. If failure occurs after migration was attempted, do not start the old JAR automatically. Keep the system stopped and use the approved database restore plus previous artifacts, or publish a higher-version forward fix. `ROLLBACK_V48__business_rules_alignment_schema.sql` is a destructive, last-resort reference that requires DBA/data-owner approval and verified backup; it is not part of automatic BaoTa rollback.

### Phase 3: Read-only source enablement

1. Validate the Deli source and enable its confirmed hourly schedule separately.
2. Configure the OA production `SELECT` account with `OA_MYSQL_ENABLED=true` while leaving `SHENZHOUHR_OA_AUTO_SYNC_ENABLED=false`.
3. Run one authorized manual `OA_ATTENDANCE` source job and reconcile representative records.
4. Enable OA automatic sync only after that evidence passes; retain the default 30-minute Shanghai schedule.

**Disablement**: Turn off the affected scheduler first. For OA, also turn off the reader when all source access must stop. Disabling prevents future ingestion but never silently deletes imported evidence, audit records, or watermarks.

### Phase 4: Acceptance and retention

- Complete staging smoke tests, production cutover, and 24-hour monitoring with external evidence before closing tasks 12.9–12.11.
- After one month of stable operation, complete the post-deployment review and retire previous application/frontend artifacts and temporary offline comparison evidence according to the approved retention policy; preserve Flyway history and required backups.

### Data validation

- Compare attendance rates for sample employees with the offline read-only comparison SQL (not runtime dual calculation)
- Verify sick leave counts match OA records
- Confirm late-to-absent conversion triggers correctly
- Check overtime classification distribution matches OA data

---

## Open Questions

None. All business rules confirmed via signed decision document `FINAL-BUSINESS-DECISIONS-SIGNOFF.md` dated 2026-08-16.

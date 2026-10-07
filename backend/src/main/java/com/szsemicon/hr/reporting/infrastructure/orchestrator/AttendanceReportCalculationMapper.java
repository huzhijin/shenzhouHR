package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchEventRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchCorrectionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.PunchExemptionRoleIntervalRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.SourceInputVersionRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Batch reads for assembling a company-month report projection. Company-month
 * statements stay company-scoped. Person-day save uses the matching
 * {@code ForEmployee} reads so one adjustment does not reload the roster.
 */
@Mapper
interface AttendanceReportCalculationMapper {

    /**
     * Returns the active Deli/OA source revisions and latest page committed by
     * the report knowledge cutoff. This is one bounded company query and does
     * not expose raw provider cursors.
     */
    List<SourceInputVersionRow> findAttendanceSourceVersions(
            @Param("companyId") String companyId,
            @Param("dataAsOf") Instant dataAsOf);

    /**
     * Returns every active employee identity interval that overlaps the
     * period. One row per employee version / assignment / organization
     * version combination, not per day.
     */
    List<EmployeeIdentityIntervalRow> findEmployeeIdentityIntervals(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive);

    List<EmployeeIdentityIntervalRow> findEmployeeIdentityIntervalsForEmployee(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("employeeId") String employeeId);

    /**
     * Returns every activated punch point in the half-open instant window.
     * Reversed or superseded events are excluded by their latest lifecycle
     * fact, consistent with the evidence-ingestion read path.
     */
    List<PunchEventRow> findActivatedPunchEvents(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<PunchEventRow> findActivatedPunchEventsForEmployee(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("dataAsOf") Instant dataAsOf,
            @Param("employeeId") String employeeId);

    /**
     * Returns approved punch corrections visible at the knowledge cutoff.
     * Pending/rejected records never enter calculation evidence.
     */
    List<PunchCorrectionRow> findApprovedPunchCorrections(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<PunchCorrectionRow> findApprovedPunchCorrectionsForEmployee(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf,
            @Param("employeeId") String employeeId);

    List<AttendanceReportCalculationRows.HrPunchAdjustmentRow> findHrPunchAdjustments(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<AttendanceReportCalculationRows.HrPunchAdjustmentRow>
            findHrPunchAdjustmentsForEmployee(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive") LocalDate periodEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf,
                    @Param("employeeId") String employeeId);

    /** Returns EXECUTIVE no-punch role intervals overlapping the month. */
    List<PunchExemptionRoleIntervalRow> findPunchExemptionRoleIntervals(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive);

    List<PunchExemptionRoleIntervalRow> findPunchExemptionRoleIntervalsForEmployee(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("employeeId") String employeeId);

    /**
     * Returns standing-list punch-exemption intervals. Matched by 工号 across
     * companies so a person listed once is exempt on every roster company.
     */
    List<PunchExemptionRoleIntervalRow> findStandingPunchExemptionIntervals(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive);

    List<PunchExemptionRoleIntervalRow>
            findStandingPunchExemptionIntervalsForEmployee(
                    @Param("companyId") String companyId,
                    @Param("windowStart") Instant windowStart,
                    @Param("windowEndExclusive") Instant windowEndExclusive,
                    @Param("employeeId") String employeeId);

    List<AttendanceReportCalculationRows.OrganizationGraphRow>
            findCurrentOrganizationGraph(@Param("companyId") String companyId);

    List<AttendanceReportCalculationRows.OrganizationAncestorRow>
            findCurrentOrganizationAncestors(@Param("companyId") String companyId);

    /**
     * Returns each employee's uniquely effective attendance-group calendar
     * authority inside the period. Missing or ambiguous employee-day rows are
     * rejected by the orchestrator rather than guessed from the weekday.
     */
    List<CalendarDayRow> findPublishedCalendarDays(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    /**
     * Returns the latest effective OA attendance-document version for each
     * source/business key in the company and time window. Version selection
     * happens before approval filtering so a newer pending or revoked version
     * cannot leave an older approved version active.
     */
    List<AttendanceReportCalculationRows.OaDocumentRow> findEffectiveOaDocuments(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<AttendanceReportCalculationRows.OaDocumentRow>
            findEffectiveOaDocumentsForEmployee(
                    @Param("companyId") String companyId,
                    @Param("windowStart") Instant windowStart,
                    @Param("windowEndExclusive") Instant windowEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf,
                    @Param("employeeId") String employeeId);

    /**
     * Returns the latest approved leave/time-off documents that must be copied
     * into the immutable monthly report. The knowledge cutoff is applied
     * before version ranking, so a historical publication cannot see a later
     * OA ingestion or lifecycle update.
     */
    List<AttendanceReportCalculationRows.OaReportFactRow> findReportableOaDocuments(
            @Param("companyId") String companyId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEndExclusive") Instant windowEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<AttendanceReportCalculationRows.OaReportFactRow>
            findReportableOaDocumentsForEmployee(
                    @Param("companyId") String companyId,
                    @Param("windowStart") Instant windowStart,
                    @Param("windowEndExclusive") Instant windowEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf,
                    @Param("employeeId") String employeeId);

    List<AttendanceReportCalculationRows.EmployeeLateDayCountRow>
            countLateDaysBeforeWindow(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("beforeDate") LocalDate beforeDate);

    List<AttendanceReportCalculationRows.EmployeeLateDayCountRow>
            countLateDaysBeforeWindowForEmployee(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("beforeDate") LocalDate beforeDate,
                    @Param("employeeId") String employeeId);

    /**
     * Returns ledger-derived account components for assignments overlapping
     * the report month at the same knowledge cutoff as daily calculation.
     */
    List<AttendanceReportCalculationRows.TimeAccountSnapshotRow>
            findTimeAccountSnapshots(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive") LocalDate periodEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf);

    List<AttendanceReportCalculationRows.TimeAccountSnapshotRow>
            findTimeAccountSnapshotsForEmployee(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive") LocalDate periodEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf,
                    @Param("employeeId") String employeeId);

    /**
     * Returns scheduled work segments for all employees in the company for
     * the given period, based on their assigned shift templates and work
     * calendar days.
     */
    List<AttendanceReportCalculationRows.ShiftSegmentRow> findScheduledWorkSegments(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    List<AttendanceReportCalculationRows.PunchWindowRow> findUniquePunchWindows(
            @Param("companyId") String companyId,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEndExclusive") LocalDate periodEndExclusive,
            @Param("dataAsOf") Instant dataAsOf);

    /**
     * Returns the policy authority for each employee/business-date pair.
     * Late-grace policies are resolved through the employee's effective
     * attendance-group revision while deadline policies are resolved from the
     * company scope. Candidate cardinalities are retained so the orchestrator
     * can fail closed on missing or ambiguous authority.
     */
    List<AttendanceReportCalculationRows.AttendancePolicyRow>
            findAttendancePolicies(
                    @Param("companyId") String companyId,
                    @Param("periodStart") LocalDate periodStart,
                    @Param("periodEndExclusive") LocalDate periodEndExclusive,
                    @Param("dataAsOf") Instant dataAsOf);
}

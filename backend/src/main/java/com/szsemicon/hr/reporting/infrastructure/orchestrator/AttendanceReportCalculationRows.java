package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read rows for the calculation orchestrator. These are raw persistence
 * projections: they carry no report semantics and are never exposed outside
 * this package.
 */
final class AttendanceReportCalculationRows {

    private AttendanceReportCalculationRows() {
    }

    /**
     * Resolves the occurrence-time identity for one business date. Overlapping
     * assignments follow the business rule "latest effective date wins";
     * candidates tied on that latest assignment date remain ambiguous so an
     * employee- or organization-version overlap is never guessed.
     */
    static EmployeeIdentityIntervalRow latestEffectiveAssignment(
            List<EmployeeIdentityIntervalRow> candidates,
            LocalDate businessDate) {
        EmployeeIdentityIntervalRow selected = null;
        LocalDate latestEffectiveFrom = null;
        boolean latestIsAmbiguous = false;
        for (EmployeeIdentityIntervalRow candidate : candidates) {
            if (!candidate.validOn(businessDate)) {
                continue;
            }
            if (latestEffectiveFrom == null
                    || candidate.employmentFrom()
                            .isAfter(latestEffectiveFrom)) {
                selected = candidate;
                latestEffectiveFrom = candidate.employmentFrom();
                latestIsAmbiguous = false;
            } else if (candidate.employmentFrom()
                    .equals(latestEffectiveFrom)) {
                latestIsAmbiguous = true;
            }
        }
        return latestIsAmbiguous ? null : selected;
    }

    /**
     * One candidate employee identity interval that overlaps the requested
     * period. The three effective ranges are intersected per business date by
     * the orchestrator so a mid-month identity change is bound to the exact
     * version that was valid on each day.
     *
     * <p>{@code employmentAssignmentId} is {@code employment_assignment
     * .assignment_id}: the same column the projection writer stores in
     * {@code attendance_report_daily_fact.employment_period_id} and the same
     * value the period-protection query reports as
     * {@code employment_assignment_id}.
     */
    record EmployeeIdentityIntervalRow(
            String employeeId,
            String employeeVersionId,
            String employeeNumber,
            String employeeName,
            String employmentAssignmentId,
            String organizationId,
            String organizationVersionId,
            String organizationName,
            LocalDate employeeVersionFrom,
            LocalDate employeeVersionTo,
            LocalDate employmentFrom,
            LocalDate employmentTo,
            LocalDate organizationVersionFrom,
            LocalDate organizationVersionTo) {

        /**
         * Returns whether every identity version of this row is valid on the
         * given business date. The predicates mirror
         * {@code AttendanceReportProjectionWriteMapper.insertDailyFact} so an
         * accepted row is guaranteed to be insertable.
         */
        boolean validOn(LocalDate businessDate) {
            return covers(employeeVersionFrom, employeeVersionTo, businessDate)
                    && covers(employmentFrom, employmentTo, businessDate)
                    && covers(
                            organizationVersionFrom,
                            organizationVersionTo,
                            businessDate);
        }

        private static boolean covers(
                LocalDate from, LocalDate toExclusive, LocalDate day) {
            return from != null
                    && !day.isBefore(from)
                    && (toExclusive == null || day.isBefore(toExclusive));
        }
    }

    /**
     * One activated punch point. The instant is read back as an
     * {@link Instant} so the business date is derived in the business zone
     * rather than in whatever wall-clock zone the column was written with.
     */
    record PunchEventRow(String employeeId, Instant pointInstant) {
    }

    /** One approved punch-correction row used as synthetic punch evidence. */
    record PunchCorrectionRow(
            String requestId,
            String employeeId,
            LocalDate businessDate,
            PunchSide punchSide,
            Instant reviewedAt) {
    }

    /** One occurrence-time interval granting a permanent no-punch role. */
    record PunchExemptionRoleIntervalRow(
            String employeeId,
            Instant validFrom,
            Instant validTo) {

        boolean activeAt(Instant instant) {
            return !instant.isBefore(validFrom)
                    && (validTo == null || instant.isBefore(validTo));
        }
    }

    /** One published work-calendar day for the company. */
    record CalendarDayRow(LocalDate businessDate, String dayType) {
    }

    /** One effective OA attendance document used by calculation. */
    record OaDocumentRow(
            String sourceBusinessKey,
            String documentType,
            OvertimeType overtimeType,
            LeaveType leaveType,
            String employeeNumber,
            Instant startInstant,
            Instant endInstant,
            String sourceTimeZone,
            Instant firstSubmittedAt,
            boolean effectiveCandidate) {

        OaDocumentRow(
                String sourceBusinessKey,
                String documentType,
                OvertimeType overtimeType,
                String employeeNumber,
                Instant startInstant,
                Instant endInstant,
                String sourceTimeZone,
                Instant firstSubmittedAt,
                boolean effectiveCandidate) {
            this(sourceBusinessKey, documentType, overtimeType, null,
                    employeeNumber, startInstant, endInstant, sourceTimeZone,
                    firstSubmittedAt, effectiveCandidate);
        }
    }

    /**
     * One effective OA document prepared for the immutable monthly report.
     * Unlike {@link OaDocumentRow}, this row carries the exact persistence and
     * occurrence-time identity anchors required by the projection writer.
     */
    record OaReportFactRow(
            String oaAttendanceDocumentId,
            String sourceBusinessKey,
            String documentType,
            LeaveType leaveType,
            String employeeId,
            String employeeNumber,
            String employmentAssignmentId,
            Instant startInstant,
            Instant endInstant,
            String sourceStatus,
            String sourceVersion) {
    }

    /**
     * Ledger components for one employee time account at the report's
     * knowledge cutoff. Amounts are already normalized to non-negative report
     * components; the publication model recomputes the displayed balance.
     */
    record TimeAccountSnapshotRow(
            String accountId,
            String employeeId,
            String employmentAssignmentId,
            TimeAccountType accountType,
            BigDecimal openingHours,
            BigDecimal grantedHours,
            BigDecimal overtimeCreditHours,
            BigDecimal manualIncreaseHours,
            BigDecimal usedHours,
            BigDecimal expiredHours,
            BigDecimal returnedHours,
            BigDecimal manualDeductionHours,
            String ledgerVersion) {
    }

    /** One scheduled work segment for an employee on a business date */
    record ShiftSegmentRow(
            String employeeId,
            LocalDate businessDate,
            String segmentId,
            Instant segmentStart,
            Instant segmentEnd,
            Instant arrivalWindowStart,
            Instant arrivalWindowEnd,
            Instant departureWindowStart,
            Instant departureWindowEnd) {
    }

    /** Attendance policy configuration for calculation */
    record AttendancePolicyRow(
            int lateGraceMaxMinutes,
            int monthlyLateGraceUses,
            Instant correctionDeadline,
            int overtimeSubmissionDeadlineMinutes) {
    }
}

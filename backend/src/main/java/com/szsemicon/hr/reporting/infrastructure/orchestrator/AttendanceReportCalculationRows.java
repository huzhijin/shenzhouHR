package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

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

    /** One employee-scoped published work-calendar authority result. */
    record CalendarDayRow(
            String employeeId,
            LocalDate businessDate,
            String dayType,
            int authorityCount,
            int distinctDayTypeCount) {

        CalendarDayRow {
            Objects.requireNonNull(businessDate, "businessDate");
            dayType = Objects.requireNonNull(dayType, "dayType").trim();
            if (dayType.isEmpty()) {
                throw new IllegalArgumentException("dayType is required");
            }
            if (authorityCount < 1 || distinctDayTypeCount < 1) {
                throw new IllegalArgumentException(
                        "calendar authority counts must be positive");
            }
        }

        /** Compatibility constructor for isolated global-calendar fixtures. */
        CalendarDayRow(LocalDate businessDate, String dayType) {
            this(null, businessDate, dayType, 1, 1);
        }
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

    /**
     * One active attendance source and the latest page that was committed no
     * later than the report knowledge cutoff. Raw provider cursors are never
     * exposed in report metadata; the immutable page digest and commit time
     * are sufficient to bind pagination and show freshness safely.
     */
    record SourceInputVersionRow(
            String sourceId,
            String sourceType,
            long sourceRowVersion,
            int configurationRevision,
            Long watermarkRowVersion,
            String committedPageDigest,
            Instant committedAt) {

        SourceInputVersionRow {
            sourceId = required(sourceId, "sourceId");
            sourceType = required(sourceType, "sourceType");
            if (sourceRowVersion < 0 || configurationRevision < 0
                    || (watermarkRowVersion != null
                    && watermarkRowVersion < 0)) {
                throw new IllegalArgumentException(
                        "attendance source versions must be non-negative");
            }
            if ((committedAt == null) != (committedPageDigest == null)) {
                throw new IllegalArgumentException(
                        "committed source metadata must be complete");
            }
            if (committedPageDigest != null
                    && !committedPageDigest.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "committed page digest is invalid");
            }
        }

        String canonicalVersion() {
            String cutoff = committedAt == null
                    ? "UNSYNCED"
                    : committedAt.toString();
            String detail = String.join(
                    "\n",
                    sourceId,
                    sourceType,
                    Long.toString(sourceRowVersion),
                    Integer.toString(configurationRevision),
                    Objects.toString(watermarkRowVersion, ""),
                    Objects.toString(committedPageDigest, ""),
                    cutoff);
            try {
                String digest = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(
                                detail.getBytes(StandardCharsets.UTF_8)));
                // VerifiedProjectionMetadata limits each version to 128 chars.
                // Keep the human-readable source type and committed cutoff;
                // hash all identifiers and revisions into the fixed suffix.
                return "SOURCE." + sourceType + ":" + cutoff + ":" + digest;
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String required(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
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

    /**
     * Policy authority for one employee and business date. Counts deliberately
     * survive the SQL projection: a single-value mapper would otherwise hide
     * missing or ambiguous policy candidates.
     */
    record AttendancePolicyRow(
            String employeeId,
            LocalDate businessDate,
            int attendanceGroupAuthorityCount,
            int lateGracePolicyCount,
            Boolean lateGraceEnabled,
            Integer lateGraceMinutes,
            int monthlyLateExemptionPolicyCount,
            Boolean monthlyLateExemptionEnabled,
            Integer monthlyLateExemptionGraceMinutes,
            Integer monthlyLateExemptionUses,
            Boolean resetOnGroupChange,
            int missingPunchPolicyCount,
            Boolean missingPunchEnabled,
            Integer correctionWindowDays,
            String correctionDeadlineMode,
            int overtimePolicyCount,
            Boolean overtimeEnabled,
            Integer overtimeSubmissionDeadlineHours) {
    }
}

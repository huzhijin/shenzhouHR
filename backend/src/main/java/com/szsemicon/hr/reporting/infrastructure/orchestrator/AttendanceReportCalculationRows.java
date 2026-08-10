package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Read rows for the calculation orchestrator. These are raw persistence
 * projections: they carry no report semantics and are never exposed outside
 * this package.
 */
final class AttendanceReportCalculationRows {

    private AttendanceReportCalculationRows() {
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

    /** One published work-calendar day for the company. */
    record CalendarDayRow(LocalDate businessDate, String dayType) {
    }
}

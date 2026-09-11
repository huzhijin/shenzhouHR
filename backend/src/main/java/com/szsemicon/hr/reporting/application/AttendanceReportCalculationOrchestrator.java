package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import java.time.Instant;
import java.time.YearMonth;

/**
 * Performs the side-effect-free batch attendance calculation for one
 * company-month.
 *
 * <p>The returned {@link PublishCommand} is an in-memory calculation result;
 * this interface never persists or publishes a report projection. Realtime
 * report reads consume the result directly, while the optional compatibility
 * publication flow may persist the same result afterwards.
 *
 * <p>The implementation must:
 * <ol>
 *   <li>Load identities, schedules, policies and committed Deli/OA evidence in
 *       bounded company-period batches.</li>
 *   <li>Run {@code DeterministicAttendanceCalculator.calculate()} for each
 *       resolvable employee-day entirely in memory.</li>
 *   <li>Project the results with {@code AttendanceReportFactProjector}.</li>
 *   <li>Combine the results into a deterministic {@link PublishCommand} and
 *       return it without database writes.</li>
 * </ol>
 */
public interface AttendanceReportCalculationOrchestrator {

    /**
     * Calculates the current committed input snapshot for the company-month.
     *
     * @param companyId   legal entity ID
     * @param period      target calendar month
     * @param periodState state to publish the period as
     * @param principalId the triggering user
     * @param dataAsOf    the knowledge cutoff for the snapshot
     * @return a deterministic in-memory result that realtime reads can consume
     *         directly and the compatibility publication path can persist
     */
    PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf);

    default PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStartInclusive,
            java.time.LocalDate writeEndExclusive) {
        return assemble(
                companyId,
                period,
                periodState,
                principalId,
                dataAsOf,
                writeStartInclusive,
                writeEndExclusive,
                (String) null);
    }

    default PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStartInclusive,
            java.time.LocalDate writeEndExclusive,
            String employeeId) {
        return assemble(
                companyId, period, periodState, principalId, dataAsOf);
    }

    default PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            Instant dataAsOf,
            java.time.LocalDate writeStartInclusive,
            java.time.LocalDate writeEndExclusive,
            java.util.Collection<String> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return assemble(
                    companyId,
                    period,
                    periodState,
                    principalId,
                    dataAsOf,
                    writeStartInclusive,
                    writeEndExclusive,
                    (String) null);
        }
        if (employeeIds.size() == 1) {
            return assemble(
                    companyId,
                    period,
                    periodState,
                    principalId,
                    dataAsOf,
                    writeStartInclusive,
                    writeEndExclusive,
                    employeeIds.iterator().next());
        }
        return assemble(
                companyId, period, periodState, principalId, dataAsOf);
    }
}

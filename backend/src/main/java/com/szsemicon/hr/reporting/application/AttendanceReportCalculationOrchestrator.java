package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import java.time.Instant;
import java.time.YearMonth;

/**
 * Assembles a {@link PublishCommand} from the current attendance calculation
 * state for a given company-month.
 *
 * <p>The implementation must:
 * <ol>
 *   <li>Iterate every active employee in the company.</li>
 *   <li>For each day in the period load the calculation snapshot via
 *       {@code AttendanceCalculationPorts.AttendanceInputSnapshotAssembler}.</li>
 *   <li>Run {@code DeterministicAttendanceCalculator.calculate()} for each
 *       employee-day that has a snapshot.</li>
 *   <li>Project the results with {@code AttendanceReportFactProjector}.</li>
 *   <li>Combine the results into a {@link PublishCommand} and return it.</li>
 * </ol>
 *
 * <p>This interface is intentionally left without an implementation for now.
 * Register a Spring {@code @Service} that implements it to activate the
 * manual publication endpoint.</p>
 *
 * <p>TODO: Implement this interface once the following adapters are wired:
 * <ul>
 *   <li>{@code AttendanceCalculationPorts.AttendanceInputSnapshotAssembler}</li>
 *   <li>{@code AttendanceCalculationPorts.AttendanceEvidenceSnapshotPort}</li>
 *   <li>{@code AttendanceCalculationPorts.AttendanceCalculationVersionStore}</li>
 * </ul>
 */
public interface AttendanceReportCalculationOrchestrator {

    /**
     * Assembles a publication command for the given company-month.
     *
     * @param companyId   legal entity ID
     * @param period      target calendar month
     * @param periodState state to publish the period as
     * @param principalId the triggering user
     * @param dataAsOf    the knowledge cutoff for the snapshot
     * @return a fully-assembled command ready for
     *         {@link AttendanceReportProjectionPublicationUseCase#publish}
     */
    PublishCommand assemble(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String principalId,
            java.time.Instant dataAsOf);
}

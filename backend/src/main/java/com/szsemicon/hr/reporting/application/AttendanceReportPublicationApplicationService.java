package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Application façade for manually triggering a report projection publication.
 *
 * <p>This service depends on {@link AttendanceReportCalculationOrchestrator} to
 * assemble verified facts for the requested company-month. While no
 * implementation of that interface is registered the endpoint returns
 * {@code 503 CALCULATION_ENGINE_NOT_AVAILABLE} so it is safe to deploy without
 * the full calculation pipeline in place. Once the orchestrator is wired in,
 * no changes to this class are needed.</p>
 */
@Service
public class AttendanceReportPublicationApplicationService {

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceReportProjectionPublicationUseCase publicationUseCase;
    private final List<AttendanceReportCalculationOrchestrator> orchestrators;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceReportPublicationApplicationService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportProjectionPublicationUseCase publicationUseCase,
            List<AttendanceReportCalculationOrchestrator> orchestrators,
            AuditService auditService,
            Clock clock) {
        this.capabilities = Objects.requireNonNull(capabilities);
        this.principalProvider = Objects.requireNonNull(principalProvider);
        this.publicationUseCase = Objects.requireNonNull(publicationUseCase);
        this.orchestrators = List.copyOf(
                Objects.requireNonNull(orchestrators));
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Publishes a report projection for the given company-month.
     *
     * @param companyId   legal entity ID
     * @param period      target calendar month
     * @param periodState desired publication period state
     * @param reason      human-readable rationale for the audit log
     * @return the publication result returned by the use case
     */
    public PublicationResult publish(
            String companyId,
            YearMonth period,
            PeriodState periodState,
            String reason) {
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        String principalId = principalProvider.currentPrincipalId();
        // All report timestamps are persisted in DATETIME(6). A system clock
        // can expose nanoseconds, so normalize at the application boundary
        // before constructing the database-precision metadata contract.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (orchestrators.size() != 1) {
            auditService.recordFailure(
                    principalId,
                    "ATTENDANCE_REPORT_PUBLISH",
                    "ATTENDANCE_REPORT",
                    companyId + ":" + period,
                    "FAILURE",
                    "CALCULATION_ENGINE_NOT_AVAILABLE");
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CALCULATION_ENGINE_NOT_AVAILABLE",
                    "考勤核算引擎尚未接入，无法触发报表发布",
                    false);
        }

        AttendanceReportCalculationOrchestrator orchestrator =
                orchestrators.getFirst();
        AttendanceReportPublicationModels.PublishCommand command;
        try {
            command = orchestrator.assemble(
                    companyId, period, periodState, principalId, now);
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId,
                    "ATTENDANCE_REPORT_PUBLISH",
                    "ATTENDANCE_REPORT",
                    companyId + ":" + period,
                    "FAILURE",
                    "CALCULATION_ASSEMBLY_FAILED");
            throw new ApiProblemException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "CALCULATION_ASSEMBLY_FAILED",
                    "考勤核算数据组装失败",
                    false);
        }

        try {
            PublicationResult result = publicationUseCase.publish(command);
            auditService.record(
                    principalId,
                    "ATTENDANCE_REPORT_PUBLISH",
                    "ATTENDANCE_REPORT",
                    result.projectionVersion(),
                    "SUCCESS",
                    result.created()
                            ? "PROJECTION_CREATED"
                            : "PROJECTION_REPLAYED");
            return result;
        } catch (RuntimeException exception) {
            auditService.recordFailure(
                    principalId,
                    "ATTENDANCE_REPORT_PUBLISH",
                    "ATTENDANCE_REPORT",
                    companyId + ":" + period,
                    "FAILURE",
                    "PROJECTION_PUBLISH_FAILED");
            throw exception;
        }
    }
}

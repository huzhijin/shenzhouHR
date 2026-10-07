package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

class AttendanceReportPublicationApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-08T10:00:00Z");
    private static final String COMPANY =
            "10000000-0000-0000-0000-000000000001";
    private static final YearMonth PERIOD = YearMonth.of(2026, 7);
    private static final String PRINCIPAL =
            "20000000-0000-0000-0000-000000000001";
    private static final String OTHER_COMPANY =
            "10000000-0000-0000-0000-000000000002";

    @Test
    void crossCompanyScopeIsDeniedBeforeCalculation() {
        AttendanceReportCalculationOrchestrator orchestrator =
                mock(AttendanceReportCalculationOrchestrator.class);
        AttendanceReportProjectionPublicationUseCase useCase =
                mock(AttendanceReportProjectionPublicationUseCase.class);
        PeopleRepository peopleRepository = mock(PeopleRepository.class);
        AuditService auditService = mock(AuditService.class);
        var service = service(
                List.of(orchestrator),
                useCase,
                Clock.fixed(NOW, ZoneOffset.UTC),
                peopleRepository,
                auditService);

        assertThatThrownBy(() -> service.publish(
                OTHER_COMPANY, PERIOD, PeriodState.OPEN, "manual run"))
                .isInstanceOf(AccessDeniedException.class);

        verify(peopleRepository).canAccessCompany(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                OTHER_COMPANY,
                NOW);
        verify(auditService).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_PUBLISH",
                "ATTENDANCE_REPORT",
                OTHER_COMPANY + ":" + PERIOD,
                "DENIED",
                "COMPANY_SCOPE_DENIED");
        verifyNoInteractions(orchestrator, useCase);
    }

    @Test
    void expiredCompanyScopeIsDeniedBeforeCalculation() {
        Instant afterScopeExpiry = Instant.parse("2026-08-09T10:00:00Z");
        AttendanceReportCalculationOrchestrator orchestrator =
                mock(AttendanceReportCalculationOrchestrator.class);
        AttendanceReportProjectionPublicationUseCase useCase =
                mock(AttendanceReportProjectionPublicationUseCase.class);
        PeopleRepository peopleRepository = mock(PeopleRepository.class);
        AuditService auditService = mock(AuditService.class);
        var service = service(
                List.of(orchestrator),
                useCase,
                Clock.fixed(afterScopeExpiry, ZoneOffset.UTC),
                peopleRepository,
                auditService);

        assertThatThrownBy(() -> service.publish(
                COMPANY, PERIOD, PeriodState.OPEN, "manual run"))
                .isInstanceOf(AccessDeniedException.class);

        verify(peopleRepository).canAccessCompany(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                COMPANY,
                afterScopeExpiry);
        verify(auditService).recordFailure(
                PRINCIPAL,
                "ATTENDANCE_REPORT_PUBLISH",
                "ATTENDANCE_REPORT",
                COMPANY + ":" + PERIOD,
                "DENIED",
                "COMPANY_SCOPE_DENIED");
        verifyNoInteractions(orchestrator, useCase);
    }

    @Test
    void returnsServiceUnavailableWhenNoOrchestratorIsRegistered() {
        var service = service(List.of());

        assertThatThrownBy(() ->
                service.publish(COMPANY, PERIOD, PeriodState.OPEN, "test"))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(ex -> {
                    var problem = (ApiProblemException) ex;
                    assertThat(problem.status())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(problem.code())
                            .isEqualTo("CALCULATION_ENGINE_NOT_AVAILABLE");
                });
    }

    @Test
    void sameCompanyScopeDelegatesToOrchestratorAndUseCase() {
        AttendanceReportCalculationOrchestrator orchestrator =
                mock(AttendanceReportCalculationOrchestrator.class);
        AttendanceReportProjectionPublicationUseCase useCase =
                mock(AttendanceReportProjectionPublicationUseCase.class);
        PeopleRepository peopleRepository = mock(PeopleRepository.class);

        // PublishCommand is a record — build a minimal real instance
        PublishCommand command = minimalCommand();
        PublicationResult expected = new PublicationResult(
                "10000000-0000-0000-0000-000000000099",
                "ARP1-" + "a".repeat(64),
                "a".repeat(64),
                PeriodState.OPEN,
                NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                true);

        when(orchestrator.assemble(
                eq(COMPANY), eq(PERIOD), eq(PeriodState.OPEN),
                eq(PRINCIPAL), any()))
                .thenReturn(command);
        when(useCase.publish(command)).thenReturn(expected);
        when(peopleRepository.canAccessCompany(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                COMPANY,
                NOW)).thenReturn(true);

        var service = service(
                List.of(orchestrator),
                useCase,
                Clock.fixed(NOW, ZoneOffset.UTC),
                peopleRepository,
                mock(AuditService.class));
        PublicationResult result = service.publish(
                COMPANY, PERIOD, PeriodState.OPEN, "manual run");

        assertThat(result).isEqualTo(expected);
        verify(peopleRepository).canAccessCompany(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                COMPANY,
                NOW);
        verify(orchestrator).assemble(
                eq(COMPANY), eq(PERIOD), eq(PeriodState.OPEN),
                eq(PRINCIPAL), any());
        verify(useCase).publish(command);
    }

    @Test
    void truncatesSystemClockToDatabaseMicrosecondPrecision() {
        Instant nanosecondNow = Instant.parse(
                "2026-08-08T10:00:00.123456789Z");
        Instant expectedDatabaseNow = Instant.parse(
                "2026-08-08T10:00:00.123456Z");
        AttendanceReportCalculationOrchestrator orchestrator =
                mock(AttendanceReportCalculationOrchestrator.class);
        AttendanceReportProjectionPublicationUseCase useCase =
                mock(AttendanceReportProjectionPublicationUseCase.class);
        PublishCommand command = minimalCommand();
        PublicationResult expected = new PublicationResult(
                "10000000-0000-0000-0000-000000000099",
                "ARP1-" + "a".repeat(64),
                "a".repeat(64),
                PeriodState.OPEN,
                expectedDatabaseNow,
                expectedDatabaseNow,
                true);

        when(orchestrator.assemble(
                eq(COMPANY), eq(PERIOD), eq(PeriodState.OPEN), eq(PRINCIPAL),
                argThat(expectedDatabaseNow::equals)))
                .thenReturn(command);
        when(useCase.publish(command)).thenReturn(expected);

        var service = service(
                List.of(orchestrator),
                useCase,
                Clock.fixed(nanosecondNow, ZoneOffset.UTC));

        PublicationResult result = service.publish(
                COMPANY, PERIOD, PeriodState.OPEN, "manual run");

        assertThat(result).isEqualTo(expected);
        verify(orchestrator).assemble(
                eq(COMPANY), eq(PERIOD), eq(PeriodState.OPEN), eq(PRINCIPAL),
                argThat(expectedDatabaseNow::equals));
        verify(useCase).publish(command);
    }

    // ---- helpers --------------------------------------------------------

    private static PublishCommand minimalCommand() {
        var metadata = new VerifiedProjectionMetadata(
                COMPANY,
                PERIOD,
                PeriodState.OPEN,
                "TEST-CATALOG-V1",
                List.of("TEST-SOURCE-V1"),
                "a".repeat(64),
                NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                PRINCIPAL);
        return new PublishCommand(metadata, List.of(), List.of(), List.of());
    }

    private AttendanceReportPublicationApplicationService service(
            List<AttendanceReportCalculationOrchestrator> orchestrators) {
        return service(
                orchestrators,
                mock(AttendanceReportProjectionPublicationUseCase.class));
    }

    private AttendanceReportPublicationApplicationService service(
            List<AttendanceReportCalculationOrchestrator> orchestrators,
            AttendanceReportProjectionPublicationUseCase useCase) {
        return service(
                orchestrators,
                useCase,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AttendanceReportPublicationApplicationService service(
            List<AttendanceReportCalculationOrchestrator> orchestrators,
            AttendanceReportProjectionPublicationUseCase useCase,
            Clock clock) {
        PeopleRepository peopleRepository = mock(PeopleRepository.class);
        when(peopleRepository.canAccessCompany(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_REFRESH),
                eq(COMPANY),
                any(Instant.class))).thenReturn(true);
        return service(
                orchestrators,
                useCase,
                clock,
                peopleRepository,
                mock(AuditService.class));
    }

    private AttendanceReportPublicationApplicationService service(
            List<AttendanceReportCalculationOrchestrator> orchestrators,
            AttendanceReportProjectionPublicationUseCase useCase,
            Clock clock,
            PeopleRepository peopleRepository,
            AuditService auditService) {
        CurrentCapabilityService caps = mock(CurrentCapabilityService.class);
        CurrentPrincipalProvider principal =
                mock(CurrentPrincipalProvider.class);
        when(principal.currentPrincipalId()).thenReturn(PRINCIPAL);
        return new AttendanceReportPublicationApplicationService(
                caps,
                principal,
                peopleRepository,
                useCase,
                orchestrators,
                auditService,
                clock);
    }
}

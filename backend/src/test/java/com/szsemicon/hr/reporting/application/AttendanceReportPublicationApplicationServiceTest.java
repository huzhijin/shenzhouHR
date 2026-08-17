package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
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

class AttendanceReportPublicationApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-08T10:00:00Z");
    private static final String COMPANY =
            "10000000-0000-0000-0000-000000000001";
    private static final YearMonth PERIOD = YearMonth.of(2026, 7);
    private static final String PRINCIPAL =
            "20000000-0000-0000-0000-000000000001";

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
    void delegatesToOrchestratorAndUseCase() {
        AttendanceReportCalculationOrchestrator orchestrator =
                mock(AttendanceReportCalculationOrchestrator.class);
        AttendanceReportProjectionPublicationUseCase useCase =
                mock(AttendanceReportProjectionPublicationUseCase.class);

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

        var service = service(List.of(orchestrator), useCase);
        PublicationResult result = service.publish(
                COMPANY, PERIOD, PeriodState.OPEN, "manual run");

        assertThat(result).isEqualTo(expected);
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
        CurrentCapabilityService caps = mock(CurrentCapabilityService.class);
        CurrentPrincipalProvider principal =
                mock(CurrentPrincipalProvider.class);
        when(principal.currentPrincipalId()).thenReturn(PRINCIPAL);
        return new AttendanceReportPublicationApplicationService(
                caps,
                principal,
                useCase,
                orchestrators,
                mock(AuditService.class),
                clock);
    }
}

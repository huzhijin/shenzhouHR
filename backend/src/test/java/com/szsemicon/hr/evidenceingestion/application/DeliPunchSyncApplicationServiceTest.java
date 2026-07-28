package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.JobStatus;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeliPunchSyncApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRINCIPAL = "principal-1";

    private final AttendanceSourceSyncRepository repository =
            mock(AttendanceSourceSyncRepository.class);
    private final DeliPunchPageTransaction pageTransaction =
            mock(DeliPunchPageTransaction.class);
    private final AuditService auditService = mock(AuditService.class);

    @Test
    void formalRunIgnoresNonProductionAdaptersAndRecordsDisabledFailure() {
        DeliPunchSourcePort synthetic = mock(DeliPunchSourcePort.class);
        when(synthetic.productionIntegration()).thenReturn(false);
        prepareCreatedJob("DELI_INTEGRATION_DISABLED");
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(synthetic),
                List.of(),
                List.of());

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.safeErrorCode())
                .isEqualTo("DELI_INTEGRATION_DISABLED");
        verify(synthetic, never()).fetchPage(any(), any());
        verify(repository).markFailed(
                eq("job-1"),
                eq("DELI_INTEGRATION_DISABLED"),
                eq(NOW));
        verify(auditService).recordFailure(
                PRINCIPAL,
                "DELI_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB",
                "job-1",
                "FAILURE",
                "DELI_INTEGRATION_DISABLED");
    }

    @Test
    void missingPeriodProtectionFailsClosedBeforeVendorFetch() {
        DeliPunchSourcePort production = productionSource();
        var configuration =
                mock(AttendanceConfigurationResolverPort.class);
        prepareCreatedJob("ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(configuration),
                List.of());

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.safeErrorCode())
                .isEqualTo("ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");
        verify(production, never()).fetchPage(any(), any(), any());
        verify(repository, never()).markRunning(any(), any());
    }

    @Test
    void authorizedRunCommitsPagesUntilTerminalEmptyPage() {
        DeliPunchSourcePort production = productionSource();
        var configuration =
                mock(AttendanceConfigurationResolverPort.class);
        var protection =
                mock(AttendancePeriodProtectionPort.class);
        var first = new DeliPunchSourcePort.DeliPage(
                List.of(new DeliPunchSourcePort.DeliPunchRecord(
                        "record-1",
                        "version-1",
                        "user-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "E001",
                        NOW,
                        "1785290400",
                        "Asia/Shanghai",
                        Direction.AUTO,
                        "fp",
                        "terminal-1",
                        null,
                        "UNKNOWN",
                        true)),
                "0",
                "1",
                "a".repeat(64));
        var terminal = new DeliPunchSourcePort.DeliPage(
                List.of(), "1", "1", "b".repeat(64));
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, java.time.ZoneId.of("Asia/Shanghai"));
        when(production.fetchPage("source-1", null, settings))
                .thenReturn(first);
        when(production.fetchPage("source-1", "1", settings))
                .thenReturn(terminal);
        prepareCreatedJob(null);
        when(pageTransaction.commitPage(
                        any(),
                        eq(PRINCIPAL),
                        eq("request-1"),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                        eq(1),
                        eq(first),
                        eq(configuration),
                        eq(protection)))
                .thenReturn(new DeliPunchPageTransaction.PageCommitResult(1, 0));
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(configuration),
                List.of(protection));

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.state()).isEqualTo("SUCCEEDED");
        verify(repository).markRunning("job-1", NOW);
        verify(pageTransaction).commitPage(
                any(),
                eq(PRINCIPAL),
                eq("request-1"),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                eq(1),
                eq(first),
                eq(configuration),
                eq(protection));
        verify(repository).markFinished("job-1", "SUCCEEDED", NOW);
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_SYNC_REQUEST",
                "ATTENDANCE_SYNC_JOB",
                "job-1",
                "SUCCESS",
                "SYNC_JOB_CREATED");
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB",
                "job-1",
                "SUCCESS",
                "DELI_SYNC_SUCCEEDED");
    }

    @Test
    void nonRetryableVendorFailureIsNotCalledAgain() {
        DeliPunchSourcePort production = productionSource();
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, java.time.ZoneId.of("Asia/Shanghai"));
        when(production.fetchPage("source-1", null, settings))
                .thenThrow(new DeliPunchSourcePort.FetchException(
                        "DELI_VENDOR_FAILURE",
                        "safe",
                        false));
        prepareCreatedJob("DELI_VENDOR_FAILURE");
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(mock(AttendanceConfigurationResolverPort.class)),
                List.of(mock(AttendancePeriodProtectionPort.class)));

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.safeErrorCode()).isEqualTo("DELI_VENDOR_FAILURE");
        verify(production, times(1))
                .fetchPage("source-1", null, settings);
        verify(pageTransaction, never()).commitPage(
                any(), any(), any(), any(), anyInt(),
                any(), any(), any());
    }

    @Test
    void transientVendorFailureRetriesWithinTheBoundedBudget() {
        DeliPunchSourcePort production = productionSource();
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, java.time.ZoneId.of("Asia/Shanghai"));
        var terminal = new DeliPunchSourcePort.DeliPage(
                List.of(), "0", "0", "b".repeat(64));
        when(production.fetchPage("source-1", null, settings))
                .thenThrow(new DeliPunchSourcePort.FetchException(
                        "DELI_TRANSPORT_FAILURE",
                        "safe",
                        true))
                .thenThrow(new DeliPunchSourcePort.FetchException(
                        "DELI_HTTP_FAILURE",
                        "safe",
                        true))
                .thenReturn(terminal);
        prepareCreatedJob(null);
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(mock(AttendanceConfigurationResolverPort.class)),
                List.of(mock(AttendancePeriodProtectionPort.class)));

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.state()).isEqualTo("SUCCEEDED");
        verify(production, times(3))
                .fetchPage("source-1", null, settings);
    }

    @Test
    void repeatedCursorOnNonEmptyPageFailsBeforeCommittingEvidence() {
        DeliPunchSourcePort production = productionSource();
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, java.time.ZoneId.of("Asia/Shanghai"));
        var repeated = new DeliPunchSourcePort.DeliPage(
                List.of(new DeliPunchSourcePort.DeliPunchRecord(
                        "record-1",
                        "version-1",
                        "user-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "E001",
                        NOW,
                        "1785290400",
                        "Asia/Shanghai",
                        Direction.AUTO,
                        "fp",
                        "terminal-1",
                        null,
                        "UNKNOWN",
                        true)),
                "0",
                "0",
                "a".repeat(64));
        when(production.fetchPage("source-1", null, settings))
                .thenReturn(repeated);
        prepareCreatedJob("DELI_CURSOR_CYCLE_DETECTED");
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(mock(AttendanceConfigurationResolverPort.class)),
                List.of(mock(AttendancePeriodProtectionPort.class)));

        JobStatus result = service.run("source-1", "request-1");

        assertThat(result.safeErrorCode())
                .isEqualTo("DELI_CURSOR_CYCLE_DETECTED");
        verify(pageTransaction, never()).commitPage(
                any(), any(), any(), any(), anyInt(),
                any(), any(), any());
    }

    @Test
    void retryCreatesANewJobFromRepositoryWatermarkUnderRetryScope() {
        DeliPunchSourcePort production = productionSource();
        var configuration =
                mock(AttendanceConfigurationResolverPort.class);
        var protection =
                mock(AttendancePeriodProtectionPort.class);
        var terminal = new DeliPunchSourcePort.DeliPage(
                List.of(), "41", "41", "b".repeat(64));
        var settings = new DeliPunchSourcePort.FetchSettings(
                75, java.time.ZoneId.of("Asia/Chongqing"));
        when(production.fetchPage("source-1", "41", settings))
                .thenReturn(terminal);
        var retryJob = new SourceJobStart(
                "job-2",
                "source-1",
                "legal-1",
                "正式得力 E+",
                "DELI_EPLUS_APP_CREDENTIALS",
                "Asia/Chongqing",
                75,
                120,
                0,
                "41");
        when(repository.createAuthorizedDeliRetryJob(
                        eq("job-1"),
                        eq(PRINCIPAL),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RETRY),
                        eq(2L),
                        eq("retry-key-00000001"),
                        any(),
                        any(),
                        eq("request-1"),
                        eq(NOW)))
                .thenReturn(
                        AttendanceSourceSyncRepository.RetryResult.created(
                                retryJob, 0));
        when(repository.findCreatedJob("job-2", PRINCIPAL))
                .thenReturn(Optional.of(status(
                        "job-2", "SUCCEEDED", null)));
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RETRY),
                List.of(production),
                List.of(configuration),
                List.of(protection));

        JobStatus result = service.retry(
                "job-1",
                2,
                "retry-key-00000001",
                "人工确认从水位重试",
                "request-1");

        assertThat(result.jobId()).isEqualTo("job-2");
        verify(production).fetchPage("source-1", "41", settings);
        verify(repository).markRunning("job-2", NOW);
        verify(repository).markFinished("job-2", "SUCCEEDED", NOW);
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_SYNC_RETRY_REQUEST",
                "ATTENDANCE_SYNC_JOB",
                "job-2",
                "SUCCESS",
                "SYNC_RETRY_JOB_CREATED");
    }

    @Test
    void retryIdempotencyReplayReturnsExistingJobWithoutVendorCall() {
        DeliPunchSourcePort production = productionSource();
        when(repository.createAuthorizedDeliRetryJob(
                        eq("job-1"),
                        eq(PRINCIPAL),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RETRY),
                        eq(2L),
                        eq("retry-key-00000001"),
                        any(),
                        any(),
                        eq("request-1"),
                        eq(NOW)))
                .thenReturn(
                        AttendanceSourceSyncRepository.RetryResult.replayed(
                                "job-2"));
        when(repository.findCreatedJob("job-2", PRINCIPAL))
                .thenReturn(Optional.of(status(
                        "job-2", "SUCCEEDED", null)));
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RETRY),
                List.of(production),
                List.of(),
                List.of());

        JobStatus result = service.retry(
                "job-1",
                2,
                "retry-key-00000001",
                "人工确认从水位重试",
                "request-1");

        assertThat(result.jobId()).isEqualTo("job-2");
        verify(production, never()).fetchPage(any(), any(), any());
        verify(repository, never()).markRunning(any(), any());
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_SYNC_RETRY_REQUEST",
                "ATTENDANCE_SYNC_JOB",
                "job-2",
                "SUCCESS",
                "IDEMPOTENCY_REPLAY");
    }

    @Test
    void inaccessibleSourceCreatesNoJobAndDoesNotCallVendor() {
        DeliPunchSourcePort production = productionSource();
        when(repository.createAuthorizedDeliJob(
                        eq("source-1"),
                        eq(PRINCIPAL),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                        any(),
                        eq("request-1"),
                        eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult
                        .resourceUnavailable());
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                List.of(production),
                List.of(mock(AttendanceConfigurationResolverPort.class)),
                List.of(mock(AttendancePeriodProtectionPort.class)));

        assertThatThrownBy(() -> service.run("source-1", "request-1"))
                .isInstanceOf(
                        ResourceNotAvailableAccessDeniedException.class);
        verify(production, never()).fetchPage(any(), any(), any());
        verify(repository, never()).markRunning(any(), any());
    }

    @Test
    void jobReadAlwaysPassesReadCapabilityAndPrincipalToSqlScope() {
        JobStatus status = status("SUCCEEDED", null);
        when(repository.findAuthorizedJob(
                        "job-1",
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SOURCE_READ,
                        NOW))
                .thenReturn(Optional.of(status));
        var service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_READ),
                List.of(),
                List.of(),
                List.of());

        assertThat(service.get("job-1")).isEqualTo(status);
        verify(repository).findAuthorizedJob(
                "job-1",
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SOURCE_READ,
                NOW);
    }

    private DeliPunchSyncApplicationService service(
            Set<String> capabilityCodes,
            List<DeliPunchSourcePort> deliSources,
            List<AttendanceConfigurationResolverPort> configurations,
            List<AttendancePeriodProtectionPort> protections) {
        CurrentPrincipalProvider principals = () -> PRINCIPAL;
        CurrentCapabilityService capabilities =
                new CurrentCapabilityService(
                        principals,
                        (principalId, at) -> capabilityCodes,
                        CLOCK);
        return new DeliPunchSyncApplicationService(
                capabilities,
                principals,
                repository,
                pageTransaction,
                auditService,
                deliSources,
                configurations,
                protections,
                CLOCK);
    }

    private void prepareCreatedJob(String failureCode) {
        when(repository.createAuthorizedDeliJob(
                        eq("source-1"),
                        eq(PRINCIPAL),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN),
                        any(),
                        eq("request-1"),
                        eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(
                        new SourceJobStart(
                                "job-1",
                                "source-1",
                                "legal-1",
                                "正式得力 E+",
                                "DELI_EPLUS_APP_CREDENTIALS",
                                "Asia/Shanghai",
                                500,
                                10_000,
                                0,
                                null)));
        JobStatus status = failureCode == null
                ? status("SUCCEEDED", null)
                : status("FAILED", failureCode);
        when(repository.findCreatedJob("job-1", PRINCIPAL))
                .thenReturn(Optional.of(status));
    }

    private static DeliPunchSourcePort productionSource() {
        DeliPunchSourcePort source = mock(DeliPunchSourcePort.class);
        when(source.productionIntegration()).thenReturn(true);
        when(source.credentialReferenceName())
                .thenReturn("DELI_EPLUS_APP_CREDENTIALS");
        return source;
    }

    private static JobStatus status(String state, String error) {
        return status("job-1", state, error);
    }

    private static JobStatus status(
            String jobId, String state, String error) {
        return new JobStatus(
                jobId,
                "source-1",
                "正式得力 E+",
                "DELI_CLOUD",
                state,
                "SUCCEEDED".equals(state) ? 1 : 0,
                "SUCCEEDED".equals(state) ? 1 : 0,
                0,
                error,
                "SUCCEEDED".equals(state) ? NOW : null,
                NOW,
                2);
    }
}

package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.JobStatus;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliPunchScheduledSyncApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-17T07:00:00Z");
    private static final Instant SINCE =
            Instant.parse("2026-08-17T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void scheduledSyncCommitsLateArrivalFromTheSourceCursor() {
        AttendanceSourceSyncRepository repository =
                mock(AttendanceSourceSyncRepository.class);
        DeliPunchPageTransaction transaction =
                mock(DeliPunchPageTransaction.class);
        DeliPunchSourcePort source = mock(DeliPunchSourcePort.class);
        AttendanceConfigurationResolverPort configuration =
                mock(AttendanceConfigurationResolverPort.class);
        AttendancePeriodProtectionPort protection =
                mock(AttendancePeriodProtectionPort.class);
        SourceJobStart job = new SourceJobStart(
                "scheduled-job",
                "source-1",
                "company-1",
                "正式得力 E+",
                "DELI_EPLUS_APP_CREDENTIALS",
                "Asia/Shanghai",
                500,
                10_000,
                0,
                "41");
        when(repository.findAllActiveDeliSourceIds())
                .thenReturn(List.of("source-1"));
        when(repository.createScheduledDeliJob(
                        eq("source-1"), anyString(), anyString(), eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(
                        job));
        when(source.productionIntegration()).thenReturn(true);
        when(source.credentialReferenceName())
                .thenReturn("DELI_EPLUS_APP_CREDENTIALS");
        when(source.fetchEmployeeDirectory("source-1"))
                .thenReturn(Map.of());
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, ZoneId.of("Asia/Shanghai"), Map.of());
        var fetched = new DeliPunchSourcePort.DeliPage(
                List.of(
                        punch("late-arrival", SINCE.minusSeconds(3_600)),
                        punch("current-record", NOW)),
                "41",
                "42",
                "a".repeat(64));
        var terminal = new DeliPunchSourcePort.DeliPage(
                List.of(), "42", "42", "b".repeat(64));
        when(source.fetchPage("source-1", "41", settings))
                .thenReturn(fetched);
        when(source.fetchPage("source-1", "42", settings))
                .thenReturn(terminal);
        stubKqTerminal(source, "source-1");
        when(transaction.commitPage(
                        eq(job),
                        eq("SYSTEM"),
                        anyString(),
                        anyString(),
                        eq(1),
                        any(),
                        eq(configuration),
                        eq(protection)))
                .thenReturn(new DeliPunchPageTransaction.PageCommitResult(2, 0));
        when(repository.findCreatedJob("scheduled-job", "SYSTEM"))
                .thenReturn(Optional.of(new JobStatus(
                        "scheduled-job",
                        "source-1",
                        "正式得力 E+",
                        "DELI_CLOUD",
                        "SUCCEEDED",
                        1,
                        2,
                        0,
                        null,
                        NOW,
                        NOW,
                        2)));
        DeliPunchSyncApplicationService service =
                new DeliPunchSyncApplicationService(
                        mock(CurrentCapabilityService.class),
                        mock(CurrentPrincipalProvider.class),
                        repository,
                        transaction,
                        mock(AuditService.class),
                        List.of(source),
                        List.of(configuration),
                        List.of(protection),
                        CLOCK);

        AttendanceSourceSyncModels.ScheduledSyncResult result =
                service.runScheduled();

        assertThat(result.successful()).isTrue();
        assertThat(result.recordCount()).isEqualTo(2);
        ArgumentCaptor<DeliPunchSourcePort.DeliPage> committedPage =
                ArgumentCaptor.forClass(DeliPunchSourcePort.DeliPage.class);
        verify(transaction).commitPage(
                eq(job),
                eq("SYSTEM"),
                anyString(),
                anyString(),
                eq(1),
                committedPage.capture(),
                eq(configuration),
                eq(protection));
        assertThat(committedPage.getValue().records())
                .extracting(DeliPunchSourcePort.DeliPunchRecord::sourceRecordId)
                .containsExactly("late-arrival", "current-record");
        verify(source).fetchPage("source-1", "41", settings);
        verify(repository).markFinished("scheduled-job", "SUCCEEDED", NOW);
    }

    @Test
    void scheduledSyncUsesAnIndependentCursorForEveryActiveSource() {
        AttendanceSourceSyncRepository repository =
                mock(AttendanceSourceSyncRepository.class);
        DeliPunchPageTransaction transaction =
                mock(DeliPunchPageTransaction.class);
        DeliPunchSourcePort source = mock(DeliPunchSourcePort.class);
        AttendanceConfigurationResolverPort configuration =
                mock(AttendanceConfigurationResolverPort.class);
        AttendancePeriodProtectionPort protection =
                mock(AttendancePeriodProtectionPort.class);
        SourceJobStart existing = job(
                "existing-job", "source-existing", "73");
        SourceJobStart newSource = job(
                "new-job", "source-new", null);
        when(repository.findAllActiveDeliSourceIds())
                .thenReturn(List.of("source-existing", "source-new"));
        when(repository.createScheduledDeliJob(
                        eq("source-existing"),
                        anyString(),
                        anyString(),
                        eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(
                        existing));
        when(repository.createScheduledDeliJob(
                        eq("source-new"),
                        anyString(),
                        anyString(),
                        eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(
                        newSource));
        when(source.productionIntegration()).thenReturn(true);
        when(source.credentialReferenceName())
                .thenReturn("DELI_EPLUS_APP_CREDENTIALS");
        when(source.fetchEmployeeDirectory("source-existing"))
                .thenReturn(Map.of());
        when(source.fetchEmployeeDirectory("source-new"))
                .thenReturn(Map.of());
        var settings = new DeliPunchSourcePort.FetchSettings(
                500, ZoneId.of("Asia/Shanghai"), Map.of());
        when(source.fetchPage("source-existing", "73", settings))
                .thenReturn(new DeliPunchSourcePort.DeliPage(
                        List.of(), "73", "73", "a".repeat(64)));
        when(source.fetchPage("source-new", null, settings))
                .thenReturn(new DeliPunchSourcePort.DeliPage(
                        List.of(), "0", "0", "b".repeat(64)));
        stubKqTerminal(source, "source-existing");
        stubKqTerminal(source, "source-new");
        when(repository.findCreatedJob("existing-job", "SYSTEM"))
                .thenReturn(Optional.of(status(
                        "existing-job", "source-existing", 0)));
        when(repository.findCreatedJob("new-job", "SYSTEM"))
                .thenReturn(Optional.of(status(
                        "new-job", "source-new", 0)));
        DeliPunchSyncApplicationService service =
                new DeliPunchSyncApplicationService(
                        mock(CurrentCapabilityService.class),
                        mock(CurrentPrincipalProvider.class),
                        repository,
                        transaction,
                        mock(AuditService.class),
                        List.of(source),
                        List.of(configuration),
                        List.of(protection),
                        CLOCK);

        AttendanceSourceSyncModels.ScheduledSyncResult result =
                service.runScheduled();

        assertThat(result.successful()).isTrue();
        assertThat(result.recordCount()).isZero();
        verify(source).fetchPage("source-existing", "73", settings);
        verify(source).fetchPage("source-new", null, settings);
        verify(transaction, never()).commitPage(
                any(), anyString(), anyString(), anyString(), anyInt(),
                any(), any(), any());
    }

    @Test
    void noActiveSourceReportsScheduledSyncFailure() {
        AttendanceSourceSyncRepository repository =
                mock(AttendanceSourceSyncRepository.class);
        when(repository.findAllActiveDeliSourceIds()).thenReturn(List.of());
        DeliPunchSyncApplicationService service = service(repository);

        AttendanceSourceSyncModels.ScheduledSyncResult result =
                service.runScheduled();

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("DELI_ACTIVE_SOURCE_NOT_FOUND");
    }

    @Test
    void alreadyRunningSourceReportsScheduledSyncFailure() {
        AttendanceSourceSyncRepository repository =
                mock(AttendanceSourceSyncRepository.class);
        when(repository.findAllActiveDeliSourceIds())
                .thenReturn(List.of("source-1"));
        when(repository.createScheduledDeliJob(
                        eq("source-1"), anyString(), anyString(), eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult
                        .alreadyRunning());
        DeliPunchSyncApplicationService service = service(repository);

        AttendanceSourceSyncModels.ScheduledSyncResult result =
                service.runScheduled();

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("DELI_SYNC_ALREADY_RUNNING");
    }

    private static void stubKqTerminal(
            DeliPunchSourcePort source, String sourceId) {
        var kqSettings = new DeliPunchSourcePort.FetchSettings(
                500,
                ZoneId.of("Asia/Shanghai"),
                Map.of(),
                DeliPunchSourcePort.FetchSettings.MODULE_KQ);
        when(source.fetchPage(sourceId, null, kqSettings))
                .thenReturn(new DeliPunchSourcePort.DeliPage(
                        List.of(), "0", "0", "k".repeat(64)));
    }

    private static DeliPunchSourcePort.DeliPunchRecord punch(
            String recordId, Instant at) {
        return new DeliPunchSourcePort.DeliPunchRecord(
                recordId,
                "version-1",
                "user-1",
                ConfirmedBindingKind.DELI_EXT_ID,
                "E001",
                null,
                at,
                Long.toString(at.getEpochSecond()),
                "Asia/Shanghai",
                Direction.AUTO,
                "fp",
                "terminal-1",
                null,
                "UNKNOWN",
                true);
    }

    private static SourceJobStart job(
            String jobId, String sourceId, String committedCursor) {
        return new SourceJobStart(
                jobId,
                sourceId,
                "company-1",
                "正式得力 E+",
                "DELI_EPLUS_APP_CREDENTIALS",
                "Asia/Shanghai",
                500,
                10_000,
                0,
                committedCursor);
    }

    private static JobStatus status(
            String jobId, String sourceId, long acceptedCount) {
        return new JobStatus(
                jobId,
                sourceId,
                "正式得力 E+",
                "DELI_CLOUD",
                "SUCCEEDED",
                0,
                acceptedCount,
                0,
                null,
                NOW,
                NOW,
                2);
    }

    private static DeliPunchSyncApplicationService service(
            AttendanceSourceSyncRepository repository) {
        return new DeliPunchSyncApplicationService(
                mock(CurrentCapabilityService.class),
                mock(CurrentPrincipalProvider.class),
                repository,
                mock(DeliPunchPageTransaction.class),
                mock(AuditService.class),
                List.of(),
                List.of(),
                List.of(),
                CLOCK);
    }
}

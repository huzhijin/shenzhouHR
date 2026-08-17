package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void scheduledSyncPropagatesAndEnforcesTheExclusiveTimeLowerBound() {
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
                null);
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
                500, ZoneId.of("Asia/Shanghai"), Map.of(), SINCE);
        var fetched = new DeliPunchSourcePort.DeliPage(
                List.of(
                        punch("at-marker", SINCE),
                        punch("after-marker", SINCE.plusSeconds(1))),
                "0",
                "1",
                "a".repeat(64));
        var terminal = new DeliPunchSourcePort.DeliPage(
                List.of(), "1", "1", "b".repeat(64));
        when(source.fetchPage("source-1", null, settings))
                .thenReturn(fetched);
        when(source.fetchPage("source-1", "1", settings))
                .thenReturn(terminal);
        when(transaction.commitPage(
                        eq(job),
                        eq("SYSTEM"),
                        anyString(),
                        anyString(),
                        eq(1),
                        any(),
                        eq(configuration),
                        eq(protection)))
                .thenReturn(new DeliPunchPageTransaction.PageCommitResult(1, 0));
        when(repository.findCreatedJob("scheduled-job", "SYSTEM"))
                .thenReturn(Optional.of(new JobStatus(
                        "scheduled-job",
                        "source-1",
                        "正式得力 E+",
                        "DELI_CLOUD",
                        "SUCCEEDED",
                        1,
                        1,
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
                service.runScheduled(SINCE);

        assertThat(result.successful()).isTrue();
        assertThat(result.recordCount()).isEqualTo(1);
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
                .containsExactly("after-marker");
        verify(source).fetchPage("source-1", null, settings);
        verify(repository).markFinished("scheduled-job", "SUCCEEDED", NOW);
    }

    @Test
    void noActiveSourceCannotAdvanceTheSuccessfulWindow() {
        AttendanceSourceSyncRepository repository =
                mock(AttendanceSourceSyncRepository.class);
        when(repository.findAllActiveDeliSourceIds()).thenReturn(List.of());
        DeliPunchSyncApplicationService service = service(repository);

        AttendanceSourceSyncModels.ScheduledSyncResult result =
                service.runScheduled(SINCE);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("DELI_ACTIVE_SOURCE_NOT_FOUND");
    }

    @Test
    void alreadyRunningSourceCannotAdvanceTheSuccessfulWindow() {
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
                service.runScheduled(SINCE);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage())
                .isEqualTo("DELI_SYNC_ALREADY_RUNNING");
    }

    private static DeliPunchSourcePort.DeliPunchRecord punch(
            String recordId, Instant at) {
        return new DeliPunchSourcePort.DeliPunchRecord(
                recordId,
                "version-1",
                "user-1",
                ConfirmedBindingKind.DELI_EXT_ID,
                "E001",
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

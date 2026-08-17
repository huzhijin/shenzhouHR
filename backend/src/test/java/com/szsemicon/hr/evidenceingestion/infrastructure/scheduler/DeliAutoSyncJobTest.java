package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class DeliAutoSyncJobTest {

    private static final String HOURLY_CRON = "0 0 * * * ?";
    private static final Instant NOW =
            Instant.parse("2026-08-17T07:00:00Z");
    private static final Instant LAST_SUCCESS =
            Instant.parse("2026-08-17T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void defaultScheduleRunsAtTheTopOfEveryHour() throws Exception {
        Scheduled scheduled = DeliAutoSyncJob.class
                .getDeclaredMethod("triggerScheduledSync")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${shenzhouhr.deli.auto-sync-cron:"
                        + HOURLY_CRON + "}");

        CronExpression expression = CronExpression.parse(HOURLY_CRON);
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 9, 17, 42)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 10, 0));
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 23, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 8, 18, 0, 0));
    }

    @Test
    void successfulRunLogsTheIncrementalWindowAndRecordCount() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(repository.findLastSuccessfulSyncTime())
                .thenReturn(Optional.of(LAST_SUCCESS));
        when(service.runScheduled(LAST_SUCCESS))
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        156, true, null));
        DeliAutoSyncJob job = new DeliAutoSyncJob(
                service, repository, CLOCK);

        job.triggerScheduledSync();

        ArgumentCaptor<String> logId = ArgumentCaptor.forClass(String.class);
        verify(repository).start(
                logId.capture(), eq(NOW), eq(LAST_SUCCESS), eq(NOW));
        verify(repository).markSucceeded(
                logId.getValue(), NOW, 156, 0);
        verify(repository, never()).markFailed(
                anyString(), eq(NOW), eq(156L), anyString(), eq(0L));
    }

    @Test
    void firstRunUsesSevenDayLookback() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        Instant lookbackStart = NOW.minus(Duration.ofDays(7));
        when(repository.findLastSuccessfulSyncTime())
                .thenReturn(Optional.empty());
        when(service.runScheduled(lookbackStart))
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        0, true, null));

        new DeliAutoSyncJob(service, repository, CLOCK)
                .triggerScheduledSync();

        verify(repository).start(
                anyString(), eq(NOW), eq(lookbackStart), eq(NOW));
        verify(service).runScheduled(lookbackStart);
    }

    @Test
    void failedRunsKeepUsingThePreviousSuccessfulMarker() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(repository.findLastSuccessfulSyncTime())
                .thenReturn(Optional.of(LAST_SUCCESS));
        when(service.runScheduled(LAST_SUCCESS))
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        0, false, "DELI_HTTP_FAILURE"));
        DeliAutoSyncJob job = new DeliAutoSyncJob(
                service, repository, CLOCK);

        job.triggerScheduledSync();
        job.triggerScheduledSync();

        verify(repository, times(2)).findLastSuccessfulSyncTime();
        verify(service, times(2)).runScheduled(LAST_SUCCESS);
        verify(repository, times(2)).markFailed(
                anyString(), eq(NOW), eq(0L),
                eq("DELI_HTTP_FAILURE"), eq(0L));
        verify(repository, never()).markSucceeded(
                anyString(), eq(NOW), eq(0L), eq(0L));
    }

    @Test
    void cleanupDeletesLogsOlderThanThirtyDays() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        DeliAutoSyncJob job = new DeliAutoSyncJob(
                service, repository, CLOCK);

        job.cleanupSyncLogs();

        verify(repository).deleteStartedBefore(
                NOW.minus(Duration.ofDays(30)));
    }
}

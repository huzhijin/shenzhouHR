package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogRepository;
import com.szsemicon.hr.reporting.application.ScheduledSourceCompletionListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class DeliAutoSyncJobTest {

    private static final String FOUR_TIMES_DAILY_CRON = "0 0 0,8,12,18 * * ?";
    private static final Instant NOW =
            Instant.parse("2026-08-17T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void defaultScheduleRunsAtEightNoonSixAndMidnight() throws Exception {
        Scheduled scheduled = DeliAutoSyncJob.class
                .getDeclaredMethod("triggerScheduledSync")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${shenzhouhr.deli.auto-sync-cron:"
                        + FOUR_TIMES_DAILY_CRON + "}");
        assertThat(scheduled.zone())
                .isEqualTo("${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}");

        CronExpression expression = CronExpression.parse(FOUR_TIMES_DAILY_CRON);
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 7, 17, 42)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 8, 0));
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 9, 17, 42)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 12, 0));
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 12, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 18, 0));
        assertThat(expression.next(LocalDateTime.of(
                2026, 8, 17, 23, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 8, 18, 0, 0));
    }

    @Test
    void successfulRunLogsObservationTimeAndRecordCount() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        156, true, null));
        DeliAutoSyncJob job = new DeliAutoSyncJob(
                service, repository, CLOCK);

        job.triggerScheduledSync();

        ArgumentCaptor<String> logId = ArgumentCaptor.forClass(String.class);
        verify(repository).start(
                logId.capture(), eq(NOW), isNull(), eq(NOW));
        verify(repository, never()).findLastSuccessfulSyncTime();
        verify(repository).markSucceeded(
                logId.getValue(), NOW, 156, 0);
        verify(repository, never()).markFailed(
                anyString(), eq(NOW), eq(156L), anyString(), eq(0L));
    }

    @Test
    void scheduledRunDoesNotPassAClockWindowIntoIngest() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        1, true, null));

        new DeliAutoSyncJob(service, repository, CLOCK)
                .triggerScheduledSync();

        verify(service).runScheduled();
        verify(repository).start(
                anyString(), eq(NOW), isNull(), eq(NOW));
        verify(repository, never()).findLastSuccessfulSyncTime();
    }

    @Test
    void firstRunHasNoSyntheticTimeLowerBound() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        0, true, null));

        new DeliAutoSyncJob(service, repository, CLOCK)
                .triggerScheduledSync();

        verify(repository).start(
                anyString(), eq(NOW), isNull(), eq(NOW));
        verify(repository, never()).findLastSuccessfulSyncTime();
        verify(service).runScheduled();
    }

    @Test
    void failedRunsDoNotConsultAGlobalSuccessfulTimeMarker() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        0, false, "DELI_HTTP_FAILURE"));
        DeliAutoSyncJob job = new DeliAutoSyncJob(
                service, repository, CLOCK);

        job.triggerScheduledSync();
        job.triggerScheduledSync();

        verify(repository, never()).findLastSuccessfulSyncTime();
        verify(service, times(2)).runScheduled();
        verify(repository, times(2)).markFailed(
                anyString(), eq(NOW), eq(0L),
                eq("DELI_HTTP_FAILURE"), eq(0L));
        verify(repository, never()).markSucceeded(
                anyString(), eq(NOW), eq(0L), eq(0L));
    }

    @Test
    void eightAmShanghaiDoesNotNotifyRecalcListener() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        ScheduledSourceCompletionListener listener =
                mock(ScheduledSourceCompletionListener.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        1, true, null));
        Clock eightAmShanghai = Clock.fixed(
                Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC);

        new DeliAutoSyncJob(service, repository, eightAmShanghai, listener)
                .triggerScheduledSync();

        verify(listener, never()).onScheduledDeliSuccess();
    }

    @Test
    void midnightShanghaiNotifiesRecalcListener() {
        DeliPunchSyncApplicationService service =
                mock(DeliPunchSyncApplicationService.class);
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        ScheduledSourceCompletionListener listener =
                mock(ScheduledSourceCompletionListener.class);
        when(service.runScheduled())
                .thenReturn(new AttendanceSourceSyncModels.ScheduledSyncResult(
                        1, true, null));
        Clock midnightShanghai = Clock.fixed(
                Instant.parse("2026-08-16T16:00:00Z"), ZoneOffset.UTC);

        new DeliAutoSyncJob(service, repository, midnightShanghai, listener)
                .triggerScheduledSync();

        verify(listener).onScheduledDeliSuccess();
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

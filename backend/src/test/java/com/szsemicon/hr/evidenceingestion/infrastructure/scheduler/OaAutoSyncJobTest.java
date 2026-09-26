package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class OaAutoSyncJobTest {

    @Test
    void schedulerRequiresBothTheReaderAndAutoSyncSwitches() {
        ConditionalOnProperty condition =
                AnnotatedElementUtils.findMergedAnnotation(
                        OaAutoSyncJob.class,
                        ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("shenzhouhr");
        assertThat(condition.name()).containsExactly(
                "oa.auto-sync-enabled",
                "integrations.oa-mysql.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void schedulerUsesExplicitProductionCronAndTimeZoneProperties()
            throws Exception {
        Method method = OaAutoSyncJob.class.getDeclaredMethod(
                "triggerScheduledSync");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron())
                .isEqualTo(
                        "${shenzhouhr.oa.auto-sync-cron:0 5/10 * * * ?}");
        assertThat(scheduled.zone())
                .isEqualTo(
                        "${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}");

        CronExpression expression = CronExpression.parse("0 5/10 * * * ?");
        assertThat(expression.next(LocalDateTime.of(2026, 8, 17, 7, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 7, 5));
        assertThat(expression.next(LocalDateTime.of(2026, 8, 17, 7, 17, 42)))
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 7, 25));
        assertThat(expression.next(LocalDateTime.of(2026, 8, 17, 23, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 8, 18, 0, 5));
    }

    @Test
    void scheduledTriggerDelegatesToTheSystemSyncEntryPoint() {
        OaDocumentSyncApplicationService service =
                mock(OaDocumentSyncApplicationService.class);

        new OaAutoSyncJob(service).triggerScheduledSync();

        verify(service).runScheduled();
    }

    @Test
    void oneUnexpectedRunFailureDoesNotEscapeTheSchedulerBoundary() {
        OaDocumentSyncApplicationService service =
                mock(OaDocumentSyncApplicationService.class);
        doThrow(new IllegalStateException("temporary OA failure"))
                .when(service).runScheduled();
        OaAutoSyncJob job = new OaAutoSyncJob(service);

        assertThatCode(job::triggerScheduledSync).doesNotThrowAnyException();

        verify(service, times(1)).runScheduled();
    }
}

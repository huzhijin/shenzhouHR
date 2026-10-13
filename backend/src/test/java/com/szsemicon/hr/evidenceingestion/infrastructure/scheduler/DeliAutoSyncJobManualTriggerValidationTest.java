package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises the scheduler's callable trigger as an operator/manual trigger
 * and verifies the persisted log state transition around the actual sync.
 */
class DeliAutoSyncJobManualTriggerValidationTest {

    private static final Instant NOW =
            Instant.parse("2026-08-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void manualTriggerTransitionsLogFromInProgressToSuccess() {
        DeliPunchSyncApplicationService sync =
                mock(DeliPunchSyncApplicationService.class);
        InMemoryLogRepository logs = new InMemoryLogRepository(null);
        when(sync.runScheduled()).thenAnswer(invocation -> {
            assertThat(logs.onlyEntry().status).isEqualTo("IN_PROGRESS");
            assertThat(logs.onlyEntry().rangeStart).isNull();
            assertThat(logs.onlyEntry().rangeEnd).isEqualTo(NOW);
            return new AttendanceSourceSyncModels.ScheduledSyncResult(
                    156, true, null);
        });

        new DeliAutoSyncJob(sync, logs, CLOCK).triggerScheduledSync();

        assertThat(logs.onlyEntry())
                .satisfies(entry -> {
                    assertThat(entry.status).isEqualTo("SUCCESS");
                    assertThat(entry.recordCount).isEqualTo(156);
                    assertThat(entry.errorMessage).isNull();
                });
        assertThat(logs.findLatestCompleted()).contains(
                new DeliSyncLogModels.SyncStatus(NOW, 156, "SUCCESS", null));
    }

    @Test
    void manualTriggerTransitionsLogToFailedWithoutControllingSourceCursor() {
        DeliPunchSyncApplicationService sync =
                mock(DeliPunchSyncApplicationService.class);
        InMemoryLogRepository logs = new InMemoryLogRepository(null);
        when(sync.runScheduled()).thenAnswer(invocation -> {
            assertThat(logs.onlyEntry().status).isEqualTo("IN_PROGRESS");
            assertThat(logs.onlyEntry().rangeStart).isNull();
            return new AttendanceSourceSyncModels.ScheduledSyncResult(
                    0, false, "DELI_HTTP_FAILURE");
        });

        new DeliAutoSyncJob(sync, logs, CLOCK).triggerScheduledSync();

        assertThat(logs.onlyEntry())
                .satisfies(entry -> {
                    assertThat(entry.status).isEqualTo("FAILED");
                    assertThat(entry.recordCount).isZero();
                    assertThat(entry.errorMessage).isEqualTo("DELI_HTTP_FAILURE");
                });
        assertThat(logs.findLatestCompleted()).contains(
                new DeliSyncLogModels.SyncStatus(
                        NOW, 0, "FAILED", "DELI_HTTP_FAILURE"));
    }

    private static final class InMemoryLogRepository
            implements DeliSyncLogRepository {

        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private Instant lastSuccessfulSyncTime;

        private InMemoryLogRepository(Instant lastSuccessfulSyncTime) {
            this.lastSuccessfulSyncTime = lastSuccessfulSyncTime;
        }

        @Override
        public void start(
                String logId,
                Instant startedAt,
                Instant syncTimeRangeStart,
                Instant syncTimeRangeEnd) {
            entries.put(logId, new Entry(
                    startedAt,
                    syncTimeRangeStart,
                    syncTimeRangeEnd,
                    "IN_PROGRESS",
                    0,
                    null));
        }

        @Override
        public void markSucceeded(
                String logId,
                Instant completedAt,
                long recordCount,
                long executionDurationMs) {
            Entry previous = entries.get(logId);
            entries.put(logId, previous.complete(
                    completedAt, "SUCCESS", recordCount, null));
            lastSuccessfulSyncTime = completedAt;
        }

        @Override
        public void markFailed(
                String logId,
                Instant completedAt,
                long recordCount,
                String errorMessage,
                long executionDurationMs) {
            Entry previous = entries.get(logId);
            entries.put(logId, previous.complete(
                    completedAt, "FAILED", recordCount, errorMessage));
        }

        @Override
        public Optional<DeliSyncLogModels.SyncStatus> findLatestCompleted() {
            return entries.values().stream()
                    .filter(entry -> !"IN_PROGRESS".equals(entry.status))
                    .reduce((first, second) -> second)
                    .map(entry -> new DeliSyncLogModels.SyncStatus(
                            entry.completedAt,
                            entry.recordCount,
                            entry.status,
                            entry.errorMessage));
        }

        @Override
        public Optional<Instant> findLastSuccessfulSyncTime() {
            return Optional.ofNullable(lastSuccessfulSyncTime);
        }

        @Override
        public int deleteStartedBefore(Instant cutoff) {
            return 0;
        }

        private Entry onlyEntry() {
            assertThat(entries).hasSize(1);
            return entries.values().iterator().next();
        }
    }

    private record Entry(
            Instant startedAt,
            Instant rangeStart,
            Instant rangeEnd,
            String status,
            long recordCount,
            String errorMessage,
            Instant completedAt) {

        private Entry(
                Instant startedAt,
                Instant rangeStart,
                Instant rangeEnd,
                String status,
                long recordCount,
                String errorMessage) {
            this(startedAt, rangeStart, rangeEnd, status, recordCount,
                    errorMessage, null);
        }

        private Entry complete(
                Instant completedAt,
                String status,
                long recordCount,
                String errorMessage) {
            return new Entry(startedAt, rangeStart, rangeEnd, status,
                    recordCount, errorMessage, completedAt);
        }
    }
}

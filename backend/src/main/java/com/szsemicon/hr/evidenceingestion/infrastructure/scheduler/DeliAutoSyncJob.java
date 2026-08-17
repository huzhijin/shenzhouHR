package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that triggers Deli E+ punch sync for every active source
 * without requiring a logged-in user. Runs as the SYSTEM principal.
 *
 * <p>Disabled by default. Enable only after the source initialization,
 * department/company routing and reconciliation gates have passed via:
 * {@code shenzhouhr.deli.auto-sync-enabled=true}</p>
 *
 * <p>Default cron: every hour on the hour. Override via:
 * {@code shenzhouhr.deli.auto-sync-cron=0 0/30 * * * ?} (every 30 min)</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.deli",
        name = "auto-sync-enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class DeliAutoSyncJob {

    private static final Duration LOG_RETENTION = Duration.ofDays(30);
    private static final Logger log =
            LoggerFactory.getLogger(DeliAutoSyncJob.class);

    private final DeliPunchSyncApplicationService syncService;
    private final DeliSyncLogRepository syncLogRepository;
    private final Clock clock;

    public DeliAutoSyncJob(
            DeliPunchSyncApplicationService syncService,
            DeliSyncLogRepository syncLogRepository,
            Clock clock) {
        this.syncService = syncService;
        this.syncLogRepository = syncLogRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${shenzhouhr.deli.auto-sync-cron:0 0 * * * ?}")
    void triggerScheduledSync() {
        log.info("Scheduled Deli sync starting");
        Instant startedAt = clock.instant();
        String logId = UUID.randomUUID().toString();
        boolean logStarted = false;
        long recordCount = 0;
        try {
            // The log range is observational only. Each Deli source resumes
            // from its own attendance_sync_watermark/next_id when the sync
            // job is created; a global scheduler timestamp must never decide
            // which vendor records are accepted.
            syncLogRepository.start(
                    logId, startedAt, null, startedAt);
            logStarted = true;

            var result = syncService.runScheduled();
            recordCount = result.recordCount();
            Instant completedAt = clock.instant();
            long durationMs = elapsedMillis(startedAt, completedAt);
            if (result.successful()) {
                syncLogRepository.markSucceeded(
                        logId, completedAt, recordCount, durationMs);
            } else {
                syncLogRepository.markFailed(
                        logId,
                        completedAt,
                        recordCount,
                        result.errorMessage(),
                        durationMs);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled Deli sync terminated with unexpected error",
                    exception);
            if (logStarted) {
                recordUnexpectedFailure(logId, startedAt, recordCount);
            }
        }
        log.info("Scheduled Deli sync finished");
    }

    @Scheduled(
            cron = "${shenzhouhr.deli.sync-log-cleanup-cron:0 15 2 * * ?}")
    void cleanupSyncLogs() {
        Instant cutoff = clock.instant().minus(LOG_RETENTION);
        try {
            int deleted = syncLogRepository.deleteStartedBefore(cutoff);
            log.info("Deleted {} Deli sync log entries older than 30 days",
                    deleted);
        } catch (RuntimeException exception) {
            log.error("Deli sync log cleanup failed", exception);
        }
    }

    private void recordUnexpectedFailure(
            String logId,
            Instant startedAt,
            long recordCount) {
        Instant completedAt = clock.instant();
        try {
            syncLogRepository.markFailed(
                    logId,
                    completedAt,
                    recordCount,
                    "DELI_SCHEDULED_SYNC_FAILED",
                    elapsedMillis(startedAt, completedAt));
        } catch (RuntimeException logException) {
            log.error("Could not persist the Deli sync failure status",
                    logException);
        }
    }

    private static long elapsedMillis(
            Instant startedAt, Instant completedAt) {
        long elapsed = Duration.between(startedAt, completedAt).toMillis();
        return Math.max(0, Math.min(elapsed, Integer.MAX_VALUE));
    }
}

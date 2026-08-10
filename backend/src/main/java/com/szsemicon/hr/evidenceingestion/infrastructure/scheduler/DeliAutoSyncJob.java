package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that triggers Deli E+ punch sync for every active source
 * without requiring a logged-in user. Runs as the SYSTEM principal.
 *
 * <p>Enabled by default. Disable via:
 * {@code shenzhouhr.deli.auto-sync-enabled=false}</p>
 *
 * <p>Default cron: every hour on the hour. Override via:
 * {@code shenzhouhr.deli.auto-sync-cron=0 0/30 * * * ?} (every 30 min)</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.deli",
        name = "auto-sync-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class DeliAutoSyncJob {

    private static final Logger log =
            LoggerFactory.getLogger(DeliAutoSyncJob.class);

    private final DeliPunchSyncApplicationService syncService;

    public DeliAutoSyncJob(DeliPunchSyncApplicationService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${shenzhouhr.deli.auto-sync-cron:0 0 * * * ?}")
    void triggerScheduledSync() {
        log.info("Scheduled Deli sync starting");
        try {
            syncService.runScheduled();
        } catch (RuntimeException exception) {
            log.error("Scheduled Deli sync terminated with unexpected error",
                    exception);
        }
        log.info("Scheduled Deli sync finished");
    }
}

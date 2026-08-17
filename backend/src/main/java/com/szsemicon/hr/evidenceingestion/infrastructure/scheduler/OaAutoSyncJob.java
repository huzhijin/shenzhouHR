package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that triggers OA attendance document sync for every active
 * OA_ATTENDANCE source without requiring a logged-in user.
 * Runs as the SYSTEM principal.
 *
 * <p>Disabled by default. Enable via:
 * {@code shenzhouhr.oa.auto-sync-enabled=true}</p>
 *
 * <p>Default cron: every 30 minutes in Asia/Shanghai. Override via the
 * {@code shenzhouhr.oa.auto-sync-cron} and
 * {@code shenzhouhr.oa.auto-sync-zone} properties.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr",
        name = {"oa.auto-sync-enabled", "integrations.oa-mysql.enabled"},
        havingValue = "true",
        matchIfMissing = false)
public final class OaAutoSyncJob {

    private static final Logger log = LoggerFactory.getLogger(OaAutoSyncJob.class);

    private final OaDocumentSyncApplicationService syncService;

    public OaAutoSyncJob(OaDocumentSyncApplicationService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            cron = "${shenzhouhr.oa.auto-sync-cron:0 0/30 * * * ?}",
            zone = "${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}")
    void triggerScheduledSync() {
        log.info("Scheduled OA sync starting");
        try {
            syncService.runScheduled();
        } catch (RuntimeException exception) {
            log.error("Scheduled OA sync terminated with unexpected error", exception);
        }
        log.info("Scheduled OA sync finished");
    }
}

package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunSummary;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.TriggerType;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires the preceding year's TIME_OFF accounts through the public,
 * idempotent database procedure. Disabled until the site cutover is accepted.
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.time-off-year-end",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeOffYearEndJob {

    private static final Logger log =
            LoggerFactory.getLogger(TimeOffYearEndJob.class);

    private final TimeOffYearEndService service;

    public TimeOffYearEndJob(TimeOffYearEndService service) {
        this.service = service;
    }

    @Scheduled(
            cron = "${shenzhouhr.time-off-year-end.cron:0 30 2 2 1 *}",
            zone = "${shenzhouhr.time-off-year-end.zone:Asia/Shanghai}")
    void expirePreviousYear() {
        log.info("TIME_OFF year-end expiry starting");
        RunSummary summary = service.runPreviousYear(TriggerType.SCHEDULED);
        log.info(
                "TIME_OFF year-end expiry finished: runId={}, year={}, status={}, "
                        + "success={}, failure={}, skipped={}",
                summary.runId(),
                summary.accountYear(),
                summary.status(),
                summary.successCount(),
                summary.failureCount(),
                summary.skippedCount());
    }
}

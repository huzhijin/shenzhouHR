package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunSummary;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunStatus;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.TriggerType;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Runs one explicitly requested year and then closes the command context. */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.time-off-year-end",
        name = "replay-year")
public final class TimeOffYearEndReplayRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(TimeOffYearEndReplayRunner.class);

    private final TimeOffYearEndService service;
    private final ConfigurableApplicationContext context;
    private final int replayYear;
    private final boolean exitAfterReplay;

    public TimeOffYearEndReplayRunner(
            TimeOffYearEndService service,
            ConfigurableApplicationContext context,
            @Value("${shenzhouhr.time-off-year-end.replay-year}")
            int replayYear,
            @Value("${shenzhouhr.time-off-year-end.exit-after-replay:true}")
            boolean exitAfterReplay) {
        this.service = service;
        this.context = context;
        this.replayYear = replayYear;
        this.exitAfterReplay = exitAfterReplay;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        RunSummary summary = service.runYear(replayYear, TriggerType.MANUAL);
        if (summary.status() == RunStatus.SKIPPED_LOCKED) {
            throw new IllegalStateException(
                    "SZSC_YEAR_END_LOCK_HELD_BY_ANOTHER_NODE");
        }
        log.info(
                "Manual TIME_OFF year-end replay finished: runId={}, year={}, "
                        + "status={}, success={}, failure={}, skipped={}",
                summary.runId(),
                summary.accountYear(),
                summary.status(),
                summary.successCount(),
                summary.failureCount(),
                summary.skippedCount());
        if (exitAfterReplay) {
            context.close();
        }
    }
}

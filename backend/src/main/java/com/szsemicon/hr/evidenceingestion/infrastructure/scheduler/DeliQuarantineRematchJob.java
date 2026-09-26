package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import com.szsemicon.hr.evidenceingestion.application.DeliQuarantineRematchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * After a new hire is saved, wait 10 minutes and then release quarantined
 * Deli punches. Only people who receive punches are recalculated, from the
 * earliest punch through today. A nightly pass covers a missed run.
 */
@Component
public class DeliQuarantineRematchJob {

    private static final Logger log =
            LoggerFactory.getLogger(DeliQuarantineRematchJob.class);

    private final DeliQuarantineRematchService rematch;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "deli-quarantine-rematch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean queued = new AtomicBoolean();

    public DeliQuarantineRematchJob(DeliQuarantineRematchService rematch) {
        this.rematch = rematch;
    }

    @Scheduled(fixedDelayString = "${shenzhouhr.deli.quarantine-rematch-poll:PT60S}")
    void pollNewHires() {
        submit(rematch::runIfDue);
    }

    @Scheduled(
            cron = "${shenzhouhr.deli.quarantine-rematch-cron:0 30 21 * * ?}",
            zone = "${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}")
    void evening() {
        submit(rematch::runEvening);
    }

    private void submit(Runnable task) {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        workers.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                log.error("Deli quarantine rematch worker failed", exception);
            } finally {
                queued.set(false);
            }
        });
    }
}

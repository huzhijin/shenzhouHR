package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountOaRecalculateService;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper;
import com.szsemicon.hr.reporting.application.ReportSlotCompletionListener;
import java.time.Clock;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OA-driven refresh of annual-leave and time-off ledgers.
 *
 * <p>Two trigger paths:</p>
 * <ol>
 *   <li><b>Report-slot callback</b> ({@link #onReportSlotCompleted}): fires once
 *       per company immediately after each automated report rebuild slot (around
 *       00:10 and 12:10 daily). This keeps personal leave balances in sync with
 *       OA approvals on the same day rather than waiting until the next fixed
 *       cron window.</li>
 *   <li><b>Daily fallback cron</b> ({@link #runDaily}): runs at 01:00 as a
 *       safety net for the 00:00 slot and catches any companies that the slot
 *       listener may have missed (e.g. if the report rebuild failed).</li>
 * </ol>
 *
 * <p>Coexists with the query-page 「按 OA 重算」 button which calls
 * {@link LeaveAccountOaRecalculateService#recalculate} directly.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.leave-account",
        name = "oa-recalculate-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class LeaveAccountOaRecalculateJob implements ReportSlotCompletionListener {

    private static final Logger log =
            LoggerFactory.getLogger(LeaveAccountOaRecalculateJob.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final LeaveAccountOaRecalculateService service;
    private final LeaveAccountOaRecalculateMapper mapper;
    private final Clock clock;

    public LeaveAccountOaRecalculateJob(
            LeaveAccountOaRecalculateService service,
            LeaveAccountOaRecalculateMapper mapper,
            Clock clock) {
        this.service = service;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Called by {@link com.szsemicon.hr.reporting.application.AttendanceReportAutoRecalcService}
     * once per company after each report-rebuild slot completes. Exceptions are
     * swallowed here because the caller already logs them per-company; rethrowing
     * would skip remaining companies in the slot listener loop.
     */
    @Override
    public void onReportSlotCompleted(String companyId, int year) {
        log.info("Report-slot leave-account sync starting company={} year={}", companyId, year);
        try {
            var result = service.recalculateAsSystem(companyId, year);
            log.info(
                    "Report-slot leave-account sync finished company={} annual={} timeOff={}",
                    result.companyId(),
                    result.annualAccounts(),
                    result.timeOffAccounts());
        } catch (RuntimeException exception) {
            log.error(
                    "Report-slot leave-account sync failed company={} year={}",
                    companyId, year, exception);
        }
    }

    /**
     * Daily fallback: runs at 01:00 (Asia/Shanghai) to cover the 00:00 report
     * slot and any companies missed by the slot listener.
     */
    @Scheduled(
            cron = "${shenzhouhr.leave-account.oa-recalculate-cron:0 0 1 * * ?}",
            zone = "${shenzhouhr.oa.auto-sync-zone:Asia/Shanghai}")
    void runDaily() {
        int year = clock.instant().atZone(ZONE).getYear();
        log.info("Scheduled leave-account OA recalculate starting year={}", year);
        for (String companyId : mapper.listActiveCompanyIds()) {
            try {
                var result = service.recalculateAsSystem(companyId, year);
                log.info(
                        "Leave-account OA recalculate finished company={} annual={} timeOff={}",
                        result.companyId(),
                        result.annualAccounts(),
                        result.timeOffAccounts());
            } catch (RuntimeException exception) {
                log.error(
                        "Leave-account OA recalculate failed company={}",
                        companyId,
                        exception);
            }
        }
        log.info("Scheduled leave-account OA recalculate finished");
    }
}

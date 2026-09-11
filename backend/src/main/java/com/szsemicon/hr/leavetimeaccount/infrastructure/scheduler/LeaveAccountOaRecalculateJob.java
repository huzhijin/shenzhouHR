package com.szsemicon.hr.leavetimeaccount.infrastructure.scheduler;

import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountOaRecalculateService;
import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper;
import java.time.Clock;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily OA-driven refresh of annual-leave and time-off ledgers. Coexists with
 * the query-page「按 OA 重算」button. Runs after the 00:00 Deli+OA sync and
 * report rebuild so published OA facts are already current.
 */
@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.leave-account",
        name = "oa-recalculate-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class LeaveAccountOaRecalculateJob {

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

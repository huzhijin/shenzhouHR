package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.evidenceingestion.application.DeliPunchPageTransaction.PromotedPunch;
import com.szsemicon.hr.people.application.NewHireAttendanceRecovery;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Re-reads a short Deli window and promotes punches that were quarantined
 * before the employee existed. Does not move the check-in watermark and does
 * not reassign punches that already belong to someone.
 */
@Service
public class DeliQuarantineRematchService implements NewHireAttendanceRecovery {

    private static final Logger log =
            LoggerFactory.getLogger(DeliQuarantineRematchService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final boolean enabled;
    private final int lookbackDays;
    private final int maxLookbackDays;
    private final long quietMillis;
    private final DeliPunchReplayApplicationService replay;
    private final AttendanceSourceSyncRepository syncRepository;
    private final RealtimeAttendanceReportSnapshotService snapshots;
    private final Clock clock;
    private final AtomicReference<LocalDate> pendingFrom = new AtomicReference<>();
    private final AtomicLong quietDeadline = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public DeliQuarantineRematchService(
            @Value("${shenzhouhr.deli.quarantine-rematch-enabled:true}") boolean enabled,
            @Value("${shenzhouhr.deli.quarantine-rematch-lookback-days:31}") int lookbackDays,
            @Value("${shenzhouhr.deli.quarantine-rematch-max-lookback-days:62}") int maxLookbackDays,
            @Value("${shenzhouhr.deli.quarantine-rematch-quiet:PT10M}") java.time.Duration quiet,
            DeliPunchReplayApplicationService replay,
            AttendanceSourceSyncRepository syncRepository,
            @Autowired(required = false) RealtimeAttendanceReportSnapshotService snapshots,
            Clock clock) {
        this.enabled = enabled;
        this.lookbackDays = Math.max(1, lookbackDays);
        this.maxLookbackDays = Math.max(this.lookbackDays, maxLookbackDays);
        this.quietMillis = Math.max(0, quiet.toMillis());
        this.replay = replay;
        this.syncRepository = syncRepository;
        this.snapshots = snapshots;
        this.clock = clock;
        log.info(
                "Deli quarantine rematch enabled={} lookbackDays={} maxLookbackDays={} quiet={}",
                enabled,
                this.lookbackDays,
                this.maxLookbackDays,
                quiet);
    }

    @Override
    public void noteHire(LocalDate effectiveFrom) {
        if (!enabled || effectiveFrom == null) {
            return;
        }
        pendingFrom.updateAndGet(current ->
                current == null || effectiveFrom.isBefore(current)
                        ? effectiveFrom
                        : current);
        quietDeadline.set(clock.instant().toEpochMilli() + quietMillis);
        log.info("Queued Deli quarantine rematch from {}", effectiveFrom);
    }

    public void runIfDue() {
        if (!enabled) {
            return;
        }
        long deadline = quietDeadline.get();
        if (deadline == 0 || clock.instant().toEpochMilli() < deadline) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LocalDate from = pendingFrom.getAndSet(null);
        quietDeadline.set(0);
        try {
            if (from != null) {
                execute(from, today(), "new-hire");
            }
        } finally {
            running.set(false);
        }
    }

    public void runEvening() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Evening Deli quarantine rematch skipped because a run is already active");
            return;
        }
        LocalDate pending = pendingFrom.getAndSet(null);
        quietDeadline.set(0);
        LocalDate today = today();
        LocalDate from = today.minusDays(lookbackDays);
        if (pending != null && pending.isBefore(from)) {
            from = pending;
        }
        try {
            execute(from, today, "evening");
        } finally {
            running.set(false);
        }
    }

    private void execute(LocalDate requestedFrom, LocalDate to, String reason) {
        LocalDate from = cap(requestedFrom, to);
        List<String> sourceIds = syncRepository.findAllActiveDeliSourceIds();
        if (sourceIds.isEmpty()) {
            log.info("Deli quarantine rematch skipped, no active source");
            return;
        }
        log.info(
                "Deli quarantine rematch starting reason={} from={} to={} sources={}",
                reason,
                from,
                to,
                sourceIds.size());
        Map<String, EmployeeSpan> spans = new LinkedHashMap<>();
        int promoted = 0;
        LocalDate today = today();
        for (String sourceId : sourceIds) {
            try {
                var result = replay.replayForRecovery(sourceId, from, to);
                for (PromotedPunch punch : result.promoted()) {
                    if (punch.companyId() == null || punch.employeeId() == null
                            || punch.punchInstant() == null) {
                        continue;
                    }
                    LocalDate punchDate = punch.punchInstant().atZone(ZONE).toLocalDate();
                    if (punchDate.isAfter(today)) {
                        continue;
                    }
                    promoted++;
                    spans.compute(punch.companyId() + "|" + punch.employeeId(), (key, current) -> {
                        if (current == null || punchDate.isBefore(current.earliest())) {
                            return new EmployeeSpan(
                                    punch.companyId(), punch.employeeId(), punchDate);
                        }
                        return current;
                    });
                }
            } catch (RuntimeException exception) {
                log.error(
                        "Deli quarantine rematch failed source={} from={} to={}",
                        sourceId,
                        from,
                        to,
                        exception);
            }
        }
        recalculate(spans, today);
        log.info(
                "Deli quarantine rematch finished reason={} promoted={} employees={}",
                reason,
                promoted,
                spans.size());
    }

    private void recalculate(Map<String, EmployeeSpan> spans, LocalDate today) {
        if (snapshots == null || spans.isEmpty()) {
            return;
        }
        var at = clock.instant();
        for (EmployeeSpan span : spans.values()) {
            try {
                snapshots.materializeEmployeeRange(
                        span.companyId(),
                        span.employeeId(),
                        span.earliest(),
                        today,
                        at);
            } catch (RuntimeException exception) {
                log.error(
                        "Deli quarantine rematch recalc failed company={} employee={} from={}",
                        span.companyId(),
                        span.employeeId(),
                        span.earliest(),
                        exception);
            }
        }
    }

    private record EmployeeSpan(String companyId, String employeeId, LocalDate earliest) {
    }

    private LocalDate cap(LocalDate from, LocalDate to) {
        LocalDate earliest = to.minusDays(maxLookbackDays);
        if (from.isAfter(to)) {
            return to;
        }
        return from.isBefore(earliest) ? earliest : from;
    }

    private LocalDate today() {
        return clock.instant().atZone(ZONE).toLocalDate();
    }
}

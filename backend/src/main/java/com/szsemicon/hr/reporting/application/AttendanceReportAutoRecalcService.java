package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AttendanceReportAutoRecalcService
        implements ScheduledSourceCompletionListener {

    private static final Logger log =
            LoggerFactory.getLogger(AttendanceReportAutoRecalcService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final RealtimeAttendanceReportSnapshotService snapshots;
    private final Clock clock;
    private final boolean enabled;
    private final Duration delay;

    public AttendanceReportAutoRecalcService(
            JdbcTemplate jdbc,
            RealtimeAttendanceReportSnapshotService snapshots,
            Clock clock,
            @Value("${shenzhouhr.report.auto-recalculate-after-sync:true}")
                    boolean enabled,
            @Value("${shenzhouhr.report.auto-recalculate-delay:PT10M}")
                    Duration delay) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.clock = clock;
        this.enabled = enabled;
        this.delay = delay;
    }

    @Override
    public void onScheduledDeliSuccess() {
        markSource("deli_succeeded_at");
    }

    @Override
    public void onScheduledOaSuccess() {
        markSource("oa_succeeded_at");
    }

    @Scheduled(fixedDelayString = "PT60S")
    public void processDueSlots() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        List<Instant> due = jdbc.query(
                """
                SELECT slot_start FROM attendance_report_auto_recalc_slot
                WHERE status = 'SCHEDULED'
                  AND deli_succeeded_at IS NOT NULL
                  AND oa_succeeded_at IS NOT NULL
                  AND recalc_due_at IS NOT NULL
                  AND recalc_due_at <= ?
                """,
                (rs, rowNum) -> rs.getTimestamp("slot_start").toInstant(),
                java.sql.Timestamp.from(now));
        for (Instant slotStart : due) {
            runSlot(slotStart, now);
        }
    }

    private void markSource(String column) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        Instant slotStart = currentSlotStart(now);
        jdbc.update(
                """
                INSERT INTO attendance_report_auto_recalc_slot (
                    slot_id, slot_start, status, %s
                ) VALUES (?, ?, 'SCHEDULED', ?)
                ON DUPLICATE KEY UPDATE
                    %s = COALESCE(%s, VALUES(%s)),
                    status = CASE
                        WHEN status IN ('RUNNING') THEN status
                        ELSE 'SCHEDULED'
                    END
                """.formatted(column, column, column, column),
                UUID.randomUUID().toString(),
                java.sql.Timestamp.from(slotStart),
                java.sql.Timestamp.from(now));
        Instant dueAt = now.plus(delay);
        jdbc.update(
                """
                UPDATE attendance_report_auto_recalc_slot
                SET recalc_due_at = ?
                WHERE slot_start = ?
                  AND deli_succeeded_at IS NOT NULL
                  AND oa_succeeded_at IS NOT NULL
                  AND status = 'SCHEDULED'
                  AND (recalc_due_at IS NULL OR recalc_due_at > ?)
                """,
                java.sql.Timestamp.from(dueAt),
                java.sql.Timestamp.from(slotStart),
                java.sql.Timestamp.from(dueAt));
    }

    private void runSlot(Instant slotStart, Instant now) {
        jdbc.update(
                """
                UPDATE attendance_report_auto_recalc_slot
                SET status = 'RUNNING'
                WHERE slot_start = ? AND status = 'SCHEDULED'
                """,
                java.sql.Timestamp.from(slotStart));
        LocalDate slotDate = slotStart.atZone(ZONE).toLocalDate();
        YearMonth current = YearMonth.from(slotDate);
        List<YearMonth> months = new java.util.ArrayList<>();
        if (slotDate.getDayOfMonth() <= 3) {
            months.add(current.minusMonths(1));
        }
        months.add(current);
        List<String> companies = jdbc.query(
                "SELECT company_id FROM company WHERE status = 'ACTIVE'",
                (rs, rowNum) -> rs.getString("company_id"));
        try {
            for (String companyId : companies) {
                for (YearMonth month : months) {
                    if (shouldSkip(companyId, month, now)) {
                        continue;
                    }
                    snapshots.materializeCompanyMonthWindow(
                            companyId, month, RecalcWindow.MONTH, now);
                }
            }
            jdbc.update(
                    """
                    UPDATE attendance_report_auto_recalc_slot
                    SET status = 'COMPLETED', recalc_completed_at = ?
                    WHERE slot_start = ?
                    """,
                    java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(slotStart));
        } catch (RuntimeException failed) {
            log.error("auto month engine failed slot={}", slotStart, failed);
            jdbc.update(
                    """
                    UPDATE attendance_report_auto_recalc_slot
                    SET status = 'FAILED', last_error = ?
                    WHERE slot_start = ?
                    """,
                    truncate(failed.getMessage()),
                    java.sql.Timestamp.from(slotStart));
        }
    }

    private boolean shouldSkip(String companyId, YearMonth month, Instant now) {
        LocalDate start = month.atDay(1);
        LocalDate endExclusive = month.plusMonths(1).atDay(1);
        List<PinState> pins = jdbc.query(
                """
                SELECT period_state, source_versions_json, projection_version
                FROM attendance_report_projection
                WHERE company_id = ?
                  AND period_start = ?
                  AND period_end_exclusive = ?
                  AND status = 'PUBLISHED'
                  AND formula_catalog_version =
                      'FULL_CALCULATION_OA_FORM_HOURS_V8'
                ORDER BY published_at DESC, attendance_report_projection_id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new PinState(
                        rs.getString("period_state"),
                        rs.getString("source_versions_json"),
                        rs.getString("projection_version")),
                companyId,
                java.sql.Date.valueOf(start),
                java.sql.Date.valueOf(endExclusive));
        if (pins.isEmpty()) {
            return false;
        }
        PinState pin = pins.getFirst();
        if ("CLOSED".equals(pin.periodState())
                || "FROZEN".equals(pin.periodState())) {
            return true;
        }
        List<String> versions = parseVersions(pin.sourceVersionsJson());
        var stub = new com.szsemicon.hr.reporting.domain
                .AttendanceReportModels.ReportSourceSnapshot(
                new com.szsemicon.hr.reporting.domain.AttendanceReportModels
                        .AuthorizedScope(
                        com.szsemicon.hr.reporting.domain.AttendanceReportModels
                                .ScopeType.COMPANY,
                        companyId,
                        companyId,
                        "a".repeat(64)),
                new ReportFilter(month, companyId, null, null, null),
                pin.projectionVersion(),
                pin.periodState(),
                now,
                versions,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return !snapshots.sourcesNewerThanPin(stub, now);
    }

    private static List<String> parseVersions(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.strip())) {
            return List.of();
        }
        String trimmed = json.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> versions = new java.util.ArrayList<>();
        for (String part : trimmed.split(",")) {
            String value = part.strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) {
                versions.add(value);
            }
        }
        return versions;
    }

    private record PinState(
            String periodState, String sourceVersionsJson, String projectionVersion) {
    }

    private Instant currentSlotStart(Instant now) {
        ZonedDateTime local = now.atZone(ZONE);
        int slotHour = local.getHour() < 12 ? 0 : 12;
        return local.withHour(slotHour).withMinute(0).withSecond(0)
                .withNano(0)
                .toInstant();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "FAILED";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}

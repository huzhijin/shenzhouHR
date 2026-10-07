package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Floors OA interval endpoints onto a 30-minute Asia/Shanghai grid before
 * calculation and report hours. Minutes 0–29 become the hour; 30–59 become
 * half past. Exact :00 and :30 clocks are unchanged.
 */
final class OaIntervalGrid {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private OaIntervalGrid() {
    }

    static Instant snap(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        ZonedDateTime local = instant.atZone(BUSINESS_ZONE);
        int minute = local.getMinute();
        int snappedMinute = minute < 30 ? 0 : 30;
        return local.withMinute(snappedMinute)
                .withSecond(0)
                .withNano(0)
                .toInstant();
    }

    static TimeInterval snapInterval(Instant start, Instant end) {
        Instant snappedStart = snap(start);
        Instant snappedEnd = snap(end);
        if (!snappedStart.isBefore(snappedEnd)) {
            return null;
        }
        return new TimeInterval(snappedStart, snappedEnd);
    }

    static Instant persistEnd(Instant snappedStart, Instant snappedEnd) {
        if (snappedStart.isBefore(snappedEnd)) {
            return snappedEnd;
        }
        return snappedStart.plusNanos(1);
    }
}

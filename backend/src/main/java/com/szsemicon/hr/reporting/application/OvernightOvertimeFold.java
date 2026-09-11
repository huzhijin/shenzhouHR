package com.szsemicon.hr.reporting.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Daily facts already credit a crossing form to the start date. Display
 * minutes are therefore the stored calendar minutes. {@link
 * #continuationOnlyOnDate} still hides Saturday-morning continuation of a
 * Friday form from the next day's overtime paint.
 */
public final class OvernightOvertimeFold {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime DEFAULT_SHIFT_START = LocalTime.of(8, 30);

    public record OvertimeSpan(
            Instant start,
            Instant endExclusive,
            boolean effective) {
    }

    private OvernightOvertimeFold() {
    }

    public static long displayMinutes(
            LocalDate businessDate,
            long calendarMinutes,
            List<OvertimeSpan> documents,
            LocalTime nextDayShiftStart) {
        Objects.requireNonNull(businessDate, "businessDate");
        return Math.max(0, calendarMinutes);
    }

    public static boolean continuationOnlyOnDate(
            Instant start,
            Instant endExclusive,
            LocalDate date,
            LocalTime shiftStart) {
        if (start == null || endExclusive == null || date == null) {
            return false;
        }
        LocalDate startDate = start.atZone(ZONE).toLocalDate();
        LocalDate endDate = endExclusive.minusNanos(1).atZone(ZONE).toLocalDate();
        if (!date.equals(endDate) || !endDate.equals(startDate.plusDays(1))) {
            return false;
        }
        LocalTime endTime = endExclusive.atZone(ZONE).toLocalTime();
        if (endExclusive.equals(date.plusDays(1).atStartOfDay(ZONE).toInstant())) {
            endTime = LocalTime.MIDNIGHT;
        }
        LocalTime cut = shiftStart == null ? DEFAULT_SHIFT_START : shiftStart;
        return endTime.isBefore(cut);
    }

    static long continuationMinutes(OvertimeSpan document, LocalTime shiftStart) {
        if (document == null
                || !document.effective()
                || document.start() == null
                || document.endExclusive() == null
                || !document.endExclusive().isAfter(document.start())) {
            return 0;
        }
        LocalDate startDate = document.start().atZone(ZONE).toLocalDate();
        LocalDate endDate = document.endExclusive()
                .minusNanos(1)
                .atZone(ZONE)
                .toLocalDate();
        if (!endDate.equals(startDate.plusDays(1))) {
            return 0;
        }
        LocalTime endTime = document.endExclusive().atZone(ZONE).toLocalTime();
        LocalTime cut = shiftStart == null ? DEFAULT_SHIFT_START : shiftStart;
        if (!endTime.isBefore(cut)
                && !document.endExclusive().equals(
                        endDate.atStartOfDay(ZONE).toInstant())) {
            return 0;
        }
        Instant dayStart = endDate.atStartOfDay(ZONE).toInstant();
        Instant end = document.endExclusive().isAfter(dayStart)
                ? document.endExclusive()
                : dayStart;
        if (!end.isAfter(dayStart)) {
            return 0;
        }
        return Duration.between(dayStart, end).toMinutes();
    }
}

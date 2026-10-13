package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formats a punch clock against a business date. Next-day leaving cards get 次日. */
public final class PunchClockFormat {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);

    private PunchClockFormat() {
    }

    public static String format(LocalDate businessDate, Instant punch) {
        if (punch == null) {
            return "";
        }
        String clock = CLOCK.format(punch);
        if (businessDate != null
                && punch.atZone(BUSINESS_ZONE).toLocalDate().isAfter(businessDate)) {
            return "次日 " + clock;
        }
        return clock;
    }

    public static boolean nextCalendarDay(LocalDate businessDate, Instant punch) {
        return businessDate != null
                && punch != null
                && punch.atZone(BUSINESS_ZONE).toLocalDate().isAfter(businessDate);
    }
}

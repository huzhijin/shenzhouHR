package com.szsemicon.hr.evidenceingestion.infrastructure.scheduler;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Auto-calculation runs only after the 00:00 and 12:00 sync cycles. Deli still
 * fetches punches at 08:00 and 18:00, and OA still copies documents every hour;
 * those runs do not start a report rebuild.
 */
final class ScheduledAttendanceRecalcHours {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private ScheduledAttendanceRecalcHours() {
    }

    static boolean isRecalcHour(Instant at) {
        if (at == null) {
            return false;
        }
        int hour = at.atZone(ZONE).getHour();
        return hour == 0 || hour == 12;
    }
}

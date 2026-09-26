package com.szsemicon.hr.evidenceingestion.application.paper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PaperOvertimeOverlap {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PaperOvertimeOverlap() {
    }

    public static Set<LocalDate> calendarDays(Instant start, Instant end) {
        Set<LocalDate> days = new LinkedHashSet<>();
        if (start == null || end == null || !end.isAfter(start)) {
            return days;
        }
        LocalDate from = start.atZone(ZONE).toLocalDate();
        LocalDate to = end.minusNanos(1).atZone(ZONE).toLocalDate();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(date);
        }
        return days;
    }

    public static boolean datesOverlap(Set<LocalDate> left, Set<LocalDate> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (LocalDate date : left) {
            if (right.contains(date)) {
                return true;
            }
        }
        return false;
    }
}

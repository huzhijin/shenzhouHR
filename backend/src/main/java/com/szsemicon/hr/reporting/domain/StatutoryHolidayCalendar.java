package com.szsemicon.hr.reporting.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Statutory dates that pay as holiday overtime. A year absent from
 * {@link #BY_YEAR} keeps the previous rule: every public-holiday calendar
 * day is holiday overtime. Extended rest inside a configured year is
 * weekend overtime. Attendance and meal deduction still read the calendar.
 */
public final class StatutoryHolidayCalendar {

    public enum OvertimeClass {
        HOLIDAY,
        WEEKEND,
        WEEKDAY
    }

    private static final Map<Integer, Set<LocalDate>> BY_YEAR = Map.of(
            2026, Set.of(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 2, 16),
                    LocalDate.of(2026, 2, 17),
                    LocalDate.of(2026, 2, 18),
                    LocalDate.of(2026, 2, 19),
                    LocalDate.of(2026, 4, 5),
                    LocalDate.of(2026, 5, 1),
                    LocalDate.of(2026, 5, 2),
                    LocalDate.of(2026, 6, 19),
                    LocalDate.of(2026, 9, 25),
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 2),
                    LocalDate.of(2026, 10, 3)));

    private StatutoryHolidayCalendar() {
    }

    public static boolean yearConfigured(int year) {
        return BY_YEAR.containsKey(year);
    }

    public static boolean isStatutory(LocalDate date) {
        if (date == null) {
            return false;
        }
        Set<LocalDate> dates = BY_YEAR.get(date.getYear());
        return dates != null && dates.contains(date);
    }

    public static OvertimeClass classify(LocalDate date, String dayType) {
        String type = dayType == null ? "" : dayType.trim().toUpperCase();
        if ("SATURDAY".equals(type)
                || "SUNDAY".equals(type)
                || "WEEKEND".equals(type)) {
            return OvertimeClass.WEEKEND;
        }
        if ("PUBLIC_HOLIDAY".equals(type) || "HOLIDAY".equals(type)) {
            if (date == null) {
                return OvertimeClass.HOLIDAY;
            }
            Set<LocalDate> dates = BY_YEAR.get(date.getYear());
            if (dates == null) {
                return OvertimeClass.HOLIDAY;
            }
            return dates.contains(date)
                    ? OvertimeClass.HOLIDAY
                    : OvertimeClass.WEEKEND;
        }
        return OvertimeClass.WEEKDAY;
    }
}

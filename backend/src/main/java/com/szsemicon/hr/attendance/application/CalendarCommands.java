package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import java.time.LocalDate;
import java.util.List;

public final class CalendarCommands {

    private CalendarCommands() {
    }

    public record CalendarCommand(
            String legalEntityId,
            String locationId,
            String code,
            String name,
            int calendarYear,
            String timeZone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record CalendarVersionCommand(
            String name,
            int calendarYear,
            String timeZone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record CalendarDayCommand(
            LocalDate businessDate,
            DayType dayType,
            String shiftVersionOverrideId) {
    }

    public record ReplaceDaysCommand(List<CalendarDayCommand> days, String reason) {
    }
}

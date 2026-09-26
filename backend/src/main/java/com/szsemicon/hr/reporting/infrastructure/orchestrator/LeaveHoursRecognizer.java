package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Recognizes leave hours as OA interval intersected with that employee's
 * published WORK segments. No location clock times are hardcoded.
 */
final class LeaveHoursRecognizer {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private LeaveHoursRecognizer() {
    }

    static long recognizedMinutes(
            Instant start,
            Instant end,
            LeaveType leaveType,
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays) {
        return recognizedMinutes(
                start,
                end,
                leaveType,
                employeeId,
                shiftSegments,
                calendarDays,
                null);
    }

    static long recognizedMinutes(
            Instant start,
            Instant end,
            LeaveType leaveType,
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            String organizationName) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            return 0;
        }
        LocalDate from = start.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate to = end.minusNanos(1).atZone(BUSINESS_ZONE).toLocalDate();
        if (to.isBefore(from)) {
            return 0;
        }
        List<ShiftSegmentRow> employeeSegments = shiftSegments.stream()
                .filter(segment -> segment.employeeId().equals(employeeId))
                .sorted(Comparator.comparing(ShiftSegmentRow::segmentStart))
                .toList();
        List<LocalTime[]> weekdayTemplate = weekdayTemplate(employeeSegments);
        if (weekdayTemplate.isEmpty()) {
            weekdayTemplate = siteWeekdayTemplate(organizationName, from);
        }
        long minutes = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            boolean restDay = isRestDay(employeeId, date, calendarDays);
            if (restDay && !includesWeekend(leaveType)) {
                continue;
            }
            Instant dayStart = start;
            Instant dayEnd = end;
            if (leaveType == LeaveType.BREASTFEEDING) {
                LocalTime clock = start.atZone(BUSINESS_ZONE).toLocalTime();
                dayStart = date.atTime(clock).atZone(BUSINESS_ZONE).toInstant();
                dayEnd = dayStart.plusSeconds(3600);
            }
            List<TimeInterval> dayWindows = workWindows(
                    employeeId, date, employeeSegments, weekdayTemplate, restDay);
            for (TimeInterval window : dayWindows) {
                TimeInterval slice = overlap(
                        dayStart, dayEnd, window.start(), window.end());
                if (slice != null) {
                    minutes += slice.minutes();
                }
            }
        }
        return minutes;
    }

    static boolean includesWeekend(LeaveType leaveType) {
        return leaveType == null || leaveType.includesWeekendHours();
    }

    private static boolean isRestDay(
            String employeeId,
            LocalDate date,
            List<CalendarDayRow> calendarDays) {
        String dayType = calendarDays.stream()
                .filter(row -> date.equals(row.businessDate()))
                .filter(row -> row.employeeId() == null
                        || row.employeeId().equals(employeeId)
                        || row.employeeId().isBlank())
                .map(CalendarDayRow::dayType)
                .findFirst()
                .orElse(null);
        if (dayType != null) {
            String normalized = dayType.trim().toUpperCase();
            return switch (normalized) {
                case "SATURDAY", "SUNDAY", "PUBLIC_HOLIDAY", "WEEKEND" -> true;
                case "WEEKDAY", "ADJUSTED_WORKDAY", "SPECIAL_WORKDAY" -> false;
                default -> weekendByCalendar(date);
            };
        }
        return weekendByCalendar(date);
    }

    private static boolean weekendByCalendar(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private static List<TimeInterval> workWindows(
            String employeeId,
            LocalDate date,
            List<ShiftSegmentRow> employeeSegments,
            List<LocalTime[]> weekdayTemplate,
            boolean restDay) {
        List<ShiftSegmentRow> daySegments = employeeSegments.stream()
                .filter(segment -> date.equals(segment.businessDate()))
                .toList();
        if (!daySegments.isEmpty()) {
            return daySegments.stream()
                    .map(segment -> new TimeInterval(
                            segment.segmentStart(), segment.segmentEnd()))
                    .toList();
        }
        List<TimeInterval> windows = new ArrayList<>();
        for (LocalTime[] part : weekdayTemplate) {
            windows.add(new TimeInterval(
                    date.atTime(part[0]).atZone(BUSINESS_ZONE).toInstant(),
                    date.atTime(part[1]).atZone(BUSINESS_ZONE).toInstant()));
        }
        return windows;
    }

    private static List<LocalTime[]> siteWeekdayTemplate(
            String organizationName, LocalDate date) {
        if (organizationName == null || organizationName.isBlank() || date == null) {
            return List.of();
        }
        return SiteShiftTemplates.workSegments(
                        "template",
                        date,
                        SiteShiftTemplates.kindFor(organizationName))
                .stream()
                .sorted(Comparator.comparing(ShiftSegmentRow::segmentStart))
                .map(segment -> new LocalTime[] {
                        segment.segmentStart().atZone(BUSINESS_ZONE).toLocalTime(),
                        segment.segmentEnd().atZone(BUSINESS_ZONE).toLocalTime()
                })
                .toList();
    }

    private static List<LocalTime[]> weekdayTemplate(
            List<ShiftSegmentRow> employeeSegments) {
        return employeeSegments.stream()
                .filter(segment -> {
                    DayOfWeek dow = segment.businessDate().getDayOfWeek();
                    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
                })
                .collect(java.util.stream.Collectors.groupingBy(
                        ShiftSegmentRow::businessDate))
                .values()
                .stream()
                .filter(group -> group.size() >= 2)
                .max(Comparator.comparing(group -> group.getFirst().businessDate()))
                .map(group -> group.stream()
                        .sorted(Comparator.comparing(ShiftSegmentRow::segmentStart))
                        .map(segment -> new LocalTime[] {
                                segment.segmentStart()
                                        .atZone(BUSINESS_ZONE)
                                        .toLocalTime(),
                                segment.segmentEnd()
                                        .atZone(BUSINESS_ZONE)
                                        .toLocalTime()
                        })
                        .toList())
                .orElse(List.of());
    }

    private static TimeInterval overlap(
            Instant leftStart,
            Instant leftEnd,
            Instant rightStart,
            Instant rightEnd) {
        Instant start = leftStart.isAfter(rightStart) ? leftStart : rightStart;
        Instant end = leftEnd.isBefore(rightEnd) ? leftEnd : rightEnd;
        return start.isBefore(end) ? new TimeInterval(start, end) : null;
    }
}

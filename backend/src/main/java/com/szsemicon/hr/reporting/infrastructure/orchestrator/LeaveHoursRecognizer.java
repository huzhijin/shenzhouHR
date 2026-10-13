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
import java.util.LinkedHashMap;
import java.util.Map;
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
        return recognizedMinutesByDate(start, end, leaveType, employeeId,
                shiftSegments, calendarDays, organizationName).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    /** Uses the same work windows as total recognition, before any month filtering. */
    static Map<LocalDate, Long> recognizedMinutesByDate(
            Instant start,
            Instant end,
            LeaveType leaveType,
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            String organizationName) {
        return recognizedMinutesByDate(start, end, leaveType, employeeId,
                shiftSegments, calendarDays, organizationName, Map.of());
    }

    /**
     * Overload that accepts per-date authorized segments (e.g. standing/EXEC
     * template segments generated during the daily loop). These are injected
     * directly as that day's shift segments and do NOT feed into weekdayTemplate
     * inference, preventing cross-day extrapolation of single-day authorizations.
     */
    static Map<LocalDate, Long> recognizedMinutesByDate(
            Instant start,
            Instant end,
            LeaveType leaveType,
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            String organizationName,
            Map<LocalDate, List<ShiftSegmentRow>> authorizedDailySegments) {
        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        recognizedSlicesByDate(start, end, leaveType, employeeId, shiftSegments,
                calendarDays, organizationName, authorizedDailySegments)
                .forEach((date, slices) -> slices.forEach(slice ->
                        byDate.merge(date, slice.minutes(), Math::addExact)));
        return Map.copyOf(byDate);
    }

    /**
     * The actual WORK-window slices behind {@link #recognizedMinutesByDate}.
     * Callers that must share one person's working time between documents
     * use these instants instead of re-deriving them from minute totals.
     */
    static Map<LocalDate, List<TimeInterval>> recognizedSlicesByDate(
            Instant start,
            Instant end,
            LeaveType leaveType,
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays,
            String organizationName,
            Map<LocalDate, List<ShiftSegmentRow>> authorizedDailySegments) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            return Map.of();
        }
        LocalDate from = start.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate to = end.minusNanos(1).atZone(BUSINESS_ZONE).toLocalDate();
        if (to.isBefore(from)) {
            return Map.of();
        }
        List<ShiftSegmentRow> employeeSegments = shiftSegments.stream()
                .filter(segment -> segment.employeeId().equals(employeeId))
                .sorted(Comparator.comparing(ShiftSegmentRow::segmentStart))
                .toList();
        List<LocalTime[]> weekdayTemplate = weekdayTemplate(employeeSegments);
        if (weekdayTemplate.isEmpty()) {
            weekdayTemplate = siteWeekdayTemplate(organizationName, from);
        }
        Map<LocalDate, List<TimeInterval>> byDate = new LinkedHashMap<>();
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
            // Per-date authorized segments (e.g. standing template) take precedence
            // as direct daySegments so they never feed into weekdayTemplate inference.
            List<ShiftSegmentRow> authorizedForDate = authorizedDailySegments.getOrDefault(date, List.of());
            List<TimeInterval> dayWindows = workWindows(
                    employeeId, date, employeeSegments, weekdayTemplate, restDay,
                    authorizedForDate);
            for (TimeInterval window : dayWindows) {
                TimeInterval slice = overlap(
                        dayStart, dayEnd, window.start(), window.end());
                if (slice != null) {
                    byDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(slice);
                }
            }
        }
        byDate.replaceAll((ignored, slices) -> List.copyOf(slices));
        return Map.copyOf(byDate);
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
        return workWindows(employeeId, date, employeeSegments, weekdayTemplate,
                restDay, List.of());
    }

    private static List<TimeInterval> workWindows(
            String employeeId,
            LocalDate date,
            List<ShiftSegmentRow> employeeSegments,
            List<LocalTime[]> weekdayTemplate,
            boolean restDay,
            List<ShiftSegmentRow> authorizedForDate) {
        // Real published segments for this day take highest priority.
        List<ShiftSegmentRow> daySegments = employeeSegments.stream()
                .filter(segment -> date.equals(segment.businessDate()))
                .toList();
        if (!daySegments.isEmpty()) {
            return daySegments.stream()
                    .map(segment -> new TimeInterval(
                            segment.segmentStart(), segment.segmentEnd()))
                    .toList();
        }
        // Per-date authorized segments (e.g. standing/EXEC template generated
        // during the daily loop) are used directly without feeding weekdayTemplate.
        if (!authorizedForDate.isEmpty()) {
            return authorizedForDate.stream()
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
        // Only authorised no-clock sites (Dalian/Wuhan in 2026-08) may use
        // the site template as fallback; other orgs have no standing authority.
        if (!SiteShiftTemplates.noClockSiteFullAttendance(organizationName, date)) {
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

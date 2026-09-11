package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Overtime hours from a snapped OA interval. Rest days remove lunch when
 * the interval fully covers {@code 12:00–13:00}, and always cut the dinner
 * window out of the interval so that meal time is never overtime. A rest-day
 * interval that sits entirely inside {@code [shiftOff-30min, shiftOff+30min]}
 * is the evening meal band and recognizes 0. Weekdays subtract published
 * WORK segments, the lunch gap, and a fixed 30-minute dinner when remaining
 * overtime intersects the dinner window. Punches are not consulted.
 */
final class OvertimeMealDeductions {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalTime LUNCH_START = LocalTime.of(12, 0);
    static final LocalTime LUNCH_END = LocalTime.of(13, 0);
    static final LocalTime DEFAULT_SHIFT_OFF = LocalTime.of(17, 30);
    private static final int LUNCH_MINUTES = 60;
    private static final int DINNER_MINUTES = 30;

    interface DayLookup {
        boolean restDay(LocalDate date);

        LocalTime shiftOff(LocalDate date);

        default List<TimeInterval> workSegments(LocalDate date) {
            return List.of();
        }
    }

    record DayMinutes(LocalDate date, long minutes) {
    }

    private OvertimeMealDeductions() {
    }

    static long recognizedMinutes(
            Instant start,
            Instant end,
            DayLookup days) {
        TimeInterval snapped = OaIntervalGrid.snapInterval(start, end);
        if (snapped == null) {
            return 0L;
        }
        return recognizedMinutes(snapped, days);
    }

    static long recognizedMinutes(TimeInterval snapped, DayLookup days) {
        return minutesOnStartDay(snapped, days).stream()
                .mapToLong(DayMinutes::minutes)
                .sum();
    }

    static TimeInterval capToLastPunch(TimeInterval snapped, Instant lastPunch) {
        if (snapped == null) {
            return null;
        }
        if (lastPunch == null) {
            return null;
        }
        Instant end = snapped.end().isAfter(lastPunch)
                ? lastPunch
                : snapped.end();
        if (!snapped.start().isBefore(end)) {
            return null;
        }
        return new TimeInterval(snapped.start(), end);
    }

    static TimeInterval capToSnappedLastPunch(
            TimeInterval snapped, Instant lastPunch) {
        if (lastPunch == null) {
            return null;
        }
        return capToLastPunch(snapped, OaIntervalGrid.snap(lastPunch));
    }

    static Instant lastPunchAtOrAfter(List<Instant> punches, Instant start) {
        if (punches == null || start == null) {
            return null;
        }
        Instant last = null;
        for (Instant punch : punches) {
            if (punch == null || punch.isBefore(start)) {
                continue;
            }
            if (last == null || punch.isAfter(last)) {
                last = punch;
            }
        }
        return last;
    }

    /**
     * Last off-duty-capable punch on the overtime start date (or next
     * morning). Morning-only arrival punches are ignored so a 18:06 leaving
     * punch still caps an 18:30–21:00 form to zero.
     */
    static Instant lastCoveringOffPunch(
            List<Instant> punches,
            TimeInterval snapped,
            LocalTime shiftOff) {
        if (punches == null || snapped == null) {
            return null;
        }
        LocalDate otDate = snapped.start().atZone(BUSINESS_ZONE).toLocalDate();
        LocalTime off = shiftOff == null ? DEFAULT_SHIFT_OFF : shiftOff;
        Instant last = null;
        for (Instant punch : punches) {
            if (punch == null) {
                continue;
            }
            LocalDate punchDate = punch.atZone(BUSINESS_ZONE).toLocalDate();
            if (!punchDate.equals(otDate)
                    && !punchDate.equals(otDate.plusDays(1))) {
                continue;
            }
            LocalTime clock = punch.atZone(BUSINESS_ZONE).toLocalTime();
            boolean covering = !clock.isBefore(LocalTime.of(13, 0))
                    || !clock.isBefore(off);
            if (!covering) {
                continue;
            }
            if (last == null || punch.isAfter(last)) {
                last = punch;
            }
        }
        return last;
    }

    /**
     * Credits the whole interval to the start calendar date. Meal and
     * scheduled-work deductions still use that start day's lookup.
     */
    static List<DayMinutes> minutesOnStartDay(
            TimeInterval snapped,
            DayLookup days) {
        Objects.requireNonNull(snapped, "snapped");
        Objects.requireNonNull(days, "days");
        LocalDate startDate = snapped.start()
                .atZone(BUSINESS_ZONE)
                .toLocalDate();
        long minutes = recognizedOnDay(startDate, snapped, days);
        if (minutes <= 0) {
            return List.of();
        }
        return List.of(new DayMinutes(startDate, minutes));
    }

    static List<DayMinutes> minutesByDay(
            TimeInterval snapped,
            DayLookup days) {
        return minutesOnStartDay(snapped, days);
    }

    static DayLookup lookup(
            String employeeId,
            List<ShiftSegmentRow> shiftSegments,
            List<CalendarDayRow> calendarDays) {
        Objects.requireNonNull(employeeId, "employeeId");
        List<ShiftSegmentRow> segments = shiftSegments == null
                ? List.of()
                : shiftSegments;
        List<CalendarDayRow> calendar = calendarDays == null
                ? List.of()
                : calendarDays;
        LocalTime weekdayOff = weekdayTemplateOff(employeeId, segments);
        return new DayLookup() {
            @Override
            public boolean restDay(LocalDate date) {
                return isRestDay(employeeId, date, calendar);
            }

            @Override
            public LocalTime shiftOff(LocalDate date) {
                LocalTime published = publishedOff(employeeId, date, segments);
                if (published != null) {
                    return published;
                }
                return weekdayOff == null
                        ? seasonalDefaultOff(date)
                        : weekdayOff;
            }

            @Override
            public List<TimeInterval> workSegments(LocalDate date) {
                return segments.stream()
                        .filter(segment -> employeeId.equals(segment.employeeId()))
                        .filter(segment -> date.equals(segment.businessDate()))
                        .filter(segment -> segment.segmentStart()
                                .isBefore(segment.segmentEnd()))
                        .map(segment -> new TimeInterval(
                                segment.segmentStart(), segment.segmentEnd()))
                        .sorted(Comparator.comparing(TimeInterval::start))
                        .toList();
            }
        };
    }

    static List<DaySlice> splitByCalendarDay(TimeInterval interval) {
        LocalDate from = interval.start().atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate to = interval.end().minusNanos(1)
                .atZone(BUSINESS_ZONE)
                .toLocalDate();
        if (to.isBefore(from)) {
            return List.of();
        }
        List<DaySlice> slices = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(BUSINESS_ZONE).toInstant();
            Instant dayEnd = date.plusDays(1)
                    .atStartOfDay(BUSINESS_ZONE)
                    .toInstant();
            Instant start = interval.start().isAfter(dayStart)
                    ? interval.start()
                    : dayStart;
            Instant end = interval.end().isBefore(dayEnd)
                    ? interval.end()
                    : dayEnd;
            if (start.isBefore(end)) {
                slices.add(new DaySlice(date, new TimeInterval(start, end)));
            }
        }
        return List.copyOf(slices);
    }

    private static long recognizedOnDay(
            LocalDate date,
            TimeInterval slice,
            DayLookup days) {
        if (days.restDay(date)) {
            LocalTime off = shiftOff(days, date);
            TimeInterval dinner = dinnerWindow(date, off);
            TimeInterval mealBand = window(
                    date,
                    off.minusMinutes(DINNER_MINUTES),
                    off.plusMinutes(DINNER_MINUTES));
            if (containedIn(slice, mealBand)) {
                return 0L;
            }
            List<TimeInterval> remaining = List.of(slice);
            TimeInterval lunch = window(date, LUNCH_START, LUNCH_END);
            if (covers(slice, lunch)) {
                remaining = subtract(remaining, lunch);
            }
            remaining = subtract(remaining, dinner);
            return remaining.stream()
                    .mapToLong(piece -> Duration.between(
                            piece.start(), piece.end()).toMinutes())
                    .sum();
        }
        List<TimeInterval> remaining = List.of(slice);
        for (TimeInterval work : days.workSegments(date)) {
            remaining = subtract(remaining, work);
        }
        remaining = subtract(
                remaining, window(date, LUNCH_START, LUNCH_END));
        long minutes = remaining.stream()
                .mapToLong(piece -> Duration.between(
                        piece.start(), piece.end()).toMinutes())
                .sum();
        if (intersects(remaining, dinnerWindow(date, shiftOff(days, date)))) {
            minutes -= DINNER_MINUTES;
        }
        return Math.max(0, minutes);
    }

    private static LocalTime shiftOff(DayLookup days, LocalDate date) {
        LocalTime off = days.shiftOff(date);
        return off == null ? seasonalDefaultOff(date) : off;
    }

    private static LocalTime seasonalDefaultOff(LocalDate date) {
        return SiteShiftTemplates.summer(date)
                ? LocalTime.of(18, 0)
                : DEFAULT_SHIFT_OFF;
    }

    private static boolean containedIn(TimeInterval inner, TimeInterval outer) {
        return !inner.start().isBefore(outer.start())
                && !inner.end().isAfter(outer.end());
    }

    private static List<TimeInterval> subtract(
            List<TimeInterval> remaining, TimeInterval cut) {
        List<TimeInterval> out = new ArrayList<>();
        for (TimeInterval piece : remaining) {
            if (!piece.overlaps(cut)) {
                out.add(piece);
                continue;
            }
            if (piece.start().isBefore(cut.start())) {
                Instant end = piece.end().isBefore(cut.start())
                        ? piece.end() : cut.start();
                if (piece.start().isBefore(end)) {
                    out.add(new TimeInterval(piece.start(), end));
                }
            }
            if (piece.end().isAfter(cut.end())) {
                Instant start = piece.start().isAfter(cut.end())
                        ? piece.start() : cut.end();
                if (start.isBefore(piece.end())) {
                    out.add(new TimeInterval(start, piece.end()));
                }
            }
        }
        return out;
    }

    private static boolean intersects(
            List<TimeInterval> remaining, TimeInterval inner) {
        return remaining.stream().anyMatch(piece -> piece.overlaps(inner));
    }

    private static TimeInterval dinnerWindow(LocalDate date, LocalTime off) {
        Instant start = date.atTime(off).atZone(BUSINESS_ZONE).toInstant();
        return new TimeInterval(start, start.plusSeconds(DINNER_MINUTES * 60L));
    }

    private static TimeInterval window(
            LocalDate date, LocalTime start, LocalTime end) {
        return new TimeInterval(
                date.atTime(start).atZone(BUSINESS_ZONE).toInstant(),
                date.atTime(end).atZone(BUSINESS_ZONE).toInstant());
    }

    private static boolean covers(TimeInterval outer, TimeInterval inner) {
        return !outer.start().isAfter(inner.start())
                && !outer.end().isBefore(inner.end());
    }

    private static boolean isRestDay(
            String employeeId,
            LocalDate date,
            List<CalendarDayRow> calendarDays) {
        String dayType = calendarDays.stream()
                .filter(row -> date.equals(row.businessDate()))
                .filter(row -> row.employeeId() == null
                        || row.employeeId().isBlank()
                        || row.employeeId().equals(employeeId))
                .map(CalendarDayRow::dayType)
                .findFirst()
                .orElse(null);
        if (dayType != null) {
            return switch (dayType.trim().toUpperCase()) {
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

    private static LocalTime publishedOff(
            String employeeId,
            LocalDate date,
            List<ShiftSegmentRow> segments) {
        return segments.stream()
                .filter(segment -> employeeId.equals(segment.employeeId()))
                .filter(segment -> date.equals(segment.businessDate()))
                .map(segment -> segment.segmentEnd()
                        .atZone(BUSINESS_ZONE)
                        .toLocalTime())
                .max(LocalTime::compareTo)
                .orElse(null);
    }

    private static LocalTime weekdayTemplateOff(
            String employeeId,
            List<ShiftSegmentRow> segments) {
        return segments.stream()
                .filter(segment -> employeeId.equals(segment.employeeId()))
                .filter(segment -> {
                    DayOfWeek dow = segment.businessDate().getDayOfWeek();
                    return dow != DayOfWeek.SATURDAY
                            && dow != DayOfWeek.SUNDAY;
                })
                .collect(java.util.stream.Collectors.groupingBy(
                        ShiftSegmentRow::businessDate))
                .values()
                .stream()
                .max(Comparator.comparing(
                        group -> group.getFirst().businessDate()))
                .map(group -> group.stream()
                        .map(segment -> segment.segmentEnd()
                                .atZone(BUSINESS_ZONE)
                                .toLocalTime())
                        .max(LocalTime::compareTo)
                        .orElse(null))
                .orElse(null);
    }

    record DaySlice(LocalDate date, TimeInterval interval) {
    }
}

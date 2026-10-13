package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for LeaveHoursRecognizer against the real September 2026
 * calendar exceptions that caused the 唐勇 SZST0026 543.5h anomaly.
 *
 * September 2026 special days (China statutory calendar):
 *   2026-09-19 (Sat) — SATURDAY (rest)
 *   2026-09-20 (Sun) — ADJUSTED_WORKDAY (make-up day for Mid-Autumn holiday)
 *   2026-09-25 (Fri) — PUBLIC_HOLIDAY (Mid-Autumn Festival eve)
 *   2026-09-26 (Sat) — PUBLIC_HOLIDAY (Mid-Autumn Festival)
 *   2026-09-27 (Sun) — PUBLIC_HOLIDAY (Mid-Autumn Festival)
 *
 * Real shift template: 08:30–12:00 + 13:30–18:00 = 270 + 270 = 480 min/day.
 */
class LeaveHoursRecognizerSeptemberCalendarTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String EMP = "emp-1";
    private static final String ORG = "研发部"; // passed as organizationName

    /** Two-segment standard shift: 08:30–12:00 + 13:30–18:00 = 480 min total. */
    private static List<ShiftSegmentRow> shifts(LocalDate date) {
        Instant am0 = date.atTime(8, 30).atZone(SHANGHAI).toInstant();
        Instant am1 = date.atTime(12, 0).atZone(SHANGHAI).toInstant();
        Instant pm0 = date.atTime(13, 30).atZone(SHANGHAI).toInstant();
        Instant pm1 = date.atTime(18, 0).atZone(SHANGHAI).toInstant();
        return List.of(
                new ShiftSegmentRow(EMP, date, "am", am0, am1, am0, am0, am1, am1),
                new ShiftSegmentRow(EMP, date, "pm", pm0, pm1, pm0, pm0, pm1, pm1));
    }

    private static CalendarDayRow cal(LocalDate date, String dayType) {
        return new CalendarDayRow(EMP, date, dayType, 1, 1);
    }

    // ── SICK leave: weekends and holidays must be 0 ───────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"SATURDAY", "SUNDAY", "WEEKEND"})
    void sickLeaveZeroMinutesOnRestDay(String dayType) {
        LocalDate saturday = LocalDate.of(2026, 9, 19);
        Instant start = saturday.atStartOfDay(SHANGHAI).toInstant();
        Instant end   = saturday.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shifts(saturday),
                List.of(cal(saturday, dayType)), ORG);

        assertThat(byDate).doesNotContainKey(saturday);
        assertThat(byDate.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }

    @Test
    void sickLeaveZeroOnPublicHoliday() {
        // 2026-09-25 PUBLIC_HOLIDAY — must produce 0 sick-leave minutes.
        LocalDate holiday = LocalDate.of(2026, 9, 25);
        Instant start = holiday.atStartOfDay(SHANGHAI).toInstant();
        Instant end   = holiday.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shifts(holiday),
                List.of(cal(holiday, "PUBLIC_HOLIDAY")), ORG);

        assertThat(byDate).doesNotContainKey(holiday);
    }

    // ── SICK leave: adjusted/special workday must count full shift ────────────

    @Test
    void sickLeaveFullShiftOnAdjustedWorkday() {
        // 2026-09-20 ADJUSTED_WORKDAY (Sunday make-up): SICK leave must count
        // the full 480-min shift even though it is a calendar Sunday.
        LocalDate adjustedDay = LocalDate.of(2026, 9, 20);
        Instant start = adjustedDay.atStartOfDay(SHANGHAI).toInstant();
        Instant end   = adjustedDay.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shifts(adjustedDay),
                List.of(cal(adjustedDay, "ADJUSTED_WORKDAY")), ORG);

        assertThat(byDate).containsKey(adjustedDay);
        assertThat(byDate.get(adjustedDay)).isEqualTo(480L);
    }

    @Test
    void sickLeaveFullShiftOnSpecialWorkday() {
        LocalDate special = LocalDate.of(2026, 9, 20);
        Instant start = special.atStartOfDay(SHANGHAI).toInstant();
        Instant end   = special.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shifts(special),
                List.of(cal(special, "SPECIAL_WORKDAY")), ORG);

        assertThat(byDate.getOrDefault(special, 0L)).isEqualTo(480L);
    }

    // ── SICK vs MATERNITY: rest-day policy contrast ───────────────────────────

    @Test
    void maternityLeaveCountsPublicHolidayUnlikeSick() {
        // MATERNITY.includesWeekendHours() == true; PUBLIC_HOLIDAY is a rest day
        // but MATERNITY must still count the shift.
        LocalDate holiday = LocalDate.of(2026, 9, 25);
        Instant start = holiday.atStartOfDay(SHANGHAI).toInstant();
        Instant end   = holiday.plusDays(1).atStartOfDay(SHANGHAI).toInstant();

        long sickMinutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.SICK, EMP, shifts(holiday),
                List.of(cal(holiday, "PUBLIC_HOLIDAY")), ORG);
        long maternityMinutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.MATERNITY, EMP, shifts(holiday),
                List.of(cal(holiday, "PUBLIC_HOLIDAY")), ORG);

        assertThat(sickMinutes).isZero();
        assertThat(maternityMinutes).isEqualTo(480L);
    }

    // ── Cross-month SICK leave: Sep portion of 唐勇's second doc ─────────────

    @Test
    void sickLeaveCrossMonthSepPortionExcludesWeekendsAndHolidays() {
        // 唐勇 second doc: 2026-09-17T00:30:00Z = 08:30 CST on Sep 17.
        // Sep 17 (Wed) is a WEEKDAY and the interval starts mid-shift → Sep 17 IS counted.
        // Sep 18–30 apply as before.
        // Calendar:
        //   17 = WEEKDAY  ✓ (interval starts 08:30 CST, within shift)
        //   18,21,22,23,24 = WEEKDAY (5)
        //   19 = SATURDAY ✗
        //   20 = ADJUSTED_WORKDAY ✓
        //   25,26,27 = PUBLIC_HOLIDAY ✗
        //   28,29,30 = WEEKDAY (3)
        // Total counted: 17,18,20,21,22,23,24,28,29,30 = 10 days × 480 = 4800 min
        Instant start = Instant.parse("2026-09-17T00:30:00Z"); // 08:30 CST Sep 17
        Instant end   = Instant.parse("2026-09-30T16:00:00Z"); // 00:00 CST Oct 1

        List<CalendarDayRow> calendar = buildSepCalendar();
        List<ShiftSegmentRow> shiftList = buildSepShifts();

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shiftList, calendar, ORG);

        // Weekends and holidays must not appear
        assertThat(byDate).doesNotContainKey(LocalDate.of(2026, 9, 19));
        assertThat(byDate).doesNotContainKey(LocalDate.of(2026, 9, 25));
        assertThat(byDate).doesNotContainKey(LocalDate.of(2026, 9, 26));
        assertThat(byDate).doesNotContainKey(LocalDate.of(2026, 9, 27));
        // ADJUSTED_WORKDAY must be included
        assertThat(byDate).containsKey(LocalDate.of(2026, 9, 20));
        // Sep 17 (interval starts 08:30 CST = mid-shift) must be included
        assertThat(byDate).containsKey(LocalDate.of(2026, 9, 17));
        // 10 workdays including Sep 17 × 480 = 4800 min (not 4320)
        assertThat(byDate.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4800L);
    }

    @Test
    void sickLeaveCrossMonthOctDatesAreKeyedToOctober() {
        // Verify that Oct dates from the same document are correctly keyed to Oct,
        // not collapsed into Sep. Input starts on Sep 17 08:30 CST.
        Instant start = Instant.parse("2026-09-17T00:30:00Z");
        Instant end   = Instant.parse("2026-10-09T16:00:00Z"); // Oct 10 00:00 CST

        List<CalendarDayRow> calendar = new java.util.ArrayList<>(buildSepCalendar());
        calendar.add(cal(LocalDate.of(2026, 10, 8), "WEEKDAY"));
        calendar.add(cal(LocalDate.of(2026, 10, 9), "WEEKDAY"));

        List<ShiftSegmentRow> shiftList = new java.util.ArrayList<>(buildSepShifts());
        shiftList.addAll(shifts(LocalDate.of(2026, 10, 8)));
        shiftList.addAll(shifts(LocalDate.of(2026, 10, 9)));

        Map<LocalDate, Long> byDate = LeaveHoursRecognizer.recognizedMinutesByDate(
                start, end, LeaveType.SICK, EMP, shiftList, calendar, ORG);

        assertThat(byDate).containsKey(LocalDate.of(2026, 10, 8));
        assertThat(byDate).containsKey(LocalDate.of(2026, 10, 9));
        // Sep 17 is included (interval starts 08:30 CST within shift), not a bug
        assertThat(byDate).containsKey(LocalDate.of(2026, 9, 17));
        // No Oct dates should appear as Sep dates
        assertThat(byDate.keySet().stream()
                .filter(d -> d.getMonthValue() == 10)
                .allMatch(d -> d.getYear() == 2026 && d.getMonthValue() == 10))
                .isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<CalendarDayRow> buildSepCalendar() {
        return List.of(
                cal(LocalDate.of(2026, 9, 18), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 19), "SATURDAY"),
                cal(LocalDate.of(2026, 9, 20), "ADJUSTED_WORKDAY"),
                cal(LocalDate.of(2026, 9, 21), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 22), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 23), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 24), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 25), "PUBLIC_HOLIDAY"),
                cal(LocalDate.of(2026, 9, 26), "PUBLIC_HOLIDAY"),
                cal(LocalDate.of(2026, 9, 27), "PUBLIC_HOLIDAY"),
                cal(LocalDate.of(2026, 9, 28), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 29), "WEEKDAY"),
                cal(LocalDate.of(2026, 9, 30), "WEEKDAY"));
    }

    private static List<ShiftSegmentRow> buildSepShifts() {
        List<ShiftSegmentRow> s = new java.util.ArrayList<>();
        // Sep 17 (interval starts 08:30 CST), plus workdays 18,20(adjusted),21–24,28–30
        for (int d : new int[]{17, 18, 20, 21, 22, 23, 24, 28, 29, 30}) {
            s.addAll(shifts(LocalDate.of(2026, 9, d)));
        }
        return s;
    }
}

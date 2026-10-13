package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.OvertimeMealDeductions.DayLookup;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WeekdayLunchGapTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @ParameterizedTest
    @CsvSource({
            "08:30,21:00,150", "08:30,18:00,0", "08:30,12:00,0",
            "13:00,13:30,0", "12:30,14:00,0", "13:00,21:00,150",
            "18:00,21:00,150", "18:30,21:00,150", "07:30,21:00,210"
    })
    void realSummerWorkSegmentsExcludeTheWholeBoundedLunchGap(String start, String end, long minutes) {
        var segments = SiteShiftTemplates.workSegments("employee", DAY, SiteShiftTemplates.Kind.YANGZHOU);
        assertThat(segments.stream().mapToLong(s -> Duration.between(s.segmentStart(), s.segmentEnd()).toMinutes()).sum())
                .isEqualTo(480L);
        var lookup = OvertimeMealDeductions.lookup("employee", segments,
                List.of(new CalendarDayRow("employee", DAY, "WEEKDAY", 1, 1)));
        assertThat(OvertimeMealDeductions.recognizedMinutes(at(DAY, start), at(DAY, end), lookup))
                .isEqualTo(minutes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADJUSTED_WORKDAY", "SPECIAL_WORKDAY"})
    void publishedWorkdayTypeOnSundayUsesTheActualLunchGap(String dayType) {
        LocalDate sunday = LocalDate.of(2026, 9, 20);
        var lookup = OvertimeMealDeductions.lookup("employee",
                SiteShiftTemplates.workSegments("employee", sunday, SiteShiftTemplates.Kind.YANGZHOU),
                List.of(new CalendarDayRow("employee", sunday, dayType, 1, 1)));
        assertThat(OvertimeMealDeductions.recognizedMinutes(at(sunday, "08:30"), at(sunday, "21:00"), lookup))
                .isEqualTo(150L);
    }

    @ParameterizedTest
    @CsvSource({"2026-10-06,YANGZHOU,08:30,180", "2026-09-29,DALIAN,07:30,240"})
    void WinterAndDalianKeepTheirSixtyMinuteLunchBreak(String date, String site, String start, long expected) {
        LocalDate day = LocalDate.parse(date);
        var lookup = OvertimeMealDeductions.lookup("employee",
                SiteShiftTemplates.workSegments("employee", day, SiteShiftTemplates.Kind.valueOf(site)),
                List.of(new CalendarDayRow("employee", day, "WEEKDAY", 1, 1)));
        assertThat(OvertimeMealDeductions.recognizedMinutes(at(day, start), at(day, "21:00"), lookup))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SATURDAY", "SUNDAY", "PUBLIC_HOLIDAY", "WEEKEND"})
    void restDayLunchPolicyDoesNotBecomeTheSummerWeekdayPolicy(String dayType) {
        // Even if published WORK rows are present, the explicit rest-day type wins.
        var lookup = OvertimeMealDeductions.lookup("employee",
                SiteShiftTemplates.workSegments("employee", DAY, SiteShiftTemplates.Kind.YANGZHOU),
                List.of(new CalendarDayRow("employee", DAY, dayType, 1, 1)));
        assertThat(OvertimeMealDeductions.recognizedMinutes(at(DAY, "13:00"), at(DAY, "13:30"), lookup))
                .isEqualTo(30L);
        assertThat(OvertimeMealDeductions.recognizedMinutes(at(DAY, "08:30"), at(DAY, "21:00"), lookup))
                .isEqualTo(660L);
    }

    @Test
    void morningOnlyShiftDoesNotTreatTheRestOfTheAfternoonAsLunch() {
        var lookup = custom(List.of(interval("08:30", "12:00")));
        assertThat(minutes("12:00", "14:00", lookup)).isEqualTo(60L);
        assertThat(minutes("13:00", "14:00", lookup)).isEqualTo(60L);
    }

    @Test
    void afternoonOnlyOrMissingShiftKeepsTheExistingStandardLunchFallback() {
        assertThat(minutes("12:00", "13:30", custom(List.of(interval("13:30", "18:00")))))
                .isEqualTo(30L);
        assertThat(minutes("12:00", "14:00", custom(List.of()))).isEqualTo(60L);
    }

    @Test
    void unsortedNestedAndDuplicateSegmentsAreMergedBeforeFindingTheGap() {
        var lookup = custom(List.of(interval("13:30", "18:00"), interval("09:00", "10:00"),
                interval("08:30", "12:00"), interval("08:30", "12:00")));
        assertThat(minutes("08:30", "21:00", lookup)).isEqualTo(150L);
    }

    @Test
    void overlappingWorkThroughLunchDoesNotCreateAnArtificialBreak() {
        var lookup = custom(List.of(interval("08:30", "13:15"), interval("11:00", "11:30"),
                interval("13:00", "18:00")));
        assertThat(minutes("08:30", "21:00", lookup)).isEqualTo(150L);
    }

    @Test
    void onlyTheBoundedGapContainingTheWholeNoonWindowIsLunch() {
        var lookup = custom(List.of(interval("08:30", "10:00"), interval("10:30", "12:00"),
                interval("13:30", "18:00")));
        assertThat(minutes("08:30", "21:00", lookup)).isEqualTo(180L);
        assertThat(minutes("10:00", "10:30", lookup)).isEqualTo(30L);
    }

    @Test
    void nextCalendarDayIsNotReclassifiedAndOvernightMinutesStayOnTheStartDate() {
        var lookup = OvertimeMealDeductions.lookup("employee",
                SiteShiftTemplates.workSegments("employee", DAY, SiteShiftTemplates.Kind.YANGZHOU),
                List.of(new CalendarDayRow("employee", DAY, "WEEKDAY", 1, 1)));
        var interval = new TimeInterval(at(DAY, "08:30"), at(DAY.plusDays(1), "01:00"));
        assertThat(OvertimeMealDeductions.minutesOnStartDay(interval, lookup))
                .containsExactly(new OvertimeMealDeductions.DayMinutes(DAY, 390L));
    }

    private static long minutes(String start, String end, DayLookup lookup) {
        return OvertimeMealDeductions.recognizedMinutes(at(DAY, start), at(DAY, end), lookup);
    }

    private static TimeInterval interval(String start, String end) {
        return new TimeInterval(at(DAY, start), at(DAY, end));
    }

    private static DayLookup custom(List<TimeInterval> work) {
        return new DayLookup() {
            @Override public boolean restDay(LocalDate date) { return false; }
            @Override public LocalTime shiftOff(LocalDate date) { return LocalTime.of(18, 0); }
            @Override public List<TimeInterval> workSegments(LocalDate date) { return work; }
        };
    }

    private static Instant at(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(OvertimeMealDeductions.BUSINESS_ZONE).toInstant();
    }
}

package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.OvertimeMealDeductions.DayLookup;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OvertimeMealDeductionsTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime SUMMER_OFF = LocalTime.of(18, 0);
    private static final LocalTime WINTER_OFF = LocalTime.of(17, 30);

    @Test
    void saturdayDaytimeDeductsLunchOnly() {
        assertThat(hours("2026-08-15T08:30:00", "2026-08-15T17:00:00", true, SUMMER_OFF))
                .isEqualTo(450L);
    }

    @Test
    void saturdayEveningDeductsSummerDinnerNotLunch() {
        assertThat(hours("2026-08-15T18:00:00", "2026-08-15T21:00:00", true, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void sundayDinnerWindowItselfIsNotOvertime() {
        assertThat(hours("2026-08-16T18:00:00", "2026-08-16T18:30:00", true, SUMMER_OFF))
                .isZero();
    }

    @Test
    void sundayEveningMealBandAroundShiftOffIsNotOvertime() {
        assertThat(hours("2026-08-16T17:30:00", "2026-08-16T18:30:00", true, SUMMER_OFF))
                .isZero();
    }

    @Test
    void sundayGongYongbiaoFormCappedToLastPunchDropsDinner() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-16T18:00:00"),
                shanghai("2026-08-16T21:00:00"));
        TimeInterval capped = OvertimeMealDeductions.capToSnappedLastPunch(
                snapped, shanghai("2026-08-16T18:41:00"));
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                capped, lookup(true, SUMMER_OFF)))
                .isZero();
    }

    @Test
    void sundayGongYongbiaoPunchesRemoveDinnerFromTheMiddle() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-16T17:27:00"),
                shanghai("2026-08-16T18:41:00"));
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                snapped, lookup(true, SUMMER_OFF)))
                .isEqualTo(60L);
    }

    @Test
    void restDayWithoutWeekdaySegmentsUsesSummerDinnerOff() {
        var days = OvertimeMealDeductions.lookup("emp-1", java.util.List.of(), java.util.List.of());
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                shanghai("2026-08-16T18:00:00"),
                shanghai("2026-08-16T18:30:00"),
                days))
                .isZero();
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                shanghai("2026-08-16T18:00:00"),
                shanghai("2026-08-16T21:00:00"),
                days))
                .isEqualTo(150L);
    }

    @Test
    void summerWeekdayEveningEighteenToTwentyOneIsTwoAndAHalfHours() {
        assertThat(hours("2026-08-17T18:00:00", "2026-08-17T21:00:00", false, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void paperEveningFormWithoutPunchesStillRecognizesTwoAndAHalfHours() {
        assertThat(hours("2026-08-18T18:00:00", "2026-08-18T21:00:00", false, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void summerFormStartingAtHalfPastDoesNotTakeDinner() {
        assertThat(hours("2026-08-17T18:30:00", "2026-08-17T21:00:00", false, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void minutesBelowThirtySnapToTheHourBeforeDuration() {
        assertThat(hours("2026-08-17T18:10:00", "2026-08-17T21:00:00", false, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void minutesFromThirtySnapToHalfPastBeforeDuration() {
        assertThat(hours("2026-08-17T18:45:00", "2026-08-17T21:10:00", false, SUMMER_OFF))
                .isEqualTo(150L);
    }

    @Test
    void degenerateSnapIsZeroHours() {
        assertThat(hours("2026-08-17T18:00:00", "2026-08-17T18:17:00", false, SUMMER_OFF))
                .isZero();
    }

    @Test
    void summerSaturdayNoonToTwentyOneDeductsLunchAndDinner() {
        assertThat(hours("2026-08-15T12:00:00", "2026-08-15T21:00:00", true, SUMMER_OFF))
                .isEqualTo(450L);
    }

    @Test
    void offPunchBeforeOvertimeWindowCapsToZero() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-26T18:30:00"),
                shanghai("2026-08-26T21:00:00"));
        Instant last = OvertimeMealDeductions.lastCoveringOffPunch(
                java.util.List.of(
                        shanghai("2026-08-26T08:22:00"),
                        shanghai("2026-08-26T18:06:00")),
                snapped,
                SUMMER_OFF);
        TimeInterval capped = OvertimeMealDeductions.capToSnappedLastPunch(
                snapped, last);
        assertThat(capped).isNull();
    }

    @Test
    void lastPunchAt2002CapsSummerEveningFormToOneAndAHalfHours() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-26T18:00:00"),
                shanghai("2026-08-26T21:00:00"));
        TimeInterval capped = OvertimeMealDeductions.capToSnappedLastPunch(
                snapped, shanghai("2026-08-26T20:02:00"));
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                capped, lookup(false, SUMMER_OFF)))
                .isEqualTo(90L);
    }

    @Test
    void missingLastPunchCapsToNull() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-26T18:00:00"),
                shanghai("2026-08-26T21:00:00"));
        assertThat(OvertimeMealDeductions.capToSnappedLastPunch(snapped, null))
                .isNull();
    }

    @Test
    void winterWeekdayUsesShiftOffPlusThirtyMinutes() {
        assertThat(hours("2026-08-17T17:30:00", "2026-08-17T21:00:00", false, WINTER_OFF))
                .isEqualTo(180L);
    }

    @Test
    void publicHolidayUsesTheSameNoonWindow() {
        assertThat(hours("2026-08-17T09:00:00", "2026-08-17T17:00:00", true, SUMMER_OFF))
                .isEqualTo(420L);
    }

    @Test
    void weekdayFormCoveringShiftStartDeductsWorkAndDinner() {
        LocalDate day = LocalDate.of(2026, 8, 4);
        DayLookup days = weekdayWithYangzhouSummerWork();
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                shanghai("2026-08-04T08:30:00"),
                shanghai("2026-08-04T21:00:00"),
                days))
                .isEqualTo(150L);
        var slices = OvertimeMealDeductions.minutesByDay(
                OaIntervalGrid.snapInterval(
                        shanghai("2026-08-04T08:30:00"),
                        shanghai("2026-08-04T21:00:00")),
                days);
        assertThat(slices).singleElement().satisfies(slice -> {
            assertThat(slice.date()).isEqualTo(day);
            assertThat(slice.minutes()).isEqualTo(150L);
        });
    }

    @Test
    void weekdayEveningFromHalfPastDoesNotDeductDinnerAgain() {
        assertThat(OvertimeMealDeductions.recognizedMinutes(
                shanghai("2026-08-04T18:30:00"),
                shanghai("2026-08-04T21:00:00"),
                weekdayWithYangzhouSummerWork()))
                .isEqualTo(150L);
    }

    @Test
    void overnightWeekdayDeductsThatDaysWork() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-04T09:00:00"),
                shanghai("2026-08-05T00:30:00"));
        var days = OvertimeMealDeductions.minutesByDay(
                snapped, weekdayWithYangzhouSummerWork());
        assertThat(days).singleElement().satisfies(slice -> {
            assertThat(slice.date()).isEqualTo(LocalDate.of(2026, 8, 4));
            assertThat(slice.minutes()).isEqualTo(
                    OvertimeMealDeductions.recognizedMinutes(
                            snapped, weekdayWithYangzhouSummerWork()));
        });
    }

    @Test
    void saturdayNightIntoSundayStaysOnSaturday() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-08T22:00:00"),
                shanghai("2026-08-09T02:00:00"));
        var days = OvertimeMealDeductions.minutesByDay(snapped, lookup(true, SUMMER_OFF));
        assertThat(days).singleElement().satisfies(slice -> {
            assertThat(slice.date()).isEqualTo(LocalDate.of(2026, 8, 8));
            assertThat(slice.minutes()).isEqualTo(240L);
        });
    }

    @Test
    void overnightWeekdaySplitsDinnerOntoTheFirstCalendarDay() {
        TimeInterval snapped = OaIntervalGrid.snapInterval(
                shanghai("2026-08-17T18:00:00"),
                shanghai("2026-08-18T07:00:00"));
        var days = OvertimeMealDeductions.minutesByDay(snapped, lookup(false, SUMMER_OFF));
        assertThat(days).singleElement().satisfies(slice -> {
            assertThat(slice.date()).isEqualTo(LocalDate.of(2026, 8, 17));
            assertThat(slice.minutes()).isEqualTo(
                    OvertimeMealDeductions.recognizedMinutes(
                            snapped, lookup(false, SUMMER_OFF)));
        });
    }

    private static long hours(
            String start, String end, boolean rest, LocalTime off) {
        return OvertimeMealDeductions.recognizedMinutes(
                shanghai(start), shanghai(end), lookup(rest, off));
    }

    private static DayLookup lookup(boolean rest, LocalTime off) {
        return new DayLookup() {
            @Override
            public boolean restDay(LocalDate date) {
                return rest;
            }

            @Override
            public LocalTime shiftOff(LocalDate date) {
                return off;
            }
        };
    }

    private static DayLookup weekdayWithYangzhouSummerWork() {
        return new DayLookup() {
            @Override
            public boolean restDay(LocalDate date) {
                return false;
            }

            @Override
            public LocalTime shiftOff(LocalDate date) {
                return SUMMER_OFF;
            }

            @Override
            public java.util.List<TimeInterval> workSegments(LocalDate date) {
                return java.util.List.of(
                        new TimeInterval(
                                date.atTime(8, 30).atZone(SHANGHAI).toInstant(),
                                date.atTime(12, 0).atZone(SHANGHAI).toInstant()),
                        new TimeInterval(
                                date.atTime(13, 0).atZone(SHANGHAI).toInstant(),
                                date.atTime(18, 0).atZone(SHANGHAI).toInstant()));
            }
        };
    }

    private static Instant shanghai(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SHANGHAI).toInstant();
    }
}

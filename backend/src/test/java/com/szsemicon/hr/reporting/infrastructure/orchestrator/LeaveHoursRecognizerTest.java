package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.CalendarDayRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeaveHoursRecognizerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String EMPLOYEE = "emp-1";

    @Test
    void marriageLeaveFridayThroughMondayExcludesWeekend() {
        LocalDate friday = LocalDate.of(2026, 1, 9);
        Instant start = friday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = LocalDate.of(2026, 1, 12).atTime(17, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> weekdays = yangzhouWeekdays(
                friday, LocalDate.of(2026, 1, 12));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.MARRIAGE, EMPLOYEE, weekdays, List.of());

        assertThat(minutes).isEqualTo(2 * 8 * 60);
        assertThat(LeaveType.MARRIAGE.includesWeekendHours()).isFalse();
        assertThat(LeaveType.MATERNITY.includesWeekendHours()).isTrue();
    }

    @Test
    void personalLeaveFridayThroughMondayExcludesWeekend() {
        LocalDate friday = LocalDate.of(2026, 1, 9);
        Instant start = friday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = LocalDate.of(2026, 1, 12).atTime(17, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> weekdays = yangzhouWeekdays(
                friday, LocalDate.of(2026, 1, 12));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.PERSONAL, EMPLOYEE, weekdays, List.of());

        assertThat(minutes).isEqualTo(2 * 8 * 60);
    }

    @Test
    void familyPlanningLeaveIsClassifiedAndIncludesWeekend() {
        assertThat(LeaveType.fromLeaveCode("FAMILY_PLANNING_LEAVE"))
                .isEqualTo(LeaveType.FAMILY_PLANNING);
        assertThat(LeaveType.FAMILY_PLANNING.includesWeekendHours()).isTrue();
    }

    @Test
    void leaveWithoutPublishedShiftIsNotGuessed() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.PERSONAL, EMPLOYEE, List.of(), List.of());

        assertThat(minutes).isZero();
    }

    @Test
    void dalianLeaveWithoutPublishedShiftUsesSiteTemplate() {
        LocalDate monday = LocalDate.of(2026, 8, 10);
        Instant start = monday.atTime(7, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(16, 30).atZone(ZONE).toInstant();

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start,
                end,
                LeaveType.PERSONAL,
                EMPLOYEE,
                List.of(),
                List.of(),
                "服务中心-客户现场服务部-大连办事处");

        assertThat(minutes).isEqualTo((long) ((4.5 + 3.5) * 60));
    }

    @Test
    void publishedAfternoon1330To1800IsFourAndAHalfHours() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:30", "18:00"));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(270);
    }

    @Test
    void oaAfternoonClippedByPublished1730IsFourHours() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:00", "17:30"));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(240);
    }

    @Test
    void publishedAfternoon1300To1800KeepsFourAndAHalfHoursForOa1330To1800() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:00", "18:00"));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(270);
    }

    @Test
    void dalianAfternoonLeaveIsThreeAndAHalfHoursFromPublishedTimes() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 0).atZone(ZONE).toInstant();
        Instant end = monday.atTime(16, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = dalianDay(monday);

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(210);
    }

    @Test
    void dalianFullDayLeaveIsEightHoursFromPublishedTimes() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(7, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(16, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = dalianDay(monday);

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(480);
    }

    @Test
    void dalianIsNotRewrittenToShenzhouSummerAfternoon() {
        LocalDate monday = LocalDate.of(2026, 8, 17);
        Instant start = monday.atTime(13, 0).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = dalianDay(monday);

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(210);
    }

    @Test
    void winterAfternoonLeaveStaysFourHoursWhenShiftStopsAt1730() {
        LocalDate monday = LocalDate.of(2026, 1, 12);
        Instant start = monday.atTime(13, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(18, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:00", "17:30"));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.PERSONAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(240);
    }

    @Test
    void dalianMorningAnnualLeaveIsFourAndAHalfHours() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        Instant start = monday.atTime(7, 30).atZone(ZONE).toInstant();
        Instant end = monday.atTime(12, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = dalianDay(monday);

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(270);
    }

    @Test
    void breastfeedingIsOneHourEachWorkdayFromDocumentStartClock() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        Instant start = monday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = LocalDate.of(2026, 8, 9).atTime(9, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = yangzhouWeekdays(
                monday, LocalDate.of(2026, 8, 7));
        List<CalendarDayRow> days = List.of(
                new CalendarDayRow(EMPLOYEE, LocalDate.of(2026, 8, 8), "SATURDAY", 1, 1),
                new CalendarDayRow(EMPLOYEE, LocalDate.of(2026, 8, 9), "SUNDAY", 1, 1));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.BREASTFEEDING, EMPLOYEE, segments, days);

        assertThat(minutes).isEqualTo(5 * 60);
        assertThat(LeaveType.fromLeaveCode("BREASTFEEDING_TIME"))
                .isEqualTo(LeaveType.BREASTFEEDING);
        assertThat(LeaveType.BREASTFEEDING.includesWeekendHours()).isFalse();
    }

    @Test
    void breastfeedingStartClockFollowsTheDocument() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        Instant start = monday.atTime(9, 0).atZone(ZONE).toInstant();
        Instant end = monday.atTime(10, 0).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:00", "17:30"));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.BREASTFEEDING, EMPLOYEE, segments, List.of());

        assertThat(minutes).isEqualTo(60);
    }

    @Test
    void publicHolidayIsExcludedForAnnualLeave() {
        LocalDate holiday = LocalDate.of(2026, 10, 1);
        Instant start = holiday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = holiday.atTime(17, 30).atZone(ZONE).toInstant();
        List<CalendarDayRow> days = List.of(
                new CalendarDayRow(EMPLOYEE, holiday, "PUBLIC_HOLIDAY", 1, 1));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.ANNUAL, EMPLOYEE, List.of(), days);

        assertThat(minutes).isZero();
    }

    @Test
    void publicHolidayIsExcludedForMarriageLeave() {
        LocalDate holiday = LocalDate.of(2026, 10, 1);
        Instant start = holiday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = holiday.atTime(17, 30).atZone(ZONE).toInstant();
        List<CalendarDayRow> days = List.of(
                new CalendarDayRow(EMPLOYEE, holiday, "PUBLIC_HOLIDAY", 1, 1));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.MARRIAGE, EMPLOYEE, List.of(), days);

        assertThat(minutes).isZero();
    }

    @Test
    void marriageLeaveCountsAdjustedWorkday() {
        LocalDate saturday = LocalDate.of(2026, 1, 10);
        Instant start = saturday.atTime(8, 30).atZone(ZONE).toInstant();
        Instant end = saturday.atTime(17, 30).atZone(ZONE).toInstant();
        List<ShiftSegmentRow> segments = List.of(
                segment(saturday, "am", "08:30", "12:00"),
                segment(saturday, "pm", "13:00", "17:30"));
        List<CalendarDayRow> days = List.of(
                new CalendarDayRow(EMPLOYEE, saturday, "ADJUSTED_WORKDAY", 1, 1));

        long minutes = LeaveHoursRecognizer.recognizedMinutes(
                start, end, LeaveType.MARRIAGE, EMPLOYEE, segments, days);

        assertThat(minutes).isEqualTo(8 * 60);
    }

    private static List<ShiftSegmentRow> yangzhouWeekdays(
            LocalDate friday, LocalDate monday) {
        return List.of(
                segment(friday, "am", "08:30", "12:00"),
                segment(friday, "pm", "13:00", "17:30"),
                segment(monday, "am", "08:30", "12:00"),
                segment(monday, "pm", "13:00", "17:30"));
    }

    private static List<ShiftSegmentRow> dalianDay(LocalDate date) {
        return List.of(
                segment(date, "am", "07:30", "12:00"),
                segment(date, "pm", "13:00", "16:30"));
    }

    private static ShiftSegmentRow segment(
            LocalDate date, String id, String start, String end) {
        Instant segmentStart = date.atTime(
                        Integer.parseInt(start.substring(0, 2)),
                        Integer.parseInt(start.substring(3)))
                .atZone(ZONE)
                .toInstant();
        Instant segmentEnd = date.atTime(
                        Integer.parseInt(end.substring(0, 2)),
                        Integer.parseInt(end.substring(3)))
                .atZone(ZONE)
                .toInstant();
        return new ShiftSegmentRow(
                EMPLOYEE,
                date,
                id,
                segmentStart,
                segmentEnd,
                segmentStart.minusSeconds(3600),
                segmentStart.plusSeconds(3600),
                segmentEnd.minusSeconds(3600),
                segmentEnd.plusSeconds(3600),
                "白班");
    }
}

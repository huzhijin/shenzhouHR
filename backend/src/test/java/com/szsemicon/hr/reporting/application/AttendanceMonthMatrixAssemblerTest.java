package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.BadgeCode;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.WorkWindowFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceMonthMatrixAssemblerTest {

    @Test
    void overnightLeavingShowsNextDayPrefixAndDoesNotPaintNextMorning() {
        LocalDate eleventh = OvernightReturnFixtures.AUG_11;
        LocalDate twelfth = OvernightReturnFixtures.AUG_12;
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-17T00:00:00Z"),
                List.of("attendance:v1"),
                List.of(
                        punches(
                                eleventh,
                                OvernightReturnFixtures.jinAug11On().toString(),
                                OvernightReturnFixtures.jinAug12OvernightOff()
                                        .toString(),
                                120,
                                0,
                                0),
                        punches(
                                twelfth,
                                OvernightReturnFixtures.jinAug12On().toString(),
                                OvernightReturnFixtures.shanghai(
                                                twelfth.atTime(18, 18))
                                        .toString(),
                                0,
                                0,
                                0)),
                List.of(oaInterval(
                        "ot-overnight",
                        "OVERTIME",
                        "PAID",
                        "APPROVED",
                        OvernightReturnFixtures.shanghai(eleventh.atTime(18, 0))
                                .toString(),
                        OvernightReturnFixtures.shanghai(twelfth.atTime(0, 30))
                                .toString())),
                List.of(),
                List.of(),
                List.of(
                        window(eleventh, "08:30", "12:00"),
                        window(eleventh, "13:30", "18:00"),
                        window(twelfth, "08:30", "12:00"),
                        window(twelfth, "13:30", "18:00")));
        var days = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows()
                .getFirst()
                .days();
        assertThat(days.get(10).afternoon().text()).isEqualTo("次日 00:14");
        assertThat(days.get(11).morning().text()).isEqualTo("08:24");
        assertThat(days.get(11).morning().text()).doesNotContain("00:14");
        assertThat(days.get(11).morning().tone())
                .isNotEqualTo(BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(days.get(11).afternoon().tone())
                .isNotEqualTo(BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(days.get(10).afternoon().tone())
                .isEqualTo(BadgeCode.RECOGNIZED_OVERTIME.name());
    }

    @Test
    void combinesFormalFactsWithoutCollapsingMultipleStatuses() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 6),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of("attendance:v1", "oa:v2"),
                List.of(
                        daily(
                                "fact-work",
                                LocalDate.of(2026, 6, 1),
                                DayType.WEEKDAY,
                                480,
                                420,
                                60,
                                60,
                                10,
                                10,
                                5,
                                1),
                        daily(
                                "fact-rest",
                                LocalDate.of(2026, 6, 7),
                                DayType.SUNDAY,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0)),
                List.of(
                        oa(
                                "oa-leave",
                                "LEAVE",
                                "PERSONAL_LEAVE",
                                "APPROVED"),
                        oa("oa-trip", "TRIP", null, "MODIFIED"),
                        oa("oa-draft", "OUTING", null, "DRAFT")),
                List.of(
                        new ExceptionFact(
                                "exception-early",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "org-a",
                                "制造中心",
                                LocalDate.of(2026, 6, 1),
                                "EARLY_DEPARTURE",
                                ExceptionSeverity.WARNING,
                                ExceptionState.OPEN,
                                5,
                                "已发布异常事实",
                                "calculation-a"),
                        new ExceptionFact(
                                "exception-resolved",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "org-a",
                                "制造中心",
                                LocalDate.of(2026, 6, 1),
                                "RESOLVED_SOURCE_CONFLICT",
                                ExceptionSeverity.INFO,
                                ExceptionState.RESOLVED,
                                0,
                                "已解决异常不再提示",
                                "calculation-a")),
                List.of());

        var result = AttendanceMonthMatrixAssembler.assemble(snapshot);

        assertThat(result.dates()).hasSize(30);
        assertThat(result.rows()).hasSize(1);
        var firstDay = result.rows().getFirst().days().getFirst();
        assertThat(firstDay.firstPunchAt())
                .isEqualTo(Instant.parse("2026-06-01T00:01:00Z"));
        assertThat(firstDay.lastPunchAt())
                .isEqualTo(Instant.parse("2026-06-01T10:01:00Z"));
        assertThat(firstDay.badges()).containsExactly(
                BadgeCode.LATE,
                BadgeCode.EARLY_DEPARTURE,
                BadgeCode.MISSING_PUNCH,
                BadgeCode.RECOGNIZED_OVERTIME,
                BadgeCode.TRIP,
                BadgeCode.PERSONAL_LEAVE);
        assertThat(result.rows().getFirst().days().get(6).badges())
                .containsExactly(BadgeCode.REST_DAY);
        assertThat(firstDay.badges()).doesNotContain(BadgeCode.OUTING);
    }

    @Test
    void marksLateWhenRawArrivalIsInsidePublishedGrace() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 6),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of("attendance:v1"),
                List.of(daily(
                        "fact-inside-grace",
                        LocalDate.of(2026, 6, 1),
                        DayType.WEEKDAY,
                        480,
                        480,
                        0,
                        0,
                        5,
                        0,
                        0,
                        0)),
                List.of(),
                List.of(new ExceptionFact(
                        "open-late-from-legacy-projection",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "制造中心",
                        LocalDate.of(2026, 6, 1),
                        "LATE",
                        ExceptionSeverity.WARNING,
                        ExceptionState.OPEN,
                        5,
                        "历史投影中的迟到异常",
                        "calculation-a")),
                List.of());

        var firstDay = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().getFirst();

        assertThat(firstDay.badges()).contains(BadgeCode.LATE);
        assertThat(firstDay.morning().text()).contains("迟到");
        assertThat(firstDay.morning().tone()).isEqualTo("LATE");
    }

    @Test
    void appliesThePublishedChinaBusinessZoneToOaDayBoundaries() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 6),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(new OaDocumentFact(
                        "oa-shanghai-boundary",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "制造中心",
                        "OUTING",
                        null,
                        Instant.parse("2026-05-31T16:30:00Z"),
                        Instant.parse("2026-05-31T17:30:00Z"),
                        60,
                        "APPROVED",
                        "oa-source-a")),
                List.of(),
                List.of());

        var result = AttendanceMonthMatrixAssembler.assemble(snapshot);

        assertThat(result.rows().getFirst().days().getFirst().badges())
                .containsExactly(BadgeCode.OUTING);
    }

    @Test
    void hidesMissingPunchAbsenceAndExemptApplicationOnCoveredDays() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 6),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of("oa:v2"),
                List.of(daily(
                        "fact-missing",
                        LocalDate.of(2026, 6, 1),
                        DayType.WEEKDAY,
                        480,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        1)),
                List.of(oa("oa-exempt", "EXEMPT_PUNCH", null, "APPROVED")),
                List.of(new ExceptionFact(
                        "exception-absence",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "制造中心",
                        LocalDate.of(2026, 6, 1),
                        "ABSENCE",
                        ExceptionSeverity.ERROR,
                        ExceptionState.OPEN,
                        480,
                        "旷工",
                        "calculation-a")),
                List.of());

        var firstDay = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().getFirst();

        assertThat(firstDay.badges()).doesNotContain(
                BadgeCode.MISSING_PUNCH,
                BadgeCode.ABSENCE,
                BadgeCode.EXEMPT_PUNCH);
    }

    @Test
    void doesNotKeepStaleMissingPunchOrAbsenceWhenDailyFactIsClean() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 6),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-07-01T00:00:00Z"),
                List.of("attendance:v1"),
                List.of(daily(
                        "fact-complete-pair",
                        LocalDate.of(2026, 6, 1),
                        DayType.WEEKDAY,
                        480,
                        480,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0)),
                List.of(),
                List.of(
                        new ExceptionFact(
                                "stale-missing-punch",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "org-a",
                                "制造中心",
                                LocalDate.of(2026, 6, 1),
                                "MISSING_PUNCH",
                                ExceptionSeverity.WARNING,
                                ExceptionState.OPEN,
                                0,
                                "历史漏刷",
                                "calculation-a"),
                        new ExceptionFact(
                                "stale-absence",
                                "employee-a",
                                "0007",
                                "陈思远",
                                "org-a",
                                "制造中心",
                                LocalDate.of(2026, 6, 1),
                                "ABSENCE",
                                ExceptionSeverity.ERROR,
                                ExceptionState.OPEN,
                                480,
                                "历史旷工",
                                "calculation-a")),
                List.of());

        var firstDay = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().getFirst();

        assertThat(firstDay.badges()).doesNotContain(
                BadgeCode.MISSING_PUNCH,
                BadgeCode.ABSENCE);
    }

    @Test
    void fullDayLeaveWithoutPunchesShowsLeaveNotMissingPunch() {
        var date = LocalDate.of(2026, 7, 15);
        var snapshot = monthSnapshot(
                List.of(new DailyFact(
                        "fact-leave-missing",
                        "company-a",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "org-version-a",
                        "制造中心",
                        date,
                        DayType.WEEKDAY,
                        "扬州总部班次",
                        480,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        480,
                        0,
                        0,
                        1,
                        0,
                        0,
                        0,
                        0,
                        2,
                        null,
                        null,
                        "calculation-a",
                        "digest-a",
                        LeaveType.PERSONAL)),
                List.of());

        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("事假");
        assertThat(day.afternoon().text()).isEqualTo("事假");
        assertThat(day.badges()).contains(BadgeCode.PERSONAL_LEAVE);
        assertThat(day.badges()).doesNotContain(
                BadgeCode.MISSING_PUNCH,
                BadgeCode.ABSENCE);
    }

    @Test
    void paintsMorningLeaveWithoutReplacingAfternoonPunch() {
        var date = LocalDate.of(2026, 7, 7);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-07T00:14:00Z",
                        "2026-07-07T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-morning-leave",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-07T00:30:00Z",
                        "2026-07-07T04:00:00Z")),
                yangzhouWinterWindows(date));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 7));
        assertThat(day.morning().text()).isEqualTo("年假");
        assertThat(day.morning().tone()).isEqualTo("ANNUAL_LEAVE");
        assertThat(day.afternoon().text()).isEqualTo("18:01");
        assertThat(day.merged()).isFalse();
        assertThat(day.hover()).contains("3.5小时");
        assertThat(day.hover()).doesNotContain("4.5小时");
        assertThat(day.morning().text()).doesNotContain("3.5");
        assertThat(day.firstPunchAt())
                .isEqualTo(Instant.parse("2026-07-07T00:14:00Z"));
        assertThat(day.lastPunchAt())
                .isEqualTo(Instant.parse("2026-07-07T10:01:00Z"));
        assertThat(day.badges()).contains(BadgeCode.ANNUAL_LEAVE);
    }

    @Test
    void paintsAfternoonLeaveWithoutReplacingMorningPunch() {
        var date = LocalDate.of(2026, 7, 22);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-22T00:14:00Z",
                        "2026-07-22T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-afternoon-leave",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-22T05:00:00Z",
                        "2026-07-22T09:30:00Z")),
                yangzhouWinterWindows(date));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 22));
        assertThat(day.morning().text()).isEqualTo("08:14");
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.afternoon().tone()).isEqualTo("ANNUAL_LEAVE");
        assertThat(day.merged()).isFalse();
        assertThat(day.hover()).contains("4.5小时");
        assertThat(day.hover()).doesNotContain("3.5小时");
        assertThat(day.hover()).doesNotContain("10.5小时");
    }

    @Test
    void afternoonLeaveHoverDoesNotCountThroughMidnight() {
        var date = LocalDate.of(2026, 7, 22);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-22T00:14:00Z",
                        "2026-07-22T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-afternoon-to-midnight",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-22T05:30:00Z",
                        "2026-07-22T16:00:00Z")),
                yangzhouSummerWindows(date));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 22));
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.hover()).contains("4.5小时");
        assertThat(day.hover()).doesNotContain("10.5小时");
    }

    @Test
    void mergesFullDayAnnualLeaveToOneLabel() {
        var snapshot = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 27),
                        "2026-07-27T00:26:00Z",
                        "2026-07-27T10:02:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-full-annual",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-27T00:30:00Z",
                        "2026-07-27T09:30:00Z")));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 27));
        assertThat(day.morning().text()).isEqualTo("年假");
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.merged()).isTrue();
        assertThat(day.hover()).contains("状态：年假");
        assertThat(day.hover()).doesNotContain("3.5小时");
        assertThat(day.hover()).doesNotContain("4.5小时");
    }

    @Test
    void paintsAfternoonCompensatoryLeaveWithoutReplacingMorningPunch() {
        var date = LocalDate.of(2026, 7, 6);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-05T23:59:00Z",
                        "2026-07-06T10:09:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-afternoon-time-off",
                        "LEAVE",
                        "COMPENSATORY",
                        "APPROVED",
                        "2026-07-06T05:30:00Z",
                        "2026-07-06T10:00:00Z")),
                yangzhouSummerWindows(date));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 6));
        assertThat(day.morning().text()).isEqualTo("07:59");
        assertThat(day.afternoon().text()).isEqualTo("调休");
        assertThat(day.afternoon().tone()).isEqualTo("TIME_OFF");
        assertThat(day.merged()).isFalse();
        assertThat(day.hover()).contains("调休");
        assertThat(day.hover()).contains("4.5小时");
        assertThat(day.hover()).doesNotContain("请假");
        assertThat(day.morning().text()).isNotEqualTo("请假");
        assertThat(day.afternoon().text()).isNotEqualTo("请假");
    }

    @Test
    void projectsContinuousPaternityLeaveWithOwnColor() {
        var snapshot = monthSnapshot(
                List.of(),
                List.of(oaInterval(
                        "oa-paternity",
                        "LEAVE",
                        "PATERNITY_LEAVE",
                        "APPROVED",
                        "2026-07-27T00:30:00Z",
                        "2026-07-31T09:31:00Z")));

        for (int day = 27; day <= 31; day++) {
            var cell = dayOn(snapshot, LocalDate.of(2026, 7, day));
            assertThat(cell.morning().text()).isEqualTo("陪产假");
            assertThat(cell.afternoon().text()).isEqualTo("陪产假");
            assertThat(cell.morning().tone()).isEqualTo("PATERNITY_LEAVE");
            assertThat(cell.merged()).isTrue();
            assertThat(cell.badges()).contains(BadgeCode.PATERNITY_LEAVE);
        }
        assertThat(dayOn(snapshot, LocalDate.of(2026, 7, 26)).merged())
                .isFalse();
        assertThat(AttendanceMonthMatrixAssembler.leaveLabel("PATERNITY_LEAVE"))
                .isEqualTo("陪产假");
        assertThat(AttendanceMonthMatrixAssembler.leaveLabel("MARRIAGE_LEAVE"))
                .isEqualTo("婚假");
        assertThat(AttendanceMonthMatrixAssembler.leaveLabel("BEREAVEMENT_LEAVE"))
                .isEqualTo("丧假");
        assertThat(AttendanceMonthMatrixAssembler.leaveLabel("COMPENSATORY"))
                .isEqualTo("调休");
        assertThat(AttendanceMonthMatrixAssembler.leaveLabel("ANNUAL_LEAVE"))
                .isNotEqualTo("其他假别");
    }

    @Test
    void paintsMarriageAndBereavementWithOwnColors() {
        var snapshot = monthSnapshot(
                List.of(),
                List.of(
                        oaInterval(
                                "oa-marriage",
                                "LEAVE",
                                "MARRIAGE",
                                "APPROVED",
                                "2026-07-01T00:30:00Z",
                                "2026-07-01T09:30:00Z"),
                        oaInterval(
                                "oa-bereavement",
                                "LEAVE",
                                "BEREAVEMENT",
                                "APPROVED",
                                "2026-07-02T00:30:00Z",
                                "2026-07-02T09:30:00Z")));

        var marriage = dayOn(snapshot, LocalDate.of(2026, 7, 1));
        assertThat(marriage.morning().text()).isEqualTo("婚假");
        assertThat(marriage.morning().tone()).isEqualTo("MARRIAGE_LEAVE");
        assertThat(marriage.merged()).isTrue();

        var bereavement = dayOn(snapshot, LocalDate.of(2026, 7, 2));
        assertThat(bereavement.morning().text()).isEqualTo("丧假");
        assertThat(bereavement.morning().tone()).isEqualTo("BEREAVEMENT_LEAVE");
        assertThat(bereavement.morning().tone())
                .isNotEqualTo("ANNUAL_LEAVE")
                .isNotEqualTo("SICK_LEAVE")
                .isNotEqualTo("TIME_OFF")
                .isNotEqualTo("RECOGNIZED_OVERTIME");
    }

    @Test
    void marriageLeaveDoesNotPaintWeekendOrHolidayCells() {
        var snapshot = monthSnapshot(
                List.of(
                        daily(
                                "fact-sat",
                                LocalDate.of(2026, 7, 4),
                                DayType.SATURDAY,
                                0, 0, 0, 0, 0, 0, 0, 0),
                        daily(
                                "fact-sun",
                                LocalDate.of(2026, 7, 5),
                                DayType.SUNDAY,
                                0, 0, 0, 0, 0, 0, 0, 0)),
                List.of(oaInterval(
                        "oa-marriage-span",
                        "LEAVE",
                        "MARRIAGE",
                        "APPROVED",
                        "2026-07-03T00:30:00Z",
                        "2026-07-06T09:30:00Z")));

        var friday = dayOn(snapshot, LocalDate.of(2026, 7, 3));
        assertThat(friday.morning().text()).isEqualTo("婚假");
        assertThat(friday.morning().tone()).isEqualTo("MARRIAGE_LEAVE");

        var saturday = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(saturday.morning().text()).isNotEqualTo("婚假");
        assertThat(saturday.morning().tone()).isEqualTo("REST_DAY");

        var sunday = dayOn(snapshot, LocalDate.of(2026, 7, 5));
        assertThat(sunday.morning().text()).isNotEqualTo("婚假");
        assertThat(sunday.morning().tone()).isEqualTo("REST_DAY");

        var monday = dayOn(snapshot, LocalDate.of(2026, 7, 6));
        assertThat(monday.morning().text()).isEqualTo("婚假");
    }

    @Test
    void paintsOvertimeWhenEffectiveOaDocumentExists() {
        var withDocument = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 1),
                        "2026-07-01T00:26:00Z",
                        "2026-07-01T13:38:00Z",
                        120,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-overtime",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-01T10:00:00Z",
                        "2026-07-01T14:00:00Z")));
        var overtimeDay = dayOn(withDocument, LocalDate.of(2026, 7, 1));
        assertThat(overtimeDay.morning().text()).isEqualTo("08:26");
        assertThat(overtimeDay.afternoon().text()).isEqualTo("21:38");
        assertThat(overtimeDay.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(overtimeDay.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(overtimeDay.merged()).isFalse();
        assertThat(overtimeDay.hover()).contains("加班");

        var lateCheckout = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 1),
                        "2026-07-01T00:26:00Z",
                        "2026-07-01T13:38:00Z",
                        0,
                        0,
                        0)),
                List.of());
        var normalDay = dayOn(lateCheckout, LocalDate.of(2026, 7, 1));
        assertThat(normalDay.afternoon().text()).isEqualTo("21:38");
        assertThat(normalDay.afternoon().tone()).isNull();
        assertThat(normalDay.hover()).doesNotContain("加班");

        var formWithoutMinutes = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 1),
                        "2026-07-01T00:22:00Z",
                        "2026-07-01T10:06:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-overtime-empty",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-01T10:30:00Z",
                        "2026-07-01T13:00:00Z")));
        var painted = dayOn(formWithoutMinutes, LocalDate.of(2026, 7, 1));
        assertThat(painted.afternoon().text()).isEqualTo("18:06");
        assertThat(painted.afternoon().tone()).isNotEqualTo("RECOGNIZED_OVERTIME");
        assertThat(painted.hover()).doesNotContain("加班");
    }

    @Test
    void approvedPaternityBeatsUnknownAnnualOnTheSameDay() {
        var snapshot = monthSnapshot(
                List.of(),
                List.of(
                        oaInterval(
                                "oa-annual-unknown",
                                "LEAVE",
                                "ANNUAL",
                                "UNKNOWN",
                                "2026-07-24T00:30:00Z",
                                "2026-07-24T10:00:00Z"),
                        oaInterval(
                                "oa-paternity",
                                "LEAVE",
                                "PATERNITY",
                                "APPROVED",
                                "2026-07-24T00:30:00Z",
                                "2026-08-04T10:00:00Z")));
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 24));
        assertThat(day.morning().text()).isEqualTo("陪产假");
        assertThat(day.morning().tone()).isEqualTo("PATERNITY_LEAVE");
        assertThat(day.afternoon().text()).isEqualTo("陪产假");
    }

    @Test
    void showsSaturdayPunchTimesAndOvertimeColorWhenFormExists() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T00:30:00Z",
                        "2026-07-04T07:00:00Z")),
                List.of(oaInterval(
                        "oa-saturday-ot",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-04T00:30:00Z",
                        "2026-07-04T07:00:00Z")));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("08:30");
        assertThat(day.afternoon().text()).isEqualTo("15:00");
        assertThat(day.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.hover()).contains("加班");
    }

    @Test
    void marksSaturdayOvertimeFormWithoutPunches() {
        var snapshot = monthSnapshot(
                List.of(restPunches(LocalDate.of(2026, 7, 4), null, null)),
                List.of(oaInterval(
                        "oa-saturday-ot-empty",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-04T00:30:00Z",
                        "2026-07-04T07:00:00Z")));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("加班");
        assertThat(day.afternoon().text()).isEqualTo("加班");
        assertThat(day.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.merged()).isTrue();
        assertThat(day.hover()).contains("加班");
        assertThat(day.hover()).doesNotContain("漏刷");
    }

    @Test
    void wuhanWeekendOvertimeWithoutPunchesShowsOvertimeNotMissedPunch() {
        var date = LocalDate.of(2026, 7, 4);
        var snapshot = monthSnapshot(
                List.of(restPunches(date, null, null, 300)),
                List.of());
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("加班");
        assertThat(day.afternoon().text()).isEqualTo("加班");
        assertThat(day.merged()).isTrue();
        assertThat(day.afternoon().text()).doesNotContain("漏刷");
        assertThat(day.badges()).contains(BadgeCode.RECOGNIZED_OVERTIME);
        assertThat(day.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
    }

    @Test
    void marksSaturdayOvertimeOffDutyMissingWhenOnlyMorningPunch() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T00:30:00Z",
                        "2026-07-04T00:30:00Z")),
                List.of(oaInterval(
                        "oa-saturday-ot-on-only",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-04T00:30:00Z",
                        "2026-07-04T07:00:00Z")));

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("08:30");
        assertThat(day.afternoon().text()).isEqualTo("漏刷");
        assertThat(day.afternoon().tone()).isEqualTo("MISSING_PUNCH");
    }

    @Test
    void showsPreNoonOffDutyOnRestDayOvertimeInsteadOfMissedPunch() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T00:17:00Z",
                        "2026-07-04T03:36:00Z")),
                List.of(oaInterval(
                        "oa-saturday-morning-ot",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-04T00:17:00Z",
                        "2026-07-04T03:36:00Z")));
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("08:17");
        assertThat(day.afternoon().text()).isEqualTo("11:36");
        assertThat(day.afternoon().text()).isNotEqualTo("漏刷");
        assertThat(day.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.hover()).doesNotContain("下班：漏刷");
    }

    @Test
    void keepsMissedOffDutyWhenRestDayOvertimeHasOnlyOnePunch() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T00:17:00Z",
                        "2026-07-04T00:17:00Z")),
                List.of(oaInterval(
                        "oa-saturday-one-punch",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-04T00:17:00Z",
                        "2026-07-04T03:36:00Z")));
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("08:17");
        assertThat(day.afternoon().text()).isEqualTo("漏刷");
    }

    @Test
    void breastfeedingPaintsOnlyTheHourSlotNotTheWholeDay() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-01T00:21:00Z",
                        "2026-07-01T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-breast",
                        "LEAVE",
                        "BREASTFEEDING_TIME",
                        "APPROVED",
                        "2026-07-01T00:30:00Z",
                        "2026-07-31T01:30:00Z")));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("哺乳假");
        assertThat(day.afternoon().text()).isNotEqualTo("哺乳假");
        assertThat(day.afternoon().text()).isEqualTo("18:01");
    }

    @Test
    void makeupAtShiftStartIsCorrectionWithoutLate() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-01T00:30:00Z",
                        "2026-07-01T10:01:00Z",
                        0,
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-makeup-on",
                        "PUNCH_CORRECTION",
                        null,
                        "APPROVED",
                        "2026-07-01T00:30:00Z",
                        "2026-07-01T00:30:00.000000001Z")),
                yangzhouWinterWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("补签08:30");
        assertThat(day.morning().tone()).isEqualTo("PUNCH_CORRECTION");
        assertThat(day.morning().text()).doesNotContain("迟到");
        assertThat(day.badges()).contains(BadgeCode.PUNCH_CORRECTION);
        assertThat(day.badges()).doesNotContain(BadgeCode.LATE);
    }

    @Test
    void showsSaturdayPunchesWithoutOvertimeColorWhenNoForm() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T00:26:00Z",
                        "2026-07-04T13:38:00Z")),
                List.of());

        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("08:26");
        assertThat(day.afternoon().text()).isEqualTo("21:38");
        assertThat(day.afternoon().tone()).isEqualTo("REST_DAY");
        assertThat(day.hover()).doesNotContain("加班");
    }

    @Test
    void restDayWithoutWorkShiftIsNotMissedPunch() {
        var snapshot = monthSnapshot(
                List.of(new DailyFact(
                        "fact-saturday-empty",
                        "company-a",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "org-version-a",
                        "制造中心",
                        LocalDate.of(2026, 7, 4),
                        DayType.SATURDAY,
                        "无班次",
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        2,
                        null,
                        null,
                        "calculation-a",
                        "digest-a")),
                List.of());
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isNotEqualTo("漏刷");
        assertThat(day.afternoon().text()).isNotEqualTo("漏刷");
        assertThat(day.morning().tone()).isEqualTo("REST_DAY");
        assertThat(day.afternoon().tone()).isEqualTo("REST_DAY");
    }

    @Test
    void labelsMissingPunchCorrectionAndIgnoresDraftDocuments() {
        var missing = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 3),
                        null,
                        null,
                        0,
                        0,
                        2)),
                List.of());
        var missingDay = dayOn(missing, LocalDate.of(2026, 7, 3));
        assertThat(missingDay.morning().text()).isEqualTo("漏刷");
        assertThat(missingDay.afternoon().text()).isEqualTo("漏刷");
        assertThat(missingDay.morning().tone()).isEqualTo("MISSING_PUNCH");

        var twoMorningSwipes = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 6),
                        "2026-07-06T00:13:00Z",
                        "2026-07-06T00:24:00Z",
                        0,
                        0,
                        1)),
                List.of());
        var twoMorningDay = dayOn(twoMorningSwipes, LocalDate.of(2026, 7, 6));
        assertThat(twoMorningDay.morning().text()).isEqualTo("08:13");
        assertThat(twoMorningDay.afternoon().text()).isEqualTo("漏刷");
        assertThat(twoMorningDay.afternoon().tone()).isEqualTo("MISSING_PUNCH");

        var correction = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 14),
                        "2026-07-14T00:21:00Z",
                        "2026-07-14T08:30:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-correction",
                        "PUNCH_CORRECTION",
                        null,
                        "APPROVED",
                        "2026-07-14T08:30:00Z",
                        "2026-07-14T08:31:00Z")));
        var correctionDay = dayOn(correction, LocalDate.of(2026, 7, 14));
        assertThat(correctionDay.afternoon().text()).contains("补签");
        assertThat(correctionDay.afternoon().text()).contains("16:30");
        assertThat(correctionDay.afternoon().tone()).isEqualTo("PUNCH_CORRECTION");
        assertThat(correctionDay.hover()).contains("补签");

        var stacked = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 15),
                        "2026-07-15T00:21:00Z",
                        "2026-07-15T08:00:00Z",
                        0,
                        0,
                        0)),
                List.of(
                        oaInterval(
                                "oa-outing",
                                "OUTING",
                                null,
                                "APPROVED",
                                "2026-07-15T05:00:00Z",
                                "2026-07-15T09:30:00Z"),
                        oaInterval(
                                "oa-correction-outing",
                                "PUNCH_CORRECTION",
                                null,
                                "APPROVED",
                                "2026-07-15T08:00:00Z",
                                "2026-07-15T08:01:00Z")));
        var stackedDay = dayOn(stacked, LocalDate.of(2026, 7, 15));
        assertThat(stackedDay.afternoon().text()).contains("补签");
        assertThat(stackedDay.afternoon().text()).contains("外出");
        assertThat(stackedDay.afternoon().tone()).isEqualTo("OUTING");

        var draft = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 8),
                        "2026-07-08T00:26:00Z",
                        "2026-07-08T10:03:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-draft-leave",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "DRAFT",
                        "2026-07-08T00:30:00Z",
                        "2026-07-08T09:30:00Z")));
        var draftDay = dayOn(draft, LocalDate.of(2026, 7, 8));
        assertThat(draftDay.morning().text()).isEqualTo("08:26");
        assertThat(draftDay.afternoon().text()).isEqualTo("18:03");
        assertThat(draftDay.merged()).isFalse();
    }

    @Test
    void paintsOvertimeFromRecognizedMinutesEvenWithoutSnapshotOvertimeDocument() {
        var saturday = LocalDate.of(2026, 8, 1);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-02T00:00:00Z"),
                List.of("attendance:v1"),
                List.of(new DailyFact(
                        "fact-saturday-ot",
                        "company-a",
                        "employee-a",
                        "SZST0040",
                        "姚芳伟",
                        "org-a",
                        "org-version-a",
                        "工程一部",
                        saturday,
                        DayType.SATURDAY,
                        "休息",
                        0,
                        0,
                        480,
                        0,
                        0,
                        480,
                        0,
                        1,
                        0,
                        0,
                        0,
                        0,
                        Instant.parse("2026-08-01T00:23:00Z"),
                        Instant.parse("2026-08-01T08:32:00Z"),
                        "calculation-a",
                        "digest-a")),
                List.of(),
                List.of(),
                List.of());

        var day = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().getFirst();
        assertThat(day.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(day.morning().text()).isEqualTo("08:23");
        assertThat(day.afternoon().text()).isEqualTo("16:32");
    }

    @Test
    void paintsOaOnlyEmployeeWeekdaysAsMissedAndSaturdayOvertime() {
        var saturday = LocalDate.of(2026, 8, 1);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-10T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(oaInterval(
                        "oa-huoyan-ot",
                        "OVERTIME",
                        "PAID",
                        "APPROVED",
                        "2026-08-01T00:00:00Z",
                        "2026-08-01T08:00:00Z")),
                List.of(),
                List.of());

        var rows = AttendanceMonthMatrixAssembler.assemble(snapshot).rows();
        assertThat(rows).hasSize(1);
        var monday = rows.getFirst().days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 3)))
                .findFirst()
                .orElseThrow();
        assertThat(monday.morning().text()).isEqualTo("漏刷");
        assertThat(monday.badges()).contains(BadgeCode.MISSING_PUNCH);
        var futureWeekday = rows.getFirst().days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 26)))
                .findFirst()
                .orElseThrow();
        assertThat(futureWeekday.morning().text()).isEmpty();
        assertThat(futureWeekday.badges()).isEmpty();
        var otDay = rows.getFirst().days().getFirst();
        assertThat(otDay.date()).isEqualTo(saturday);
        assertThat(otDay.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
    }

    @Test
    void dalianOaOnlyWeekdaysAreFullAttendanceNotMissedPunch() {
        var saturday = LocalDate.of(2026, 8, 1);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-10T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(new OaDocumentFact(
                        "oa-huoyan-ot",
                        "employee-a",
                        "SZST0445",
                        "霍岩",
                        "org-a",
                        "客户现场服务部-大连办事处",
                        "OVERTIME",
                        "PAID",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-01T08:00:00Z"),
                        60,
                        "APPROVED",
                        "oa-source-a")),
                List.of(),
                List.of());

        var monday = AttendanceMonthMatrixAssembler.assemble(snapshot).rows()
                .getFirst().days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 3)))
                .findFirst()
                .orElseThrow();
        assertThat(monday.morning().text()).isNotEqualTo("漏刷");
        assertThat(monday.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
        var saturdayCell = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().stream()
                .filter(day -> day.date().equals(saturday))
                .findFirst()
                .orElseThrow();
        assertThat(saturdayCell.morning().tone())
                .isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(saturdayCell.afternoon().tone())
                .isEqualTo("RECOGNIZED_OVERTIME");
    }

    @Test
    void dalianAugustLeaveAndOvertimeKeepDocumentColors() {
        var saturday = LocalDate.of(2026, 8, 1);
        var monday = LocalDate.of(2026, 8, 3);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-10T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(
                        new OaDocumentFact(
                                "oa-huoyan-ot",
                                "employee-a",
                                "SZST0445",
                                "霍岩",
                                "org-a",
                                "客户现场服务部-大连办事处",
                                "OVERTIME",
                                "PAID",
                                Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-01T08:00:00Z"),
                                60,
                                "APPROVED",
                                "oa-source-a"),
                        new OaDocumentFact(
                                "oa-dalian-leave",
                                "employee-a",
                                "SZST0445",
                                "霍岩",
                                "org-a",
                                "客户现场服务部-大连办事处",
                                "LEAVE",
                                "ANNUAL_LEAVE",
                                Instant.parse("2026-08-02T16:00:00Z"),
                                Instant.parse("2026-08-03T16:00:00Z"),
                                480,
                                "APPROVED",
                                "oa-source-a")),
                List.of(),
                List.of());

        var days = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days();
        var otDay = days.stream()
                .filter(day -> day.date().equals(saturday))
                .findFirst()
                .orElseThrow();
        assertThat(otDay.morning().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        assertThat(otDay.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
        var leaveDay = days.stream()
                .filter(day -> day.date().equals(monday))
                .findFirst()
                .orElseThrow();
        assertThat(leaveDay.morning().text()).isEqualTo("年假");
        assertThat(leaveDay.morning().tone()).isEqualTo("ANNUAL_LEAVE");
        assertThat(leaveDay.afternoon().tone()).isEqualTo("ANNUAL_LEAVE");
        assertThat(leaveDay.morning().text()).doesNotContain("漏刷");
        var tuesday = days.stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 8, 4)))
                .findFirst()
                .orElseThrow();
        assertThat(tuesday.morning().text()).isNotEqualTo("漏刷");
        assertThat(tuesday.morning().tone()).isNotEqualTo("ANNUAL_LEAVE");
    }

    @Test
    void dalianLeaveOutsideYangzhouWindowsStillPaintsLeave() {
        var date = LocalDate.of(2026, 8, 3);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-10T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(new OaDocumentFact(
                        "oa-dalian-early-leave",
                        "employee-a",
                        "SZST0445",
                        "霍岩",
                        "org-a",
                        "客户现场服务部-大连办事处",
                        "LEAVE",
                        "PERSONAL_LEAVE",
                        Instant.parse("2026-08-02T23:30:00Z"),
                        Instant.parse("2026-08-03T00:20:00Z"),
                        50,
                        "APPROVED",
                        "oa-source-a")),
                List.of(),
                List.of());
        var day = AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows().getFirst().days().stream()
                .filter(cell -> cell.date().equals(date))
                .findFirst()
                .orElseThrow();
        assertThat(day.morning().text()).isEqualTo("事假");
        assertThat(day.morning().tone()).isEqualTo("PERSONAL_LEAVE");
        assertThat(day.afternoon().tone()).isEqualTo("PERSONAL_LEAVE");
    }

    @Test
    void septemberDalianOaOnlyWeekdaysAreMissedPunch() {
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 9),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-09-10T00:00:00Z"),
                List.of("oa:v2"),
                List.of(),
                List.of(new OaDocumentFact(
                        "oa-huoyan-ot",
                        "employee-a",
                        "SZST0445",
                        "霍岩",
                        "org-a",
                        "客户现场服务部-大连办事处",
                        "OVERTIME",
                        "PAID",
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-01T08:00:00Z"),
                        60,
                        "APPROVED",
                        "oa-source-a")),
                List.of(),
                List.of());

        var weekday = AttendanceMonthMatrixAssembler.assemble(snapshot).rows()
                .getFirst().days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 9, 2)))
                .findFirst()
                .orElseThrow();
        assertThat(weekday.morning().text()).isEqualTo("漏刷");
        assertThat(weekday.badges()).contains(BadgeCode.MISSING_PUNCH);
    }

    @Test
    void keepsLegacyPunchInstantsAndBadgesOnTheSameDay() {
        var snapshot = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 2),
                        "2026-07-02T00:32:00Z",
                        "2026-07-02T10:29:00Z",
                        0,
                        6,
                        0)),
                List.of());
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 2));
        assertThat(day.firstPunchAt())
                .isEqualTo(Instant.parse("2026-07-02T00:32:00Z"));
        assertThat(day.lastPunchAt())
                .isEqualTo(Instant.parse("2026-07-02T10:29:00Z"));
        assertThat(day.badges()).contains(BadgeCode.LATE);
        assertThat(day.morning().text()).isEqualTo("08:32 迟到");
    }

    @Test
    void doesNotMarkLateWhenPunchIsOneSecondBeforeShiftStart() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-01T00:29:59Z",
                        "2026-07-01T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(),
                yangzhouWinterWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("08:29");
        assertThat(day.morning().tone()).isNotEqualTo("LATE");
        assertThat(day.badges()).doesNotContain(BadgeCode.LATE);
    }

    @Test
    void marksExactShiftStartAsLate() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-01T00:30:00Z",
                        "2026-07-01T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(),
                yangzhouWinterWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("08:30 迟到");
        assertThat(day.morning().tone()).isEqualTo("LATE");
    }

    @Test
    void keepsLateColorWhenAfternoonHasOvertime() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-01T00:32:00Z",
                        "2026-07-01T13:38:00Z",
                        0,
                        2,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-ot",
                        "OVERTIME",
                        null,
                        "APPROVED",
                        "2026-07-01T10:00:00Z",
                        "2026-07-01T14:00:00Z")));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("08:32 迟到");
        assertThat(day.morning().tone()).isEqualTo("LATE");
        assertThat(day.afternoon().text()).isEqualTo("21:38");
        assertThat(day.afternoon().tone()).isEqualTo("RECOGNIZED_OVERTIME");
    }

    @Test
    void showsBothRestDayAfternoonPunches() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T07:00:00Z",
                        "2026-07-04T10:00:00Z")),
                List.of());
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("15:00");
        assertThat(day.afternoon().text()).isEqualTo("18:00");
    }

    @Test
    void showsBothRestDayMorningPunches() {
        var snapshot = monthSnapshot(
                List.of(restPunches(
                        LocalDate.of(2026, 7, 4),
                        "2026-07-04T02:00:00Z",
                        "2026-07-04T04:00:00Z")),
                List.of());
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 4));
        assertThat(day.morning().text()).isEqualTo("10:00");
        assertThat(day.afternoon().text()).isEqualTo("12:00");
    }

    @Test
    void summerAfternoonAnnualLeaveHoverIsFourAndAHalfNotFive() {
        var date = LocalDate.of(2026, 7, 22);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-22T00:14:00Z",
                        "2026-07-22T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-summer-afternoon",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-22T05:00:00Z",
                        "2026-07-22T10:00:00Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.afternoon().text()).doesNotContain("4.5");
        assertThat(day.hover()).contains("4.5小时");
        assertThat(day.hover()).doesNotContain("年假 5小时");
        assertThat(day.hover()).doesNotContain("\n5小时");
    }

    @Test
    void dalianAfternoonAnnualLeaveHoverIsThreeAndAHalf() {
        var date = LocalDate.of(2026, 7, 22);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-22T00:14:00Z",
                        "2026-07-22T08:30:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-dalian-afternoon",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-22T05:00:00Z",
                        "2026-07-22T08:30:00Z")),
                dalianWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.hover()).contains("3.5小时");
        assertThat(day.hover()).doesNotContain("4.5小时");
    }

    @Test
    void hireDayWithoutMorningPunchDisplays0829InsteadOfMissedPunch() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(hireDayFact(
                        date,
                        null,
                        "2026-07-01T10:00:00Z",
                        2)),
                List.of(),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("08:29");
        assertThat(day.morning().text()).doesNotContain("漏刷");
        assertThat(day.morning().tone()).isNotEqualTo(BadgeCode.MISSING_PUNCH.name());
    }

    @Test
    void hireDayWithRealMorningPunchKeepsTheClock() {
        var date = LocalDate.of(2026, 7, 1);
        var snapshot = monthSnapshot(
                List.of(hireDayFact(
                        date,
                        "2026-07-01T00:41:00Z",
                        "2026-07-01T10:01:00Z",
                        0)),
                List.of(),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).contains("08:41");
        assertThat(day.morning().text()).doesNotContain("08:29");
    }

    @Test
    void leaveDayWithoutOffDutyPunchDisplaysAfterShiftEndNotMissedPunch() {
        var date = LocalDate.of(2026, 7, 10);
        var snapshot = monthSnapshot(
                List.of(leaveDayFact(
                        date,
                        "2026-07-10T00:30:00Z",
                        null,
                        1)),
                List.of(),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.afternoon().text()).isEqualTo("18:01");
        assertThat(day.afternoon().text()).doesNotContain("漏刷");
        assertThat(day.afternoon().tone()).isNotEqualTo(BadgeCode.MISSING_PUNCH.name());
        assertThat(day.morning().text()).doesNotContain("漏刷");
        assertThat(day.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
    }

    @Test
    void leaveDayWithRealOffDutyPunchKeepsTheClock() {
        var date = LocalDate.of(2026, 7, 10);
        var snapshot = monthSnapshot(
                List.of(leaveDayFact(
                        date,
                        "2026-07-10T00:30:00Z",
                        "2026-07-10T09:05:00Z",
                        0)),
                List.of(),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.afternoon().text()).isEqualTo("17:05");
        assertThat(day.afternoon().text()).doesNotContain("18:01");
    }

    @Test
    void outingPlusOvertimeShowsOutingThenOvertimeNotMissedPunch() {
        var date = LocalDate.of(2026, 7, 8);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-08T00:21:00Z",
                        "2026-07-08T10:00:00Z",
                        120,
                        0,
                        0)),
                List.of(
                        oaInterval(
                                "oa-outing",
                                "OUTING",
                                null,
                                "APPROVED",
                                "2026-07-08T00:00:00Z",
                                "2026-07-08T10:00:00Z"),
                        oaInterval(
                                "oa-ot",
                                "OVERTIME",
                                "PAID",
                                "APPROVED",
                                "2026-07-08T10:00:00Z",
                                "2026-07-08T12:00:00Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("18:00");
        assertThat(day.afternoon().text()).isEqualTo("20:00");
        assertThat(day.morning().tone()).isEqualTo(
                BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(day.afternoon().tone()).isEqualTo(
                BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(day.afternoon().text()).doesNotContain("漏刷");
        assertThat(day.hover()).contains("加班");
    }

    @Test
    void outingEveningOvertimeWithoutPunchesShowsOutingThenOvertime() {
        var date = LocalDate.of(2026, 7, 28);
        var snapshot = monthSnapshot(
                List.of(punches(date, null, null, 240, 0, 0)),
                List.of(
                        oaInterval(
                                "oa-outing-all-day",
                                "OUTING",
                                null,
                                "APPROVED",
                                "2026-07-27T16:00:00Z",
                                "2026-07-28T16:00:00Z"),
                        oaInterval(
                                "oa-evening-ot",
                                "OVERTIME",
                                "PAID",
                                "APPROVED",
                                "2026-07-28T10:30:00Z",
                                "2026-07-28T14:30:00Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("18:30");
        assertThat(day.afternoon().text()).isEqualTo("22:30");
        assertThat(day.afternoon().tone()).isEqualTo(
                BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(day.morning().text()).doesNotContain("漏刷");
        assertThat(day.afternoon().text()).doesNotContain("漏刷");
        assertThat(day.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
    }

    @Test
    void weekendOutingPlusOvertimeShowsOvertimeClocks() {
        var saturday = LocalDate.of(2026, 7, 4);
        var snapshot = monthSnapshot(
                List.of(),
                List.of(
                        oaInterval(
                                "oa-outing",
                                "OUTING",
                                null,
                                "APPROVED",
                                "2026-07-03T16:00:00Z",
                                "2026-07-04T16:00:00Z"),
                        oaInterval(
                                "oa-ot",
                                "OVERTIME",
                                "PAID",
                                "APPROVED",
                                "2026-07-04T01:00:00Z",
                                "2026-07-04T10:00:00Z")),
                List.of());
        var day = dayOn(snapshot, saturday);
        assertThat(day.morning().text()).isEqualTo("09:00");
        assertThat(day.afternoon().text()).isEqualTo("18:00");
        assertThat(day.morning().tone()).isEqualTo(
                BadgeCode.RECOGNIZED_OVERTIME.name());
        assertThat(day.afternoon().tone()).isNotEqualTo("REST_DAY");
    }

    @Test
    void pendingOvernightOutingCoversBothDaysWithoutMissedPunch() {
        LocalDate nineteenth = LocalDate.of(2026, 8, 19);
        LocalDate twentieth = LocalDate.of(2026, 8, 20);
        var snapshot = new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 8),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-09-01T00:00:00Z"),
                List.of("attendance:v1", "oa:v2"),
                List.of(
                        punches(nineteenth, null, null, 0, 0, 2),
                        punches(twentieth, null, null, 0, 0, 2)),
                List.of(oaInterval(
                        "oa-ju-jun-outing",
                        "OUTING",
                        null,
                        "UNKNOWN",
                        "2026-08-19T00:30:00Z",
                        "2026-08-20T10:00:00Z")),
                List.of(),
                List.of(),
                concatWindows(
                        yangzhouSummerWindows(nineteenth),
                        yangzhouSummerWindows(twentieth)));
        var rows = AttendanceMonthMatrixAssembler.assemble(snapshot).rows();
        var days = rows.getFirst().days();
        var day19 = days.get(18);
        var day20 = days.get(19);
        assertThat(day19.morning().text()).isEqualTo("外出");
        assertThat(day19.afternoon().text()).isEqualTo("外出");
        assertThat(day20.morning().text()).isEqualTo("外出");
        assertThat(day20.afternoon().text()).isEqualTo("外出");
        assertThat(day19.morning().text()).doesNotContain("漏刷");
        assertThat(day20.afternoon().text()).doesNotContain("漏刷");
        assertThat(day19.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
        assertThat(day20.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
    }

    @Test
    void makeupOnDutyShowsCorrectionAndKeepsOffDutyPunch() {
        var date = LocalDate.of(2026, 7, 25);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-25T00:30:00Z",
                        "2026-07-25T10:12:00Z",
                        0,
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-makeup-on",
                        "PUNCH_CORRECTION",
                        null,
                        "APPROVED",
                        "2026-07-25T00:30:00Z",
                        "2026-07-25T00:30:00.000000001Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("补签08:30");
        assertThat(day.morning().tone()).isEqualTo("PUNCH_CORRECTION");
        assertThat(day.afternoon().text()).isEqualTo("18:12");
        assertThat(day.morning().text()).doesNotContain("迟到");
        assertThat(day.badges()).contains(BadgeCode.PUNCH_CORRECTION);
        assertThat(day.badges()).doesNotContain(BadgeCode.LATE);
    }

    @Test
    void makeupOffDutyShowsCorrectionAndKeepsOnDutyPunch() {
        var date = LocalDate.of(2026, 7, 21);
        var snapshot = monthSnapshot(
                List.of(punches(
                        date,
                        "2026-07-21T00:28:00Z",
                        "2026-07-21T10:00:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-makeup-off",
                        "PUNCH_CORRECTION",
                        null,
                        "APPROVED",
                        "2026-07-21T10:00:00Z",
                        "2026-07-21T10:00:00.000000001Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("08:28");
        assertThat(day.afternoon().text()).contains("补签");
        assertThat(day.afternoon().text()).contains("18:00");
        assertThat(day.afternoon().tone()).isEqualTo("PUNCH_CORRECTION");
    }

    @Test
    void paperOutingWithoutPunchesShowsOutingNotMissedPunch() {
        var date = LocalDate.of(2026, 7, 12);
        var snapshot = monthSnapshot(
                List.of(punches(date, null, null, 0, 0, 2)),
                List.of(oaInterval(
                        "oa-paper-outing",
                        "OUTING",
                        null,
                        "APPROVED",
                        "2026-07-12T00:00:00Z",
                        "2026-07-12T10:00:00Z")),
                yangzhouSummerWindows(date));
        var day = dayOn(snapshot, date);
        assertThat(day.morning().text()).isEqualTo("外出");
        assertThat(day.afternoon().text()).isEqualTo("外出");
        assertThat(day.morning().text()).doesNotContain("漏刷");
        assertThat(day.badges()).doesNotContain(BadgeCode.MISSING_PUNCH);
        assertThat(day.badges()).doesNotContain(BadgeCode.ABSENCE);
    }

    @Test
    void omitsHoverHoursWhenWorkWindowsAreMissing() {
        var snapshot = monthSnapshot(
                List.of(punches(
                        LocalDate.of(2026, 7, 22),
                        "2026-07-22T00:14:00Z",
                        "2026-07-22T10:01:00Z",
                        0,
                        0,
                        0)),
                List.of(oaInterval(
                        "oa-afternoon-leave",
                        "LEAVE",
                        "ANNUAL_LEAVE",
                        "APPROVED",
                        "2026-07-22T05:00:00Z",
                        "2026-07-22T10:00:00Z")));
        var day = dayOn(snapshot, LocalDate.of(2026, 7, 22));
        assertThat(day.afternoon().text()).isEqualTo("年假");
        assertThat(day.hover()).doesNotContain("5小时");
        assertThat(day.hover()).doesNotContain("4.5小时");
    }

    private static AttendanceMonthMatrixPage.DayCell dayOn(
            ReportSourceSnapshot snapshot, LocalDate date) {
        return AttendanceMonthMatrixAssembler.assemble(snapshot)
                .rows()
                .getFirst()
                .days()
                .get(date.getDayOfMonth() - 1);
    }

    private static ReportSourceSnapshot monthSnapshot(
            List<DailyFact> dailyFacts,
            List<OaDocumentFact> oaFacts,
            List<WorkWindowFact> workWindows) {
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        "company-a",
                        null,
                        null,
                        null),
                "projection-a",
                "OPEN",
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of("attendance:v1", "oa:v2"),
                dailyFacts,
                oaFacts,
                List.of(),
                List.of(),
                workWindows);
    }

    private static ReportSourceSnapshot monthSnapshot(
            List<DailyFact> dailyFacts,
            List<OaDocumentFact> oaFacts) {
        return monthSnapshot(dailyFacts, oaFacts, List.of());
    }

    private static List<WorkWindowFact> yangzhouWinterWindows(LocalDate date) {
        return List.of(
                window(date, "08:30", "12:00"),
                window(date, "13:00", "17:30"));
    }

    private static List<WorkWindowFact> yangzhouSummerWindows(LocalDate date) {
        return List.of(
                window(date, "08:30", "12:00"),
                window(date, "13:30", "18:00"));
    }

    private static List<WorkWindowFact> concatWindows(
            List<WorkWindowFact> left, List<WorkWindowFact> right) {
        java.util.ArrayList<WorkWindowFact> combined = new java.util.ArrayList<>();
        combined.addAll(left);
        combined.addAll(right);
        return List.copyOf(combined);
    }

    private static List<WorkWindowFact> dalianWindows(LocalDate date) {
        return List.of(
                window(date, "07:30", "12:00"),
                window(date, "13:00", "16:30"));
    }

    private static WorkWindowFact window(
            LocalDate date, String start, String end) {
        return new WorkWindowFact(
                "employee-a",
                date,
                date.atTime(java.time.LocalTime.parse(start))
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant(),
                date.atTime(java.time.LocalTime.parse(end))
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant());
    }

    private static DailyFact restPunches(
            LocalDate date,
            String firstPunch,
            String lastPunch) {
        return restPunches(date, firstPunch, lastPunch, 0);
    }

    private static DailyFact restPunches(
            LocalDate date,
            String firstPunch,
            String lastPunch,
            long overtime) {
        Instant first = firstPunch == null ? null : Instant.parse(firstPunch);
        Instant last = lastPunch == null ? null : Instant.parse(lastPunch);
        return new DailyFact(
                "fact-" + date,
                "company-a",
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "org-version-a",
                "制造中心",
                date,
                DayType.SATURDAY,
                "无班次",
                0,
                0,
                overtime,
                0,
                0,
                overtime,
                0,
                0,
                0,
                0,
                0,
                0,
                first,
                last,
                "calculation-a",
                "digest-a");
    }

    private static DailyFact leaveDayFact(
            LocalDate date,
            String firstPunch,
            String lastPunch,
            int missing) {
        Instant first = firstPunch == null ? null : Instant.parse(firstPunch);
        Instant last = lastPunch == null ? null : Instant.parse(lastPunch);
        long confirmed = missing > 0 ? 240 : 480;
        return new DailyFact(
                "fact-leave-" + date,
                "company-a",
                "employee-a",
                "SZST0674",
                "徐利民",
                "org-a",
                "org-version-a",
                "工程二部",
                date,
                DayType.WEEKDAY,
                "扬州总部班次 离职",
                480,
                confirmed,
                0,
                0,
                0,
                confirmed,
                1,
                confirmed > 0 ? 1 : 0,
                0,
                0,
                0,
                missing,
                first,
                last,
                "calculation-a",
                "digest-a");
    }

    private static DailyFact hireDayFact(
            LocalDate date,
            String firstPunch,
            String lastPunch,
            int missing) {
        Instant first = firstPunch == null ? null : Instant.parse(firstPunch);
        Instant last = lastPunch == null ? null : Instant.parse(lastPunch);
        long confirmed = missing > 0 ? 0 : 480;
        return new DailyFact(
                "fact-hire-" + date,
                "company-a",
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "org-version-a",
                "制造中心",
                date,
                DayType.WEEKDAY,
                "扬州总部班次 入职",
                480,
                confirmed,
                0,
                0,
                0,
                confirmed,
                1,
                confirmed > 0 ? 1 : 0,
                0,
                0,
                0,
                missing,
                first,
                last,
                "calculation-a",
                "digest-a");
    }

    private static DailyFact punches(
            LocalDate date,
            String firstPunch,
            String lastPunch,
            long overtime,
            long penalizedLate,
            int missing) {
        return punches(
                date, firstPunch, lastPunch, overtime, penalizedLate, penalizedLate, missing);
    }

    private static DailyFact punches(
            LocalDate date,
            String firstPunch,
            String lastPunch,
            long overtime,
            long lateMinutes,
            long penalizedLate,
            int missing) {
        Instant first = firstPunch == null ? null : Instant.parse(firstPunch);
        Instant last = lastPunch == null ? null : Instant.parse(lastPunch);
        long confirmed = missing > 0 ? 0 : 480;
        return new DailyFact(
                "fact-" + date,
                "company-a",
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "org-version-a",
                "制造中心",
                date,
                DayType.WEEKDAY,
                "扬州总部班次",
                480,
                confirmed,
                overtime,
                0,
                0,
                confirmed + overtime,
                1,
                confirmed + overtime > 0 ? 1 : 0,
                lateMinutes,
                penalizedLate,
                0,
                missing,
                first,
                last,
                "calculation-a",
                "digest-a");
    }

    private static OaDocumentFact oaInterval(
            String id,
            String type,
            String leaveType,
            String status,
            String start,
            String endExclusive) {
        return new OaDocumentFact(
                id,
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "制造中心",
                type,
                leaveType,
                Instant.parse(start),
                Instant.parse(endExclusive),
                60,
                status,
                "oa-source-a");
    }

    private static DailyFact daily(
            String factId,
            LocalDate date,
            DayType dayType,
            long scheduled,
            long confirmed,
            long overtime,
            long leave,
            long late,
            long penalizedLate,
            long early,
            int missing) {
        return new DailyFact(
                factId,
                "company-a",
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "org-version-a",
                "制造中心",
                date,
                dayType,
                "扬州总部班次",
                scheduled,
                confirmed,
                overtime,
                leave,
                0,
                confirmed + overtime,
                scheduled > 0 ? 1 : 0,  // scheduledAttendanceDays
                (confirmed + overtime) > 0 ? 1 : 0,  // actualAttendanceDays
                late,
                penalizedLate,
                early,
                missing,
                scheduled == 0
                        ? null
                        : Instant.parse("2026-06-01T00:01:00Z"),
                scheduled == 0
                        ? null
                        : Instant.parse("2026-06-01T10:01:00Z"),
                "calculation-a",
                "digest-a");
    }

    private static OaDocumentFact oa(
            String id,
            String type,
            String leaveType,
            String status) {
        return new OaDocumentFact(
                id,
                "employee-a",
                "0007",
                "陈思远",
                "org-a",
                "制造中心",
                type,
                leaveType,
                Instant.parse("2026-06-01T01:00:00Z"),
                Instant.parse("2026-06-01T02:00:00Z"),
                60,
                status,
                "oa-source-a");
    }
}

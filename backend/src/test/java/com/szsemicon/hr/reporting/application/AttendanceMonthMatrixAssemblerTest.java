package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceMonthMatrixAssemblerTest {

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
    void doesNotMarkLateWhenRawArrivalIsInsidePublishedGrace() {
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

        assertThat(firstDay.badges()).doesNotContain(BadgeCode.LATE);
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

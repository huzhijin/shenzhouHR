package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceReportVisibilityDigestTest {

    private static final String AUTHORIZATION_DIGEST =
            "a".repeat(64);

    @Test
    void aggregateWithSameRenderedValuesStillBindsSourceFactIdentity() {
        ReportSourceSnapshot first = snapshot("fact-a");
        ReportSourceSnapshot replacement = snapshot("fact-b");
        var calculator = new AttendanceReportCalculator();
        var firstData = calculator.calculate(
                ReportType.WORK_HOURS, first);
        var replacementData = calculator.calculate(
                ReportType.WORK_HOURS, replacement);

        assertThat(replacementData.rows())
                .isEqualTo(firstData.rows());
        assertThat(AttendanceReportVisibilityDigest.calculate(
                        ReportType.WORK_HOURS,
                        replacement,
                        replacementData))
                .isNotEqualTo(
                        AttendanceReportVisibilityDigest.calculate(
                                ReportType.WORK_HOURS,
                                first,
                                firstData));
    }

    private static ReportSourceSnapshot snapshot(String factId) {
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                "legal-1",
                null,
                null,
                null);
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "authorized-scope-set:"
                                + AUTHORIZATION_DIGEST,
                        "公司授权范围",
                        AUTHORIZATION_DIGEST),
                filter,
                "projection-1",
                "OPEN",
                Instant.parse("2026-07-29T01:00:00Z"),
                List.of("source-1"),
                List.of(new DailyFact(
                        factId,
                        "legal-1",
                        "employee-1",
                        "0001",
                        "员工-0001",
                        "org-1",
                        "org-version-1",
                        "制造中心",
                        LocalDate.of(2026, 7, 1),
                        DayType.WEEKDAY,
                        "标准班",
                        480,
                        480,
                        0,
                        0,
                        0,
                        480,
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        "calculation-1",
                        "same-result-digest")),
                List.of(),
                List.of(),
                List.of());
    }
}

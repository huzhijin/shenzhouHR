package com.szsemicon.hr.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceOvertimeClassificationReportTest {

    private final AttendanceReportCalculator calculator =
            new AttendanceReportCalculator();

    @Test
    void paid_and_compensatory_values_display_separately_and_total_is_their_sum() {
        var report = calculator.calculate(
                ReportType.OVERTIME,
                snapshot(List.of(
                        fact("paid", LocalDate.of(2026, 7, 15),
                                120, 120, 0, 0),
                        fact("compensatory", LocalDate.of(2026, 7, 16),
                                60, 0, 60, 0))));

        assertThat(report.columns())
                .extracting(column -> column.field().label())
                .contains("计薪加班", "转调休加班", "义务加班", "汇总加班");
        assertThat(report.rows()).singleElement().satisfies(row ->
                assertThat(row.values())
                        .containsEntry(ReportField.PAID_OVERTIME_HOURS, "2.00")
                        .containsEntry(
                                ReportField.COMPENSATORY_OVERTIME_HOURS,
                                "1.00")
                        .containsEntry(
                                ReportField.VOLUNTARY_OVERTIME_HOURS,
                                "0.00")
                        .containsEntry(
                                ReportField.TOTAL_OVERTIME_HOURS,
                                "3.00"));
    }

    @Test
    void daily_report_exposes_all_four_classified_overtime_columns() {
        var report = calculator.calculate(
                ReportType.ATTENDANCE_DETAIL,
                snapshot(List.of(fact(
                        "paid", LocalDate.of(2026, 7, 15),
                        120, 120, 0, 0))));

        assertThat(report.rows()).singleElement().satisfies(row ->
                assertThat(row.values())
                        .containsEntry(ReportField.PAID_OVERTIME_HOURS, "2.00")
                        .containsEntry(
                                ReportField.COMPENSATORY_OVERTIME_HOURS,
                                "0.00")
                        .containsEntry(
                                ReportField.VOLUNTARY_OVERTIME_HOURS,
                                "0.00")
                        .containsEntry(
                                ReportField.TOTAL_OVERTIME_HOURS,
                                "2.00"));
    }

    private ReportSourceSnapshot snapshot(List<DailyFact> facts) {
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "company-1",
                        "神州公司",
                        "authorization-digest"),
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        "company-1",
                        null,
                        null,
                        null),
                "projection-v1",
                "OPEN",
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of("attendance-v1"),
                facts,
                List.of(),
                List.of(),
                List.of());
    }

    private DailyFact fact(
            String id,
            LocalDate date,
            long recognized,
            long paid,
            long compensatory,
            long voluntary) {
        long total = paid + compensatory + voluntary;
        return new DailyFact(
                "fact-" + id,
                "company-1",
                "employee-1",
                "E001",
                "张三",
                "organization-1",
                "organization-version-1",
                "制造部",
                date,
                DayType.WEEKDAY,
                "标准班",
                0,
                0,
                recognized,
                paid,
                compensatory,
                voluntary,
                total,
                0,
                0,
                recognized,
                0,
                recognized > 0 ? 1 : 0,
                0,
                0,
                0,
                0,
                null,
                null,
                "calculation-v1",
                "result-digest-" + id);
    }
}

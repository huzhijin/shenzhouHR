package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.BadgeCode;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.DayCell;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.EmployeeRow;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class AttendanceMonthMatrixControllerTest {

    @Test
    void mapsSemanticBadgeCodesWithoutEmbeddingPresentationColors() {
        AttendanceReportQueryService service =
                mock(AttendanceReportQueryService.class);
        YearMonth period = YearMonth.of(2026, 7);
        String companyId = "company-a";
        var dates = period.atDay(1)
                .datesUntil(period.atEndOfMonth().plusDays(1))
                .toList();
        var filter = new ReportFilter(
                period, companyId, null, null, null);
        var page = new AttendanceMonthMatrixPage(
                "projection-a",
                "a".repeat(64),
                "ATTENDANCE_MONTH_MATRIX_V3",
                "OPEN",
                Instant.parse("2026-07-31T01:00:00Z"),
                List.of("attendance:v1", "oa:v1"),
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "scope-a",
                        "公司范围",
                        "scope-digest"),
                filter,
                List.of("REPORT_DRILL_DOWN"),
                dates,
                List.of(new EmployeeRow(
                        "employee-a",
                        "SZ001",
                        "张三",
                        "org-a",
                        "制造一部",
                        dates.stream()
                                .map(date -> new DayCell(
                                        date,
                                        date.equals(dates.getFirst())
                                                ? "制造一部"
                                                : null,
                                        date.equals(dates.getFirst())
                                                ? "扬州总部班次"
                                                : null,
                                        null,
                                        null,
                                        date.equals(dates.getFirst())
                                                ? List.of(
                                                        BadgeCode.LATE,
                                                        BadgeCode.OUTING)
                                                : List.of()))
                                .toList())),
                0,
                20,
                1,
                1,
                false);
        when(service.queryMonthMatrix(
                period,
                companyId,
                null,
                null,
                "projection-a",
                null,
                null,
                0,
                20))
                .thenReturn(page);
        var controller = new AttendanceReportController(
                service,
                Clock.fixed(
                        Instant.parse("2026-07-31T01:00:00Z"),
                        ZoneOffset.UTC));

        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("expectedProjectionVersion", "projection-a");
        var response = controller.monthMatrix(
                period,
                companyId,
                null,
                null,
                null,
                null,
                0,
                20,
                parameters);

        verify(service).queryMonthMatrix(
                period,
                companyId,
                null,
                null,
                "projection-a",
                null,
                null,
                0,
                20);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().kind())
                .isEqualTo("ATTENDANCE_MONTH_MATRIX");
        assertThat(response.getBody().metadata().timeZone())
                .isEqualTo("Asia/Shanghai");
        assertThat(response.getBody()
                        .rows()
                        .getFirst()
                        .days()
                        .getFirst()
                        .badges())
                .containsExactly("LATE", "OUTING");
        assertThat(response.getBody().toString())
                .doesNotContain("#", "color");
    }
}

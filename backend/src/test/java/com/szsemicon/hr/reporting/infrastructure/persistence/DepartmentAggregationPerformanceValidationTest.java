package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * A repeatable large-result validation for department aggregation. It checks
 * repository mapping cost and guards against N+1 mapper invocations without
 * tying CI reliability to database-specific execution plans.
 */
class DepartmentAggregationPerformanceValidationTest {

    private static final String PRINCIPAL = "principal-001";
    private static final String COMPANY_A = "company-a";
    private static final String COMPANY_B = "company-b";
    private static final String PROJECTION_A = "projection-a";
    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final Instant AUTHORIZATION_TIME =
            Instant.parse("2026-08-17T08:00:00Z");
    private static final int DEPARTMENT_COUNT = 5_000;

    @Test
    void largeCompanyScopedDepartmentAggregationUsesTwoBoundedQueries() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        MyBatisAttendanceReportSourceRepository repository =
                new MyBatisAttendanceReportSourceRepository(
                        mapper, mock(ObjectMapper.class));
        ReportFilter filter = new ReportFilter(
                PERIOD, COMPANY_A, null, null, null);
        when(mapper.listLatestAuthorizedProjections(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_READ),
                eq(PERIOD.atDay(1)),
                eq(PERIOD.plusMonths(1).atDay(1)),
                eq(COMPANY_A),
                eq(AUTHORIZATION_TIME))).thenReturn(List.of(
                        new ReportRows.ProjectionRow(
                                PROJECTION_A,
                                COMPANY_A,
                                "projection-v1",
                                "PRELIMINARY",
                                "{}",
                                AUTHORIZATION_TIME)));
        when(mapper.listAuthorizedDepartmentAttendanceRates(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_READ),
                eq(PROJECTION_A),
                eq(PERIOD.atDay(1)),
                eq(PERIOD.plusMonths(1).atDay(1)),
                eq(COMPANY_A),
                eq(null),
                eq(AUTHORIZATION_TIME))).thenReturn(largeCompanyARows());

        long startedNanos = System.nanoTime();
        var rates = repository.listAuthorizedDepartmentAttendanceRates(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                AUTHORIZATION_TIME);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        assertThat(rates).hasSize(DEPARTMENT_COUNT);
        assertThat(rates)
                .allSatisfy(rate -> {
                    assertThat(rate.companyId()).isEqualTo(COMPANY_A);
                    assertThat(rate.scheduledAttendanceDays()).isEqualTo(22);
                    assertThat(rate.actualAttendanceDays()).isEqualTo(20);
                    assertThat(rate.attendanceRate())
                            .isEqualByComparingTo("90.91");
                });
        // This is intentionally generous: its purpose is to catch accidental
        // per-row I/O or pathological mapping, not to benchmark CI hosts.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        verify(mapper, times(1)).listLatestAuthorizedProjections(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_READ),
                eq(PERIOD.atDay(1)),
                eq(PERIOD.plusMonths(1).atDay(1)),
                eq(COMPANY_A),
                eq(AUTHORIZATION_TIME));
        verify(mapper, times(1)).listAuthorizedDepartmentAttendanceRates(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_READ),
                eq(PROJECTION_A),
                eq(PERIOD.atDay(1)),
                eq(PERIOD.plusMonths(1).atDay(1)),
                eq(COMPANY_A),
                eq(null),
                eq(AUTHORIZATION_TIME));
        verify(mapper, times(0)).listAuthorizedDepartmentAttendanceRates(
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_REPORT_READ),
                any(),
                any(),
                any(),
                eq(COMPANY_B),
                any(),
                any());
    }

    private static List<ReportRows.DepartmentAttendanceRateRow>
            largeCompanyARows() {
        List<ReportRows.DepartmentAttendanceRateRow> rows =
                new ArrayList<>(DEPARTMENT_COUNT);
        for (int index = 0; index < DEPARTMENT_COUNT; index++) {
            rows.add(new ReportRows.DepartmentAttendanceRateRow(
                    COMPANY_A,
                    "organization-" + index,
                    20,
                    22,
                    new BigDecimal("90.91")));
        }
        return rows;
    }
}

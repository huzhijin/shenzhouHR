package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MyBatisAttendanceDashboardRepositoryTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final LocalDate BUSINESS_DATE =
            LocalDate.of(2026, 7, 29);

    @Test
    void trendWindowNeverCrossesThePublishedMonthlyProjection() {
        assertThat(MyBatisAttendanceDashboardRepository.trendStart(
                        LocalDate.of(2026, 7, 3)))
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(MyBatisAttendanceDashboardRepository.trendStart(
                        BUSINESS_DATE))
                .isEqualTo(LocalDate.of(2026, 7, 23));
    }

    @Test
    void blankPrincipalFailsClosedBeforeAnyMapperRead() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceDashboardRepository(
                mapper, new ObjectMapper());

        assertThat(repository.listAuthorizedCompanies(
                " ", java.time.YearMonth.of(2026, 7), NOW)).isEmpty();
        assertThat(repository.loadAuthorizedToday(
                " ", "company-a", BUSINESS_DATE, NOW)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void authorizedSnapshotUsesDashboardCapabilityAndSafeTopRows() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceDashboardRepository(
                mapper, new ObjectMapper());
        when(mapper.listLatestDashboardAuthorizedProjections(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        "company-a",
                        NOW))
                .thenReturn(List.of(new DashboardRows.ProjectionRow(
                        "projection-a",
                        "company-a",
                        "projection-version-a",
                        "OPEN",
                        "[\"deli:20\",\"oa:8\"]",
                        NOW)));
        when(mapper.listDashboardAuthorizedScopes(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        NOW))
                .thenReturn(List.of(new DashboardRows.ScopeRow(
                        "scope-a",
                        "ORGANIZATION",
                        null,
                        "organization-a",
                        true,
                        null)));
        when(mapper.summarizeDashboardExceptions(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(new DashboardRows.SummaryRow(3, 2, 1));
        when(mapper.listDashboardDailyTrend(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        LocalDate.of(2026, 7, 23),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new DashboardRows.DailyTrendRow(
                                LocalDate.of(2026, 7, 27),
                                2,
                                0,
                                2),
                        new DashboardRows.DailyTrendRow(
                                BUSINESS_DATE,
                                3,
                                1,
                                2)));
        when(mapper.listDashboardSeverityDistribution(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new DashboardRows.SeverityDistributionRow(
                                "WARNING", 2),
                        new DashboardRows.SeverityDistributionRow(
                                "ERROR", 1)));
        when(mapper.listDashboardTypeDistribution(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new DashboardRows.TypeDistributionRow(
                                "MISSING_PUNCH_PENDING", 2),
                        new DashboardRows.TypeDistributionRow(
                                "LATE", 1)));
        when(mapper.listDashboardOrganizationRanking(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new DashboardRows.OrganizationRankingRow(
                                "制造中心", 3, 1)));
        when(mapper.listDashboardExceptions(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(new DashboardRows.ExceptionRow(
                        "case-a",
                        "0007",
                        "陈思远",
                        "制造中心",
                        BUSINESS_DATE,
                        "MISSING_PUNCH_PENDING",
                        "ERROR",
                        "OPEN",
                        0,
                        "下班有效打卡缺失")));

        var snapshot = repository.loadAuthorizedToday(
                "principal-a", "company-a", BUSINESS_DATE, NOW);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().sourceVersions())
                .containsExactly("deli:20", "oa:8");
        assertThat(snapshot.orElseThrow().scope().type())
                .isEqualTo(ScopeType.ORGANIZATION);
        assertThat(snapshot.orElseThrow().summary().unresolvedCount())
                .isEqualTo(3);
        assertThat(snapshot.orElseThrow().analytics().dailyTrend())
                .hasSize(7)
                .first()
                .extracting(point -> point.businessDate())
                .isEqualTo(LocalDate.of(2026, 7, 23));
        assertThat(snapshot.orElseThrow()
                        .analytics()
                        .dailyTrend()
                        .get(4)
                        .exceptionCount())
                .isEqualTo(2);
        assertThat(snapshot.orElseThrow()
                        .analytics()
                        .severityDistribution())
                .extracting(
                        item -> item.severity(),
                        item -> item.count())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INFO", 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                "WARNING", 2L),
                        org.assertj.core.groups.Tuple.tuple("ERROR", 1L));
        assertThat(snapshot.orElseThrow()
                        .analytics()
                        .typeDistribution())
                .extracting(item -> item.exceptionType())
                .containsExactly("MISSING_PUNCH_PENDING", "LATE");
        assertThat(snapshot.orElseThrow()
                        .analytics()
                        .organizationRanking())
                .extracting(item -> item.organizationName())
                .containsExactly("制造中心");
        assertThat(snapshot.orElseThrow().exceptions())
                .extracting(item -> item.exceptionReference())
                .containsExactly("case-a");
        verify(mapper).listDashboardDailyTrend(
                "principal-a",
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                "projection-a",
                "company-a",
                LocalDate.of(2026, 7, 23),
                BUSINESS_DATE,
                NOW);
    }

    @Test
    void mismatchedCompanyProjectionFailsClosed() {
        AttendanceReportMapper mapper = mock(AttendanceReportMapper.class);
        var repository = new MyBatisAttendanceDashboardRepository(
                mapper, new ObjectMapper());
        when(mapper.listLatestDashboardAuthorizedProjections(
                        "principal-a",
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        "company-a",
                        NOW))
                .thenReturn(List.of(new DashboardRows.ProjectionRow(
                        "projection-b",
                        "company-b",
                        "projection-version-b",
                        "OPEN",
                        "[]",
                        NOW)));

        assertThat(repository.loadAuthorizedToday(
                "principal-a", "company-a", BUSINESS_DATE, NOW)).isEmpty();
    }
}

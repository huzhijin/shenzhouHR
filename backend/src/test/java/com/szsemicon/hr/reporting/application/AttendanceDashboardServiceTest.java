package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedDashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedScope;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DailyTrendPoint;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardAnalytics;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionSummary;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.OrganizationRankingItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.SeverityDistributionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.TypeDistributionItem;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttendanceDashboardServiceTest {

    private static final String PRINCIPAL = "principal-dashboard";
    private static final String COMPANY = "company-a";
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate BUSINESS_DATE =
            LocalDate.of(2026, 7, 29);

    @Test
    void freshSingleCompanyProjectionReturnsRealTodayCountsAndTopRows() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        CompanyOption company =
                new CompanyOption(COMPANY, "神州半导体");
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(List.of(company));
        when(repository.loadAuthorizedToday(
                        PRINCIPAL, COMPANY, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(new AuthorizedDashboardSnapshot(
                        snapshot(
                                NOW,
                                new ExceptionSummary(2, 1, 1),
                                List.of(exception("case-a"))),
                        true)));
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        var service = service(capabilities, repository);

        var result = service.query(null);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AttendanceDashboardService.Ready.class,
                        ready -> {
                            assertThat(ready.businessDate())
                                    .isEqualTo(BUSINESS_DATE);
                            assertThat(ready.selectedCompany())
                                    .isEqualTo(company);
                            assertThat(ready.snapshot().summary())
                                    .isEqualTo(
                                            new ExceptionSummary(2, 1, 1));
                            assertThat(ready.snapshot().exceptions())
                                    .extracting(
                                            ExceptionItem
                                                    ::exceptionReference)
                                    .containsExactly("case-a");
                            assertThat(ready.allowedActions())
                                    .containsExactly(
                                            "DASHBOARD_DRILL_DOWN");
                        });
        verify(capabilities).require(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ);
    }

    @Test
    void freshProjectionWithNoExceptionFactsIsARealZero() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        CompanyOption company =
                new CompanyOption(COMPANY, "神州半导体");
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(List.of(company));
        when(repository.loadAuthorizedToday(
                        PRINCIPAL, COMPANY, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(new AuthorizedDashboardSnapshot(
                        snapshot(
                                NOW,
                                new ExceptionSummary(0, 0, 0),
                                List.of()),
                        false)));
        when(capabilities.currentCapabilities()).thenReturn(
                Set.of(CapabilityCodes.ATTENDANCE_DASHBOARD_READ));

        var result = service(capabilities, repository).query(null);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AttendanceDashboardService.Ready.class,
                        ready -> {
                            assertThat(
                                    ready.snapshot()
                                            .summary()
                                            .unresolvedCount())
                                    .isZero();
                            assertThat(ready.snapshot().exceptions())
                                    .isEmpty();
                            assertThat(ready.allowedActions()).isEmpty();
                        });
    }

    @Test
    void dashboardOnlyRepositoryResultKeepsAggregatesWithoutEmployeeDetails() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        CompanyOption company =
                new CompanyOption(COMPANY, "神州半导体");
        DashboardSnapshot sourceSnapshot = snapshot(
                NOW,
                new ExceptionSummary(2, 1, 1),
                List.of());
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(List.of(company));
        when(repository.loadAuthorizedToday(
                        PRINCIPAL, COMPANY, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(new AuthorizedDashboardSnapshot(
                        sourceSnapshot, false)));
        when(capabilities.currentCapabilities()).thenReturn(
                Set.of(CapabilityCodes.ATTENDANCE_DASHBOARD_READ));

        var result = service(capabilities, repository).query(null);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AttendanceDashboardService.Ready.class,
                        ready -> {
                            assertThat(ready.snapshot().summary())
                                    .isEqualTo(
                                            new ExceptionSummary(2, 1, 1));
                            assertThat(ready.snapshot().analytics())
                                    .isEqualTo(sourceSnapshot.analytics());
                            assertThat(ready.snapshot().exceptions())
                                    .isEmpty();
                            assertThat(ready.allowedActions()).isEmpty();
                        });
    }

    @Test
    void repositoryAccessMarkerCannotCarryUnauthorizedEmployeeDetails() {
        assertThatThrownBy(() -> new AuthorizedDashboardSnapshot(
                        snapshot(
                                NOW,
                                new ExceptionSummary(1, 1, 1),
                                List.of(exception("case-sensitive"))),
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain employee details");
    }

    @Test
    void staleMonthlyProjectionCannotMasqueradeAsTodayZero() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        CompanyOption company =
                new CompanyOption(COMPANY, "神州半导体");
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(List.of(company));
        when(repository.loadAuthorizedToday(
                        PRINCIPAL, COMPANY, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(new AuthorizedDashboardSnapshot(
                        snapshot(
                                Instant.parse("2026-07-28T15:59:59Z"),
                                new ExceptionSummary(0, 0, 0),
                                List.of()),
                        false)));

        assertProjectionNotReady(() ->
                service(capabilities, repository).query(null));
        verify(capabilities, never()).currentCapabilities();
    }

    @Test
    void multipleCompaniesReturnExplicitSelectionWithoutReadingFacts() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        List<CompanyOption> companies = List.of(
                new CompanyOption("company-a", "公司甲"),
                new CompanyOption("company-b", "公司乙"));
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(companies);

        var result = service(capabilities, repository).query(null);

        assertThat(result)
                .isEqualTo(new AttendanceDashboardService.CompanySelection(
                        BUSINESS_DATE, companies));
        verify(repository, never()).loadAuthorizedToday(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(capabilities, never()).currentCapabilities();
    }

    @Test
    void missingOrUnauthorizedProjectionIsNotReportedAsZero() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(List.of());

        assertProjectionNotReady(() ->
                service(capabilities, repository).query(null));
    }

    @Test
    void invalidCompanySelectorFailsBeforeAuthorizationOrDatabaseWork() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);

        assertThatThrownBy(() ->
                service(capabilities, repository).query(" company-a"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(capabilities, repository);
    }

    private static void assertProjectionNotReady(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_DASHBOARD_"
                                            + "PROJECTION_NOT_READY");
                            assertThat(problem.retryable()).isTrue();
                        });
    }

    private static AttendanceDashboardService service(
            CurrentCapabilityService capabilities,
            AttendanceDashboardRepository repository) {
        CurrentPrincipalProvider principal = () -> PRINCIPAL;
        return new AttendanceDashboardService(
                capabilities, principal, repository, CLOCK);
    }

    private static DashboardSnapshot snapshot(
            Instant dataAsOf,
            ExceptionSummary summary,
            List<ExceptionItem> exceptions) {
        return new DashboardSnapshot(
                COMPANY,
                "projection-1",
                List.of("deli:20", "oa:8"),
                dataAsOf,
                "OPEN",
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        "authorized-scope-set:abc",
                        "公司授权范围",
                        "a".repeat(64)),
                summary,
                analytics(summary),
                exceptions);
    }

    private static DashboardAnalytics analytics(
            ExceptionSummary summary) {
        List<TypeDistributionItem> types =
                summary.unresolvedCount() == 0
                        ? List.of()
                        : List.of(new TypeDistributionItem(
                                "MISSING_PUNCH_PENDING",
                                summary.unresolvedCount()));
        List<OrganizationRankingItem> organizations =
                summary.unresolvedCount() == 0
                        ? List.of()
                        : List.of(new OrganizationRankingItem(
                                "制造中心",
                                summary.unresolvedCount(),
                                summary.blockingCount()));
        return new DashboardAnalytics(
                List.of(new DailyTrendPoint(
                        BUSINESS_DATE,
                        summary.unresolvedCount(),
                        summary.blockingCount(),
                        summary.affectedEmployeeCount())),
                List.of(
                        new SeverityDistributionItem("INFO", 0),
                        new SeverityDistributionItem(
                                "WARNING",
                                summary.unresolvedCount()
                                        - summary.blockingCount()),
                        new SeverityDistributionItem(
                                "ERROR",
                                summary.blockingCount())),
                types,
                organizations);
    }

    private static ExceptionItem exception(String reference) {
        return new ExceptionItem(
                reference,
                "0007",
                "陈思远",
                "制造中心",
                BUSINESS_DATE,
                "MISSING_PUNCH_PENDING",
                "ERROR",
                "OPEN",
                0,
                "下班有效打卡缺失");
    }
}

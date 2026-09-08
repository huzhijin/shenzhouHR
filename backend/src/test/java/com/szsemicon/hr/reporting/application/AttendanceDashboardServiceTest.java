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
    void multipleCompaniesPreferJiangsuShenzhouAndLoadFacts() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        CompanyOption shengzhou = new CompanyOption("company-a", "上海昇州半导体科技有限公司");
        CompanyOption shenzhou = new CompanyOption(
                "company-szsc", "江苏神州半导体科技股份有限公司");
        List<CompanyOption> companies = List.of(shengzhou, shenzhou);
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        NOW))
                .thenReturn(companies);
        when(repository.loadAuthorizedToday(
                        PRINCIPAL, "company-szsc", BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(new AuthorizedDashboardSnapshot(
                        new DashboardSnapshot(
                                "company-szsc",
                                "projection-1",
                                List.of("deli:20", "oa:8"),
                                NOW,
                                "OPEN",
                                new AuthorizedScope(
                                        ScopeType.COMPANY,
                                        "authorized-scope-set:abc",
                                        "公司授权范围",
                                        "a".repeat(64)),
                                new ExceptionSummary(0, 0, 0),
                                analytics(new ExceptionSummary(0, 0, 0)),
                                List.of()),
                        true)));
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));

        var result = service(capabilities, repository).query(null);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AttendanceDashboardService.Ready.class,
                        ready -> assertThat(ready.selectedCompany())
                                .isEqualTo(shenzhou));
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

    @Test
    void workbenchUsesAssemblerInsteadOfPublishedProjection() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        AttendanceReportSourceRepository reportSources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchAssembler workbench =
                mock(AttendanceDashboardWorkbenchAssembler.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        CompanyOption companyA = new CompanyOption("company-a", "神州半导体");
        CompanyOption companyB = new CompanyOption("company-b", "神州科技");
        DashboardSnapshot assembledSnapshot = snapshot(
                NOW,
                new ExceptionSummary(2, 2, 1),
                List.of(
                        new ExceptionItem(
                                "case-a",
                                "SZST0004",
                                "丁书龙",
                                "销售中心 · 神州半导体",
                                BUSINESS_DATE,
                                "MISSING_PUNCH_OVERDUE",
                                "ERROR",
                                "OPEN",
                                480,
                                "今日尚无有效打卡"),
                        new ExceptionItem(
                                "case-b",
                                "SZKJ0001",
                                "李悦",
                                "销售中心 · 神州科技",
                                BUSINESS_DATE,
                                "LATE",
                                "WARNING",
                                "OPEN",
                                18,
                                "首次有效打卡 09:18")));
        when(workbench.assemble(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        BUSINESS_DATE,
                        false,
                        NOW,
                        true))
                .thenReturn(Optional.of(
                        new AttendanceDashboardWorkbenchAssembler.Assembled(
                                companyA,
                                List.of(companyA, companyB),
                                assembledSnapshot,
                                List.of(),
                                List.of())));

        var service = new AttendanceDashboardService(
                capabilities,
                () -> PRINCIPAL,
                repository,
                CLOCK,
                reportSources,
                workbench);

        var result = service.query(null);

        assertThat(result)
                .isInstanceOfSatisfying(AttendanceDashboardService.Ready.class,
                        ready -> {
                            assertThat(ready.companies())
                                    .extracting(CompanyOption::companyId)
                                    .containsExactly(
                                            "company-a", "company-b");
                            assertThat(ready.snapshot().exceptions())
                                    .extracting(ExceptionItem::organizationName)
                                    .containsExactly(
                                            "销售中心 · 神州半导体",
                                            "销售中心 · 神州科技");
                        });
        verify(repository, never()).loadAuthorizedToday(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workbenchPublishesTheSnapshotTrendDateAsBusinessDate() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        AttendanceReportSourceRepository reportSources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchAssembler workbench =
                mock(AttendanceDashboardWorkbenchAssembler.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        CompanyOption company = new CompanyOption(COMPANY, "神州半导体");
        LocalDate yesterday = BUSINESS_DATE.minusDays(1);
        ExceptionSummary summary = new ExceptionSummary(2, 1, 1);
        DashboardSnapshot assembledSnapshot = new DashboardSnapshot(
                COMPANY,
                "LIVE-WB-" + yesterday,
                List.of("WORKBENCH-PUNCH:V1"),
                NOW,
                "OPEN",
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        COMPANY,
                        "公司授权范围",
                        "a".repeat(64)),
                summary,
                new DashboardAnalytics(
                        List.of(new DailyTrendPoint(
                                yesterday,
                                summary.unresolvedCount(),
                                summary.blockingCount(),
                                summary.affectedEmployeeCount())),
                        List.of(
                                new SeverityDistributionItem("INFO", 0),
                                new SeverityDistributionItem("WARNING", 1),
                                new SeverityDistributionItem("ERROR", 1)),
                        List.of(new TypeDistributionItem(
                                "MISSING_ON_DUTY", 2)),
                        List.of(new OrganizationRankingItem(
                                "销售中心", 2, 1))),
                List.of(new ExceptionItem(
                        "case-yesterday",
                        "SZST0004",
                        "丁书龙",
                        "销售中心",
                        yesterday,
                        "MISSING_ON_DUTY",
                        "WARNING",
                        "OPEN",
                        240,
                        "上班漏签")));
        when(workbench.assemble(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        BUSINESS_DATE,
                        false,
                        NOW,
                        true))
                .thenReturn(Optional.of(
                        new AttendanceDashboardWorkbenchAssembler.Assembled(
                                company,
                                List.of(company),
                                assembledSnapshot,
                                List.of(),
                                List.of())));

        var result = new AttendanceDashboardService(
                capabilities,
                () -> PRINCIPAL,
                repository,
                CLOCK,
                reportSources,
                workbench).query(null);

        assertThat(result)
                .isInstanceOfSatisfying(
                        AttendanceDashboardService.Ready.class,
                        ready -> assertThat(ready.businessDate())
                                .isEqualTo(yesterday));
    }

    @Test
    void liveAssemblerIsPreferredOverPinnedMonthlySnapshot() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        AttendanceReportSourceRepository reportSources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchAssembler workbench =
                mock(AttendanceDashboardWorkbenchAssembler.class);
        RealtimeAttendanceReportSnapshotService realtimeSnapshots =
                mock(RealtimeAttendanceReportSnapshotService.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        CompanyOption company = new CompanyOption(COMPANY, "江苏神州半导体科技股份有限公司");
        when(workbench.assemble(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        BUSINESS_DATE,
                        false,
                        NOW,
                        true))
                .thenReturn(Optional.of(
                        new AttendanceDashboardWorkbenchAssembler.Assembled(
                                company,
                                List.of(company),
                                snapshot(
                                        NOW,
                                        new ExceptionSummary(0, 0, 0),
                                        List.of()),
                                List.of(),
                                List.of())));

        var result = new AttendanceDashboardService(
                capabilities,
                () -> PRINCIPAL,
                repository,
                CLOCK,
                reportSources,
                workbench,
                realtimeSnapshots).query(null);

        assertThat(result).isInstanceOf(AttendanceDashboardService.Ready.class);
        verify(workbench).assemble(
                PRINCIPAL,
                YearMonth.of(2026, 7),
                BUSINESS_DATE,
                false,
                NOW,
                true);
        verifyNoInteractions(realtimeSnapshots);
        verify(repository, never()).loadAuthorizedToday(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void workbenchRuntimeFailureIsNotReportedAsANetworkOrZeroResult() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceDashboardRepository repository =
                mock(AttendanceDashboardRepository.class);
        AttendanceReportSourceRepository reportSources =
                mock(AttendanceReportSourceRepository.class);
        AttendanceDashboardWorkbenchAssembler workbench =
                mock(AttendanceDashboardWorkbenchAssembler.class);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(workbench.assemble(
                        PRINCIPAL,
                        YearMonth.of(2026, 7),
                        BUSINESS_DATE,
                        false,
                        NOW,
                        true))
                .thenThrow(new RuntimeException("statement timeout"));

        assertThatThrownBy(() -> new AttendanceDashboardService(
                capabilities,
                () -> PRINCIPAL,
                repository,
                CLOCK,
                reportSources,
                workbench).query(null))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_DASHBOARD_SOURCE_NOT_READY");
                            assertThat(problem.retryable()).isTrue();
                        });
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

    private static com.szsemicon.hr.reporting.domain
            .AttendanceReportModels.ReportSourceSnapshot reportSnapshot(
            String companyId,
            com.szsemicon.hr.reporting.domain.AttendanceReportModels
                    .ExceptionFact fact) {
        return new com.szsemicon.hr.reporting.domain
                .AttendanceReportModels.ReportSourceSnapshot(
                new com.szsemicon.hr.reporting.domain.AttendanceReportModels
                        .AuthorizedScope(
                        ScopeType.COMPANY,
                        companyId,
                        "公司授权范围",
                        "a".repeat(64)),
                new com.szsemicon.hr.reporting.domain.AttendanceReportModels
                        .ReportFilter(
                        YearMonth.of(2026, 7),
                        companyId,
                        null,
                        null,
                        null),
                "LIVE-" + companyId,
                "OPEN",
                NOW,
                List.of("deli:1"),
                List.of(),
                List.of(),
                List.of(fact),
                List.of());
    }

    private static com.szsemicon.hr.reporting.domain
            .AttendanceReportModels.ExceptionFact exceptionFact(
            String caseId,
            String employeeId,
            String employeeNumber,
            String organizationName) {
        return new com.szsemicon.hr.reporting.domain
                .AttendanceReportModels.ExceptionFact(
                caseId,
                employeeId,
                employeeNumber,
                "员工" + employeeNumber,
                "org-" + caseId,
                organizationName,
                BUSINESS_DATE,
                "MISSING_PUNCH_PENDING",
                com.szsemicon.hr.reporting.domain.AttendanceReportModels
                        .ExceptionSeverity.ERROR,
                com.szsemicon.hr.reporting.domain.AttendanceReportModels
                        .ExceptionState.OPEN,
                0,
                "下班有效打卡缺失",
                "calculation-v1");
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

package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealtimeAttendanceReportSnapshotServiceTest {

    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CAPABILITY = "ATTENDANCE_REPORT:READ";
    private static final String COMPANY_ID = "company-a";
    private static final YearMonth PERIOD = YearMonth.of(2026, 8);
    private static final Instant NOW =
            Instant.parse("2026-08-17T07:00:15Z");

    @Mock
    private AttendanceReportCalculationOrchestrator orchestrator;

    @Mock
    private AttendanceReportSourceRepository repository;

    @Mock
    private AttendanceReportProjectionPublicationUseCase publisher;

    private RealtimeAttendanceReportSnapshotService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RealtimeAttendanceReportSnapshotService(
                orchestrator, repository, meterRegistry);
        org.mockito.Mockito.lenient().when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(
                        repository.listCommittedSourceCutoffs(
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    @Test
    void getWithoutPinAndWithoutSourcesReturnsEmptyWithoutCalculating() {
        authorizePeriod(companyAuthorization());

        var snapshot = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();

        assertThat(snapshot.projectionVersion()).isEqualTo("UNPINNED-EMPTY");
        assertThat(snapshot.dailyFacts()).isEmpty();
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getWithoutPinAndWithSourcesFailsClosedAsNotReady() {
        authorizePeriod(companyAuthorization());
        when(repository.hasCommittedSourceEvidence(
                        COMPANY_ID, PERIOD, NOW))
                .thenReturn(true);

        assertThatThrownBy(() -> service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW))
                .isInstanceOf(
                        com.szsemicon.hr.shared.web.ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PIN_NOT_READY");
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recalculateCalculatesAndCachesTheCompanyMonth() {
        authorizePeriod(companyAuthorization());
        when(orchestrator.assemble(
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.eq(PeriodState.OPEN),
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> command(
                        COMPANY_ID,
                        PERIOD,
                        invocation.getArgument(4),
                        "a".repeat(64),
                        List.of(dailyFact())));

        var first = service.recalculate(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                NOW);
        var second = service.recalculate(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                NOW.plusSeconds(10));

        assertThat(first.projectionVersion())
                .isEqualTo("LIVE-" + "a".repeat(64));
        assertThat(first.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
        assertThat(second.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
        verify(orchestrator, times(2)).assemble(
                org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(PERIOD),
                org.mockito.ArgumentMatchers.eq(PeriodState.OPEN),
                org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recalculationRefreshesOaBeforeAssemblyAndUsesPostSyncCutoff() {
        authorizePeriod(companyAuthorization());
        var refresh = org.mockito.Mockito.mock(AttendanceReportSourceRefreshPort.class);
        Instant committed = NOW.plusSeconds(5);
        when(refresh.refresh(COMPANY_ID, PERIOD, NOW)).thenReturn(committed);
        service.configureSourceRefresh(refresh);
        when(orchestrator.assemble(COMPANY_ID, PERIOD, PeriodState.OPEN, PRINCIPAL_ID, committed))
                .thenReturn(command(COMPANY_ID, PERIOD, committed, "a".repeat(64), List.of(dailyFact())));
        var result = service.recalculate(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), NOW);
        var order = org.mockito.Mockito.inOrder(refresh, orchestrator);
        order.verify(refresh).refresh(COMPANY_ID, PERIOD, NOW);
        order.verify(orchestrator).assemble(COMPANY_ID, PERIOD, PeriodState.OPEN, PRINCIPAL_ID, committed);
        assertThat(result.dataAsOf()).isEqualTo(committed);
    }

    @Test
    void failedOaRefreshDoesNotCalculateOrPublishAndExistingPinRemainsReadable() {
        authorizePeriod(companyAuthorization());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));
        var refresh = org.mockito.Mockito.mock(AttendanceReportSourceRefreshPort.class);
        when(refresh.refresh(COMPANY_ID, PERIOD, NOW)).thenThrow(
                new com.szsemicon.hr.shared.web.ApiProblemException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "OA_REPORT_SOURCE_REFRESH_FAILED", "OA unavailable", true));
        service.configureSourceRefresh(refresh);
        assertThatThrownBy(() -> service.recalculate(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), NOW))
                .extracting("code").isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
        assertThat(service.loadAuthorizedSnapshot(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), null, NOW).orElseThrow()).isEqualTo(pinned);
    }

    @Test
    void employeeWindowRefreshesTheSamePlanConsumedByAssembly() {
        authorizePeriod(companyAuthorization());
        var refresh = org.mockito.Mockito.mock(AttendanceReportSourceRefreshPort.class);
        Instant committed = NOW.plusSeconds(5);
        LocalDate start = PERIOD.atDay(17);
        var plan = AttendanceReportDependencyPlan.bounded(COMPANY_ID, PERIOD, start,
                start.plusDays(1), List.of("employee-1"));
        when(orchestrator.publishedBaseline(COMPANY_ID, PERIOD)).thenReturn(Optional.of(
                new AttendanceReportDependencyPlan.Baseline("old-pin", NOW.minusSeconds(1), "OPEN")));
        when(orchestrator.planWindow(org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(PERIOD), org.mockito.ArgumentMatchers.eq("old-pin"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(start),
                org.mockito.ArgumentMatchers.eq(start.plusDays(1)), org.mockito.ArgumentMatchers.eq(List.of("employee-1"))))
                .thenReturn(plan);
        when(refresh.refreshRange(COMPANY_ID, plan.refreshFromInclusive(), plan.refreshToExclusive(), NOW,
                List.of("employee-1")))
                .thenReturn(committed);
        service.configureSourceRefresh(refresh);
        when(orchestrator.assemblePlanned(plan, PeriodState.OPEN, PRINCIPAL_ID, committed))
                .thenReturn(command(COMPANY_ID, PERIOD, committed, "b".repeat(64), List.of(dailyFact())));
        service.recalculateEmployee(PRINCIPAL_ID, CAPABILITY, filter(COMPANY_ID, null, null),
                NOW, "employee-1", start, start.plusDays(1));
        var order = org.mockito.Mockito.inOrder(refresh, orchestrator);
        order.verify(refresh).refreshRange(COMPANY_ID, plan.refreshFromInclusive(), plan.refreshToExclusive(), NOW,
                List.of("employee-1"));
        order.verify(orchestrator).assemblePlanned(plan, PeriodState.OPEN, PRINCIPAL_ID, committed);
        verify(refresh, never()).refresh(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retainsThePinnedSnapshotAcrossTheNextTimeBucket() {
        authorizePeriod(companyAuthorization());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));
        var first = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();

        var nextPage = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        first.projectionVersion(),
                        NOW.plusSeconds(40))
                .orElseThrow();

        assertThat(nextPage).isEqualTo(first);
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reusesThePinnedSnapshotWithoutAToken() {
        Instant laterInWindow = NOW.plusSeconds(140);
        authorizePeriod(companyAuthorization());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));

        var first = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();
        var reused = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        laterInWindow)
                .orElseThrow();

        assertThat(reused).isEqualTo(first);
        verify(orchestrator, never()).assemble(
                COMPANY_ID,
                PERIOD,
                PeriodState.OPEN,
                PRINCIPAL_ID,
                NOW);
    }

    @Test
    void staleExpectedTokenFailsClosedWhenPinVersionChanged() {
        authorizePeriod(companyAuthorization());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));

        assertThatThrownBy(() -> service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        "LIVE-" + "z".repeat(64),
                        NOW))
                .isInstanceOf(
                        com.szsemicon.hr.shared.web.ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_SNAPSHOT_CHANGED");
    }

    @Test
    void companyMonthCalculationIsSharedButAuthorizationAndFiltersAreNot() {
        String scopedPrincipal = "principal-2";
        when(repository.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        COMPANY_ID,
                        PERIOD,
                        NOW))
                .thenReturn(Optional.of(companyAuthorization()));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        org.mockito.ArgumentMatchers.eq(scopedPrincipal),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        scope(ScopeType.ORGANIZATION),
                        COMPANY_ID,
                        false,
                        null,
                        Set.of("employee-1"),
                        Set.of("organization-a"))));
        when(orchestrator.assemble(
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.eq(PeriodState.OPEN),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> command(
                        COMPANY_ID,
                        PERIOD,
                        invocation.getArgument(4),
                        "a".repeat(64),
                        List.of(
                                dailyFact(),
                                dailyFact(
                                        "fact-2",
                                        COMPANY_ID,
                                        "employee-2",
                                        "organization-b"),
                                dailyFact(
                                        "fact-cross-company",
                                        "company-b",
                                        "employee-1",
                                        "organization-a"))));

        var companySnapshot = service.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW);
        var scopedSnapshot = service.recalculate(
                        scopedPrincipal,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW.plusSeconds(10));
        var forgedOrganization = service.recalculate(
                        scopedPrincipal,
                        CAPABILITY,
                        filter(COMPANY_ID, "organization-b", null),
                        NOW.plusSeconds(11));

        assertThat(companySnapshot.dailyFacts()).hasSize(2);
        assertThat(scopedSnapshot.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
        assertThat(forgedOrganization.dailyFacts()).isEmpty();
        verify(orchestrator, org.mockito.Mockito.atLeastOnce()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void companyWideDepartmentFilterIncludesCurrentOrgDescendants() {
        authorizePeriod(companyAuthorization());
        when(repository.listOrganizationSubtree("organization-a"))
                .thenReturn(Set.of("organization-a", "organization-b"));
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command(
                        COMPANY_ID,
                        PERIOD,
                        NOW,
                        "a".repeat(64),
                        List.of(
                                dailyFact(),
                                dailyFact(
                                        "fact-2",
                                        COMPANY_ID,
                                        "employee-2",
                                        "organization-b"))));

        var snapshot = service.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, "organization-a", null),
                        NOW);

        assertThat(snapshot.dailyFacts())
                .extracting(DailyFact::organizationId)
                .containsExactlyInAnyOrder(
                        "organization-a", "organization-b");
    }

    @Test
    void cachedCalculationDoesNotCacheARevokedAuthorization() {
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        PERIOD,
                        NOW))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        PERIOD,
                        NOW.plusSeconds(10)))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        Optional.of(companyAuthorization()),
                        Optional.empty());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(NOW)))
                .thenReturn(Optional.of(pinned));

        assertThat(service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                null,
                NOW)).isPresent();
        assertThat(service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                null,
                NOW.plusSeconds(10))).isEmpty();

        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void filtersFactsWithCurrentOrganizationAuthorization() {
        authorizePeriod(new RealtimeAuthorization(
                scope(ScopeType.ORGANIZATION),
                COMPANY_ID,
                false,
                null,
                Set.of("employee-1"),
                Set.of("organization-a")));
        when(orchestrator.assemble(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(command());

        var snapshot = service.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW);

        assertThat(snapshot.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
    }

    @Test
    void rejectsACompanyOutsideTheCurrentScopeBeforeCalculation() {
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL_ID, CAPABILITY, PERIOD, NOW))
                .thenReturn(List.of(
                        new CompanyOption("company-b", "其他公司")));

        assertThat(service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                null,
                NOW)).isEmpty();

        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMismatchedAuthorizationCompanyBeforeCalculation() {
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL_ID, CAPABILITY, PERIOD, NOW))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        COMPANY_ID,
                        PERIOD,
                        NOW))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        scope(ScopeType.COMPANY),
                        "company-b",
                        true,
                        null,
                        Set.of(),
                        Set.of())));

        assertThat(service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, null),
                null,
                NOW)).isEmpty();
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cacheHasMaximumEntriesAndRemovesExpiredSnapshots() {
        when(repository.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.any(YearMonth.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.any(YearMonth.class),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(companyAuthorization()));
        when(orchestrator.assemble(
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.any(YearMonth.class),
                        org.mockito.ArgumentMatchers.eq(PeriodState.OPEN),
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> command(
                        COMPANY_ID,
                        invocation.getArgument(1),
                        invocation.getArgument(4),
                        "c".repeat(64),
                        List.of()));

        YearMonth firstPeriod = YearMonth.of(2020, 1);
        for (int index = 0; index < 65; index++) {
            YearMonth period = firstPeriod.plusMonths(index);
            assertThat(service.recalculate(
                    PRINCIPAL_ID,
                    CAPABILITY,
                    filter(period, COMPANY_ID, null, null),
                    NOW)).isNotNull();
        }

        assertThat(service.cacheMetrics().entries()).isEqualTo(64);
        assertThat(service.cacheMetrics().maximumEntries()).isEqualTo(64);
        assertThat(service.cacheMetrics().misses()).isEqualTo(65);
        assertThat(service.cacheMetrics().evictions()).isEqualTo(1);

        assertThat(service.recalculate(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(
                        firstPeriod.plusMonths(100),
                        COMPANY_ID,
                        null,
                        null),
                NOW.plusSeconds(1801))).isNotNull();

        assertThat(service.cacheMetrics().entries()).isEqualTo(1);
        assertThat(service.cacheMetrics().evictions())
                .isGreaterThanOrEqualTo(65);
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.cache.evictions")
                .counter()
                .count()).isGreaterThanOrEqualTo(65);
    }

    @Test
    void realtimeSnapshotKeepsTheSameBusinessValuesAsPublicationCommand() {
        authorizePeriod(companyAuthorization());
        PublishCommand publicationCommand = command();
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(publicationCommand);

        var snapshot = service.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW);

        DailyFact published = publicationCommand.calculatedFacts()
                .getFirst()
                .facts()
                .dailyFact();
        assertThat(snapshot.dailyFacts()).containsExactly(published);
        assertThat(snapshot.dailyFacts().getFirst().actualWorkMinutes())
                .isEqualTo(published.actualWorkMinutes());
        assertThat(snapshot.dailyFacts().getFirst().lateMinutes())
                .isEqualTo(published.lateMinutes());
        assertThat(snapshot.dailyFacts().getFirst().shiftLabel())
                .isEqualTo(published.shiftLabel());
        assertThat(snapshot.periodState()).isEqualTo("OPEN");
        assertThat(snapshot.projectionVersion())
                .isEqualTo("LIVE-"
                        + publicationCommand.metadata().sourceSnapshotDigest());
    }

    @Test
    void slowEngineDoesNotReturnASecondPunchRoster() throws Exception {
        authorizePeriod(companyAuthorization());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var waiting = new RealtimeAttendanceReportSnapshotService(
                    orchestrator,
                    repository,
                    meterRegistry,
                    null,
                    executor,
                    java.time.Duration.ofMillis(150));
            when(repository.hasCommittedSourceEvidence(
                            COMPANY_ID, PERIOD, NOW))
                    .thenReturn(true);
            assertThatThrownBy(() -> waiting.loadAuthorizedSnapshot(
                            PRINCIPAL_ID,
                            CAPABILITY,
                            filter(COMPANY_ID, null, null),
                            null,
                            NOW))
                    .isInstanceOfSatisfying(
                            com.szsemicon.hr.shared.web.ApiProblemException.class,
                            problem -> assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_REPORT_PIN_NOT_READY"));
            verify(orchestrator, never()).assemble(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mapsAmbiguousAndSourceFailuresToRetryableSafeCodes() {
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "OA employee identity is missing or ambiguous: E001"))
                .isEqualTo("ATTENDANCE_IDENTITY_AMBIGUOUS");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "Scheduled shift authority missing for employee emp-1 on 2026-08-01"))
                .isEqualTo("ATTENDANCE_SHIFT_AMBIGUOUS");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "Calendar authority missing for employee emp-1 on 2026-08-01"))
                .isEqualTo("ATTENDANCE_CALENDAR_AMBIGUOUS");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "attendance-group authority count must be exactly one"))
                .isEqualTo("ATTENDANCE_POLICY_AMBIGUOUS");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "Required attendance source is not synchronized: DELI"))
                .isEqualTo("ATTENDANCE_SOURCE_NOT_READY");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "row QUARANTINED by match"))
                .isEqualTo("ATTENDANCE_SOURCE_QUARANTINED");
        assertThat(RealtimeAttendanceReportSnapshotService.failClosedCode(
                "Statement cancelled due to timeout or client request"))
                .isEqualTo("ATTENDANCE_REPORT_QUERY_TIMEOUT");
        assertThat(RealtimeAttendanceReportSnapshotService.firstNonBlankLine(
                "\n### Error updating database.  Cause: "
                        + "Data truncation: Out of range value for "
                        + "column 'opening_hours' at row 1"))
                .contains("Out of range value for column 'opening_hours'");
    }

    @Test
    void recordsSafeCalculationFailuresWithoutSensitiveTags() {
        authorizePeriod(companyAuthorization());
        when(orchestrator.assemble(
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.eq(PeriodState.OPEN),
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException(
                        "employee evidence cannot be resolved"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW))
                .isInstanceOf(
                        com.szsemicon.hr.shared.web.ApiProblemException.class)
                .extracting("code")
                .isEqualTo(
                        "ATTENDANCE_REPORT_REALTIME_CALCULATION_UNAVAILABLE");

        assertThat(meterRegistry.get(
                        "attendance.report.realtime.safe.failures")
                .counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey())
                                .isIn("path", "result")));
    }

    @Test
    void readsPinnedSnapshotWithoutRecalculating() {
        authorizePeriod(companyAuthorization());
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));

        var first = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();
        var second = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        pinned.projectionVersion(),
                        NOW.plusSeconds(90))
                .orElseThrow();

        assertThat(first.projectionVersion())
                .isEqualTo(pinned.projectionVersion());
        assertThat(second.projectionVersion())
                .isEqualTo(pinned.projectionVersion());
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void selfServiceGetDoesNotCalculate() {
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL_ID,
                        com.szsemicon.hr.authorization.domain.CapabilityCodes
                                .ATTENDANCE_SELF_READ,
                        PERIOD,
                        NOW))
                .thenReturn(List.of(new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        PRINCIPAL_ID,
                        com.szsemicon.hr.authorization.domain.CapabilityCodes
                                .ATTENDANCE_SELF_READ,
                        COMPANY_ID,
                        PERIOD,
                        NOW))
                .thenReturn(Optional.of(companyAuthorization()));
        when(repository.hasCommittedSourceEvidence(
                        COMPANY_ID, PERIOD, NOW))
                .thenReturn(true);

        assertThatThrownBy(() -> service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                com.szsemicon.hr.authorization.domain.CapabilityCodes
                        .ATTENDANCE_SELF_READ,
                filter(COMPANY_ID, null, "employee-1"),
                null,
                NOW))
                .isInstanceOf(
                        com.szsemicon.hr.shared.web.ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PIN_NOT_READY");

        verify(orchestrator, never()).assemble(
                COMPANY_ID,
                PERIOD,
                PeriodState.OPEN,
                PRINCIPAL_ID,
                NOW);
    }

    @Test
    void employeeDayRecalcDoesNotCacheAPartialMonth() {
        authorizePeriod(companyAuthorization());
        AttendanceReportPublicationModels.PublicationResult published =
                new AttendanceReportPublicationModels.PublicationResult(
                        "11111111-1111-1111-1111-111111111111",
                        "ARP1-" + "a".repeat(64),
                        "a".repeat(64),
                        PeriodState.OPEN,
                        NOW,
                        NOW,
                        true);
        LocalDate writeStart = LocalDate.of(2026, 8, 10);
        LocalDate writeEndExclusive = LocalDate.of(2026, 8, 13);
        var plan = AttendanceReportDependencyPlan.bounded(COMPANY_ID, PERIOD,
                writeStart, writeEndExclusive, List.of("emp-1"));
        when(orchestrator.publishedBaseline(COMPANY_ID, PERIOD)).thenReturn(Optional.of(
                new AttendanceReportDependencyPlan.Baseline("old-pin", NOW.minusSeconds(1), "OPEN")));
        when(orchestrator.planWindow(org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(PERIOD), org.mockito.ArgumentMatchers.eq("old-pin"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(writeStart),
                org.mockito.ArgumentMatchers.eq(writeEndExclusive), org.mockito.ArgumentMatchers.eq(List.of("emp-1"))))
                .thenReturn(plan);
        when(orchestrator.assemblePlanned(plan, PeriodState.OPEN, PRINCIPAL_ID, NOW))
                .thenReturn(command());
        when(publisher.publishScoped(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(writeStart), org.mockito.ArgumentMatchers.eq(writeEndExclusive),
                org.mockito.ArgumentMatchers.eq(List.of("emp-1")), org.mockito.ArgumentMatchers.eq("old-pin")))
                .thenReturn(published);
        RealtimeAttendanceReportSnapshotService pinning =
                new RealtimeAttendanceReportSnapshotService(
                        orchestrator, repository, meterRegistry, publisher);

        var snapshot = pinning.recalculateEmployee(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(COMPANY_ID, null, "emp-1"),
                NOW,
                "emp-1",
                writeStart,
                writeEndExclusive);

        assertThat(snapshot.projectionVersion()).startsWith("ARP1-");
        assertThat(pinning.cacheMetrics().entries()).isZero();
        verify(publisher).publishScoped(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(writeStart), org.mockito.ArgumentMatchers.eq(writeEndExclusive),
                org.mockito.ArgumentMatchers.eq(List.of("emp-1")), org.mockito.ArgumentMatchers.eq("old-pin"));
    }

    @Test
    void firstCompleteCalculationPersistsAPinAndRecalculateReplacesIt() {
        authorizePeriod(companyAuthorization());
        authorizeRefresh();
        AttendanceReportPublicationModels.PublicationResult published =
                new AttendanceReportPublicationModels.PublicationResult(
                        "11111111-1111-1111-1111-111111111111",
                        "ARP1-" + "a".repeat(64),
                        "a".repeat(64),
                        PeriodState.OPEN,
                        NOW,
                        NOW,
                        true);
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());
        when(publisher.publish(org.mockito.ArgumentMatchers.any()))
                .thenReturn(published);
        RealtimeAttendanceReportSnapshotService pinning =
                new RealtimeAttendanceReportSnapshotService(
                        orchestrator, repository, meterRegistry, publisher);

        var first = pinning.recalculate(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        NOW);

        assertThat(first.projectionVersion()).startsWith("ARP1-");
        verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());

        AttendanceReportPublicationModels.PublicationResult next =
                new AttendanceReportPublicationModels.PublicationResult(
                        "22222222-2222-2222-2222-222222222222",
                        "ARP1-" + "b".repeat(64),
                        "b".repeat(64),
                        PeriodState.OPEN,
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(1),
                        true);
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW.plusSeconds(1)))
                .thenReturn(command(
                        COMPANY_ID,
                        PERIOD,
                        NOW.plusSeconds(1),
                        "b".repeat(64),
                        List.of(dailyFact())));
        when(publisher.publish(org.mockito.ArgumentMatchers.any()))
                .thenReturn(next);

        var recalculated = pinning.recalculate(
                PRINCIPAL_ID,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                filter(COMPANY_ID, null, null),
                NOW.plusSeconds(1));

        assertThat(recalculated.projectionVersion())
                .isEqualTo("ARP1-" + "b".repeat(64));
        verify(publisher, times(2)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedRecalculateDoesNotRequireANewPinReadOnTheNextGet() {
        authorizePeriod(companyAuthorization());
        authorizeRefresh();
        var pinned = snapshotFromCommand(command());
        when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(pinned));
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenThrow(new IllegalStateException("source not ready"));
        RealtimeAttendanceReportSnapshotService pinning =
                new RealtimeAttendanceReportSnapshotService(
                        orchestrator, repository, meterRegistry, publisher);

        assertThatThrownBy(() -> pinning.recalculate(
                        PRINCIPAL_ID,
                        CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                        filter(COMPANY_ID, null, null),
                        NOW))
                .isInstanceOf(com.szsemicon.hr.shared.web.ApiProblemException.class);

        var afterFailure = pinning.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();
        assertThat(afterFailure.projectionVersion())
                .isEqualTo(pinned.projectionVersion());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sourceNewerThanPinComparesCommittedCutoffsWithoutRecalculating() {
        var snapshot = new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                scope(ScopeType.COMPANY),
                filter(COMPANY_ID, null, null),
                "ARP1-" + "a".repeat(64),
                "OPEN",
                NOW,
                List.of(
                        "SOURCE.DELI_CLOUD:2026-08-12T00:00:00Z:"
                                + "a".repeat(64),
                        "SOURCE.OA_ATTENDANCE:2026-08-12T00:00:00Z:"
                                + "b".repeat(64)),
                List.of(dailyFact()),
                List.of(),
                List.of(),
                List.of());
        when(repository.listCommittedSourceCutoffs(NOW)).thenReturn(List.of(
                new AttendanceReportSourceRepository.SourceCutoff(
                        "DELI_CLOUD",
                        Instant.parse("2026-08-18T00:00:00Z"))));

        assertThat(service.sourcesNewerThanPin(snapshot, NOW)).isTrue();
        verify(orchestrator, never()).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newOrReversedAuditDecisionMakesPinStaleEvenWithoutNewProviderData() {
        var snapshot = new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                scope(ScopeType.COMPANY), filter(COMPANY_ID, null, null), "ARP1-" + "a".repeat(64), "OPEN", NOW,
                List.of(), List.of(), List.of(), List.of(), List.of());
        var provider = org.mockito.Mockito.mock(AttendanceReportDecisionVersionProvider.class);
        service.configureDecisionVersions(provider);
        String active = "DECISION.OT:recognition:" + "a".repeat(64);
        String reversed = "DECISION.OT:recognition:" + "b".repeat(64);
        when(provider.versions(COMPANY_ID, PERIOD, NOW)).thenReturn(List.of(active));
        assertThat(service.sourcesNewerThanPin(snapshot, NOW)).isTrue();
        var applied = new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                snapshot.scope(), snapshot.filter(), snapshot.projectionVersion(), snapshot.periodState(), NOW,
                List.of(active), List.of(), List.of(), List.of(), List.of());
        assertThat(service.sourcesNewerThanPin(applied, NOW)).isFalse();
        when(provider.versions(COMPANY_ID, PERIOD, NOW)).thenReturn(List.of(reversed));
        assertThat(service.sourcesNewerThanPin(applied, NOW)).isTrue();
        verify(provider, times(3)).versions(COMPANY_ID, PERIOD, NOW);
        verify(orchestrator, never()).assemble(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exclusionMarkerChangeAlsoInvalidatesPin() {
        var snapshot = new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                scope(ScopeType.COMPANY), filter(COMPANY_ID, null, null), "ARP1-" + "a".repeat(64), "OPEN", NOW,
                List.of(), List.of(), List.of(), List.of(), List.of());
        var provider = org.mockito.Mockito.mock(AttendanceReportDecisionVersionProvider.class);
        service.configureDecisionVersions(provider);
        String exclusionActive = "DECISION.EXCLUSION:exclusion-1:2026-10-06T01:00:00Z:" + "a".repeat(64);
        String exclusionReversed = "DECISION.EXCLUSION:exclusion-1:2026-10-06T01:00:00Z:" + "b".repeat(64);
        when(provider.versions(COMPANY_ID, PERIOD, NOW)).thenReturn(List.of(exclusionActive));
        assertThat(service.sourcesNewerThanPin(snapshot, NOW)).isTrue();
        var pinned = new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                snapshot.scope(), snapshot.filter(), snapshot.projectionVersion(), snapshot.periodState(), NOW,
                List.of(exclusionActive), List.of(), List.of(), List.of(), List.of());
        assertThat(service.sourcesNewerThanPin(pinned, NOW)).isFalse();
        when(provider.versions(COMPANY_ID, PERIOD, NOW)).thenReturn(List.of(exclusionReversed));
        assertThat(service.sourcesNewerThanPin(pinned, NOW)).isTrue();
        verify(provider, times(3)).versions(COMPANY_ID, PERIOD, NOW);
    }

    private static com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot
            snapshotFromCommand(PublishCommand command) {
        var daily = command.calculatedFacts().getFirst().facts().dailyFact();
        return new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot(
                scope(ScopeType.COMPANY),
                filter(COMPANY_ID, null, null),
                "ARP1-" + command.metadata().sourceSnapshotDigest(),
                "OPEN",
                command.metadata().dataAsOf(),
                command.metadata().sourceVersions(),
                List.of(daily),
                List.of(),
                List.of(),
                List.of());
    }

    private void authorizeRefresh() {
        when(repository.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(
                                CapabilityCodes.ATTENDANCE_REPORT_REFRESH),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(
                                CapabilityCodes.ATTENDANCE_REPORT_REFRESH),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(companyAuthorization()));
    }

    @Test
    void dateRangeWithoutPublishedBaseDoesNotReadOrPublishSource() {
        authorizePeriod(companyAuthorization());
        var refresh = org.mockito.Mockito.mock(AttendanceReportSourceRefreshPort.class);
        service.configureSourceRefresh(refresh);
        assertThatThrownBy(() -> service.recalculateRange(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), NOW, PERIOD.atDay(17), PERIOD.atDay(18)))
                .extracting("code").isEqualTo("ATTENDANCE_REPORT_BASE_PROJECTION_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(refresh);
    }

    @Test
    void rangeRefreshExpansionIsReadAgainBeforeCalculationAndUsesLatestCutoff() {
        authorizePeriod(companyAuthorization());
        var first = AttendanceReportDependencyPlan.bounded(COMPANY_ID, PERIOD,
                PERIOD.atDay(16), PERIOD.atDay(18), List.of());
        var expanded = AttendanceReportDependencyPlan.bounded(COMPANY_ID, PERIOD,
                PERIOD.atDay(12), PERIOD.atDay(18), List.of());
        when(orchestrator.publishedBaseline(COMPANY_ID, PERIOD)).thenReturn(Optional.of(
                new AttendanceReportDependencyPlan.Baseline("base-pin", NOW.minusSeconds(1), "OPEN")));
        when(orchestrator.planWindow(org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(PERIOD), org.mockito.ArgumentMatchers.eq("base-pin"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(List.of())))
                .thenReturn(first, expanded, expanded);
        var refresh = org.mockito.Mockito.mock(AttendanceReportSourceRefreshPort.class);
        when(refresh.refreshRange(COMPANY_ID, first.refreshFromInclusive(), first.refreshToExclusive(), NOW))
                .thenReturn(NOW.plusSeconds(1));
        when(refresh.refreshRange(COMPANY_ID, expanded.refreshFromInclusive(), expanded.refreshToExclusive(), NOW.plusSeconds(1)))
                .thenReturn(NOW.plusSeconds(2));
        service.configureSourceRefresh(refresh);
        when(orchestrator.assemblePlanned(expanded, PeriodState.OPEN, PRINCIPAL_ID, NOW.plusSeconds(2)))
                .thenReturn(command(COMPANY_ID, PERIOD, NOW.plusSeconds(2), "c".repeat(64), List.of()));
        var result = service.recalculateRange(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), NOW, PERIOD.atDay(17), PERIOD.atDay(18));
        assertThat(result.dataAsOf()).isEqualTo(NOW.plusSeconds(2));
        var order = org.mockito.Mockito.inOrder(refresh, orchestrator);
        order.verify(refresh).refreshRange(COMPANY_ID, first.refreshFromInclusive(), first.refreshToExclusive(), NOW);
        order.verify(refresh).refreshRange(COMPANY_ID, expanded.refreshFromInclusive(), expanded.refreshToExclusive(), NOW.plusSeconds(1));
        order.verify(orchestrator).assemblePlanned(expanded, PeriodState.OPEN, PRINCIPAL_ID, NOW.plusSeconds(2));
    }

    @Test
    void reopenedRangePreservesReopenedPeriodState() {
        authorizePeriod(companyAuthorization());
        var plan = AttendanceReportDependencyPlan.bounded(COMPANY_ID, PERIOD,
                PERIOD.atDay(16), PERIOD.atDay(18), List.of());
        when(orchestrator.publishedBaseline(COMPANY_ID, PERIOD)).thenReturn(Optional.of(
                new AttendanceReportDependencyPlan.Baseline("base-pin", NOW.minusSeconds(1), "REOPENED")));
        when(orchestrator.planWindow(org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                org.mockito.ArgumentMatchers.eq(PERIOD), org.mockito.ArgumentMatchers.eq("base-pin"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(List.of())))
                .thenReturn(plan);
        var original = command();
        var metadata = original.metadata();
        var reopened = new PublishCommand(new VerifiedProjectionMetadata(COMPANY_ID, PERIOD,
                PeriodState.REOPENED, metadata.formulaCatalogVersion(), metadata.sourceVersions(),
                metadata.sourceSnapshotDigest(), NOW, PRINCIPAL_ID), List.of(), List.of(), List.of(), List.of());
        when(orchestrator.assemblePlanned(plan, PeriodState.REOPENED, PRINCIPAL_ID, NOW)).thenReturn(reopened);
        assertThat(service.recalculateRange(PRINCIPAL_ID, CAPABILITY,
                filter(COMPANY_ID, null, null), NOW, PERIOD.atDay(17), PERIOD.atDay(18)).periodState())
                .isEqualTo("REOPENED");
    }

    @Test
    void internalFullMonthBoundsKeepMonthRefreshAndDoNotRequireExistingPin() {
        when(orchestrator.assemble(COMPANY_ID, PERIOD, PeriodState.OPEN, "SYSTEM", NOW)).thenReturn(command());
        service.materializeCompanyMonthWindow(COMPANY_ID, PERIOD, RecalcWindow.MONTH, NOW);
        verify(orchestrator).assemble(COMPANY_ID, PERIOD, PeriodState.OPEN, "SYSTEM", NOW);
        verify(orchestrator, never()).publishedBaseline(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void authorizePeriod(RealtimeAuthorization authorization) {
        when(repository.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorizationForPeriod(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(authorization));
        org.mockito.Mockito.lenient().when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(
                        repository.listCommittedSourceCutoffs(
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    private void authorize(RealtimeAuthorization authorization) {
        when(repository.listAuthorizedCompanies(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(PERIOD),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new CompanyOption(COMPANY_ID, "测试公司")));
        when(repository.resolveRealtimeAuthorization(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(authorization));
        org.mockito.Mockito.lenient().when(repository.loadAuthorizedSnapshot(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(
                        repository.listCommittedSourceCutoffs(
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
    }

    private static RealtimeAuthorization companyAuthorization() {
        return new RealtimeAuthorization(
                scope(ScopeType.COMPANY),
                COMPANY_ID,
                true,
                null,
                Set.of("employee-1"),
                Set.of("organization-a"));
    }

    private static AuthorizedScope scope(ScopeType type) {
        return new AuthorizedScope(
                type,
                "scope-reference",
                "测试范围",
                "scope-digest");
    }

    private static ReportFilter filter(
            String companyId,
            String organizationId,
            String employeeId) {
        return filter(PERIOD, companyId, organizationId, employeeId);
    }

    private static ReportFilter filter(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId) {
        return new ReportFilter(
                period,
                companyId,
                organizationId,
                employeeId,
                null);
    }

    private static PublishCommand command() {
        return command(
                COMPANY_ID,
                PERIOD,
                NOW,
                "a".repeat(64),
                List.of(dailyFact()));
    }

    private static PublishCommand command(
            String companyId,
            YearMonth period,
            Instant dataAsOf,
            String sourceDigest,
            List<DailyFact> dailyFacts) {
        return new PublishCommand(
                new VerifiedProjectionMetadata(
                        companyId,
                        period,
                        PeriodState.OPEN,
                        "FULL_CALCULATION_OA_FORM_HOURS_V8",
                        List.of("DELI:WATERMARK-1", "OA:WATERMARK-1"),
                        sourceDigest,
                        dataAsOf,
                        PRINCIPAL_ID),
                dailyFacts.stream()
                        .map(fact -> new VerifiedCalculatedFacts(
                                "employee-version-1",
                                "assignment-1",
                                new ProjectionFacts(fact, List.of())))
                        .toList(),
                List.of(),
                List.of(),
                List.of());
    }

    private static DailyFact dailyFact() {
        return dailyFact(
                "fact-1",
                COMPANY_ID,
                "employee-1",
                "organization-a");
    }

    private static DailyFact dailyFact(
            String factId,
            String companyId,
            String employeeId,
            String organizationId) {
        return new DailyFact(
                factId,
                companyId,
                employeeId,
                "E001",
                "测试员工",
                organizationId,
                "organization-version-1",
                "测试部门",
                LocalDate.of(2026, 8, 17),
                DayType.WEEKDAY,
                "白班",
                480,
                480,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                480,
                1,
                1,
                0,
                0,
                0,
                0,
                Instant.parse("2026-08-17T01:00:00Z"),
                Instant.parse("2026-08-17T09:00:00Z"),
                "ATTENDANCE.FULL_CALC:V2",
                "b".repeat(64),
                null);
    }
}
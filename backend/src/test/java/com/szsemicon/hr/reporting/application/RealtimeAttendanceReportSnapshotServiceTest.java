package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private RealtimeAttendanceReportSnapshotService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RealtimeAttendanceReportSnapshotService(
                orchestrator, repository, meterRegistry);
    }

    @Test
    void calculatesWithoutAReportProjectionAndCachesTheCompanyMonth() {
        authorize(companyAuthorization());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());

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
                        null,
                        NOW.plusSeconds(10))
                .orElseThrow();

        assertThat(first.projectionVersion())
                .isEqualTo("LIVE-" + "a".repeat(64));
        assertThat(first.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
        assertThat(second).isEqualTo(first);
        verify(orchestrator, times(1)).assemble(
                COMPANY_ID,
                PERIOD,
                PeriodState.OPEN,
                PRINCIPAL_ID,
                NOW);
        verify(repository, never()).loadAuthorizedSnapshot(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).loadAuthorizedSnapshotIntersection(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any());
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.cache.requests")
                .tag("result", "miss")
                .counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.cache.requests")
                .tag("result", "hit")
                .counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.calculation.duration")
                .timer()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.fact.rows")
                .summary()
                .totalAmount()).isEqualTo(1);
    }

    @Test
    void retainsTheFirstPageSnapshotAcrossTheNextTimeBucket() {
        authorize(companyAuthorization());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());
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
        verify(orchestrator, times(1)).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newCacheBucketCalculatesAtTheActualRequestInstant() {
        Instant nextBucketRequest = NOW.plusSeconds(20);
        authorize(companyAuthorization());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        nextBucketRequest))
                .thenReturn(command(
                        COMPANY_ID,
                        PERIOD,
                        nextBucketRequest,
                        "b".repeat(64),
                        List.of(dailyFact())));

        var first = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();
        var refreshed = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        nextBucketRequest)
                .orElseThrow();

        assertThat(refreshed.dataAsOf()).isEqualTo(nextBucketRequest);
        assertThat(refreshed.projectionVersion())
                .isNotEqualTo(first.projectionVersion());
        verify(orchestrator).assemble(
                COMPANY_ID,
                PERIOD,
                PeriodState.OPEN,
                PRINCIPAL_ID,
                nextBucketRequest);
    }

    @Test
    void retainedTokenExpiresAfterApproximatelyFiveMinutes() {
        Instant afterRetention = NOW.plusSeconds(301);
        authorize(companyAuthorization());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        afterRetention))
                .thenReturn(command(
                        COMPANY_ID,
                        PERIOD,
                        afterRetention,
                        "b".repeat(64),
                        List.of(dailyFact())));
        var first = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();

        var refreshed = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        first.projectionVersion(),
                        afterRetention)
                .orElseThrow();

        assertThat(refreshed.projectionVersion())
                .isNotEqualTo(first.projectionVersion());
        assertThat(service.cacheMetrics().entries()).isEqualTo(1);
        assertThat(service.cacheMetrics().evictions()).isEqualTo(1);
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
        when(repository.resolveRealtimeAuthorization(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        COMPANY_ID,
                        NOW))
                .thenReturn(Optional.of(companyAuthorization()));
        when(repository.resolveRealtimeAuthorization(
                        org.mockito.ArgumentMatchers.eq(scopedPrincipal),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new RealtimeAuthorization(
                        scope(ScopeType.ORGANIZATION),
                        COMPANY_ID,
                        false,
                        null,
                        Set.of("employee-1"),
                        Set.of("organization-a"))));
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
                                        "organization-b"),
                                dailyFact(
                                        "fact-cross-company",
                                        "company-b",
                                        "employee-1",
                                        "organization-a"))));

        var companySnapshot = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();
        var scopedSnapshot = service.loadAuthorizedSnapshot(
                        scopedPrincipal,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW.plusSeconds(10))
                .orElseThrow();
        var forgedOrganization = service.loadAuthorizedSnapshot(
                        scopedPrincipal,
                        CAPABILITY,
                        filter(COMPANY_ID, "organization-b", null),
                        null,
                        NOW.plusSeconds(11))
                .orElseThrow();

        assertThat(companySnapshot.dailyFacts()).hasSize(2);
        assertThat(scopedSnapshot.dailyFacts())
                .extracting(DailyFact::employeeId)
                .containsExactly("employee-1");
        assertThat(forgedOrganization.dailyFacts()).isEmpty();
        verify(orchestrator, times(1)).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(service.cacheMetrics().misses()).isEqualTo(1);
        assertThat(service.cacheMetrics().hits()).isEqualTo(2);
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
        when(repository.resolveRealtimeAuthorization(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        Optional.of(companyAuthorization()),
                        Optional.empty());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenReturn(command());

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

        verify(orchestrator, times(1)).assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void filtersFactsWithCurrentOrganizationAuthorization() {
        authorize(new RealtimeAuthorization(
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

        var snapshot = service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
                        NOW)
                .orElseThrow();

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
        when(repository.resolveRealtimeAuthorization(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        COMPANY_ID,
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
        when(repository.resolveRealtimeAuthorization(
                        org.mockito.ArgumentMatchers.eq(PRINCIPAL_ID),
                        org.mockito.ArgumentMatchers.eq(CAPABILITY),
                        org.mockito.ArgumentMatchers.eq(COMPANY_ID),
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
            assertThat(service.loadAuthorizedSnapshot(
                    PRINCIPAL_ID,
                    CAPABILITY,
                    filter(period, COMPANY_ID, null, null),
                    null,
                    NOW)).isPresent();
        }

        assertThat(service.cacheMetrics().entries()).isEqualTo(64);
        assertThat(service.cacheMetrics().maximumEntries()).isEqualTo(64);
        assertThat(service.cacheMetrics().misses()).isEqualTo(65);
        assertThat(service.cacheMetrics().evictions()).isEqualTo(1);

        assertThat(service.loadAuthorizedSnapshot(
                PRINCIPAL_ID,
                CAPABILITY,
                filter(
                        firstPeriod.plusMonths(100),
                        COMPANY_ID,
                        null,
                        null),
                null,
                NOW.plusSeconds(301))).isPresent();

        assertThat(service.cacheMetrics().entries()).isEqualTo(1);
        assertThat(service.cacheMetrics().evictions())
                .isGreaterThanOrEqualTo(65);
        assertThat(meterRegistry.get(
                        "attendance.report.realtime.cache.evictions")
                .counter()
                .count()).isGreaterThanOrEqualTo(65);
    }

    @Test
    void recordsSafeCalculationFailuresWithoutSensitiveTags() {
        authorize(companyAuthorization());
        when(orchestrator.assemble(
                        COMPANY_ID,
                        PERIOD,
                        PeriodState.OPEN,
                        PRINCIPAL_ID,
                        NOW))
                .thenThrow(new IllegalStateException(
                        "employee evidence cannot be resolved"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.loadAuthorizedSnapshot(
                        PRINCIPAL_ID,
                        CAPABILITY,
                        filter(COMPANY_ID, null, null),
                        null,
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
                        "FULL_CALCULATION_OVERTIME_CLASSIFICATION_V2",
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

package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
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
import org.springframework.http.HttpStatus;

class AttendanceReportQueryServiceTest {

    private static final String PRINCIPAL = "principal-report";
    private static final String COMPANY = "legal-a";
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void companyDirectoryIsBoundToCurrentPrincipalPeriodAndReadCapability() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        YearMonth period = YearMonth.of(2026, 7);
        var expected = List.of(
                new AttendanceReportSourceRepository.CompanyOption(
                        COMPANY, "神州半导体"));
        when(repository.listAuthorizedCompanies(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        period,
                        NOW))
                .thenReturn(expected);
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertThat(service.companies(period)).isEqualTo(expected);
        verify(capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_READ);
        verify(repository).listAuthorizedCompanies(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                period,
                NOW);
    }

    @Test
    void capabilityAndSqlScopeAreEvaluatedBeforeAggregationAndPaging() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                COMPANY,
                "org-a",
                null,
                null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE));
        ReportSourceSnapshot authorizedSnapshot = snapshot(filter);
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(authorizedSnapshot));
        when(repository.loadAuthorizedSnapshotIntersection(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                authorizedSnapshot,
                false,
                NOW)).thenReturn(Optional.of(authorizedSnapshot));
        var service = new AttendanceReportQueryService(
                capabilities,
                principal(),
                repository,
                CLOCK);

        AttendanceReportPage result = service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                filter.organizationId(),
                null,
                null,
                0,
                20);

        verify(capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_READ);
        verify(repository).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW);
        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.allowedActions())
                .containsExactly(
                        "REPORT_DRILL_DOWN",
                        "REPORT_EXPORT_CREATE");
    }

    @Test
    void productionRealtimePathDoesNotRequireAPublishedProjection() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        RealtimeAttendanceReportSnapshotService realtime =
                mock(RealtimeAttendanceReportSnapshotService.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7), COMPANY, null, null, null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD));
        when(realtime.loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        null,
                        NOW))
                .thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities,
                principal(),
                repository,
                realtime,
                CLOCK,
                new AttendanceReportCalculator());

        AttendanceReportPage result = service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                null,
                null,
                null,
                0,
                20);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.allowedActions())
                .containsExactly("REPORT_DRILL_DOWN");
        verify(realtime).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                null,
                NOW);
        verifyNoInteractions(repository);
    }

    @Test
    void productionRealtimePathTruncatesTheSystemClockToDatabasePrecision() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        RealtimeAttendanceReportSnapshotService realtime =
                mock(RealtimeAttendanceReportSnapshotService.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7), COMPANY, null, null, null);
        Instant systemNow = Instant.parse(
                "2026-07-29T01:00:00.123456789Z");
        Instant databaseNow = Instant.parse(
                "2026-07-29T01:00:00.123456Z");
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(realtime.loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        null,
                        databaseNow))
                .thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities,
                principal(),
                repository,
                realtime,
                Clock.fixed(systemNow, ZoneOffset.UTC),
                new AttendanceReportCalculator());

        assertThat(service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                null,
                null,
                null,
                0,
                20).totalRows()).isEqualTo(1);

        verify(realtime).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                null,
                databaseNow);
    }

    @Test
    void monthMatrixUsesTheSameAuthorizedSnapshotAndPagesByEmployee() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7),
                COMPANY,
                "org-a",
                null,
                null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities,
                principal(),
                repository,
                CLOCK);

        AttendanceMonthMatrixPage result = service.queryMonthMatrix(
                filter.period(),
                filter.companyId(),
                filter.organizationId(),
                null,
                0,
                20);

        verify(capabilities).require(
                CapabilityCodes.ATTENDANCE_REPORT_READ);
        verify(repository).loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW);
        assertThat(result.totalEmployees()).isEqualTo(1);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().days()).hasSize(31);
        assertThat(result.formulaVersion())
                .isEqualTo("ATTENDANCE_MONTH_MATRIX_V1");
        assertThat(result.allowedActions())
                .containsExactly("REPORT_DRILL_DOWN");
    }

    @Test
    void reportAndMonthMatrixAcceptTheSameExpectedProjectionVersion() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7), COMPANY, null, null, null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        AttendanceReportPage report = service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                null,
                null,
                null,
                "projection-1",
                0,
                50);
        AttendanceMonthMatrixPage matrix = service.queryMonthMatrix(
                filter.period(),
                filter.companyId(),
                null,
                null,
                "projection-1",
                0,
                20);

        assertThat(report.projectionVersion()).isEqualTo("projection-1");
        assertThat(matrix.projectionVersion()).isEqualTo("projection-1");
    }

    @Test
    void staleExpectedProjectionVersionReturnsOpaqueNonRetryableConflict() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7), COMPANY, null, null, null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                NOW)).thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertProjectionChanged(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                null,
                null,
                null,
                "older-projection",
                0,
                50));
        assertProjectionChanged(() -> service.queryMonthMatrix(
                filter.period(),
                filter.companyId(),
                null,
                null,
                "older-projection",
                0,
                20));

        verify(repository, org.mockito.Mockito.times(2))
                .loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        NOW);
    }

    @Test
    void staleRealtimeSnapshotTokenReturnsSafeRetryableConflict() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        RealtimeAttendanceReportSnapshotService realtime =
                mock(RealtimeAttendanceReportSnapshotService.class);
        ReportFilter filter = new ReportFilter(
                YearMonth.of(2026, 7), COMPANY, null, null, null);
        when(capabilities.currentCapabilities()).thenReturn(Set.of(
                CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(realtime.loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        "LIVE-older",
                        NOW))
                .thenReturn(Optional.of(snapshot(filter)));
        var service = new AttendanceReportQueryService(
                capabilities,
                principal(),
                repository,
                realtime,
                CLOCK,
                new AttendanceReportCalculator());

        assertSnapshotChanged(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                filter.period(),
                filter.companyId(),
                null,
                null,
                null,
                "LIVE-older",
                0,
                50));
        assertSnapshotChanged(() -> service.queryMonthMatrix(
                filter.period(),
                filter.companyId(),
                null,
                null,
                "LIVE-older",
                0,
                20));

        verify(realtime, org.mockito.Mockito.times(2))
                .loadAuthorizedSnapshot(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        "LIVE-older",
                        NOW);
        verifyNoInteractions(repository);
    }

    @Test
    void invalidExpectedProjectionVersionIsRejectedBeforeAuthorization() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        for (String invalid : List.of(
                " ",
                " projection-1",
                "projection\u0000-1",
                "p".repeat(129))) {
            assertThatThrownBy(() -> service.query(
                    ReportType.ATTENDANCE_DETAIL,
                    YearMonth.of(2026, 7),
                    COMPANY,
                    null,
                    null,
                    null,
                    invalid,
                    0,
                    50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.queryMonthMatrix(
                    YearMonth.of(2026, 7),
                    COMPANY,
                    null,
                    null,
                    invalid,
                    0,
                    20)).isInstanceOf(IllegalArgumentException.class);
        }

        verifyNoInteractions(capabilities, repository);
    }

    @Test
    void invalidPaginationIsRejectedBeforeAuthorizationOrRepositoryWork() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertThatThrownBy(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                YearMonth.of(2026, 7),
                null,
                null,
                null,
                null,
                0,
                201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                YearMonth.of(2026, 7),
                null,
                null,
                null,
                null,
                1_000_001,
                200)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(capabilities, repository);
    }

    @Test
    void invalidOrMisappliedFiltersAreRejectedBeforeAuthorization() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertThatThrownBy(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                YearMonth.of(2026, 7),
                "o".repeat(37),
                null,
                null,
                null,
                0,
                50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(
                ReportType.WORK_HOURS,
                YearMonth.of(2026, 7),
                null,
                null,
                null,
                "OPEN",
                0,
                50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(
                ReportType.EXCEPTIONS,
                YearMonth.of(2026, 7),
                null,
                null,
                null,
                "UNKNOWN",
                0,
                50)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(capabilities, repository);
    }

    @Test
    void missingPublishedProjectionIsNotMisreportedAsAnEmptyAuthorizedReport() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_REPORT_READ));
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                new ReportFilter(
                        YearMonth.of(2026, 7),
                        null,
                        null,
                        null,
                        null),
                NOW)).thenReturn(Optional.empty());
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertThatThrownBy(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                YearMonth.of(2026, 7),
                null,
                null,
                null,
                null,
                0,
                50))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PROJECTION_NOT_READY");
    }

    @Test
    void forgedCrossCompanyFilterCannotTurnAnEmptyAuthorizedSnapshotIntoData() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        AttendanceReportSourceRepository repository =
                mock(AttendanceReportSourceRepository.class);
        when(capabilities.currentCapabilities())
                .thenReturn(Set.of(CapabilityCodes.ATTENDANCE_REPORT_READ));
        var forged = new ReportFilter(
                YearMonth.of(2026, 7),
                "company-outside-scope",
                "organization-outside-scope",
                null,
                null);
        when(repository.loadAuthorizedSnapshot(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                forged,
                NOW)).thenReturn(Optional.empty());
        var service = new AttendanceReportQueryService(
                capabilities, principal(), repository, CLOCK);

        assertThatThrownBy(() -> service.query(
                ReportType.ATTENDANCE_DETAIL,
                forged.period(),
                forged.companyId(),
                forged.organizationId(),
                null,
                null,
                0,
                50))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PROJECTION_NOT_READY");
        assertThatThrownBy(() -> service.queryMonthMatrix(
                forged.period(),
                forged.companyId(),
                forged.organizationId(),
                null,
                0,
                50))
                .isInstanceOf(ApiProblemException.class)
                .extracting("code")
                .isEqualTo("ATTENDANCE_REPORT_PROJECTION_NOT_READY");
    }

    @Test
    void queryFingerprintBindsFiltersProjectionScopeAndFormula() {
        var filter = new ReportFilter(
                YearMonth.of(2026, 7),
                COMPANY,
                "org-a",
                "employee-a",
                null);

        String first = AttendanceReportQueryService.fingerprint(
                ReportType.WORK_HOURS,
                filter,
                "projection-1",
                "scope-a",
                "formula-1");
        String second = AttendanceReportQueryService.fingerprint(
                ReportType.WORK_HOURS,
                filter,
                "projection-1",
                "scope-b",
                "formula-1");
        String otherCompany = AttendanceReportQueryService.fingerprint(
                ReportType.WORK_HOURS,
                new ReportFilter(
                        filter.period(),
                        "legal-b",
                        filter.organizationId(),
                        filter.employeeId(),
                        filter.status()),
                "projection-1",
                "scope-a",
                "formula-1");

        assertThat(first)
                .hasSize(64)
                .isNotEqualTo(second)
                .isNotEqualTo(otherCompany);
    }

    private CurrentPrincipalProvider principal() {
        return () -> PRINCIPAL;
    }

    private static void assertProjectionChanged(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.status())
                                    .isEqualTo(HttpStatus.CONFLICT);
                            assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_REPORT_PROJECTION_CHANGED");
                            assertThat(problem.retryable()).isFalse();
                            assertThat(problem.getMessage())
                                    .doesNotContain("projection-1");
                        });
    }

    private static void assertSnapshotChanged(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.status())
                                    .isEqualTo(HttpStatus.CONFLICT);
                            assertThat(problem.code()).isEqualTo(
                                    "ATTENDANCE_REPORT_SNAPSHOT_CHANGED");
                            assertThat(problem.retryable()).isTrue();
                            assertThat(problem.getMessage())
                                    .doesNotContain("LIVE-older");
                        });
    }

    private ReportSourceSnapshot snapshot(ReportFilter filter) {
        return new ReportSourceSnapshot(
                new AuthorizedScope(
                        ScopeType.ORGANIZATION,
                        "scope-set:abc",
                        "当前授权组织范围",
                        "scope-digest"),
                filter,
                "projection-1",
                "OPEN",
                NOW,
                List.of("deli:20", "oa:8"),
                List.of(new DailyFact(
                        "fact-a",
                        "legal-a",
                        "employee-a",
                        "0007",
                        "陈思远",
                        "org-a",
                        "org-version-a",
                        "制造中心",
                        LocalDate.of(2026, 7, 1),
                        DayType.WEEKDAY,
                        "总部夏令班",
                        480,
                        480,
                        0,
                        0,
                        0,
                        480,
                        1,  // scheduledAttendanceDays
                        1,  // actualAttendanceDays
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        "calculation-a",
                        "digest-a")),
                List.of(),
                List.of(),
                List.of());
    }
}

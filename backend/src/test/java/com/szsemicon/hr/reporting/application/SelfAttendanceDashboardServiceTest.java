package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.AuthorizedSelf;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.DailyFact;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.DailyIssueCount;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.ExceptionTypeCount;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.RecentException;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.SourceSnapshot;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelfAttendanceDashboardServiceTest {

    private static final String PRINCIPAL = "principal-self";
    private static final AuthorizedSelf AUTHORIZED =
            new AuthorizedSelf("employee-self", "company-a");
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final LocalDate BUSINESS_DATE =
            LocalDate.of(2026, 7, 29);

    @Test
    void buildsOnlyTheCurrentEmployeesMonthAndTodayMetrics() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(AUTHORIZED));
        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(Optional.of(snapshot(NOW)));

        var dashboard = service(
                capabilities,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC))
                .query();

        assertThat(dashboard.businessDate())
                .isEqualTo(BUSINESS_DATE);
        assertThat(dashboard.summary())
                .isEqualTo(new SelfAttendanceDashboardService.Summary(
                        960, 930, 30, 30, 3));
        assertThat(dashboard.dailyTrend())
                .hasSize(7)
                .extracting(
                        SelfAttendanceDashboardService
                                .DailyTrendPoint::businessDate)
                .containsExactly(
                        LocalDate.of(2026, 7, 23),
                        LocalDate.of(2026, 7, 24),
                        LocalDate.of(2026, 7, 25),
                        LocalDate.of(2026, 7, 26),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 7, 28),
                        LocalDate.of(2026, 7, 29));
        assertThat(dashboard.dailyTrend().getFirst())
                .isEqualTo(
                        new SelfAttendanceDashboardService
                                .DailyTrendPoint(
                                        LocalDate.of(2026, 7, 23),
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        null,
                                        null));
        assertThat(dashboard.dailyTrend().getLast().issueCount())
                .isEqualTo(2);
        assertThat(dashboard.today().statusLabel())
                .isEqualTo("存在未解决异常");
        assertThat(dashboard.today().issueLabels())
                .containsExactly("LATE", "MISSING_PUNCH");
        assertThat(dashboard.recentExceptions())
                .extracting(RecentException::type)
                .containsExactly("MISSING_PUNCH", "LATE");
        verify(capabilities).require(
                CapabilityCodes.ATTENDANCE_SELF_READ);
    }

    @Test
    void missingEmployeeBindingOrSelfScopeIsForbidden() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, NOW))
                .thenReturn(Optional.empty());

        assertProblem(
                () -> service(
                                capabilities,
                                repository,
                                Clock.fixed(NOW, ZoneOffset.UTC))
                        .query(),
                403,
                "ATTENDANCE_SELF_SCOPE_REQUIRED",
                false);
        verify(repository, never()).loadLatestPublished(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingOrStalePublishedProjectionIsExplicitlyNotReady() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(AUTHORIZED));
        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(Optional.empty());
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertProblem(
                () -> service(capabilities, repository, clock).query(),
                409,
                "SELF_ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY",
                true);

        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(Optional.of(snapshot(
                        Instant.parse("2026-07-28T15:59:59Z"))));
        var dashboard = service(capabilities, repository, clock).query();
        assertThat(dashboard.recentExceptions())
                .extracting(RecentException::type)
                .contains("LATE");
    }

    @Test
    void publishedSelfSnapshotIsPreferredOverRealtimeReportPipeline() {
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        RealtimeAttendanceReportSnapshotService realtime =
                mock(RealtimeAttendanceReportSnapshotService.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, NOW))
                .thenReturn(Optional.of(AUTHORIZED));
        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(Optional.of(snapshot(NOW)));

        var dashboard = new SelfAttendanceDashboardService(
                capabilities,
                () -> PRINCIPAL,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                realtime)
                .query();

        assertThat(dashboard.summary().unresolvedExceptionCount())
                .isEqualTo(3);
        org.mockito.Mockito.verifyNoInteractions(realtime);
    }

    @Test
    void workbenchKeepsYesterdayExceptionsFirst() {
        Instant afternoon = Instant.parse("2026-07-29T05:00:00Z");
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, afternoon))
                .thenReturn(Optional.of(AUTHORIZED));
        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        BUSINESS_DATE,
                        afternoon))
                .thenReturn(Optional.of(snapshot(afternoon)));

        var dashboard = service(
                capabilities,
                repository,
                Clock.fixed(afternoon, ZoneOffset.UTC))
                .query();

        assertThat(dashboard.recentExceptions())
                .extracting(RecentException::type)
                .containsExactly("MISSING_PUNCH");
    }

    @Test
    void monthStartReturnsOnlyCoveredDatesAndNullableToday() {
        Instant now = Instant.parse("2026-07-03T01:00:00Z");
        LocalDate businessDate = LocalDate.of(2026, 7, 3);
        CurrentCapabilityService capabilities =
                mock(CurrentCapabilityService.class);
        SelfAttendanceDashboardRepository repository =
                mock(SelfAttendanceDashboardRepository.class);
        when(repository.resolveAuthorizedSelf(
                        PRINCIPAL, businessDate, now))
                .thenReturn(Optional.of(AUTHORIZED));
        when(repository.loadLatestPublished(
                        PRINCIPAL,
                        AUTHORIZED,
                        businessDate,
                        now))
                .thenReturn(Optional.of(new SourceSnapshot(
                        AUTHORIZED.employeeId(),
                        AUTHORIZED.companyId(),
                        "self-projection-1",
                        List.of("deli:20"),
                        now,
                        "OPEN",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of())));

        var dashboard = service(
                capabilities,
                repository,
                Clock.fixed(now, ZoneOffset.UTC))
                .query();

        assertThat(dashboard.dailyTrend())
                .hasSize(3)
                .allMatch(point -> point.scheduledMinutes() == 0);
        assertThat(dashboard.today()).isNull();
        assertThat(dashboard.summary().unresolvedExceptionCount())
                .isZero();
    }

    private static SourceSnapshot snapshot(Instant dataAsOf) {
        return new SourceSnapshot(
                AUTHORIZED.employeeId(),
                AUTHORIZED.companyId(),
                "self-projection-1",
                List.of("deli:20", "oa:8"),
                dataAsOf,
                "OPEN",
                List.of(
                        new DailyFact(
                                LocalDate.of(2026, 7, 27),
                                "日班",
                                480,
                                450,
                                30,
                                30,
                                Instant.parse(
                                        "2026-07-27T00:25:00Z"),
                                Instant.parse(
                                        "2026-07-27T09:35:00Z")),
                        new DailyFact(
                                BUSINESS_DATE,
                                "日班",
                                480,
                                480,
                                0,
                                0,
                                Instant.parse(
                                        "2026-07-29T00:20:00Z"),
                                null)),
                List.of(
                        new DailyIssueCount(
                                LocalDate.of(2026, 7, 27), 1),
                        new DailyIssueCount(BUSINESS_DATE, 2)),
                List.of("MISSING_PUNCH", "LATE"),
                List.of(
                        new ExceptionTypeCount("MISSING_PUNCH", 2),
                        new ExceptionTypeCount("LATE", 1)),
                List.of(
                        new RecentException(
                                BUSINESS_DATE,
                                "MISSING_PUNCH",
                                "ERROR",
                                "OPEN",
                                0,
                                "下班有效打卡缺失"),
                        new RecentException(
                                LocalDate.of(2026, 7, 27),
                                "LATE",
                                "WARNING",
                                "PENDING_REVIEW",
                                15,
                                "首次有效打卡晚于计划开始时间")));
    }

    private static SelfAttendanceDashboardService service(
            CurrentCapabilityService capabilities,
            SelfAttendanceDashboardRepository repository,
            Clock clock) {
        CurrentPrincipalProvider principalProvider = () -> PRINCIPAL;
        return new SelfAttendanceDashboardService(
                capabilities,
                principalProvider,
                repository,
                clock);
    }

    private static void assertProblem(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            int status,
            String code,
            boolean retryable) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        problem -> {
                            assertThat(problem.status().value())
                                    .isEqualTo(status);
                            assertThat(problem.code()).isEqualTo(code);
                            assertThat(problem.retryable())
                                    .isEqualTo(retryable);
                        });
    }
}

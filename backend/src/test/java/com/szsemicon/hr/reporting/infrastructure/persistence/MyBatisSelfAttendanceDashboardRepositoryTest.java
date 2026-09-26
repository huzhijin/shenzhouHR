package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.AuthorizedSelf;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MyBatisSelfAttendanceDashboardRepositoryTest {

    private static final String PRINCIPAL = "principal-self";
    private static final Instant NOW =
            Instant.parse("2026-07-29T01:00:00Z");
    private static final LocalDate BUSINESS_DATE =
            LocalDate.of(2026, 7, 29);
    private static final AuthorizedSelf AUTHORIZED =
            new AuthorizedSelf("employee-self", "company-a");

    @Test
    void blankPrincipalFailsClosedBeforeMapperAccess() {
        SelfAttendanceDashboardMapper mapper =
                mock(SelfAttendanceDashboardMapper.class);
        var repository = repository(mapper);

        assertThat(repository.resolveAuthorizedSelf(
                " ", BUSINESS_DATE, NOW)).isEmpty();
        assertThat(repository.loadLatestPublished(
                " ", AUTHORIZED, BUSINESS_DATE, NOW)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void resolvesOnlyTheExactSelfCapabilityAndScopeResult() {
        SelfAttendanceDashboardMapper mapper =
                mock(SelfAttendanceDashboardMapper.class);
        when(mapper.resolveAuthorizedSelf(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.AuthorizationRow(
                                "employee-self", "company-a")));

        assertThat(repository(mapper).resolveAuthorizedSelf(
                        PRINCIPAL, BUSINESS_DATE, NOW))
                .contains(AUTHORIZED);
    }

    @Test
    void mapsOnlySafeSelfFactsFromTheLatestProjection() {
        SelfAttendanceDashboardMapper mapper =
                mock(SelfAttendanceDashboardMapper.class);
        when(mapper.listLatestPublishedSelfProjection(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "company-a",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.ProjectionRow(
                                "projection-a",
                                "company-a",
                                "self-projection-1",
                                "[\"deli:20\"]",
                                NOW,
                                "OPEN")));
        when(mapper.listSelfDailyFacts(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "projection-a",
                        "company-a",
                        LocalDate.of(2026, 7, 1),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(new SelfDashboardRows.DailyRow(
                        BUSINESS_DATE,
                        "日班",
                        480,
                        450,
                        30,
                        0,
                        Instant.parse("2026-07-29T00:25:00Z"),
                        null)));
        when(mapper.listSelfDailyIssueCounts(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "projection-a",
                        "company-a",
                        LocalDate.of(2026, 7, 23),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.DailyIssueCountRow(
                                BUSINESS_DATE, 1)));
        when(mapper.listSelfTodayIssueLabels(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "projection-a",
                        "company-a",
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.TodayIssueLabelRow(
                                "MISSING_PUNCH")));
        when(mapper.listSelfExceptionTypeDistribution(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "projection-a",
                        "company-a",
                        LocalDate.of(2026, 7, 1),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.ExceptionTypeCountRow(
                                "MISSING_PUNCH", 1)));
        when(mapper.listSelfRecentExceptions(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "projection-a",
                        "company-a",
                        LocalDate.of(2026, 7, 1),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.RecentExceptionRow(
                                BUSINESS_DATE,
                                "MISSING_PUNCH",
                                "ERROR",
                                "OPEN",
                                0,
                                "下班有效打卡缺失")));

        var snapshot = repository(mapper).loadLatestPublished(
                PRINCIPAL, AUTHORIZED, BUSINESS_DATE, NOW);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().employeeId())
                .isEqualTo("employee-self");
        assertThat(snapshot.orElseThrow().sourceVersions())
                .containsExactly("deli:20");
        assertThat(snapshot.orElseThrow().dailyFacts())
                .extracting(item -> item.businessDate())
                .containsExactly(BUSINESS_DATE);
        assertThat(snapshot.orElseThrow().todayIssueLabels())
                .containsExactly("MISSING_PUNCH");
        assertThat(snapshot.orElseThrow().recentExceptions())
                .extracting(item -> item.safeEvidenceSummary())
                .containsExactly("下班有效打卡缺失");
        verify(mapper).listSelfDailyIssueCounts(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SELF_READ,
                "projection-a",
                "company-a",
                LocalDate.of(2026, 7, 23),
                BUSINESS_DATE,
                NOW);
    }

    @Test
    void mismatchedProjectionCompanyFailsClosedBeforeFactReads() {
        SelfAttendanceDashboardMapper mapper =
                mock(SelfAttendanceDashboardMapper.class);
        when(mapper.listLatestPublishedSelfProjection(
                        PRINCIPAL,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        "company-a",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        BUSINESS_DATE,
                        NOW))
                .thenReturn(List.of(
                        new SelfDashboardRows.ProjectionRow(
                                "projection-b",
                                "company-b",
                                "self-projection-2",
                                "[]",
                                NOW,
                                "OPEN")));

        assertThat(repository(mapper).loadLatestPublished(
                PRINCIPAL, AUTHORIZED, BUSINESS_DATE, NOW)).isEmpty();
        verify(mapper, org.mockito.Mockito.never())
                .listSelfDailyFacts(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static MyBatisSelfAttendanceDashboardRepository repository(
            SelfAttendanceDashboardMapper mapper) {
        return new MyBatisSelfAttendanceDashboardRepository(
                mapper, new ObjectMapper());
    }
}

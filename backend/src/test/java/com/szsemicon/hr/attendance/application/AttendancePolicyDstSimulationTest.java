package com.szsemicon.hr.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.ConfigurationSnapshot;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchDirection;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationResult;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationStatus;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.CalendarModels.WorkCalendarDay;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.SegmentType;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AttendancePolicyDstSimulationTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void resolves_gap_and_overlap_boundaries_deterministically() {
        ZoneId newYork = ZoneId.of("America/New_York");

        assertThat(AttendancePolicyService.resolveLocalInstant(
                LocalDateTime.parse("2026-11-01T01:30"),
                newYork,
                true))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(AttendancePolicyService.resolveLocalInstant(
                LocalDateTime.parse("2026-11-01T01:30"),
                newYork,
                false))
                .isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
        assertThat(AttendancePolicyService.resolveLocalInstant(
                LocalDateTime.parse("2026-03-08T02:30"),
                newYork,
                true))
                .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    }

    @Test
    void fall_back_lateness_uses_instant_order_not_repeated_wall_clock_order() {
        Fixture fixture = fixture("""
                {
                  "enabled":false,
                  "mealWindowStart":"18:00",
                  "mealWindowEnd":"20:00",
                  "deductionMinutes":30,
                  "triggerMinutes":240,
                  "applicableDayTypes":["WORKDAY"]
                }
                """);
        LocalDate businessDate = LocalDate.parse("2026-11-01");
        ConfigurationSnapshot configuration = configuration(
                businessDate,
                "America/New_York",
                new Segment(
                        SegmentType.WORK,
                        LocalTime.parse("01:30"),
                        0,
                        LocalTime.parse("09:30"),
                        0));
        SimulationInput input = new SimulationInput(
                "employee-1",
                businessDate,
                List.of(
                        punch(PunchDirection.ENTRY, "2026-11-01T01:15:00-05:00"),
                        punch(PunchDirection.EXIT, "2026-11-01T09:30:00-05:00")),
                Instant.parse("2026-11-02T00:00:00Z"));

        SimulationResult grace = fixture.service().simulateResolved(
                        input, configuration, usage(businessDate))
                .stream()
                .filter(result -> result.policyKind() == PolicyKind.LATE_GRACE)
                .findFirst()
                .orElseThrow();

        assertThat(grace.rawLateMinutes()).isEqualTo(45);
        assertThat(grace.status()).isEqualTo(SimulationStatus.LATE);
    }

    @Test
    void spring_forward_meal_threshold_uses_elapsed_minutes() {
        Fixture fixture = fixture("""
                {
                  "enabled":true,
                  "mealWindowStart":"01:00",
                  "mealWindowEnd":"03:00",
                  "deductionMinutes":30,
                  "triggerMinutes":90,
                  "applicableDayTypes":["WORKDAY"]
                }
                """);
        LocalDate businessDate = LocalDate.parse("2026-03-08");
        ConfigurationSnapshot configuration = configuration(
                businessDate,
                "America/New_York",
                new Segment(
                        SegmentType.WORK,
                        LocalTime.MIDNIGHT,
                        0,
                        LocalTime.parse("04:00"),
                        0));
        SimulationInput input = new SimulationInput(
                "employee-1",
                businessDate,
                List.of(
                        punch(PunchDirection.ENTRY, "2026-03-08T01:00:00-05:00"),
                        punch(PunchDirection.EXIT, "2026-03-08T03:00:00-04:00")),
                Instant.parse("2026-03-09T00:00:00Z"));

        SimulationResult meal = fixture.service().simulateResolved(
                        input, configuration, usage(businessDate))
                .stream()
                .filter(result -> result.policyKind() == PolicyKind.MEAL_DEDUCTION)
                .findFirst()
                .orElseThrow();

        assertThat(meal.status()).isEqualTo(SimulationStatus.NOT_MATCHED);
        assertThat(meal.matched()).isFalse();
    }

    private Fixture fixture(String mealParameters) {
        AttendancePolicyRepository repository =
                mock(AttendancePolicyRepository.class);
        when(repository.publishedVersionParameters("meal-version"))
                .thenReturn(mealParameters);
        when(repository.publishedVersionParameters("grace-version"))
                .thenReturn("""
                        {"enabled":true,"graceMinutes":15}
                        """);
        when(repository.publishedVersionParameters("monthly-version"))
                .thenReturn("""
                        {
                          "enabled":true,
                          "graceMinutes":15,
                          "monthlyUses":1,
                          "resetOnGroupChange":false
                        }
                        """);
        SecurityTokenService tokenService = new SecurityTokenService();
        var objectMapper = JsonMapper.builder().findAndAddModules().build();
        Clock clock = Clock.fixed(
                Instant.parse("2026-12-01T00:00:00Z"),
                ZoneOffset.UTC);
        AttendancePolicyService service = new AttendancePolicyService(
                mock(CurrentCapabilityService.class),
                mock(CurrentPrincipalProvider.class),
                mock(PeopleRepository.class),
                mock(AttendanceGroupRepository.class),
                repository,
                mock(AttendanceSetupIdempotencyService.class),
                new AttendancePolicyImpactTokenService(
                        tokenService, objectMapper, clock),
                mock(AuditService.class),
                tokenService,
                objectMapper,
                clock);
        return new Fixture(service);
    }

    private ConfigurationSnapshot configuration(
            LocalDate businessDate, String timeZone, Segment segment) {
        ShiftVersion shift = new ShiftVersion(
                "shift-version",
                "shift",
                1,
                VersionStatus.PUBLISHED,
                businessDate.minusDays(1),
                businessDate.plusDays(1),
                timeZone,
                List.of(segment),
                "shift-digest",
                1,
                "test",
                "actor",
                CREATED_AT,
                CREATED_AT,
                "actor",
                CREATED_AT);
        WorkCalendarDay day = new WorkCalendarDay(
                "day",
                "calendar",
                "calendar-version",
                businessDate,
                DayType.WORKDAY,
                null,
                0,
                "test",
                "actor",
                CREATED_AT,
                "actor",
                CREATED_AT);
        return new ConfigurationSnapshot(
                "RESOLVED",
                "employee-1",
                businessDate,
                "group",
                "group-revision",
                "location-revision",
                "calendar-version",
                day,
                shift,
                List.of(
                        binding(PolicyKind.MEAL_DEDUCTION, "meal-version"),
                        binding(PolicyKind.LATE_GRACE, "grace-version"),
                        binding(
                                PolicyKind.MONTHLY_LATE_EXEMPTION,
                                "monthly-version")),
                "configuration-digest",
                "employee-1:" + YearMonth.from(businessDate),
                "test");
    }

    private PolicyBinding binding(PolicyKind kind, String versionId) {
        return new PolicyBinding(
                "binding-" + kind,
                "revision-" + kind,
                1,
                "legal-entity",
                kind,
                versionId,
                "group",
                "group-revision",
                LocalDate.parse("2026-01-01"),
                null,
                LifecycleStatus.ACTIVE,
                "digest-" + kind,
                0,
                "test",
                "actor",
                CREATED_AT,
                "actor",
                CREATED_AT);
    }

    private PunchInput punch(PunchDirection direction, String value) {
        return new PunchInput(
                direction,
                OffsetDateTime.parse(value),
                "segment-0",
                "SCHEDULED_WORK");
    }

    private AttendanceMonthlyExemptionUsageProvider.UsageSnapshot usage(
            LocalDate businessDate) {
        return new AttendanceMonthlyExemptionUsageProvider.UsageSnapshot(
                "employee-1",
                YearMonth.from(businessDate),
                0,
                "TEST",
                CREATED_AT);
    }

    private record Fixture(AttendancePolicyService service) {
    }
}

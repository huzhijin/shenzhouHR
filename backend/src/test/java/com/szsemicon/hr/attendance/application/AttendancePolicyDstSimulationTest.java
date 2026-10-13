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
import com.szsemicon.hr.attendance.domain.MealDeductionPolicyResolver.Source;
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
                  "applicableDayTypes":["WORKDAY","WEEKEND"],
                  "sundayMealWindowStart":"01:00",
                  "sundayMealWindowEnd":"03:00",
                  "sundayDeductionMinutes":60,
                  "sundayTriggerMinutes":60
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

    @Test
    void spring_forward_weekend_override_uses_elapsed_minutes_and_selected_deduction() {
        Fixture fixture = fixture("""
                {
                  "enabled":true,
                  "mealWindowStart":"01:00",
                  "mealWindowEnd":"03:00",
                  "deductionMinutes":30,
                  "triggerMinutes":90,
                  "applicableDayTypes":["WORKDAY","WEEKEND"],
                  "sundayMealWindowStart":"01:00",
                  "sundayMealWindowEnd":"03:00",
                  "sundayDeductionMinutes":60,
                  "sundayTriggerMinutes":60
                }
                """);
        LocalDate businessDate = LocalDate.parse("2026-03-08");
        ConfigurationSnapshot configuration = configuration(
                businessDate,
                "America/New_York",
                DayType.WEEKEND,
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

        SimulationResult meal = mealResult(fixture, input, configuration);

        assertThat(meal.status()).isEqualTo(SimulationStatus.MATCHED);
        assertThat(meal.deductionMinutes()).isEqualTo(60);
        assertThat(meal.explanation()).contains("周日覆盖", "WEEKEND");
    }

    @Test
    void meal_window_is_not_covered_across_an_actual_exit_and_reentry_gap() {
        Fixture fixture = fixture("""
                {
                  "enabled":true,
                  "mealWindowStart":"11:20",
                  "mealWindowEnd":"11:40",
                  "deductionMinutes":60,
                  "triggerMinutes":0,
                  "applicableDayTypes":["WORKDAY"]
                }
                """);
        LocalDate businessDate = LocalDate.parse("2026-07-15");
        ConfigurationSnapshot configuration = configuration(
                businessDate,
                "UTC",
                new Segment(
                        SegmentType.WORK,
                        LocalTime.parse("08:00"),
                        0,
                        LocalTime.parse("23:00"),
                        0));
        SimulationInput input = new SimulationInput(
                "employee-1",
                businessDate,
                List.of(
                        punch(PunchDirection.ENTRY, "2026-07-15T10:00:00Z"),
                        punch(PunchDirection.EXIT, "2026-07-15T11:15:00Z"),
                        punch(PunchDirection.ENTRY, "2026-07-15T11:45:00Z"),
                        punch(PunchDirection.EXIT, "2026-07-15T14:00:00Z")),
                Instant.parse("2026-07-16T00:00:00Z"));

        SimulationResult meal = mealResult(fixture, input, configuration);

        assertThat(meal.status()).isEqualTo(SimulationStatus.NOT_MATCHED);
        assertThat(meal.deductionMinutes()).isNull();
    }

    @Test
    void saturday_and_sunday_calendar_weekends_use_distinct_deductions() {
        Fixture fixture = fixture(weekendOverrideParameters());
        Segment segment = new Segment(
                SegmentType.WORK,
                LocalTime.parse("08:00"),
                0,
                LocalTime.parse("23:00"),
                0);

        LocalDate saturday = LocalDate.parse("2026-08-01");
        SimulationResult saturdayMeal = mealResult(
                fixture,
                input(
                        saturday,
                        "2026-08-01T12:00:00Z",
                        "2026-08-01T19:00:00Z"),
                configuration(saturday, "UTC", DayType.WEEKEND, segment));

        LocalDate sunday = LocalDate.parse("2026-08-02");
        SimulationResult sundayMeal = mealResult(
                fixture,
                input(
                        sunday,
                        "2026-08-02T12:30:00Z",
                        "2026-08-02T21:00:00Z"),
                configuration(sunday, "UTC", DayType.WEEKEND, segment));

        assertThat(saturdayMeal.deductionMinutes()).isEqualTo(105);
        assertThat(saturdayMeal.matchedMealWindows())
                .extracting(value -> value.windowId())
                .containsExactly("SATURDAY_LUNCH", "SATURDAY_DINNER");
        assertThat(saturdayMeal.explanation())
                .contains("周六午餐", "周六覆盖晚餐", "总扣减 105 分钟");
        assertThat(sundayMeal.deductionMinutes()).isEqualTo(110);
        assertThat(sundayMeal.matchedMealWindows())
                .extracting(value -> value.windowId())
                .containsExactly("SUNDAY_LUNCH", "SUNDAY_DINNER");
        assertThat(sundayMeal.explanation())
                .contains("周日午餐", "周日覆盖晚餐", "总扣减 110 分钟");
    }

    @Test
    void special_workday_and_public_holiday_take_precedence_over_natural_weekend() {
        Fixture fixture = fixture(weekendOverrideParameters());
        Segment segment = new Segment(
                SegmentType.WORK,
                LocalTime.parse("08:00"),
                0,
                LocalTime.parse("23:00"),
                0);
        LocalDate saturday = LocalDate.parse("2026-08-01");
        LocalDate sunday = LocalDate.parse("2026-08-02");

        SimulationResult specialWorkday = mealResult(
                fixture,
                input(
                        saturday,
                        "2026-08-01T18:00:00Z",
                        "2026-08-01T20:00:00Z"),
                configuration(
                        saturday, "UTC", DayType.SPECIAL_WORKDAY, segment));
        SimulationResult publicHoliday = mealResult(
                fixture,
                input(
                        sunday,
                        "2026-08-02T18:00:00Z",
                        "2026-08-02T20:00:00Z"),
                configuration(
                        sunday, "UTC", DayType.PUBLIC_HOLIDAY, segment));

        assertThat(specialWorkday.deductionMinutes()).isEqualTo(30);
        assertThat(specialWorkday.explanation())
                .contains("基础参数", "SPECIAL_WORKDAY");
        assertThat(publicHoliday.deductionMinutes()).isEqualTo(30);
        assertThat(publicHoliday.explanation())
                .contains("基础参数", "PUBLIC_HOLIDAY");
    }

    @Test
    void public_holiday_uses_holiday_lunch_and_base_dinner_not_natural_weekend() {
        Fixture fixture = fixture(weekendOverrideParameters());
        LocalDate saturdayHoliday = LocalDate.parse("2026-08-01");
        Segment segment = new Segment(
                SegmentType.WORK,
                LocalTime.parse("08:00"),
                0,
                LocalTime.parse("23:00"),
                0);

        SimulationResult result = mealResult(
                fixture,
                input(
                        saturdayHoliday,
                        "2026-08-01T12:00:00Z",
                        "2026-08-01T20:00:00Z"),
                configuration(
                        saturdayHoliday,
                        "UTC",
                        DayType.PUBLIC_HOLIDAY,
                        segment));

        assertThat(result.deductionMinutes()).isEqualTo(90);
        assertThat(result.matchedMealWindows())
                .extracting(value -> value.windowId())
                .containsExactly("PUBLIC_HOLIDAY_LUNCH", "BASE_DINNER");
        assertThat(result.explanation())
                .contains("法定节假日午餐", "基础参数晚餐")
                .doesNotContain("周六午餐", "周六覆盖晚餐");
    }

    @Test
    void public_holiday_simulation_uses_independent_dinner_override() {
        Fixture fixture = fixture("""
                {
                  "enabled":true,
                  "mealWindowStart":"18:00",
                  "mealWindowEnd":"20:00",
                  "deductionMinutes":30,
                  "triggerMinutes":0,
                  "applicableDayTypes":["PUBLIC_HOLIDAY"],
                  "publicHolidayMealWindowStart":"18:30",
                  "publicHolidayMealWindowEnd":"19:15",
                  "publicHolidayDeductionMinutes":45,
                  "publicHolidayTriggerMinutes":180,
                  "publicHolidayLunchWindowStart":"12:00",
                  "publicHolidayLunchWindowEnd":"13:00",
                  "publicHolidayLunchDeductionMinutes":60,
                  "publicHolidayLunchTriggerMinutes":0
                }
                """);
        LocalDate saturdayHoliday = LocalDate.parse("2026-08-01");
        Segment segment = new Segment(
                SegmentType.WORK,
                LocalTime.parse("08:00"),
                0,
                LocalTime.parse("23:00"),
                0);

        SimulationResult result = mealResult(
                fixture,
                input(
                        saturdayHoliday,
                        "2026-08-01T12:00:00Z",
                        "2026-08-01T19:15:00Z"),
                configuration(
                        saturdayHoliday,
                        "UTC",
                        DayType.PUBLIC_HOLIDAY,
                        segment));

        assertThat(result.status()).isEqualTo(SimulationStatus.MATCHED);
        assertThat(result.deductionMinutes()).isEqualTo(105);
        assertThat(result.matchedMealWindows())
                .extracting(
                        value -> value.windowId(),
                        value -> value.source())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_LUNCH",
                                Source.PUBLIC_HOLIDAY_LUNCH),
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_DINNER",
                                Source.PUBLIC_HOLIDAY_OVERRIDE));
        assertThat(result.explanation())
                .contains(
                        "法定节假日午餐",
                        "法定节假日覆盖晚餐",
                        "总扣减 105 分钟")
                .doesNotContain("基础参数晚餐", "周六覆盖晚餐");
    }

    @Test
    void meal_window_requires_complete_half_open_coverage_at_exact_boundaries() {
        Fixture fixture = fixture("""
                {
                  "enabled":true,
                  "mealWindowStart":"18:00",
                  "mealWindowEnd":"20:00",
                  "deductionMinutes":30,
                  "triggerMinutes":0,
                  "applicableDayTypes":["WORKDAY"]
                }
                """);
        LocalDate businessDate = LocalDate.parse("2026-08-03");
        ConfigurationSnapshot configuration = configuration(
                businessDate,
                "UTC",
                new Segment(
                        SegmentType.WORK,
                        LocalTime.parse("08:00"),
                        0,
                        LocalTime.parse("23:00"),
                        0));

        SimulationResult exactCoverage = mealResult(
                fixture,
                input(
                        businessDate,
                        "2026-08-03T18:00:00Z",
                        "2026-08-03T20:00:00Z"),
                configuration);
        SimulationResult missingLastSecond = mealResult(
                fixture,
                input(
                        businessDate,
                        "2026-08-03T18:00:00Z",
                        "2026-08-03T19:59:59Z"),
                configuration);

        assertThat(exactCoverage.status()).isEqualTo(SimulationStatus.MATCHED);
        assertThat(missingLastSecond.status())
                .isEqualTo(SimulationStatus.NOT_MATCHED);
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
        return configuration(
                businessDate, timeZone, DayType.WORKDAY, segment);
    }

    private ConfigurationSnapshot configuration(
            LocalDate businessDate,
            String timeZone,
            DayType dayType,
            Segment segment) {
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
                dayType,
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

    private SimulationInput input(
            LocalDate businessDate, String entry, String exit) {
        return new SimulationInput(
                "employee-1",
                businessDate,
                List.of(
                        punch(PunchDirection.ENTRY, entry),
                        punch(PunchDirection.EXIT, exit)),
                businessDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private SimulationResult mealResult(
            Fixture fixture,
            SimulationInput input,
            ConfigurationSnapshot configuration) {
        return fixture.service().simulateResolved(
                        input, configuration, usage(input.businessDate()))
                .stream()
                .filter(result -> result.policyKind() == PolicyKind.MEAL_DEDUCTION)
                .findFirst()
                .orElseThrow();
    }

    private String weekendOverrideParameters() {
        return """
                {
                  "enabled":true,
                  "mealWindowStart":"18:00",
                  "mealWindowEnd":"20:00",
                  "deductionMinutes":30,
                  "triggerMinutes":60,
                  "applicableDayTypes":[
                    "WORKDAY","SPECIAL_WORKDAY","WEEKEND","PUBLIC_HOLIDAY"
                  ],
                  "saturdayMealWindowStart":"17:00",
                  "saturdayMealWindowEnd":"19:00",
                  "saturdayDeductionMinutes":45,
                  "saturdayTriggerMinutes":60,
                  "saturdayLunchWindowStart":"12:00",
                  "saturdayLunchWindowEnd":"13:00",
                  "saturdayLunchDeductionMinutes":60,
                  "saturdayLunchTriggerMinutes":0,
                  "sundayMealWindowStart":"19:00",
                  "sundayMealWindowEnd":"21:00",
                  "sundayDeductionMinutes":60,
                  "sundayTriggerMinutes":60,
                  "sundayLunchWindowStart":"12:30",
                  "sundayLunchWindowEnd":"13:15",
                  "sundayLunchDeductionMinutes":50,
                  "sundayLunchTriggerMinutes":30,
                  "publicHolidayLunchWindowStart":"12:00",
                  "publicHolidayLunchWindowEnd":"13:00",
                  "publicHolidayLunchDeductionMinutes":60,
                  "publicHolidayLunchTriggerMinutes":0
                }
                """;
    }

    private PolicyBinding binding(PolicyKind kind, String versionId) {
        return new PolicyBinding(
                "binding-" + kind,
                "revision-" + kind,
                1,
                "company",
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

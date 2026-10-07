package com.szsemicon.hr.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import com.szsemicon.hr.attendance.domain.MealDeductionPolicyResolver.Source;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MealDeductionPolicyResolverTest {

    @Test
    void legacy_parameters_fall_back_to_base_values_on_weekends() {
        var resolved = MealDeductionPolicyResolver.resolve(
                baseParameters(), DayType.WEEKEND, LocalDate.parse("2026-08-01"));
        var all = MealDeductionPolicyResolver.resolveAll(
                baseParameters(), DayType.WEEKEND, LocalDate.parse("2026-08-01"));

        assertThat(resolved.source()).isEqualTo(Source.BASE);
        assertThat(resolved.mealWindowStart()).isEqualTo(LocalTime.parse("18:00"));
        assertThat(resolved.deductionMinutes()).isEqualTo(30);
        assertThat(all).singleElement().satisfies(window -> {
            assertThat(window.windowId()).isEqualTo("BASE_DINNER");
            assertThat(window.mealType())
                    .isEqualTo(MealDeductionPolicyResolver.MealType.DINNER);
        });
    }

    @Test
    void weekend_calendar_days_select_distinct_saturday_and_sunday_values() {
        Map<String, Object> parameters = withWeekendOverrides();

        var saturday = MealDeductionPolicyResolver.resolve(
                parameters, DayType.WEEKEND, LocalDate.parse("2026-08-01"));
        var sunday = MealDeductionPolicyResolver.resolve(
                parameters, DayType.WEEKEND, LocalDate.parse("2026-08-02"));

        assertThat(saturday.source()).isEqualTo(Source.SATURDAY_OVERRIDE);
        assertThat(saturday.mealWindowStart())
                .isEqualTo(LocalTime.parse("17:00"));
        assertThat(saturday.deductionMinutes()).isEqualTo(45);
        assertThat(saturday.triggerMinutes()).isEqualTo(180);
        assertThat(sunday.source()).isEqualTo(Source.SUNDAY_OVERRIDE);
        assertThat(sunday.mealWindowStart())
                .isEqualTo(LocalTime.parse("19:00"));
        assertThat(sunday.deductionMinutes()).isEqualTo(60);
        assertThat(sunday.triggerMinutes()).isEqualTo(300);
    }

    @Test
    void authoritative_non_weekend_day_types_never_use_natural_weekend_overrides() {
        Map<String, Object> parameters = withWeekendOverrides();
        LocalDate saturday = LocalDate.parse("2026-08-01");

        for (DayType dayType : new DayType[]{
                DayType.WORKDAY,
                DayType.SPECIAL_WORKDAY,
                DayType.PUBLIC_HOLIDAY}) {
            var resolved = MealDeductionPolicyResolver.resolve(
                    parameters, dayType, saturday);
            assertThat(resolved.source()).isEqualTo(Source.BASE);
            assertThat(resolved.deductionMinutes()).isEqualTo(30);
        }
    }

    @Test
    void weekend_and_public_holiday_resolve_lunch_before_one_dinner() {
        Map<String, Object> parameters = withWeekendOverrides();
        parameters.put("saturdayLunchWindowStart", "12:00");
        parameters.put("saturdayLunchWindowEnd", "13:00");
        parameters.put("saturdayLunchDeductionMinutes", 60);
        parameters.put("saturdayLunchTriggerMinutes", 0);
        parameters.put("sundayLunchWindowStart", "12:30");
        parameters.put("sundayLunchWindowEnd", "13:15");
        parameters.put("sundayLunchDeductionMinutes", 50);
        parameters.put("sundayLunchTriggerMinutes", 30);
        parameters.put("publicHolidayLunchWindowStart", "12:00");
        parameters.put("publicHolidayLunchWindowEnd", "13:00");
        parameters.put("publicHolidayLunchDeductionMinutes", 60);
        parameters.put("publicHolidayLunchTriggerMinutes", 0);

        var saturday = MealDeductionPolicyResolver.resolveAll(
                parameters, DayType.WEEKEND, LocalDate.parse("2026-08-01"));
        var sunday = MealDeductionPolicyResolver.resolveAll(
                parameters, DayType.WEEKEND, LocalDate.parse("2026-08-02"));
        var holidayOnSaturday = MealDeductionPolicyResolver.resolveAll(
                parameters,
                DayType.PUBLIC_HOLIDAY,
                LocalDate.parse("2026-08-01"));
        var specialWorkdayOnSaturday = MealDeductionPolicyResolver.resolveAll(
                parameters,
                DayType.SPECIAL_WORKDAY,
                LocalDate.parse("2026-08-01"));

        assertThat(saturday)
                .extracting(value -> value.windowId())
                .containsExactly("SATURDAY_LUNCH", "SATURDAY_DINNER");
        assertThat(sunday)
                .extracting(value -> value.windowId())
                .containsExactly("SUNDAY_LUNCH", "SUNDAY_DINNER");
        assertThat(holidayOnSaturday)
                .extracting(value -> value.windowId())
                .containsExactly("PUBLIC_HOLIDAY_LUNCH", "BASE_DINNER");
        assertThat(specialWorkdayOnSaturday)
                .extracting(value -> value.windowId())
                .containsExactly("BASE_DINNER");
    }

    @Test
    void public_holiday_can_override_dinner_without_changing_legacy_fallback() {
        Map<String, Object> parameters = withWeekendOverrides();
        parameters.put("publicHolidayLunchWindowStart", "12:00");
        parameters.put("publicHolidayLunchWindowEnd", "13:00");
        parameters.put("publicHolidayLunchDeductionMinutes", 60);
        parameters.put("publicHolidayLunchTriggerMinutes", 0);
        parameters.put("publicHolidayMealWindowStart", "18:30");
        parameters.put("publicHolidayMealWindowEnd", "19:15");
        parameters.put("publicHolidayDeductionMinutes", 45);
        parameters.put("publicHolidayTriggerMinutes", 180);

        var holiday = MealDeductionPolicyResolver.resolveAll(
                parameters,
                DayType.PUBLIC_HOLIDAY,
                LocalDate.parse("2026-08-01"));
        var specialWorkday = MealDeductionPolicyResolver.resolveAll(
                parameters,
                DayType.SPECIAL_WORKDAY,
                LocalDate.parse("2026-08-01"));

        assertThat(holiday)
                .extracting(
                        value -> value.windowId(),
                        value -> value.source(),
                        value -> value.deductionMinutes(),
                        value -> value.triggerMinutes())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_LUNCH",
                                Source.PUBLIC_HOLIDAY_LUNCH,
                                60,
                                0),
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_DINNER",
                                Source.PUBLIC_HOLIDAY_OVERRIDE,
                                45,
                                180));
        assertThat(specialWorkday)
                .extracting(value -> value.windowId())
                .containsExactly("BASE_DINNER");
    }

    @Test
    void partial_override_is_rejected_even_if_called_without_prior_validation() {
        Map<String, Object> parameters = new HashMap<>(baseParameters());
        parameters.put("saturdayMealWindowStart", "17:00");

        assertThatThrownBy(() -> MealDeductionPolicyResolver.resolve(
                parameters, DayType.WEEKEND, LocalDate.parse("2026-08-01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saturday");
    }

    @Test
    void partial_lunch_group_is_rejected_even_when_that_day_type_is_not_selected() {
        Map<String, Object> parameters = new HashMap<>(baseParameters());
        parameters.put("sundayLunchWindowStart", "12:00");

        assertThatThrownBy(() -> MealDeductionPolicyResolver.resolveAll(
                parameters, DayType.WORKDAY, LocalDate.parse("2026-08-03")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sunday lunch");

        Map<String, Object> partialHolidayDinner =
                new HashMap<>(baseParameters());
        partialHolidayDinner.put(
                "publicHolidayMealWindowStart", "18:00");
        assertThatThrownBy(() -> MealDeductionPolicyResolver.resolveAll(
                partialHolidayDinner,
                DayType.WORKDAY,
                LocalDate.parse("2026-08-03")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public holiday dinner");
    }

    private static Map<String, Object> baseParameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("mealWindowStart", "18:00");
        parameters.put("mealWindowEnd", "20:00");
        parameters.put("deductionMinutes", 30);
        parameters.put("triggerMinutes", 240);
        return parameters;
    }

    private static Map<String, Object> withWeekendOverrides() {
        Map<String, Object> parameters = new HashMap<>(baseParameters());
        parameters.put("saturdayMealWindowStart", "17:00");
        parameters.put("saturdayMealWindowEnd", "19:00");
        parameters.put("saturdayDeductionMinutes", 45);
        parameters.put("saturdayTriggerMinutes", 180);
        parameters.put("sundayMealWindowStart", "19:00");
        parameters.put("sundayMealWindowEnd", "21:00");
        parameters.put("sundayDeductionMinutes", 60);
        parameters.put("sundayTriggerMinutes", 300);
        return parameters;
    }
}

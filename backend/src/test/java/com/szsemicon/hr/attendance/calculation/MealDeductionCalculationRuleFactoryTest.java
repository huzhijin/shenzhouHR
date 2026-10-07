package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.MealDeductionCalculationRuleFactory;
import com.szsemicon.hr.attendance.domain.CalendarModels.DayType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MealDeductionCalculationRuleFactoryTest {

    @Test
    void formal_rules_keep_saturday_and_sunday_windows_and_triggers_independent() {
        Map<String, Object> parameters = parameters();

        var saturday = MealDeductionCalculationRuleFactory.resolve(
                parameters,
                DayType.WEEKEND,
                LocalDate.parse("2026-08-01"),
                ZoneId.of("Asia/Shanghai"));
        var sunday = MealDeductionCalculationRuleFactory.resolve(
                parameters,
                DayType.WEEKEND,
                LocalDate.parse("2026-08-02"),
                ZoneId.of("Asia/Shanghai"));

        assertThat(saturday)
                .extracting(
                        value -> value.ruleId(),
                        value -> value.deductionMinutes(),
                        value -> value.triggerMinutes())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "SATURDAY_LUNCH", 60, 180),
                        org.assertj.core.groups.Tuple.tuple(
                                "SATURDAY_DINNER", 45, 240));
        assertThat(sunday)
                .extracting(
                        value -> value.ruleId(),
                        value -> value.deductionMinutes(),
                        value -> value.triggerMinutes())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "SUNDAY_LUNCH", 50, 300),
                        org.assertj.core.groups.Tuple.tuple(
                                "SUNDAY_DINNER", 30, 360));
    }

    @Test
    void authoritative_special_workday_uses_base_rule_not_natural_weekend() {
        var result = MealDeductionCalculationRuleFactory.resolve(
                parameters(),
                DayType.SPECIAL_WORKDAY,
                LocalDate.parse("2026-08-01"),
                ZoneId.of("Asia/Shanghai"));

        assertThat(result)
                .extracting(value -> value.ruleId())
                .containsExactly("BASE_DINNER");
    }

    @Test
    void formal_public_holiday_rules_use_optional_dinner_override_with_legacy_fallback() {
        Map<String, Object> parameters = parameters();
        var overridden = MealDeductionCalculationRuleFactory.resolve(
                parameters,
                DayType.PUBLIC_HOLIDAY,
                LocalDate.parse("2026-10-01"),
                ZoneId.of("Asia/Shanghai"));

        assertThat(overridden)
                .extracting(
                        value -> value.ruleId(),
                        value -> value.deductionMinutes(),
                        value -> value.triggerMinutes())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_LUNCH", 60, 0),
                        org.assertj.core.groups.Tuple.tuple(
                                "PUBLIC_HOLIDAY_DINNER", 45, 180));

        parameters.remove("publicHolidayMealWindowStart");
        parameters.remove("publicHolidayMealWindowEnd");
        parameters.remove("publicHolidayDeductionMinutes");
        parameters.remove("publicHolidayTriggerMinutes");
        var legacy = MealDeductionCalculationRuleFactory.resolve(
                parameters,
                DayType.PUBLIC_HOLIDAY,
                LocalDate.parse("2026-10-01"),
                ZoneId.of("Asia/Shanghai"));

        assertThat(legacy)
                .extracting(value -> value.ruleId())
                .containsExactly("PUBLIC_HOLIDAY_LUNCH", "BASE_DINNER");
    }

    private Map<String, Object> parameters() {
        Map<String, Object> values = new HashMap<>();
        values.put("enabled", true);
        values.put(
                "applicableDayTypes",
                List.of(
                        "WORKDAY",
                        "SPECIAL_WORKDAY",
                        "WEEKEND",
                        "PUBLIC_HOLIDAY"));
        values.put("mealWindowStart", "18:00");
        values.put("mealWindowEnd", "18:30");
        values.put("deductionMinutes", 30);
        values.put("triggerMinutes", 0);
        values.put("saturdayMealWindowStart", "18:00");
        values.put("saturdayMealWindowEnd", "18:30");
        values.put("saturdayDeductionMinutes", 45);
        values.put("saturdayTriggerMinutes", 240);
        values.put("sundayMealWindowStart", "18:00");
        values.put("sundayMealWindowEnd", "18:30");
        values.put("sundayDeductionMinutes", 30);
        values.put("sundayTriggerMinutes", 360);
        values.put("saturdayLunchWindowStart", "12:00");
        values.put("saturdayLunchWindowEnd", "13:00");
        values.put("saturdayLunchDeductionMinutes", 60);
        values.put("saturdayLunchTriggerMinutes", 180);
        values.put("sundayLunchWindowStart", "12:00");
        values.put("sundayLunchWindowEnd", "13:00");
        values.put("sundayLunchDeductionMinutes", 50);
        values.put("sundayLunchTriggerMinutes", 300);
        values.put("publicHolidayMealWindowStart", "18:30");
        values.put("publicHolidayMealWindowEnd", "19:15");
        values.put("publicHolidayDeductionMinutes", 45);
        values.put("publicHolidayTriggerMinutes", 180);
        values.put("publicHolidayLunchWindowStart", "12:00");
        values.put("publicHolidayLunchWindowEnd", "13:00");
        values.put("publicHolidayLunchDeductionMinutes", 60);
        values.put("publicHolidayLunchTriggerMinutes", 0);
        return values;
    }
}

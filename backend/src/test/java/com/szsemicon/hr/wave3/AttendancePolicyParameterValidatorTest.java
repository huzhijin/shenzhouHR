package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyParameterValidator;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendancePolicyParameterValidatorTest {

    @Test
    void accepts_only_complete_declared_and_typed_parameter_sets() {
        // V1 published versions contain only these six fields and must remain valid.
        assertThat(AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "20:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter(
                                "applicableDayTypes",
                                List.of("SPECIAL_WORKDAY", "WORKDAY")))))
                .isEmpty();
        assertThat(AttendancePolicyParameterValidator.validate(
                PolicyKind.LATE_GRACE,
                List.of(
                        parameter("enabled", true),
                        parameter("graceMinutes", 15L))))
                .isEmpty();
        assertThat(AttendancePolicyParameterValidator.validate(
                PolicyKind.MONTHLY_LATE_EXEMPTION,
                List.of(
                        parameter("enabled", true),
                        parameter("graceMinutes", 15),
                        parameter("monthlyUses", 1),
                        parameter("resetOnGroupChange", false))))
                .isEmpty();
    }

    @Test
    void accepts_complete_optional_weekend_and_holiday_meal_groups() {
        assertThat(AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "20:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter("applicableDayTypes", List.of("WEEKEND")),
                        parameter("saturdayMealWindowStart", "17:30"),
                        parameter("saturdayMealWindowEnd", "19:00"),
                        parameter("saturdayDeductionMinutes", 45),
                        parameter("saturdayTriggerMinutes", 180),
                        parameter("saturdayLunchWindowStart", "12:00"),
                        parameter("saturdayLunchWindowEnd", "13:00"),
                        parameter("saturdayLunchDeductionMinutes", 60),
                        parameter("saturdayLunchTriggerMinutes", 0),
                        parameter("sundayMealWindowStart", "18:30"),
                        parameter("sundayMealWindowEnd", "20:30"),
                        parameter("sundayDeductionMinutes", 60),
                        parameter("sundayTriggerMinutes", 300),
                        parameter("sundayLunchWindowStart", "12:30"),
                        parameter("sundayLunchWindowEnd", "13:15"),
                        parameter("sundayLunchDeductionMinutes", 50),
                        parameter("sundayLunchTriggerMinutes", 30),
                        parameter("publicHolidayMealWindowStart", "18:00"),
                        parameter("publicHolidayMealWindowEnd", "18:30"),
                        parameter("publicHolidayDeductionMinutes", 30),
                        parameter("publicHolidayTriggerMinutes", 240),
                        parameter("publicHolidayLunchWindowStart", "12:00"),
                        parameter("publicHolidayLunchWindowEnd", "13:00"),
                        parameter("publicHolidayLunchDeductionMinutes", 60),
                        parameter("publicHolidayLunchTriggerMinutes", 0))))
                .isEmpty();
    }

    @Test
    void rejects_partial_equal_or_out_of_range_weekend_override_groups() {
        var partialIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "20:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter("applicableDayTypes", List.of("WEEKEND")),
                        parameter("saturdayMealWindowStart", "17:30")));
        assertThat(partialIssues).extracting(value -> value.code())
                .contains("POLICY_PARAMETER_GROUP_INCOMPLETE")
                .doesNotContain("POLICY_PARAMETER_REQUIRED");

        var invalidSundayIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "20:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter("applicableDayTypes", List.of("WEEKEND")),
                        parameter("sundayMealWindowStart", "19:00"),
                        parameter("sundayMealWindowEnd", "19:00"),
                        parameter("sundayDeductionMinutes", 241),
                        parameter("sundayTriggerMinutes", 1441)));
        assertThat(invalidSundayIssues)
                .extracting(value -> value.code())
                .contains(
                        "POLICY_PARAMETER_FORMAT_INVALID",
                        "POLICY_PARAMETER_OUT_OF_RANGE");

        var partialLunchIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "20:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter(
                                "applicableDayTypes",
                                List.of("PUBLIC_HOLIDAY")),
                        parameter(
                                "publicHolidayLunchWindowStart",
                                "12:00")));
        assertThat(partialLunchIssues)
                .extracting(value -> value.code())
                .contains("POLICY_PARAMETER_GROUP_INCOMPLETE");

        var partialHolidayDinnerIssues =
                AttendancePolicyParameterValidator.validate(
                        PolicyKind.MEAL_DEDUCTION,
                        List.of(
                                parameter("enabled", true),
                                parameter("mealWindowStart", "18:00"),
                                parameter("mealWindowEnd", "20:00"),
                                parameter("deductionMinutes", 30),
                                parameter("triggerMinutes", 240),
                                parameter(
                                        "applicableDayTypes",
                                        List.of("PUBLIC_HOLIDAY")),
                                parameter(
                                        "publicHolidayMealWindowStart",
                                        "18:00")));
        assertThat(partialHolidayDinnerIssues)
                .extracting(value -> value.code())
                .contains("POLICY_PARAMETER_GROUP_INCOMPLETE")
                .doesNotContain("POLICY_PARAMETER_REQUIRED");
    }

    @Test
    void rejects_unknown_missing_duplicate_null_and_blank_keys() {
        var issues = AttendancePolicyParameterValidator.validate(
                PolicyKind.LATE_GRACE,
                List.of(
                        parameter("enabled", true),
                        parameter("enabled", false),
                        parameter("monthlyUses", 1),
                        parameter("", true),
                        parameter("graceMinutes", null)));

        assertThat(issues).extracting(value -> value.code())
                .contains(
                        "POLICY_PARAMETER_DUPLICATE",
                        "POLICY_PARAMETER_UNKNOWN",
                        "POLICY_PARAMETER_KEY_REQUIRED",
                        "POLICY_PARAMETER_VALUE_REQUIRED");
    }

    @Test
    void rejects_wrong_json_types_ranges_enums_and_time_windows() {
        var mealIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", "true"),
                        parameter("mealWindowStart", "not-a-time"),
                        parameter("mealWindowEnd", "18:00"),
                        parameter("deductionMinutes", 240.5),
                        parameter("triggerMinutes", 1441),
                        parameter(
                                "applicableDayTypes",
                                List.of("WEEKEND_ONLY", "WEEKEND_ONLY"))));

        assertThat(mealIssues).extracting(value -> value.code())
                .contains(
                        "POLICY_PARAMETER_TYPE_INVALID",
                        "POLICY_PARAMETER_FORMAT_INVALID",
                        "POLICY_PARAMETER_OUT_OF_RANGE",
                        "POLICY_PARAMETER_ENUM_INVALID");

        var equalWindowIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MEAL_DEDUCTION,
                List.of(
                        parameter("enabled", true),
                        parameter("mealWindowStart", "18:00"),
                        parameter("mealWindowEnd", "18:00"),
                        parameter("deductionMinutes", 30),
                        parameter("triggerMinutes", 240),
                        parameter("applicableDayTypes", List.of("WORKDAY"))));
        assertThat(equalWindowIssues).extracting(value -> value.code())
                .contains("POLICY_PARAMETER_FORMAT_INVALID");

        var boundaryIssues = AttendancePolicyParameterValidator.validate(
                PolicyKind.MONTHLY_LATE_EXEMPTION,
                List.of(
                        parameter("enabled", true),
                        parameter("graceMinutes", 14),
                        parameter("monthlyUses", 2),
                        parameter("resetOnGroupChange", "false")));

        assertThat(boundaryIssues).extracting(value -> value.code())
                .containsOnly(
                        "POLICY_PARAMETER_OUT_OF_RANGE",
                        "POLICY_PARAMETER_TYPE_INVALID");
    }

    private static ParameterValue parameter(String key, Object value) {
        return new ParameterValue(key, value);
    }
}

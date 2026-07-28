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

package com.szsemicon.hr.wave3;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.AttendancePolicyCatalog;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttendancePolicyCatalogValidationTest {

    @Test
    void catalog_exposes_only_the_three_attendance_owned_templates_and_fields() {
        var templates = AttendancePolicyCatalog.templates();

        assertThat(templates).extracting(value -> value.policyKind().name())
                .containsExactly(
                        "MEAL_DEDUCTION",
                        "LATE_GRACE",
                        "MONTHLY_LATE_EXEMPTION",
                        "PUNCH_WINDOW",
                        "PERIOD_CLOSE");

        assertThat(templates.get(0).fields())
                .extracting(
                        AttendancePolicyCatalog.FieldDefinition::key,
                        AttendancePolicyCatalog.FieldDefinition::valueType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enabled", "BOOLEAN"),
                        org.assertj.core.groups.Tuple.tuple(
                                "mealWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "mealWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple("deductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple("triggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "applicableDayTypes", "ENUM_LIST"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayMealWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayMealWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayTriggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayLunchWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayLunchWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayLunchDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "saturdayLunchTriggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayMealWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayMealWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayTriggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayLunchWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayLunchWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayLunchDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "sundayLunchTriggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayMealWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayMealWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayTriggerMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayLunchWindowStart", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayLunchWindowEnd", "LOCAL_TIME"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayLunchDeductionMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple(
                                "publicHolidayLunchTriggerMinutes", "INTEGER"));
        assertThat(templates.get(0).fields().subList(0, 6))
                .allSatisfy(field -> assertThat(field.required()).isTrue());
        assertThat(templates.get(0).fields().subList(6, 30))
                .allSatisfy(field -> assertThat(field.required()).isFalse());
        assertThat(templates.get(1).fields())
                .extracting(
                        AttendancePolicyCatalog.FieldDefinition::key,
                        AttendancePolicyCatalog.FieldDefinition::valueType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enabled", "BOOLEAN"),
                        org.assertj.core.groups.Tuple.tuple("graceMinutes", "INTEGER"));
        assertThat(templates.get(2).fields())
                .extracting(
                        AttendancePolicyCatalog.FieldDefinition::key,
                        AttendancePolicyCatalog.FieldDefinition::valueType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("enabled", "BOOLEAN"),
                        org.assertj.core.groups.Tuple.tuple("graceMinutes", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple("monthlyUses", "INTEGER"),
                        org.assertj.core.groups.Tuple.tuple("resetOnGroupChange", "BOOLEAN"));
    }

    @Test
    void catalog_boundary_forbids_executable_fields_and_duplicate_keys() {
        Set<String> forbiddenKeys = Set.of(
                "script", "expression", "command", "executable");
        Set<String> controlledTypes = Set.of(
                "BOOLEAN", "INTEGER", "DURATION", "LOCAL_TIME",
                "TIME_WINDOW", "ENUM", "ENUM_LIST");

        assertThat(AttendancePolicyCatalog.templates())
                .allSatisfy(template -> {
                    assertThat(template.fields())
                            .allSatisfy(field -> {
                                assertThat(field.key()).isNotIn(forbiddenKeys);
                                assertThat(field.valueType()).isIn(controlledTypes);
                            });
                    assertThat(template.fields())
                            .extracting(AttendancePolicyCatalog.FieldDefinition::key)
                            .doesNotHaveDuplicates();
                });
    }
}

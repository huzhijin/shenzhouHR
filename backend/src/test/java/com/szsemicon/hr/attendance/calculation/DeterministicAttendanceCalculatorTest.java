package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationPolicy;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationNodeType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.MealDeductionRule;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.CanonicalAttendanceDigests;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicAttendanceCalculatorTest {

    @Test
    void canonical_input_and_result_do_not_depend_on_collection_order() {
        var first = SyntheticAttendanceFixtures.mixedDay(false);
        var reordered = SyntheticAttendanceFixtures.mixedDay(true);

        assertThat(CanonicalAttendanceDigests.inputDigest(first))
                .isEqualTo(CanonicalAttendanceDigests.inputDigest(reordered));

        var calculator = new DeterministicAttendanceCalculator();
        var firstResult = calculator.calculate(
                "synthetic-calculation-v1", first);
        var reorderedResult = calculator.calculate(
                "synthetic-calculation-v1", reordered);

        assertThat(firstResult.resultDigest())
                .isEqualTo(reorderedResult.resultDigest());
        assertThat(firstResult.items()).isEqualTo(reorderedResult.items());
    }

    @Test
    void mixed_day_keeps_segment_results_metrics_and_explanation_chain() {
        var result = new DeterministicAttendanceCalculator().calculate(
                "synthetic-calculation-v1",
                SyntheticAttendanceFixtures.mixedDay(false));

        assertThat(result.metrics().scheduledMinutes()).isEqualTo(480);
        assertThat(result.metrics().confirmedScheduledWorkMinutes())
                .isEqualTo(240);
        assertThat(result.metrics().leaveOrTimeOffMinutes()).isEqualTo(240);
        assertThat(result.metrics().actualWorkMinutes()).isEqualTo(240);
        assertThat(result.items())
                .extracting(item -> item.category())
                .contains(ResultCategory.SCHEDULED_WORK, ResultCategory.LEAVE);
        assertThat(result.explanation().nodes())
                .extracting(node -> node.type())
                .contains(
                        ExplanationNodeType.DAILY_RESULT,
                        ExplanationNodeType.RESULT_ITEM,
                        ExplanationNodeType.SCHEDULED_SEGMENT,
                        ExplanationNodeType.EVIDENCE,
                        ExplanationNodeType.REQUEST);
        assertThat(result.resultDigest()).isNotBlank();
    }

    @Test
    void canonical_input_digest_includes_selected_weekend_lunch_and_dinner_values() {
        CalculationPolicy base = SyntheticAttendanceFixtures.defaultPolicy();
        var saturdayPolicy = new CalculationPolicy(
                base.lateGraceMaxMinutes(),
                base.monthlyLateGraceUses(),
                base.correctionDeadline(),
                base.timelyPendingSubmission(),
                base.overtimeFirstSubmittedAt(),
                base.overtimeSubmissionDeadlineMinutes(),
                List.of(
                        new MealDeductionRule(
                                "SATURDAY_LUNCH",
                                SyntheticAttendanceFixtures.interval(
                                        "2026-07-20T04:00:00Z",
                                        "2026-07-20T05:00:00Z"),
                                60,
                                true),
                        new MealDeductionRule(
                                "SATURDAY_DINNER",
                                SyntheticAttendanceFixtures.interval(
                                        "2026-07-20T09:00:00Z",
                                        "2026-07-20T11:00:00Z"),
                                45,
                                true)));
        var sundayPolicy = new CalculationPolicy(
                base.lateGraceMaxMinutes(),
                base.monthlyLateGraceUses(),
                base.correctionDeadline(),
                base.timelyPendingSubmission(),
                base.overtimeFirstSubmittedAt(),
                base.overtimeSubmissionDeadlineMinutes(),
                List.of(
                        new MealDeductionRule(
                                "SUNDAY_LUNCH",
                                SyntheticAttendanceFixtures.interval(
                                        "2026-07-20T04:30:00Z",
                                        "2026-07-20T05:15:00Z"),
                                50,
                                true),
                        new MealDeductionRule(
                                "SUNDAY_DINNER",
                                SyntheticAttendanceFixtures.interval(
                                        "2026-07-20T10:00:00Z",
                                        "2026-07-20T12:00:00Z"),
                                60,
                                true)));
        var saturday = SyntheticAttendanceFixtures.snapshot(
                List.of(), List.of(), List.of(), List.of(),
                saturdayPolicy, Instant.parse("2026-07-20T12:00:00Z"));
        var sunday = SyntheticAttendanceFixtures.snapshot(
                List.of(), List.of(), List.of(), List.of(),
                sundayPolicy, Instant.parse("2026-07-20T12:00:00Z"));

        assertThat(CanonicalAttendanceDigests.inputDigest(saturday))
                .isNotEqualTo(CanonicalAttendanceDigests.inputDigest(sunday));
    }

    @Test
    void canonical_input_digest_includes_public_holiday_dinner_trigger() {
        CalculationPolicy base = SyntheticAttendanceFixtures.defaultPolicy();
        var holidayDinnerWindow = SyntheticAttendanceFixtures.interval(
                "2026-07-20T10:30:00Z",
                "2026-07-20T11:15:00Z");
        var lowerTrigger = new CalculationPolicy(
                base.lateGraceMaxMinutes(),
                base.monthlyLateGraceUses(),
                base.correctionDeadline(),
                base.timelyPendingSubmission(),
                base.overtimeFirstSubmittedAt(),
                base.overtimeSubmissionDeadlineMinutes(),
                List.of(new MealDeductionRule(
                        "PUBLIC_HOLIDAY_DINNER",
                        holidayDinnerWindow,
                        45,
                        179,
                        true)));
        var higherTrigger = new CalculationPolicy(
                base.lateGraceMaxMinutes(),
                base.monthlyLateGraceUses(),
                base.correctionDeadline(),
                base.timelyPendingSubmission(),
                base.overtimeFirstSubmittedAt(),
                base.overtimeSubmissionDeadlineMinutes(),
                List.of(new MealDeductionRule(
                        "PUBLIC_HOLIDAY_DINNER",
                        holidayDinnerWindow,
                        45,
                        180,
                        true)));

        var first = SyntheticAttendanceFixtures.snapshot(
                List.of(), List.of(), List.of(), List.of(),
                lowerTrigger, Instant.parse("2026-07-20T12:00:00Z"));
        var second = SyntheticAttendanceFixtures.snapshot(
                List.of(), List.of(), List.of(), List.of(),
                higherTrigger, Instant.parse("2026-07-20T12:00:00Z"));

        assertThat(CanonicalAttendanceDigests.inputDigest(first))
                .isNotEqualTo(CanonicalAttendanceDigests.inputDigest(second));
    }
}

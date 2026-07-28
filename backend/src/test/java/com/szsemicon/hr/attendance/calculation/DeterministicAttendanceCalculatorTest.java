package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationNodeType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.CanonicalAttendanceDigests;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
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
}

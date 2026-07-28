package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationGraph;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.DifferenceCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.ItemChangeType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationTarget;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceResultDifferenceEngine;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttendanceResultDifferenceEngineTest {

    @Test
    void identifies_semantic_item_metric_and_causal_differences() {
        var oldVersion = version(
                "calculation-v1",
                "input-old",
                "result-old",
                ResultCategory.MISSING_PUNCH_PENDING,
                new AttendanceMetrics(240, 0, 0, 0, 0, 0, 0));
        var newVersion = version(
                "calculation-v2",
                "input-new",
                "result-new",
                ResultCategory.SCHEDULED_WORK,
                new AttendanceMetrics(240, 240, 0, 0, 0, 0, 240));

        var difference = new AttendanceResultDifferenceEngine().compare(
                "synthetic-difference",
                oldVersion,
                newVersion,
                List.of(DifferenceCategory.EVIDENCE_CHANGED),
                List.of("synthetic-correction-evidence"));

        assertThat(difference.itemDifferences()).hasSize(1);
        assertThat(difference.itemDifferences().getFirst().changeType())
                .isEqualTo(ItemChangeType.MODIFIED);
        assertThat(difference.metricDeltas())
                .containsEntry("W_IN", 240L)
                .containsEntry("ACTUAL_WORK", 240L);
        assertThat(difference.causalReferences())
                .containsExactly("synthetic-correction-evidence");
        assertThat(difference.differenceDigest()).isNotBlank();
    }

    private CalculationVersion version(
            String versionId,
            String inputDigest,
            String resultDigest,
            ResultCategory category,
            AttendanceMetrics metrics) {
        var interval = SyntheticAttendanceFixtures.interval(
                "2026-07-15T01:00:00Z",
                "2026-07-15T05:00:00Z");
        var item = new ResultItem(
                "synthetic-employee-001|2026-07-15|synthetic-segment-am|"
                        + interval.start() + "|" + interval.end(),
                "synthetic-segment-am",
                interval,
                category,
                category == ResultCategory.SCHEDULED_WORK ? 240 : 0,
                category.name(),
                List.of("synthetic-evidence"),
                category == ResultCategory.MISSING_PUNCH_PENDING
                        ? "synthetic-exception"
                        : null);
        var result = new DailyAttendanceResult(
                versionId,
                inputDigest,
                resultDigest,
                "w5-domain-v1",
                metrics,
                List.of(item),
                List.of(),
                List.of(),
                new ExplanationGraph(List.of(), List.of()),
                Set.of(),
                category == ResultCategory.MISSING_PUNCH_PENDING
                        ? List.of("synthetic-exception")
                        : List.of());
        return new CalculationVersion(
                versionId,
                new RecalculationTarget(
                        "synthetic-legal-entity",
                        "synthetic-employee-001",
                        SyntheticAttendanceFixtures.BUSINESS_DATE),
                inputDigest,
                resultDigest,
                "w5-domain-v1",
                versionId.endsWith("1") ? 1 : 2,
                "synthetic-period",
                1,
                Instant.parse("2026-07-23T00:00:00Z"),
                result);
    }
}

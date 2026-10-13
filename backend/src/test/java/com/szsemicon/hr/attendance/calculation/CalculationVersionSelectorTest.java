package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationBatchRequest;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationTarget;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.TargetOutcome;
import com.szsemicon.hr.attendance.calculation.domain.CalculationVersionSelector;
import com.szsemicon.hr.attendance.calculation.domain.DeterministicAttendanceCalculator;
import com.szsemicon.hr.attendance.calculation.domain.RecalculationRequestDigests;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CalculationVersionSelectorTest {

    private final CalculationVersionSelector selector =
            new CalculationVersionSelector();

    @Test
    void identical_input_and_algorithm_reuses_current_version() {
        CalculationVersion current = version(
                target("synthetic-employee-001", "2026-07-15"),
                "calculation-v1",
                1,
                "input-v1",
                "w5-domain-v1");
        CalculationVersion proposed = version(
                current.target(),
                "calculation-v2",
                2,
                "input-v1",
                "w5-domain-v1");

        var selection = selector.select(
                Optional.of(current), proposed);

        assertThat(selection.outcome())
                .isEqualTo(TargetOutcome.NO_CHANGE_REPLAY);
        assertThat(selection.selected()).isSameAs(current);
        assertThat(selection.appendRequired()).isFalse();
    }

    @Test
    void changed_input_requires_next_sequence_and_narrow_update() {
        RecalculationTarget target =
                target("synthetic-employee-001", "2026-07-15");
        RecalculationTarget unrelated =
                target("synthetic-employee-002", "2026-07-15");
        CalculationVersion current = version(
                target, "calculation-v1", 1, "input-v1", "w5-domain-v1");
        CalculationVersion unchanged = version(
                unrelated, "calculation-u1", 1, "input-u1", "w5-domain-v1");
        CalculationVersion next = version(
                target, "calculation-v2", 2, "input-v2", "w5-domain-v1");

        assertThat(selector.select(Optional.of(current), next).outcome())
                .isEqualTo(TargetOutcome.CALCULATED);
        Map<RecalculationTarget, CalculationVersion> updated =
                selector.applyNarrow(
                        Map.of(target, current, unrelated, unchanged),
                        Map.of(target, next));

        assertThat(updated.get(target)).isSameAs(next);
        assertThat(updated.get(unrelated)).isSameAs(unchanged);
        assertThatThrownBy(() -> selector.select(
                Optional.of(current),
                version(
                        target,
                        "calculation-v3",
                        3,
                        "input-v3",
                        "w5-domain-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next sequence");
    }

    @Test
    void targets_are_deduplicated_and_request_digest_is_order_stable() {
        RecalculationTarget first =
                target("synthetic-employee-001", "2026-07-15");
        RecalculationTarget second =
                target("synthetic-employee-002", "2026-07-16");
        var one = request(List.of(first, second, first));
        var two = request(List.of(second, first));

        assertThat(one.targets()).containsExactly(first, second);
        assertThat(RecalculationRequestDigests.canonicalRequestDigest(one))
                .isEqualTo(
                        RecalculationRequestDigests.canonicalRequestDigest(two));
    }

    private RecalculationBatchRequest request(
            List<RecalculationTarget> targets) {
        return new RecalculationBatchRequest(
                "synthetic-batch",
                "synthetic-company",
                "synthetic-period",
                1,
                "synthetic-open-token-v1",
                targets,
                List.of("synthetic-intent"),
                "synthetic narrow recalculation",
                "synthetic-actor",
                "synthetic-request",
                "synthetic-correlation",
                "synthetic-idempotency");
    }

    private CalculationVersion version(
            RecalculationTarget target,
            String versionId,
            long sequence,
            String inputDigest,
            String algorithmVersion) {
        var result = new DeterministicAttendanceCalculator().calculate(
                versionId, SyntheticAttendanceFixtures.mixedDay(false));
        return new CalculationVersion(
                versionId,
                target,
                inputDigest,
                result.resultDigest(),
                algorithmVersion,
                sequence,
                "synthetic-period",
                1,
                Instant.parse("2026-07-23T00:00:00Z"),
                result);
    }

    private RecalculationTarget target(
            String employeeId, String date) {
        return new RecalculationTarget(
                "synthetic-company",
                employeeId,
                LocalDate.parse(date));
    }
}

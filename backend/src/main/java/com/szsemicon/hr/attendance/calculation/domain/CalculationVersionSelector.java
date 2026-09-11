package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.TargetOutcome;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CalculationVersionSelector {

    public record Selection(
            TargetOutcome outcome,
            CalculationVersion selected,
            boolean appendRequired) {

        public Selection {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(selected, "selected");
            if (outcome == TargetOutcome.NO_CHANGE_REPLAY
                    && appendRequired) {
                throw new IllegalArgumentException(
                        "no-change replay cannot require append");
            }
        }
    }

    public Selection select(
            Optional<CalculationVersion> current,
            CalculationVersion proposed) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(proposed, "proposed");
        if (current.isEmpty()) {
            if (proposed.versionSequence() != 1) {
                throw new IllegalArgumentException(
                        "first calculation version sequence must be 1");
            }
            return new Selection(
                    TargetOutcome.CALCULATED, proposed, true);
        }
        CalculationVersion existing = current.orElseThrow();
        if (!existing.target().equals(proposed.target())) {
            throw new IllegalArgumentException(
                    "current and proposed versions must share a target");
        }
        if (existing.inputDigest().equals(proposed.inputDigest())
                && existing.algorithmVersion().equals(
                        proposed.algorithmVersion())) {
            return new Selection(
                    TargetOutcome.NO_CHANGE_REPLAY, existing, false);
        }
        if (proposed.versionSequence()
                != existing.versionSequence() + 1) {
            throw new IllegalArgumentException(
                    "changed calculation must append the next sequence");
        }
        return new Selection(TargetOutcome.CALCULATED, proposed, true);
    }

    public Map<AttendanceRecalculationModels.RecalculationTarget,
            CalculationVersion> applyNarrow(
            Map<AttendanceRecalculationModels.RecalculationTarget,
                    CalculationVersion> current,
            Map<AttendanceRecalculationModels.RecalculationTarget,
                    CalculationVersion> updates) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(updates, "updates");
        Map<AttendanceRecalculationModels.RecalculationTarget,
                CalculationVersion> result = new LinkedHashMap<>(current);
        updates.forEach((target, version) -> {
            if (!target.equals(version.target())) {
                throw new IllegalArgumentException(
                        "update key must match calculation target");
            }
            result.put(target, version);
        });
        return Map.copyOf(result);
    }
}

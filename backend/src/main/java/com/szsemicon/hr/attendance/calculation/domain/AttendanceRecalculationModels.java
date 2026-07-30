package com.szsemicon.hr.attendance.calculation.domain;

import static com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.requireText;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AttendanceRecalculationModels {

    private AttendanceRecalculationModels() {
    }

    public enum BatchStatus {
        REQUESTED,
        RUNNING,
        SUCCEEDED,
        PARTIALLY_FAILED,
        FAILED,
        CANCELLED
    }

    public enum TargetOutcome {
        CALCULATED,
        NO_CHANGE_REPLAY,
        FAILED
    }

    public enum DifferenceCategory {
        EVIDENCE_CHANGED,
        RULE_CHANGED,
        SCHEDULE_CHANGED,
        ADJUSTMENT_CHANGED,
        ALGORITHM_CHANGED,
        PERIOD_REOPENED
    }

    public enum ItemChangeType {
        ADDED,
        REMOVED,
        MODIFIED
    }

    public record RecalculationTarget(
            String companyId,
            String employeeId,
            LocalDate businessDate) {

        public RecalculationTarget {
            companyId = requireText(companyId, "companyId");
            employeeId = requireText(employeeId, "employeeId");
            Objects.requireNonNull(businessDate, "businessDate");
        }

        public String stableKey() {
            return companyId + "|" + employeeId + "|" + businessDate;
        }
    }

    public record RecalculationBatchRequest(
            String batchId,
            String companyId,
            String periodId,
            long periodVersion,
            String periodToken,
            List<RecalculationTarget> targets,
            List<String> triggerReferences,
            String reason,
            String actorId,
            String requestId,
            String correlationId,
            String idempotencyKey) {

        public RecalculationBatchRequest {
            batchId = requireText(batchId, "batchId");
            companyId = requireText(companyId, "companyId");
            periodId = requireText(periodId, "periodId");
            if (periodVersion < 0) {
                throw new IllegalArgumentException(
                        "periodVersion must be non-negative");
            }
            periodToken = requireText(periodToken, "periodToken");
            targets = Objects.requireNonNull(targets, "targets").stream()
                    .sorted(Comparator.comparing(
                            RecalculationTarget::stableKey))
                    .distinct()
                    .toList();
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "recalculation targets must not be empty");
            }
            for (RecalculationTarget target : targets) {
                if (!companyId.equals(target.companyId())) {
                    throw new IllegalArgumentException(
                            "targets must belong to the request company");
                }
            }
            triggerReferences = Objects.requireNonNull(
                            triggerReferences, "triggerReferences")
                    .stream()
                    .sorted()
                    .distinct()
                    .toList();
            reason = requireText(reason, "reason");
            actorId = requireText(actorId, "actorId");
            requestId = requireText(requestId, "requestId");
            correlationId = requireText(correlationId, "correlationId");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
    }

    public record CalculationVersion(
            String calculationVersionId,
            RecalculationTarget target,
            String inputDigest,
            String resultDigest,
            String algorithmVersion,
            long versionSequence,
            String periodId,
            long periodVersion,
            Instant calculatedAt,
            DailyAttendanceResult result) {

        public CalculationVersion {
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            Objects.requireNonNull(target, "target");
            inputDigest = requireText(inputDigest, "inputDigest");
            resultDigest = requireText(resultDigest, "resultDigest");
            algorithmVersion = requireText(
                    algorithmVersion, "algorithmVersion");
            if (versionSequence < 1 || periodVersion < 0) {
                throw new IllegalArgumentException(
                        "version sequences must be positive/non-negative");
            }
            periodId = requireText(periodId, "periodId");
            Objects.requireNonNull(calculatedAt, "calculatedAt");
            Objects.requireNonNull(result, "result");
        }
    }

    public record ItemDifference(
            String semanticKey,
            ItemChangeType changeType,
            ResultItem oldItem,
            ResultItem newItem) {

        public ItemDifference {
            semanticKey = requireText(semanticKey, "semanticKey");
            Objects.requireNonNull(changeType, "changeType");
            if (changeType == ItemChangeType.ADDED
                    && (oldItem != null || newItem == null)) {
                throw new IllegalArgumentException(
                        "added difference requires only new item");
            }
            if (changeType == ItemChangeType.REMOVED
                    && (oldItem == null || newItem != null)) {
                throw new IllegalArgumentException(
                        "removed difference requires only old item");
            }
            if (changeType == ItemChangeType.MODIFIED
                    && (oldItem == null || newItem == null)) {
                throw new IllegalArgumentException(
                        "modified difference requires old and new items");
            }
        }
    }

    public record AttendanceResultDifference(
            String differenceId,
            String oldCalculationVersionId,
            String newCalculationVersionId,
            String oldInputDigest,
            String newInputDigest,
            String oldResultDigest,
            String newResultDigest,
            List<DifferenceCategory> categories,
            List<ItemDifference> itemDifferences,
            Map<String, Long> metricDeltas,
            List<String> causalReferences,
            String differenceDigest) {

        public AttendanceResultDifference {
            differenceId = requireText(differenceId, "differenceId");
            oldCalculationVersionId = requireText(
                    oldCalculationVersionId, "oldCalculationVersionId");
            newCalculationVersionId = requireText(
                    newCalculationVersionId, "newCalculationVersionId");
            oldInputDigest = requireText(oldInputDigest, "oldInputDigest");
            newInputDigest = requireText(newInputDigest, "newInputDigest");
            oldResultDigest = requireText(oldResultDigest, "oldResultDigest");
            newResultDigest = requireText(newResultDigest, "newResultDigest");
            categories = Objects.requireNonNull(categories, "categories")
                    .stream()
                    .sorted()
                    .distinct()
                    .toList();
            itemDifferences = Objects.requireNonNull(
                            itemDifferences, "itemDifferences")
                    .stream()
                    .sorted(Comparator.comparing(
                            ItemDifference::semanticKey))
                    .toList();
            metricDeltas = Map.copyOf(Objects.requireNonNull(
                    metricDeltas, "metricDeltas"));
            causalReferences = Objects.requireNonNull(
                            causalReferences, "causalReferences")
                    .stream()
                    .sorted()
                    .distinct()
                    .toList();
            differenceDigest = requireText(
                    differenceDigest, "differenceDigest");
        }
    }

    static Map<String, Long> metricMap(AttendanceMetrics metrics) {
        return Map.of(
                "S", metrics.scheduledMinutes(),
                "W_IN", metrics.confirmedScheduledWorkMinutes(),
                "E", metrics.extendedPresenceMinutes(),
                "O", metrics.recognizedOvertimeMinutes(),
                "L", metrics.leaveOrTimeOffMinutes(),
                "A", metrics.absenceMinutes(),
                "ACTUAL_WORK", metrics.actualWorkMinutes());
    }
}

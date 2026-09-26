package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.AttendanceResultDifference;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.DifferenceCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.ItemChangeType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.ItemDifference;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class AttendanceResultDifferenceEngine {

    public AttendanceResultDifference compare(
            String differenceId,
            CalculationVersion oldVersion,
            CalculationVersion newVersion,
            List<DifferenceCategory> categories,
            List<String> causalReferences) {
        Objects.requireNonNull(oldVersion, "oldVersion");
        Objects.requireNonNull(newVersion, "newVersion");
        if (!oldVersion.target().equals(newVersion.target())) {
            throw new IllegalArgumentException(
                    "calculation versions must share a target");
        }
        Map<String, ResultItem> oldItems = bySemanticKey(
                oldVersion.result().items());
        Map<String, ResultItem> newItems = bySemanticKey(
                newVersion.result().items());
        List<ItemDifference> itemDifferences = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(oldItems.keySet());
        keys.addAll(newItems.keySet());
        for (String key : keys) {
            ResultItem oldItem = oldItems.get(key);
            ResultItem newItem = newItems.get(key);
            if (oldItem == null) {
                itemDifferences.add(new ItemDifference(
                        key, ItemChangeType.ADDED, null, newItem));
            } else if (newItem == null) {
                itemDifferences.add(new ItemDifference(
                        key, ItemChangeType.REMOVED, oldItem, null));
            } else if (!oldItem.equals(newItem)) {
                itemDifferences.add(new ItemDifference(
                        key,
                        ItemChangeType.MODIFIED,
                        oldItem,
                        newItem));
            }
        }
        Map<String, Long> oldMetrics =
                AttendanceRecalculationModels.metricMap(
                        oldVersion.result().metrics());
        Map<String, Long> newMetrics =
                AttendanceRecalculationModels.metricMap(
                        newVersion.result().metrics());
        Map<String, Long> metricDeltas = new LinkedHashMap<>();
        new TreeSet<>(oldMetrics.keySet()).forEach(key ->
                metricDeltas.put(
                        key, newMetrics.get(key) - oldMetrics.get(key)));
        List<String> digestParts = new ArrayList<>();
        digestParts.add(oldVersion.calculationVersionId());
        digestParts.add(newVersion.calculationVersionId());
        categories.stream().map(Enum::name).sorted().forEach(
                digestParts::add);
        itemDifferences.forEach(difference ->
                digestParts.add(difference.toString()));
        metricDeltas.forEach((key, value) ->
                digestParts.add(key + "=" + value));
        causalReferences.stream().sorted().forEach(digestParts::add);
        String differenceDigest = CanonicalAttendanceDigests.digestStrings(
                "W5_RESULT_DIFFERENCE_V1", digestParts);
        return new AttendanceResultDifference(
                differenceId,
                oldVersion.calculationVersionId(),
                newVersion.calculationVersionId(),
                oldVersion.inputDigest(),
                newVersion.inputDigest(),
                oldVersion.resultDigest(),
                newVersion.resultDigest(),
                categories,
                itemDifferences,
                metricDeltas,
                causalReferences,
                differenceDigest);
    }

    private Map<String, ResultItem> bySemanticKey(List<ResultItem> items) {
        Map<String, ResultItem> result = new LinkedHashMap<>();
        for (ResultItem item : items) {
            if (result.put(item.semanticKey(), item) != null) {
                throw new IllegalArgumentException(
                        "duplicate result item semantic key");
            }
        }
        return result;
    }
}

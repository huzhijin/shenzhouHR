package com.szsemicon.hr.reporting.domain;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Separates document visibility from the existing work-hours breakdown policy.
 * Independent documents must remain visible even when their intervals overlap.
 * Interval overlap is not evidence of a document identity or header relationship.
 */
public final class CoveringLeaveDocuments {

    private CoveringLeaveDocuments() {
    }

    /** Only identical copies of the same immutable document fact are redundant. */
    public static List<OaDocumentFact> distinctFacts(List<OaDocumentFact> facts) {
        return facts == null ? List.of() : facts.stream().distinct().toList();
    }

    /**
     * Retains the existing work-hours breakdown policy for compatibility.
     * This policy must not determine which documents appear in leave details.
     */
    public static List<OaDocumentFact> dropCoveringFacts(List<OaDocumentFact> facts) {
        if (facts == null || facts.size() < 2) {
            return facts == null ? List.of() : facts;
        }
        // NET_WORK_V1 rows are already net of same-type overlaps; dropping a
        // covering form again would lose its allocated share. Only legacy
        // (NULL) rows go through the old whole-form heuristic.
        Set<String> drop = coveringIds(facts.stream()
                .filter(fact -> !fact.netWorkAllocated())
                .map(fact -> new Span(
                        fact.documentId(),
                        fact.employeeId(),
                        fact.documentType(),
                        fact.start(),
                        fact.endExclusive(),
                        fact.recognizedMinutes(),
                        fact.sourceStatus()))
                .toList());
        if (drop.isEmpty()) {
            return facts;
        }
        // Drop by legacy identity only: a NET_WORK_V1 row with the same
        // document ID (e.g. a newer month's pin) is never removed here.
        return facts.stream()
                .filter(fact -> fact.netWorkAllocated() || !drop.contains(fact.documentId()))
                .toList();
    }

    public static Set<String> coveringIds(List<Span> spans) {
        Map<String, List<Span>> byEmployee = new LinkedHashMap<>();
        for (Span span : spans) {
            if (span == null || !isLeaveLike(span.documentType()) || span.start() == null
                    || span.end() == null || !span.start().isBefore(span.end())) {
                continue;
            }
            byEmployee.computeIfAbsent(
                    Objects.requireNonNullElse(span.employeeId(), ""),
                    ignored -> new ArrayList<>())
                    .add(span);
        }
        Set<String> drop = new HashSet<>();
        for (List<Span> group : byEmployee.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (Span candidate : group) {
                List<Span> contained = group.stream()
                        .filter(other -> other != candidate)
                        .filter(other -> strictlyContains(candidate, other))
                        .toList();
                if (contained.isEmpty()) {
                    continue;
                }
                long containedMinutes = contained.stream()
                        .mapToLong(Span::minutes)
                        .sum();
                boolean minutesMatch = containedMinutes == candidate.minutes()
                        && candidate.minutes() > 0;
                boolean unionCovers = unionCovers(candidate, contained);
                boolean pending = candidate.status() != null
                        && "PENDING".equalsIgnoreCase(candidate.status());
                if ((contained.size() >= 2 && (minutesMatch || unionCovers))
                        || (pending && minutesMatch)) {
                    drop.add(candidate.id());
                }
            }
        }
        return drop;
    }

    private static boolean isLeaveLike(String documentType) {
        if (documentType == null) {
            return false;
        }
        String type = documentType.toUpperCase(Locale.ROOT);
        return "LEAVE".equals(type) || "TIME_OFF".equals(type);
    }

    private static boolean strictlyContains(Span outer, Span inner) {
        return !inner.start().isBefore(outer.start())
                && !inner.end().isAfter(outer.end())
                && (inner.start().isAfter(outer.start())
                        || inner.end().isBefore(outer.end()));
    }

    private static boolean unionCovers(Span outer, List<Span> parts) {
        List<Span> ordered = parts.stream()
                .sorted((left, right) -> {
                    int start = left.start().compareTo(right.start());
                    return start != 0 ? start : left.end().compareTo(right.end());
                })
                .toList();
        Instant cursor = outer.start();
        Instant coveredEnd = cursor;
        for (Span part : ordered) {
            if (part.start().isAfter(coveredEnd)) {
                return false;
            }
            if (part.end().isAfter(coveredEnd)) {
                coveredEnd = part.end();
            }
        }
        return !coveredEnd.isBefore(outer.end());
    }

    public record Span(
            String id,
            String employeeId,
            String documentType,
            Instant start,
            Instant end,
            long minutes,
            String status) {
    }
}

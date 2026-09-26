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
 * Mixed-type OA leave on the same day often keeps both the typed detail
 * intervals and a covering header. The header is usually UNKNOWN 事假 whose
 * span/minutes equal the union of 调休+事假, and it double-counts unpaid leave.
 */
public final class CoveringLeaveDocuments {

    private CoveringLeaveDocuments() {
    }

    public static List<OaDocumentFact> dropCoveringFacts(List<OaDocumentFact> facts) {
        if (facts == null || facts.size() < 2) {
            return facts == null ? List.of() : facts;
        }
        Set<String> drop = coveringIds(facts.stream()
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
        return facts.stream()
                .filter(fact -> !drop.contains(fact.documentId()))
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
                boolean unknown = candidate.status() != null
                        && "UNKNOWN".equalsIgnoreCase(candidate.status());
                if ((contained.size() >= 2 && (minutesMatch || unionCovers))
                        || (unknown && minutesMatch)) {
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

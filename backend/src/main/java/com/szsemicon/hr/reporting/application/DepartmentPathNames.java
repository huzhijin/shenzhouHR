package com.szsemicon.hr.reporting.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a short-name organization ancestor chain into report department
 * fields. The attendance 「部门」 column joins every non-company short name
 * from 一级部门 to the leaf, yielding
 * {@code 服务中心-工程二部-RF-B组}. A zero-width space after each
 * hierarchy hyphen is a wrap opportunity so names like {@code RF-B组}
 * are not split.
 */
public final class DepartmentPathNames {

    public static final char SEGMENT_BREAK = '\u200B';
    public static final String SEGMENT_JOIN = "-" + SEGMENT_BREAK;

    private DepartmentPathNames() {
    }

    public record Levels(
            String levelOne,
            String levelTwo,
            String levelThree,
            String group,
            String reportDepartment,
            List<String> segments) {

        public Levels {
            segments = List.copyOf(segments == null ? List.of() : segments);
        }
    }

    public static Levels fromRootToLeaf(List<String> namesExcludingCompany) {
        List<String> names = namesExcludingCompany == null
                ? List.of()
                : namesExcludingCompany.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
        String levelOne = names.isEmpty() ? null : names.get(0);
        String levelTwo = names.size() > 1 ? names.get(1) : null;
        String levelThree = names.size() > 2 ? names.get(2) : null;
        String group = names.size() > 3 ? names.get(3) : null;
        String reportDepartment = names.isEmpty()
                ? null
                : String.join(SEGMENT_JOIN, names);
        return new Levels(
                levelOne, levelTwo, levelThree, group, reportDepartment, names);
    }

    public static Map<String, String> reportDepartmentsFromAncestors(
            List<AncestorName> rows) {
        Map<String, List<String>> chains = new java.util.LinkedHashMap<>();
        if (rows == null) {
            return Map.of();
        }
        for (AncestorName row : rows) {
            if (row == null || row.organizationId() == null) {
                continue;
            }
            if ("COMPANY".equalsIgnoreCase(row.orgType())) {
                continue;
            }
            if (row.name() == null || row.name().isBlank()) {
                continue;
            }
            chains.computeIfAbsent(row.organizationId(), key -> new ArrayList<>())
                    .add(row.name().trim());
        }
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        chains.forEach((organizationId, names) -> {
            String formatted = fromRootToLeaf(names).reportDepartment();
            if (formatted != null) {
                paths.put(organizationId, formatted);
            }
        });
        return Map.copyOf(paths);
    }

    /**
     * Prefers the longer of the closure path and the parent-pointer walk so
     * a missing parent column still yields {@code 服务中心-工程一部-DC组}.
     */
    public static Map<String, String> displayDepartments(
            Map<String, ParentName> graph,
            List<AncestorName> ancestors) {
        Map<String, String> closure = reportDepartmentsFromAncestors(ancestors);
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (graph != null) {
            ids.addAll(graph.keySet());
        }
        ids.addAll(closure.keySet());
        for (String organizationId : ids) {
            ParentName node = graph == null ? null : graph.get(organizationId);
            String fallback = node == null ? closure.get(organizationId) : node.name();
            String display = displayDepartment(
                    organizationId, fallback, closure, graph);
            if (display != null) {
                paths.put(organizationId, display);
            }
        }
        return Map.copyOf(paths);
    }

    public static String displayDepartment(
            String organizationId,
            String fallbackName,
            Map<String, String> closureDepartments,
            Map<String, ParentName> graph) {
        String closureDisplay = closureDepartments == null
                ? null
                : closureDepartments.get(organizationId);
        String parentDisplay = reportDepartment(
                organizationId, fallbackName, graph);
        return preferLongerDepartmentPath(
                closureDisplay, parentDisplay, fallbackName);
    }

    static String preferLongerDepartmentPath(
            String closureDisplay,
            String parentDisplay,
            String fallback) {
        int closureSegments = departmentSegmentCount(closureDisplay);
        int parentSegments = departmentSegmentCount(parentDisplay);
        if (closureSegments >= parentSegments && closureSegments > 0) {
            return closureDisplay;
        }
        if (parentSegments > 0) {
            return parentDisplay;
        }
        return fallback;
    }

    private static int departmentSegmentCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int breaks = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == SEGMENT_BREAK) {
                breaks++;
            }
        }
        return breaks + 1;
    }

    public static String reportDepartment(
            String organizationId,
            String fallbackName,
            Map<String, ParentName> graph) {
        if (organizationId == null || graph == null || graph.isEmpty()) {
            return fallbackName;
        }
        List<String> chain = new ArrayList<>();
        String current = organizationId;
        int guard = 0;
        while (current != null && guard++ < 16) {
            ParentName node = graph.get(current);
            if (node == null) {
                break;
            }
            if (!"COMPANY".equalsIgnoreCase(node.orgType())) {
                chain.add(0, node.name());
            }
            current = node.parentOrganizationId();
        }
        if (chain.isEmpty()) {
            return fallbackName;
        }
        String formatted = fromRootToLeaf(chain).reportDepartment();
        return formatted == null ? fallbackName : formatted;
    }

    public static Levels fromReportDepartment(String reportDepartment) {
        if (reportDepartment == null || reportDepartment.isBlank()) {
            return fromRootToLeaf(List.of());
        }
        List<String> names = new ArrayList<>();
        for (String part : reportDepartment.split(String.valueOf(SEGMENT_BREAK), -1)) {
            String trimmed = part;
            if (trimmed.endsWith("-")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.startsWith("-")) {
                trimmed = trimmed.substring(1);
            }
            trimmed = trimmed.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return fromRootToLeaf(names);
    }

    public static String visibleDepartment(String reportDepartment) {
        if (reportDepartment == null) {
            return null;
        }
        return reportDepartment.replace(String.valueOf(SEGMENT_BREAK), "");
    }

    public static String excelWrappedDepartment(String reportDepartment) {
        if (reportDepartment == null || reportDepartment.isEmpty()) {
            return reportDepartment;
        }
        return reportDepartment.replace(String.valueOf(SEGMENT_BREAK), "\n");
    }

    public record ParentName(
            String parentOrganizationId,
            String name,
            String orgType) {
    }

    public record AncestorName(
            String organizationId,
            String name,
            String orgType) {
    }
}

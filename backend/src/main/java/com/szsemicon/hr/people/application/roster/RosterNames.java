package com.szsemicon.hr.people.application.roster;

import java.util.List;
import java.util.Optional;

public final class RosterNames {

    public static final List<String> HEADERS = List.of(
            "序号", "公司名称", "工号", "姓名", "一级部门", "二级部门",
            "三级部门", "组别", "职位", "入职日期");

    private RosterNames() {
    }

    public static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()
                || "/".equals(trimmed)
                || "-".equals(trimmed)
                || "—".equals(trimmed)
                || "无".equals(trimmed)) {
            return "";
        }
        if (trimmed.startsWith("聚能-")) {
            trimmed = trimmed.substring("聚能-".length()).strip();
        } else if (trimmed.startsWith("神州-")) {
            trimmed = trimmed.substring("神州-".length()).strip();
        }
        return trimmed;
    }

    public static String alias(String companyCode, String name) {
        String normalized = normalizeToken(name);
        if (normalized.isEmpty()) {
            return "";
        }
        if ("SZJN".equals(companyCode)
                && ("RD1".equalsIgnoreCase(normalized) || "研发一部".equals(normalized))) {
            return "研发一部";
        }
        return normalized;
    }

    public static boolean namesMatch(String companyCode, String left, String right) {
        String first = alias(companyCode, left);
        String second = alias(companyCode, right);
        return !first.isEmpty() && first.equalsIgnoreCase(second);
    }

    public static Optional<String> mapCompanyCode(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return Optional.empty();
        }
        String name = companyName.strip();
        if (name.contains("芯越")) {
            return Optional.of("XINYUE");
        }
        if (name.contains("昇州")) {
            return Optional.of("SZSZ");
        }
        if (name.contains("聚能") || name.contains("晟州")) {
            return Optional.of("SZJN");
        }
        if (name.contains("神州")) {
            return Optional.of("SZSC");
        }
        return Optional.empty();
    }

    public static boolean isXinyue(String companyName) {
        return "XINYUE".equals(mapCompanyCode(companyName).orElse(""));
    }

    public static String fileName() {
        return "shenzhouhr-roster-import.xlsx";
    }

    public static boolean headerEquals(List<String> actual) {
        if (actual.size() < HEADERS.size()) {
            return false;
        }
        for (int index = 0; index < HEADERS.size(); index++) {
            String cell = actual.get(index) == null ? "" : actual.get(index).strip().replace("\n", "");
            if (!HEADERS.get(index).equals(cell)) {
                return false;
            }
        }
        return true;
    }
}

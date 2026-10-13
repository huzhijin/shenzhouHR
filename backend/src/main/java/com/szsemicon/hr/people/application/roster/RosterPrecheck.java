package com.szsemicon.hr.people.application.roster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RosterPrecheck {

    private RosterPrecheck() {
    }

    public static Result run(List<RosterRow> rows, Snapshot snapshot) {
        List<Issue> issues = new ArrayList<>();
        List<Diff> diffs = new ArrayList<>();
        Map<String, Integer> fileNumbers = new HashMap<>();
        for (RosterRow row : rows) {
            String key = RosterNames.mapCompanyCode(row.companyName()).orElse("?")
                    + "|" + row.employeeNumber().strip();
            Integer previous = fileNumbers.put(key, row.rowNumber());
            if (previous != null && !row.employeeNumber().isBlank()) {
                issues.add(blocking(
                        row.rowNumber(), "工号", "DUPLICATE_EMPLOYEE_NUMBER",
                        "同一公司工号在文件中重复（也出现在第 " + previous + " 行）"));
            }
        }
        for (RosterRow row : rows) {
            diffs.add(matchRow(row, snapshot, issues));
        }
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int conflict = 0;
        int error = 0;
        int blocking = 0;
        for (Diff diff : diffs) {
            switch (diff.category()) {
                case "ADDED" -> added++;
                case "UPDATED" -> updated++;
                case "UNCHANGED" -> unchanged++;
                case "CONFLICT" -> conflict++;
                case "ERROR" -> error++;
                default -> {
                }
            }
        }
        for (Issue issue : issues) {
            if ("BLOCKING".equals(issue.severity())) {
                blocking++;
            }
        }
        return new Result(
                List.copyOf(diffs),
                List.copyOf(issues),
                new Summary(added, updated, unchanged, conflict, error, blocking));
    }

    private static Diff matchRow(RosterRow row, Snapshot snapshot, List<Issue> issues) {
        Map<String, Object> source = sourceValues(row);
        if (RosterNames.isXinyue(row.companyName())) {
            issues.add(blocking(row.rowNumber(), "公司名称", "XINYUE_FORBIDDEN", "芯越人员不在本次导入范围"));
            return diff(row, "ERROR", null, source, null, source);
        }
        String companyCode = RosterNames.mapCompanyCode(row.companyName()).orElse(null);
        if (companyCode == null) {
            issues.add(blocking(row.rowNumber(), "公司名称", "UNKNOWN_COMPANY", "无法识别公司名称"));
            return diff(row, "ERROR", null, source, null, source);
        }
        Company company = snapshot.companyByCode.get(companyCode);
        if (company == null) {
            issues.add(blocking(row.rowNumber(), "公司名称", "UNKNOWN_COMPANY", "系统中没有对应公司"));
            return diff(row, "ERROR", null, source, null, source);
        }
        if (row.employeeNumber().isBlank()) {
            issues.add(blocking(row.rowNumber(), "工号", "EMPLOYEE_NUMBER_REQUIRED", "工号不能为空"));
            return diff(row, "ERROR", null, source, null, source);
        }
        if (row.displayName().isBlank()) {
            issues.add(blocking(row.rowNumber(), "姓名", "DISPLAY_NAME_REQUIRED", "姓名不能为空"));
            return diff(row, "ERROR", null, source, null, source);
        }
        if (row.hireDate() == null) {
            issues.add(blocking(row.rowNumber(), "入职日期", "HIRE_DATE_REQUIRED", "入职日期不能为空"));
            return diff(row, "ERROR", null, source, null, source);
        }
        List<String> path = row.pathTokens(company.code());
        if (path.isEmpty()) {
            issues.add(blocking(row.rowNumber(), "一级部门", "DEPARTMENT_PATH_REQUIRED", "部门路径不能为空"));
            return diff(row, "ERROR", null, source, null, source);
        }
        PathMatch pathMatch = matchPath(company, path, snapshot);
        if (pathMatch.conflictMessage() != null) {
            issues.add(blocking(row.rowNumber(), "一级部门", "ORGANIZATION_PATH_CONFLICT", pathMatch.conflictMessage()));
            return diff(row, "CONFLICT", pathMatch.leafId(), source, currentOrg(pathMatch, snapshot), proposed(row, company, path));
        }
        Employee existing = snapshot.employeeByNumber.get(company.id() + "|" + row.employeeNumber().strip());
        for (Employee sameName : snapshot.employeesByName.getOrDefault(
                company.id() + "|" + row.displayName().strip(), List.of())) {
            if (existing == null || !sameName.id().equals(existing.id())) {
                issues.add(warning(
                        row.rowNumber(), "姓名", "SAME_NAME_DIFFERENT_NUMBER",
                        "同公司已有同名员工，工号为 " + sameName.number() + "，不会自动合并"));
            }
        }
        Map<String, Object> proposed = proposed(row, company, path);
        if (existing == null) {
            return diff(row, "ADDED", pathMatch.leafId(), source, null, proposed);
        }
        Map<String, Object> current = currentEmployee(existing, snapshot);
        if (!existing.name().equals(row.displayName().strip())) {
            issues.add(blocking(
                    row.rowNumber(), "姓名", "EMPLOYEE_NAME_CONFLICT",
                    "工号已存在且姓名不同（现为 " + existing.name() + "）"));
            return diff(row, "CONFLICT", existing.id(), source, current, proposed);
        }
        if (!Objects.equals(existing.organizationId(), pathMatch.leafId())
                && pathMatch.leafId() != null
                && existing.organizationId() != null) {
            issues.add(blocking(
                    row.rowNumber(), "一级部门", "EMPLOYMENT_MOVE_CONFLICT",
                    "该工号当前部门与文件不一致，发布将调动任职"));
            return diff(row, "CONFLICT", existing.id(), source, current, proposed);
        }
        if (Objects.equals(existing.organizationId(), pathMatch.leafId())
                && existing.name().equals(row.displayName().strip())) {
            return diff(row, "UNCHANGED", existing.id(), source, current, proposed);
        }
        return diff(row, "UPDATED", existing.id(), source, current, proposed);
    }

    private static PathMatch matchPath(Company company, List<String> path, Snapshot snapshot) {
        List<Org> nodes = snapshot.orgsByCompany.getOrDefault(company.id(), List.of());
        String parentId = companyRoot(company, nodes);
        String leafId = parentId;
        for (int index = 0; index < path.size(); index++) {
            String token = path.get(index);
            List<Org> matches = childrenNamed(nodes, company.code(), parentId, token);
            if (matches.isEmpty() && index == 0) {
                matches = childrenNamed(nodes, company.code(), null, token);
            }
            if (matches.size() > 1) {
                return new PathMatch(null, "部门 “" + token + "” 在同一上级下有多个同名节点");
            }
            if (matches.size() == 1) {
                leafId = matches.get(0).id();
                parentId = leafId;
                continue;
            }
            leafId = null;
            break;
        }
        return new PathMatch(leafId, null);
    }

    private static List<Org> childrenNamed(
            List<Org> nodes, String companyCode, String parentId, String token) {
        List<Org> matches = new ArrayList<>();
        for (Org org : nodes) {
            if (!Objects.equals(org.parentId(), parentId)) {
                continue;
            }
            if (RosterNames.namesMatch(companyCode, org.name(), token)) {
                matches.add(org);
            }
        }
        return matches;
    }

    private static String companyRoot(Company company, List<Org> nodes) {
        for (Org org : nodes) {
            if ("COMPANY".equals(org.type()) && org.parentId() == null) {
                return org.id();
            }
        }
        for (Org org : nodes) {
            if (org.parentId() == null) {
                return org.id();
            }
        }
        return null;
    }

    private static Map<String, Object> sourceValues(RosterRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rowNumber", row.rowNumber());
        values.put("companyName", row.companyName());
        values.put("employeeNumber", row.employeeNumber());
        values.put("displayName", row.displayName());
        values.put("level1", row.level1());
        values.put("level2", row.level2());
        values.put("level3", row.level3());
        values.put("groupName", row.groupName());
        values.put("title", row.title());
        values.put("hireDate", row.hireDate() == null ? null : row.hireDate().toString());
        return values;
    }

    private static Map<String, Object> proposed(RosterRow row, Company company, List<String> path) {
        Map<String, Object> values = new LinkedHashMap<>(sourceValues(row));
        values.put("companyId", company.id());
        values.put("companyCode", company.code());
        values.put("path", path);
        return values;
    }

    private static Map<String, Object> currentEmployee(Employee employee, Snapshot snapshot) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("employeeId", employee.id());
        values.put("employeeNumber", employee.number());
        values.put("displayName", employee.name());
        values.put("organizationId", employee.organizationId());
        Org org = snapshot.orgById.get(employee.organizationId());
        values.put("organizationName", org == null ? null : org.name());
        return values;
    }

    private static Map<String, Object> currentOrg(PathMatch match, Snapshot snapshot) {
        if (match.leafId() == null) {
            return null;
        }
        Org org = snapshot.orgById.get(match.leafId());
        if (org == null) {
            return null;
        }
        return Map.of(
                "organizationId", org.id(),
                "name", org.name(),
                "parentOrganizationId", org.parentId() == null ? "" : org.parentId());
    }

    private static Diff diff(
            RosterRow row,
            String category,
            String matchedId,
            Map<String, Object> source,
            Map<String, Object> current,
            Map<String, Object> proposed) {
        return new Diff(row.rowNumber(), category, matchedId, source, current, proposed);
    }

    private static Issue blocking(int rowNumber, String field, String code, String message) {
        return new Issue(rowNumber, field, code, message, "BLOCKING");
    }

    private static Issue warning(int rowNumber, String field, String code, String message) {
        return new Issue(rowNumber, field, code, message, "WARNING");
    }

    public static Snapshot snapshot(
            List<Company> companies,
            List<Org> orgs,
            List<Employee> employees) {
        Map<String, Company> byCode = new LinkedHashMap<>();
        for (Company company : companies) {
            byCode.put(company.code(), company);
        }
        Map<String, List<Org>> orgsByCompany = new HashMap<>();
        Map<String, Org> orgById = new HashMap<>();
        for (Org org : orgs) {
            orgsByCompany.computeIfAbsent(org.companyId(), key -> new ArrayList<>()).add(org);
            orgById.put(org.id(), org);
        }
        Map<String, Employee> byNumber = new HashMap<>();
        Map<String, List<Employee>> byName = new HashMap<>();
        for (Employee employee : employees) {
            byNumber.put(employee.companyId() + "|" + employee.number(), employee);
            byName.computeIfAbsent(employee.companyId() + "|" + employee.name(), key -> new ArrayList<>())
                    .add(employee);
        }
        return new Snapshot(byCode, orgsByCompany, orgById, byNumber, byName);
    }

    public record Company(String id, String code, String name) {
    }

    public record Org(
            String id,
            String companyId,
            String parentId,
            String name,
            String code,
            String type,
            String status) {
    }

    public record Employee(
            String id,
            String companyId,
            String number,
            String name,
            String organizationId) {
    }

    public record Snapshot(
            Map<String, Company> companyByCode,
            Map<String, List<Org>> orgsByCompany,
            Map<String, Org> orgById,
            Map<String, Employee> employeeByNumber,
            Map<String, List<Employee>> employeesByName) {
    }

    public record Diff(
            int rowNumber,
            String category,
            String matchedResourceId,
            Map<String, Object> sourceValues,
            Map<String, Object> currentValues,
            Map<String, Object> proposedValues) {
    }

    public record Issue(
            int rowNumber,
            String field,
            String code,
            String message,
            String severity) {
    }

    public record Summary(
            int added,
            int updated,
            int unchanged,
            int conflict,
            int error,
            int blocking) {
    }

    public record Result(List<Diff> diffs, List<Issue> issues, Summary summary) {
        public boolean canPublish() {
            return summary.blocking() == 0 && summary.conflict() == 0 && summary.error() == 0;
        }
    }

    private record PathMatch(String leafId, String conflictMessage) {
    }
}

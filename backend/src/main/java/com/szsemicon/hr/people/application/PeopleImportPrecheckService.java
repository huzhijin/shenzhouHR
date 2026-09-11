package com.szsemicon.hr.people.application;

import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.IssueSeverity;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateField;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class PeopleImportPrecheckService {

    private final PeopleRepository repository;
    private final PeopleWorkbookGateway workbookGateway;

    PeopleImportPrecheckService(
            PeopleRepository repository, PeopleWorkbookGateway workbookGateway) {
        this.repository = repository;
        this.workbookGateway = workbookGateway;
    }

    ValidationContext buildValidationContext(
            ImportBatch batch, List<Map<String, Object>> rows) {
        Map<Integer, List<ImportIssue>> issuesByRow = new HashMap<>();
        Set<String> organizationCodes = new HashSet<>();
        Map<String, Integer> keyCounts = new HashMap<>();
        Map<String, String> organizationParents = new HashMap<>();
        Map<String, List<BatchInterval>> employmentByEmployee = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int rowNumber = rowNumber(row);
            switch (batch.templateType()) {
                case ORGANIZATION -> {
                    String code = value(row, "organizationCode");
                    if (!code.isBlank()) {
                        organizationCodes.add(code);
                        keyCounts.merge("ORG:" + code, 1, Integer::sum);
                        organizationParents.put(code, value(row, "parentOrganizationCode"));
                    }
                }
                case EMPLOYEE -> {
                    mergeKey(keyCounts, "EMP_NO:", value(row, "employeeNumber"));
                    mergeKey(keyCounts, "EMP_EXT:", value(row, "externalEmployeeId"));
                }
                case EMPLOYMENT -> collectEmploymentInterval(
                        employmentByEmployee, rowNumber, row);
                case PRIOR_SERVICE -> {
                    // Immutable ledger rows are ordered and replayed below.
                }
            }
        }
        addDuplicateAndCycleIssues(
                batch, rows, keyCounts, organizationParents, issuesByRow);
        addEmploymentOverlapIssues(batch, employmentByEmployee, issuesByRow);
        if (batch.templateType() == TemplateType.PRIOR_SERVICE) {
            validatePriorServiceBatch(batch, rows, issuesByRow);
        }
        return new ValidationContext(
                Set.copyOf(organizationCodes),
                issuesByRow.entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> List.copyOf(entry.getValue()))));
    }

    List<ImportIssue> validateRow(
            String batchId,
            TemplateType type,
            int rowNumber,
            Map<String, Object> row) {
        List<ImportIssue> issues = new ArrayList<>();
        for (TemplateField field : workbookGateway.listTemplates(type).getFirst().fields()) {
            if (field.required() && value(row, field.key()).isBlank()) {
                issues.add(issue(
                        batchId, rowNumber, field.key(), "REQUIRED_FIELD_MISSING",
                        "必填字段为空: " + field.label(), List.of()));
            }
        }
        validateDates(issues, batchId, type, rowNumber, row);
        if (type == TemplateType.PRIOR_SERVICE) {
            validatePriorServiceAmount(issues, batchId, rowNumber, row);
        }
        validateTypeFields(issues, batchId, type, rowNumber, row);
        return issues;
    }

    private void addDuplicateAndCycleIssues(
            ImportBatch batch,
            List<Map<String, Object>> rows,
            Map<String, Integer> keyCounts,
            Map<String, String> organizationParents,
            Map<Integer, List<ImportIssue>> issuesByRow) {
        for (Map<String, Object> row : rows) {
            int rowNumber = rowNumber(row);
            if (batch.templateType() == TemplateType.ORGANIZATION) {
                String code = value(row, "organizationCode");
                if (!code.isBlank() && keyCounts.getOrDefault("ORG:" + code, 0) > 1) {
                    addIssue(issuesByRow, issue(
                            batch.batchId(), rowNumber, "organizationCode",
                            "DUPLICATE_BUSINESS_KEY", "同一工作簿内组织编码重复", List.of()));
                }
                if (!code.isBlank() && organizationCycle(code, organizationParents)) {
                    addIssue(issuesByRow, issue(
                            batch.batchId(), rowNumber, "parentOrganizationCode",
                            "ORGANIZATION_PARENT_CYCLE", "同一工作簿内组织层级形成环", List.of()));
                }
            } else if (batch.templateType() == TemplateType.EMPLOYEE) {
                addDuplicateEmployeeIssue(
                        batch, rowNumber, row, keyCounts, issuesByRow,
                        "employeeNumber", "EMP_NO:", "同一工作簿内员工编号重复");
                addDuplicateEmployeeIssue(
                        batch, rowNumber, row, keyCounts, issuesByRow,
                        "externalEmployeeId", "EMP_EXT:", "同一工作簿内外部精确员工 ID 重复");
            }
        }
    }

    private static void addDuplicateEmployeeIssue(
            ImportBatch batch,
            int rowNumber,
            Map<String, Object> row,
            Map<String, Integer> counts,
            Map<Integer, List<ImportIssue>> issuesByRow,
            String field,
            String prefix,
            String message) {
        String key = value(row, field);
        if (!key.isBlank() && counts.getOrDefault(prefix + key, 0) > 1) {
            addIssue(issuesByRow, issue(
                    batch.batchId(), rowNumber, field,
                    "DUPLICATE_BUSINESS_KEY", message, List.of()));
        }
    }

    private static void addEmploymentOverlapIssues(
            ImportBatch batch,
            Map<String, List<BatchInterval>> employmentByEmployee,
            Map<Integer, List<ImportIssue>> issuesByRow) {
        for (List<BatchInterval> intervals : employmentByEmployee.values()) {
            intervals.sort(java.util.Comparator.comparing(BatchInterval::start));
            for (int left = 0; left < intervals.size(); left++) {
                for (int right = left + 1; right < intervals.size(); right++) {
                    BatchInterval first = intervals.get(left);
                    BatchInterval second = intervals.get(right);
                    if (first.endExclusive() != null
                            && !second.start().isBefore(first.endExclusive())) {
                        break;
                    }
                    addIssue(issuesByRow, issue(
                            batch.batchId(), second.rowNumber(), "startDate",
                            "EMPLOYMENT_PERIOD_OVERLAP",
                            "同一工作簿内任职周期重叠", List.of()));
                }
            }
        }
    }

    private void validatePriorServiceBatch(
            ImportBatch batch,
            List<Map<String, Object>> rows,
            Map<Integer, List<ImportIssue>> issuesByRow) {
        Map<String, List<Map<String, Object>>> byEmployeeNumber = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        row -> value(row, "employeeNumber"),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (Map.Entry<String, List<Map<String, Object>>> entry
                : byEmployeeNumber.entrySet()) {
            EmployeeVersion employee = repository.findEmployeeByNumber(
                    batch.companyId(), entry.getKey()).orElse(null);
            if (employee == null) {
                continue;
            }
            List<PriorServiceRecord> current =
                    repository.listAllPriorServiceRecords(employee.employeeId());
            int runningTotal = current.isEmpty()
                    ? 0 : current.getLast().resultingTotalDays();
            LocalDate latestDate = current.isEmpty()
                    ? null : current.getLast().businessDate();
            for (Map<String, Object> row : entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(
                            PeopleImportPrecheckService::rowNumber))
                    .toList()) {
                int rowNumber = rowNumber(row);
                int amount;
                LocalDate businessDate;
                try {
                    amount = Integer.parseInt(value(row, "amountDays"));
                    businessDate = LocalDate.parse(value(row, "businessDate"));
                } catch (Exception ignored) {
                    continue;
                }
                boolean valid = true;
                if (latestDate != null && businessDate.isBefore(latestDate)) {
                    addIssue(issuesByRow, issue(
                            batch.batchId(), rowNumber, "businessDate",
                            "PRIOR_SERVICE_BUSINESS_DATE_OUT_OF_ORDER",
                            "累计工龄业务日期不得早于最新发生额",
                            List.of(employee.employeeId())));
                    valid = false;
                }
                long replayed = (long) runningTotal + amount;
                if (replayed < 0 || replayed > Integer.MAX_VALUE) {
                    addIssue(issuesByRow, issue(
                            batch.batchId(), rowNumber, "amountDays",
                            "PRIOR_SERVICE_TOTAL_NEGATIVE",
                            "累计工龄重放结果不得小于零且不得溢出",
                            List.of(employee.employeeId())));
                    valid = false;
                }
                if (valid) {
                    runningTotal = (int) replayed;
                    latestDate = businessDate;
                }
            }
        }
    }

    private static void validateDates(
            List<ImportIssue> issues,
            String batchId,
            TemplateType type,
            int rowNumber,
            Map<String, Object> row) {
        for (String field : dateFields(type)) {
            String date = value(row, field);
            if (!date.isBlank()) {
                try {
                    LocalDate.parse(date);
                } catch (Exception exception) {
                    issues.add(issue(
                            batchId, rowNumber, field, "INVALID_VALUE",
                            "日期必须为 YYYY-MM-DD", List.of()));
                }
            }
        }
    }

    private static void validatePriorServiceAmount(
            List<ImportIssue> issues,
            String batchId,
            int rowNumber,
            Map<String, Object> row) {
        String amountValue = value(row, "amountDays");
        if (amountValue.isBlank()) {
            return;
        }
        try {
            int amount = Integer.parseInt(amountValue);
            if (amount < -36500 || amount > 36500) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            issues.add(issue(
                    batchId, rowNumber, "amountDays", "INVALID_VALUE",
                    "发生天数必须为 -36500 至 36500 的整数", List.of()));
        }
    }

    private static void validateTypeFields(
            List<ImportIssue> issues,
            String batchId,
            TemplateType type,
            int rowNumber,
            Map<String, Object> row) {
        switch (type) {
            case ORGANIZATION -> validateOrganization(
                    issues, batchId, rowNumber, row);
            case EMPLOYEE -> validateEmployee(
                    issues, batchId, rowNumber, row);
            case EMPLOYMENT -> validateEmployment(
                    issues, batchId, rowNumber, row);
            case PRIOR_SERVICE -> validatePriorService(
                    issues, batchId, rowNumber, row);
        }
    }

    private static void validateOrganization(
            List<ImportIssue> issues, String batchId, int rowNumber,
            Map<String, Object> row) {
        validateLength(issues, batchId, rowNumber, row, "organizationCode", 128, "组织编码");
        validateLength(issues, batchId, rowNumber, row, "name", 200, "组织名称");
        validateLength(
                issues, batchId, rowNumber, row, "parentOrganizationCode", 128, "上级组织编码");
        String type = value(row, "organizationType");
        if (!type.isBlank() && !List.of("COMPANY", "DEPARTMENT", "TEAM").contains(type)) {
            issues.add(issue(
                    batchId, rowNumber, "organizationType", "INVALID_VALUE",
                    "组织类型仅允许 COMPANY、DEPARTMENT 或 TEAM", List.of()));
        }
    }

    private static void validateEmployee(
            List<ImportIssue> issues, String batchId, int rowNumber,
            Map<String, Object> row) {
        validateLength(issues, batchId, rowNumber, row, "employeeNumber", 128, "员工编号");
        validateLength(
                issues, batchId, rowNumber, row, "externalEmployeeId", 128, "外部精确员工 ID");
        validateLength(issues, batchId, rowNumber, row, "displayName", 100, "姓名");
        String externalId = value(row, "externalEmployeeId");
        if (!externalId.isBlank()) {
            try {
                new ExternalPreciseId(externalId);
            } catch (Exception exception) {
                issues.add(issue(
                        batchId, rowNumber, "externalEmployeeId", "INVALID_VALUE",
                        "外部精确员工 ID 格式无效", List.of()));
            }
        }
    }

    private static void validateEmployment(
            List<ImportIssue> issues, String batchId, int rowNumber,
            Map<String, Object> row) {
        validateLength(issues, batchId, rowNumber, row, "employeeNumber", 128, "员工编号");
        validateLength(issues, batchId, rowNumber, row, "organizationCode", 128, "组织编码");
        try {
            LocalDate start = LocalDate.parse(value(row, "startDate"));
            String termination = value(row, "terminationDate");
            if (!termination.isBlank() && LocalDate.parse(termination).isBefore(start)) {
                issues.add(issue(
                        batchId, rowNumber, "terminationDate", "INVALID_VALUE",
                        "业务离职日不得早于任职开始日", List.of()));
            }
        } catch (Exception ignored) {
            // General date validation owns malformed values.
        }
    }

    private static void validatePriorService(
            List<ImportIssue> issues, String batchId, int rowNumber,
            Map<String, Object> row) {
        validateLength(issues, batchId, rowNumber, row, "employeeNumber", 128, "员工编号");
        String reason = value(row, "reason");
        if (!reason.isBlank() && (reason.length() < 2 || reason.length() > 500)) {
            issues.add(issue(
                    batchId, rowNumber, "reason", "INVALID_VALUE",
                    "原因长度必须为 2 至 500", List.of()));
        }
    }

    private static void validateLength(
            List<ImportIssue> issues,
            String batchId,
            int rowNumber,
            Map<String, Object> row,
            String field,
            int maxLength,
            String label) {
        if (value(row, field).length() > maxLength) {
            issues.add(issue(
                    batchId, rowNumber, field, "INVALID_VALUE",
                    label + "长度不能超过 " + maxLength, List.of()));
        }
    }

    private static void collectEmploymentInterval(
            Map<String, List<BatchInterval>> intervals,
            int rowNumber,
            Map<String, Object> row) {
        try {
            LocalDate start = LocalDate.parse(value(row, "startDate"));
            String termination = value(row, "terminationDate");
            LocalDate endExclusive = termination.isBlank()
                    ? null : LocalDate.parse(termination).plusDays(1);
            intervals.computeIfAbsent(
                            value(row, "employeeNumber"), ignored -> new ArrayList<>())
                    .add(new BatchInterval(rowNumber, start, endExclusive));
        } catch (Exception ignored) {
            // Row-level date validation owns malformed values.
        }
    }

    private static void mergeKey(
            Map<String, Integer> counts, String prefix, String value) {
        if (!value.isBlank()) {
            counts.merge(prefix + value, 1, Integer::sum);
        }
    }

    private static boolean organizationCycle(
            String start, Map<String, String> parents) {
        Set<String> visited = new HashSet<>();
        String current = start;
        while (current != null && !current.isBlank() && parents.containsKey(current)) {
            if (!visited.add(current)) {
                return true;
            }
            current = parents.get(current);
        }
        return false;
    }

    private static List<String> dateFields(TemplateType type) {
        return switch (type) {
            case ORGANIZATION, EMPLOYEE -> List.of("effectiveFrom");
            case EMPLOYMENT -> List.of("startDate", "terminationDate");
            case PRIOR_SERVICE -> List.of("businessDate");
        };
    }

    private static void addIssue(
            Map<Integer, List<ImportIssue>> issuesByRow, ImportIssue issue) {
        issuesByRow.computeIfAbsent(issue.rowNumber(), ignored -> new ArrayList<>())
                .add(issue);
    }

    private static ImportIssue issue(
            String batchId,
            int rowNumber,
            String field,
            String code,
            String message,
            List<String> candidates) {
        return new ImportIssue(
                UUID.randomUUID().toString(),
                batchId,
                rowNumber,
                field,
                code,
                message,
                IssueSeverity.BLOCKING,
                candidates);
    }

    private static int rowNumber(Map<String, Object> row) {
        return Integer.parseInt(row.get("_rowNumber").toString());
    }

    private static String value(Map<String, Object> row, String key) {
        Object raw = row.get(key);
        return raw == null ? "" : raw.toString().trim();
    }

    record ValidationContext(
            Set<String> organizationCodes,
            Map<Integer, List<ImportIssue>> issuesByRow) {
    }

    private record BatchInterval(
            int rowNumber, LocalDate start, LocalDate endExclusive) {
    }
}

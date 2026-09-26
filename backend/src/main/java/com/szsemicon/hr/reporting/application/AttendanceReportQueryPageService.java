package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.CoveringLeaveDocuments;
import com.szsemicon.hr.reporting.infrastructure.export.QueryPageExcelEncoder;
import com.szsemicon.hr.reporting.infrastructure.export.QueryPageExcelEncoder.WorkbookFile;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceReportQueryPageMapper;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.FactQuery;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.LeaveStatAccountRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.MonthlyLeaveUsageRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.PinRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.TimeAccountRow;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AttendanceReportQueryPageService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(BUSINESS_ZONE);
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final int MAX_RANGE_MONTHS = 12;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final AttendanceReportSourceRepository sources;
    private final AttendanceReportQueryPageMapper mapper;
    private final QueryPageExcelEncoder excelEncoder;
    private final Clock clock;

    public AttendanceReportQueryPageService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            AttendanceReportSourceRepository sources,
            AttendanceReportQueryPageMapper mapper,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.sources = sources;
        this.mapper = mapper;
        this.clock = clock;
        this.excelEncoder = new QueryPageExcelEncoder();
    }

    public DirectoryPage directory(String companyId, YearMonth period) {
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ);
        Instant at = clock.instant();
        YearMonth month = period == null
                ? YearMonth.from(at.atZone(BUSINESS_ZONE))
                : period;
        String principalId = principals.currentPrincipalId();
        List<AttendanceReportSourceRepository.CompanyOption> companies =
                sources.listAuthorizedCompanies(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        month,
                        at);
        if (companies.isEmpty()
                || companyId == null
                || companyId.isBlank()) {
            return new DirectoryPage(
                    month.toString(),
                    companyId,
                    companies,
                    List.of());
        }
        Resolved resolved = resolve(companyId, null, month, at);
        List<String> orgFilter = resolved.selectedOrAuthorizedOrgs(null);
        boolean companyWide = resolved.authorization().companyWide()
                && orgFilter.isEmpty();
        if (!companyWide && resolved.employeeIdList().isEmpty()) {
            return new DirectoryPage(
                    month.toString(),
                    resolved.companyId(),
                    companies,
                    List.of());
        }
        Map<String, String> departments = departmentPaths(resolved.companyId());
        List<QueryPageRows.DirectoryEmployeeRow> rows =
                mapper.listDirectoryEmployees(
                        resolved.companyId(),
                        orgFilter.isEmpty() ? null : orgFilter,
                        companyWide,
                        resolved.employeeIdList(),
                        at)
                        .stream()
                        .map(row -> withDirectoryDepartment(row, departments))
                        .toList();
        return new DirectoryPage(
                month.toString(),
                resolved.companyId(),
                companies,
                rows);
    }

    public QueryPage query(QueryCommand command) {
        return query(command, MAX_PAGE_SIZE);
    }

    public WorkbookFile export(QueryCommand command) {
        return export(command, "ALL");
    }

    public WorkbookFile export(QueryCommand command, String exportScope) {
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ);
        java.util.Set<String> active = capabilities.currentCapabilities();
        if (!active.contains(CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)
                && !active.contains(CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "required capability is not granted");
        }
        String scope = exportScope == null || exportScope.isBlank()
                ? "ALL"
                : exportScope.strip().toUpperCase(java.util.Locale.ROOT);
        if ("PAGE".equals(scope)) {
            return excelEncoder.encode(query(command, MAX_PAGE_SIZE));
        }
        if (!"ALL".equals(scope)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTENDANCE_REPORT_QUERY_EXPORT_SCOPE_INVALID",
                    "导出范围只能是全部或当前页",
                    true);
        }
        QueryCommand allRows = command.withPaging(0, MAX_EXPORT_ROWS);
        return excelEncoder.encode(query(allRows, MAX_EXPORT_ROWS));
    }

    private QueryPage query(QueryCommand command, int maxSize) {
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ);
        command = command.withLockedOvertimeTreatment();
        Instant at = clock.instant();
        DateWindow window = dateWindow(command, at);
        Resolved resolved = resolve(
                command.companyId(),
                command.organizationId(),
                window.months().getFirst(),
                at);
        if (window.months().size() > MAX_RANGE_MONTHS) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTENDANCE_REPORT_QUERY_RANGE_TOO_LONG",
                    "查询期间最长 12 个月");
        }
        List<PinRow> pins = new ArrayList<>();
        List<YearMonth> missing = new ArrayList<>();
        for (YearMonth month : window.months()) {
            PinRow pin = mapper.findLatestPin(
                    resolved.companyId(),
                    month.atDay(1),
                    month.plusMonths(1).atDay(1));
            if (pin != null) {
                pins.add(pin);
            } else {
                missing.add(month);
            }
        }
        List<OmittedMonth> omitted = new ArrayList<>();
        boolean anyNotReady = false;
        if (pins.isEmpty()) {
            for (YearMonth month : missing) {
                if (hasSourceEvidence(resolved.companyId(), month, at)) {
                    anyNotReady = true;
                    omitted.add(new OmittedMonth(month.toString(), "NOT_READY"));
                } else {
                    omitted.add(new OmittedMonth(month.toString(), "NO_DATA"));
                }
            }
        } else {
            LocalDate openingFrom = LeaveStatAssembler.OPENING_INCLUSIVE_FROM;
            boolean yearStat = "annual-leave-stat".equals(command.sheet())
                    || "time-off-stat".equals(command.sheet());
            for (YearMonth month : missing) {
                if (yearStat && month.atEndOfMonth().isBefore(openingFrom)) {
                    continue;
                }
                omitted.add(new OmittedMonth(month.toString(), "NO_DATA"));
            }
        }
        if (pins.isEmpty()) {
            if (anyNotReady) {
                throw new ApiProblemException(
                        HttpStatus.CONFLICT,
                        "ATTENDANCE_REPORT_PIN_NOT_READY",
                        "该月核算尚未完成",
                        true);
            }
            return QueryPage.empty(
                    command.sheet(),
                    resolved.companyId(),
                    window,
                    omitted,
                    "该期间暂无打卡或核算数据",
                    allowedActions());
        }
        List<String> projectionIds = pins.stream().map(PinRow::projectionId).toList();
        List<String> orgIds = resolved.selectedOrAuthorizedOrgs(command.organizationId());
        String employeeId = command.employeeId();
        if (employeeId != null
                && !resolved.authorization().companyWide()
                && !resolved.authorization().employeeIds().contains(employeeId)) {
            return QueryPage.empty(
                    command.sheet(),
                    resolved.companyId(),
                    window,
                    omitted,
                    "当前筛选条件下没有记录",
                    allowedActions());
        }
        int size = normalizeSize(command.size(), maxSize);
        int page = Math.max(command.page(), 0);
        Map<String, String> departments = departmentPaths(resolved.companyId());
        FactQuery query = new FactQuery(
                projectionIds,
                resolved.companyId(),
                window.from(),
                window.toExclusive(),
                orgIds.isEmpty() ? null : orgIds,
                resolved.employeeIdList().isEmpty() ? null : resolved.employeeIdList(),
                command.employeeNumber(),
                employeeId,
                command.exceptionType(),
                command.severity(),
                command.state(),
                documentType(command.sheet()),
                command.leaveType(),
                command.approvalState(),
                command.overtimeTreatment(),
                command.occurrenceDay(),
                command.lateCountBand(),
                command.lateMinuteBand(),
                command.punchSide(),
                command.employmentStatus(),
                command.attendanceType(),
                command.rateBelow(),
                command.annualBalanceBand(),
                command.annualLevelOne(),
                command.annualLevelTwo(),
                accountType(command.sheet()),
                page * size,
                size,
                command.sheet());
        QueryPage result = switch (command.sheet()) {
            case "exceptions", "missed-punch" -> exceptionPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "leave", "overtime", "makeup" -> oaPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "late", "work-hours", "attendance-rate" -> dailyPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "annual-leave", "time-off" -> annualPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "annual-leave-stat", "time-off-stat" -> leaveStatPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "missed-punch-stat" -> missedPunchStatPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "matrix" -> matrixPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "daily-journal", "overtime-daily", "time-off-daily" -> journalPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "finance-overtime",
                    "overtime-fee-daily",
                    "overtime-comp-daily",
                    "overtime-voluntary-daily" -> financeOvertimePage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "absence-stat", "leave-stat" -> metricStatPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            case "leave-summary" -> leaveSummaryPage(
                    command, query, pins, omitted, window, resolved, size, page, departments);
            default -> throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "ATTENDANCE_REPORT_QUERY_SHEET_UNKNOWN",
                    "不支持的查询报表");
        };
        return result;
    }

    private QueryPage exceptionPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        if ("missed-punch".equals(command.sheet())
                && query.exceptionType() == null) {
            query = withExceptionType(query, "MISSING_PUNCH");
        } else if ("exceptions".equals(command.sheet())
                && query.exceptionType() == null) {
            query = withExceptionType(query, "ACTIONABLE");
        }
        long total = mapper.countExceptions(query);
        List<Map<String, Object>> rows = mapper.listExceptions(query).stream()
                .map(row -> rowMap(
                        "employeeNumber", row.employeeNumber(),
                        "employeeName", row.employeeName(),
                        "department", reportDepartment(
                                row.organizationId(),
                                row.organizationName(),
                                departments),
                        "businessDate", row.businessDate(),
                        "exceptionType", row.exceptionType(),
                        "severity", row.severity(),
                        "state", row.state(),
                        "minutes", row.exceptionMinutes(),
                        "details", formatExceptionDetails(row)))
                .toList();
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage oaPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        boolean overtime = "overtime".equals(command.sheet());
        boolean leave = "leave".equals(command.sheet());
        FactQuery listQuery = overtime || leave
                ? new FactQuery(
                        query.projectionIds(),
                        query.companyId(),
                        query.fromDate(),
                        query.toDateExclusive(),
                        query.organizationIds(),
                        query.employeeIds(),
                        query.employeeNumber(),
                        query.employeeId(),
                        query.exceptionType(),
                        query.severity(),
                        query.state(),
                        query.documentType(),
                        query.leaveType(),
                        query.sourceStatus(),
                        query.overtimeTreatment(),
                        query.occurrenceDay(),
                        query.lateCountBand(),
                        query.lateMinuteBand(),
                        query.punchSide(),
                        query.employmentStatus(),
                        query.attendanceType(),
                        query.rateBelow(),
                        query.annualBalanceBand(),
                        query.annualLevelOne(),
                        query.annualLevelTwo(),
                        query.accountType(),
                        0,
                        MAX_EXPORT_ROWS)
                : query;
        List<QueryPageRows.OaRow> documents = mapper.listOaDocuments(listQuery);
        if (leave) {
            documents = dropCoveringLeaveRows(documents);
        }
        List<Map<String, Object>> mapped = documents
                .stream()
                .map(row -> rowMap(
                        "documentId", row.documentId(),
                        "employeeNumber", row.employeeNumber(),
                        "employeeName", row.employeeName(),
                        "department", reportDepartment(
                                row.organizationId(),
                                row.organizationName(),
                                departments),
                        "documentType", row.documentType(),
                        "leaveType", row.leaveType(),
                        "startAt", row.startAt(),
                        "endAt", row.endExclusive(),
                        "hours", minutesToHours(row.recognizedMinutes()),
                        "approvalState", row.sourceStatus(),
                        "sourceOrigin", row.sourceOrigin() == null
                                ? "OA"
                                : row.sourceOrigin()))
                .toList();
        if (overtime) {
            mapped = dedupeOvertimeRows(mapped);
        }
        long total = overtime || leave ? mapped.size() : mapper.countOaDocuments(query);
        List<Map<String, Object>> rows = overtime || leave
                ? mapped.subList(
                        Math.min(page * size, mapped.size()),
                        Math.min(page * size + size, mapped.size()))
                : mapped;
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private static List<QueryPageRows.OaRow> dropCoveringLeaveRows(
            List<QueryPageRows.OaRow> rows) {
        java.util.Set<String> drop = CoveringLeaveDocuments.coveringIds(
                rows.stream()
                        .map(row -> new CoveringLeaveDocuments.Span(
                                row.documentId(),
                                row.employeeId(),
                                row.documentType(),
                                row.startAt(),
                                row.endExclusive(),
                                row.recognizedMinutes(),
                                row.sourceStatus()))
                        .toList());
        if (drop.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> !drop.contains(row.documentId()))
                .toList();
    }

    private static List<Map<String, Object>> dedupeOvertimeRows(
            List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("employeeNumber"))
                    + "|"
                    + String.valueOf(row.get("startAt"))
                    + "|"
                    + String.valueOf(row.get("endAt"));
            Map<String, Object> existing = unique.get(key);
            if (existing == null) {
                unique.put(key, row);
                continue;
            }
            String existingId = String.valueOf(existing.get("documentId"));
            String nextId = String.valueOf(row.get("documentId"));
            if (nextId.compareTo(existingId) < 0) {
                unique.put(key, row);
            }
        }
        return List.copyOf(unique.values());
    }

    private QueryPage dailyPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        if ("work-hours".equals(command.sheet())
                && (command.employmentStatus() == null
                        || command.employmentStatus().isBlank())) {
            return workHoursPage(
                    command, query, pins, omitted, window, resolved, size, page,
                    departments);
        }
        long total = mapper.countEmployeeDailyAggregates(query);
        List<Map<String, Object>> rows = mapper.listEmployeeDailyAggregates(query)
                .stream()
                .map(row -> withDepartment(
                        dailyRow(command.sheet(), row),
                        row.organizationId(),
                        row.organizationName(),
                        departments))
                .toList();
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage workHoursPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        FactQuery all = withOffsetLimit(query, 0, 2000);
        List<QueryPageRows.EmployeeDailyAggregateRow> aggregates =
                mapper.listEmployeeDailyAggregates(all);
        List<String> ids = aggregates.stream()
                .map(QueryPageRows.EmployeeDailyAggregateRow::employeeId)
                .toList();
        Map<String, List<QueryPageRows.OaRow>> oaByEmployee = new HashMap<>();
        if (!ids.isEmpty()) {
            for (QueryPageRows.OaRow row : mapper.listOaForEmployees(
                    withEmployeeIds(all, ids))) {
                oaByEmployee
                        .computeIfAbsent(row.employeeId(), ignored -> new ArrayList<>())
                        .add(row);
            }
        }
        List<Map<String, Object>> mapped = aggregates.stream()
                .map(row -> withDepartment(
                        workHoursRow(
                                row,
                                oaByEmployee.getOrDefault(
                                        row.employeeId(), List.of())),
                        row.organizationId(),
                        row.organizationName(),
                        departments))
                .toList();
        long total = mapped.size();
        int from = Math.min(page * size, mapped.size());
        int to = Math.min(from + size, mapped.size());
        return pageOf(
                command, resolved, pins, omitted, window, total, size, page,
                mapped.subList(from, to), allowedActions());
    }

    private static QueryPageRows.EmployeeDailyAggregateRow emptyWorkHours(
            QueryPageRows.DirectoryEmployeeRow person) {
        return new QueryPageRows.EmployeeDailyAggregateRow(
                person.employeeId(),
                person.employeeNumber(),
                person.employeeName(),
                person.organizationId(),
                person.organizationName(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                null);
    }

    private QueryPage annualPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        long total = mapper.countTimeAccounts(query);
        boolean timeOff = "time-off".equals(command.sheet());
        List<Map<String, Object>> rows = mapper.listTimeAccounts(query).stream()
                .map(row -> rowMap(
                        "employeeNumber", row.employeeNumber(),
                        "employeeName", row.employeeName(),
                        "department", reportDepartment(
                                row.organizationId(),
                                row.organizationName(),
                                departments),
                        "accountType", timeOff ? "调休" : "年假",
                        "openingHours", row.openingHours(),
                        "grantedHours", row.grantedHours(),
                        "overtimeCreditHours", row.overtimeCreditHours(),
                        "usedHours", row.usedHours(),
                        "remainingHours", row.remainingHours(),
                        "remainingDays", hoursToDays(row.remainingHours())))
                .toList();
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage missedPunchStatPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        long total = mapper.countMissedPunchStatEmployees(query);
        List<QueryPageRows.DirectoryEmployeeRow> employees =
                mapper.listMissedPunchStatEmployees(query);
        if (employees.isEmpty()) {
            return pageOf(command, resolved, pins, omitted, window, total, size, page, List.of(), allowedActions());
        }
        List<String> pageEmployeeIds = employees.stream()
                .map(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .toList();
        FactQuery cellsQuery = withEmployeeIds(query, pageEmployeeIds);
        Map<String, List<QueryPageRows.DailyCellRow>> cells = new LinkedHashMap<>();
        for (QueryPageRows.DailyCellRow cell : mapper.listDailyCells(cellsQuery)) {
            cells.computeIfAbsent(cell.employeeId(), ignored -> new ArrayList<>()).add(cell);
        }
        Map<String, List<QueryPageRows.OaRow>> documents = new LinkedHashMap<>();
        for (QueryPageRows.OaRow row : mapper.listOaForEmployees(cellsQuery)) {
            documents.computeIfAbsent(row.employeeId(), ignored -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int sequence = page * size;
        for (QueryPageRows.DirectoryEmployeeRow employee : employees) {
            List<Map<String, Object>> days = MissedPunchStatAssembler.days(
                    cells.getOrDefault(employee.employeeId(), List.of()),
                    documents.getOrDefault(employee.employeeId(), List.of()));
            if (!MissedPunchStatAssembler.matchesFilters(
                    days, command.exceptionType(), command.punchSide())) {
                continue;
            }
            sequence++;
            MissedPunchStatAssembler.Summary summary = MissedPunchStatAssembler.summary(days);
            Map<String, Object> row = rowMap(
                    "employeeId", employee.employeeId(),
                    "employeeNumber", employee.employeeNumber(),
                    "employeeName", employee.employeeName(),
                    "department", reportDepartment(
                            employee.organizationId(),
                            employee.organizationName(),
                            departments),
                    "sequence", sequence,
                    "missedCount", summary.missedCount(),
                    "remark", summary.remark());
            row.put("days", days);
            rows.add(row);
        }
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage leaveStatPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        PinRow latest = pins.getLast();
        FactQuery accountQuery = withProjectionIds(query, List.of(latest.projectionId()));
        long total = mapper.countLeaveStatAccounts(accountQuery);
        List<LeaveStatAccountRow> accounts = leaveStatAccounts(accountQuery);
        if (accounts.isEmpty()) {
            return pageOf(
                    command, resolved, pins, omitted, window, total, size, page, List.of(),
                    leaveStatActions());
        }
        List<String> pageEmployeeIds = accounts.stream()
                .map(LeaveStatAccountRow::employeeId)
                .toList();
        FactQuery usageQuery = withEmployeeIds(query, pageEmployeeIds);
        Map<String, List<MonthlyLeaveUsageRow>> usage = new LinkedHashMap<>();
        for (MonthlyLeaveUsageRow row : monthlyLeaveUsage(usageQuery)) {
            usage.computeIfAbsent(row.employeeId(), ignored -> new ArrayList<>()).add(row);
        }
        boolean timeOff = "time-off-stat".equals(command.sheet());
        int year = window.from().getYear();
        List<Map<String, Object>> rows = new ArrayList<>();
        int sequence = page * size;
        for (QueryPageRows.LeaveStatAccountRow account : accounts) {
            sequence++;
            String department = reportDepartment(
                    account.organizationId(),
                    account.organizationName(),
                    departments);
            List<QueryPageRows.MonthlyLeaveUsageRow> monthly =
                    usage.getOrDefault(account.employeeId(), List.of());
            rows.add(timeOff
                    ? LeaveStatAssembler.timeOffRow(account, monthly, year, sequence, department)
                    : LeaveStatAssembler.annualRow(account, monthly, year, sequence, department));
        }
        return pageOf(
                command, resolved, pins, omitted, window, total, size, page, rows,
                leaveStatActions());
    }

    private List<String> leaveStatActions() {
        List<String> actions = new ArrayList<>(allowedActions());
        if (capabilities.currentCapabilities().contains(
                CapabilityCodes.ANNUAL_LEAVE_ADJUST)) {
            actions.add("LEAVE_ADJUST");
        }
        return actions;
    }

    private List<LeaveStatAccountRow> leaveStatAccounts(FactQuery accountQuery) {
        try {
            return mapper.listLeaveStatAccounts(accountQuery);
        } catch (PersistenceException | DataAccessException ex) {
            return mapper.listTimeAccounts(accountQuery).stream()
                    .map(AttendanceReportQueryPageService::withoutHireDate)
                    .toList();
        }
    }

    private List<MonthlyLeaveUsageRow> monthlyLeaveUsage(FactQuery usageQuery) {
        try {
            return mapper.listMonthlyLeaveUsage(usageQuery);
        } catch (PersistenceException | DataAccessException ex) {
            return List.of();
        }
    }

    private static LeaveStatAccountRow withoutHireDate(TimeAccountRow row) {
        return new LeaveStatAccountRow(
                row.employeeId(),
                row.employeeNumber(),
                row.employeeName(),
                row.organizationId(),
                row.organizationName(),
                row.accountType(),
                row.openingHours(),
                row.grantedHours(),
                row.overtimeCreditHours(),
                row.usedHours(),
                row.remainingHours(),
                null,
                null);
    }

    private QueryPage matrixPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        long total = mapper.countMatrixEmployees(query);
        List<QueryPageRows.DirectoryEmployeeRow> employees =
                mapper.listMatrixEmployees(query);
        if (employees.isEmpty()) {
            return pageOf(command, resolved, pins, omitted, window, total, size, page, List.of(), allowedActions());
        }
        List<String> pageEmployeeIds = employees.stream()
                .map(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .toList();
        FactQuery cellsQuery = new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                pageEmployeeIds,
                query.employeeNumber(),
                query.employeeId(),
                command.attendanceStatus(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                0,
                pageEmployeeIds.size());
        Map<String, List<QueryPageRows.DailyCellRow>> cells = new LinkedHashMap<>();
        for (QueryPageRows.DailyCellRow cell : mapper.listDailyCells(cellsQuery)) {
            cells.computeIfAbsent(cell.employeeId(), ignored -> new ArrayList<>()).add(cell);
        }
        List<Map<String, Object>> rows = employees.stream()
                .map(employee -> {
                    List<QueryPageRows.DailyCellRow> days =
                            cells.getOrDefault(employee.employeeId(), List.of());
                    if (command.attendanceStatus() != null
                            && !command.attendanceStatus().isBlank()
                            && days.stream().noneMatch(day ->
                                    matchesStatus(day, command.attendanceStatus()))) {
                        return null;
                    }
                    Map<String, Object> row = rowMap(
                            "employeeId", employee.employeeId(),
                            "employeeNumber", employee.employeeNumber(),
                            "employeeName", employee.employeeName(),
                            "department", reportDepartment(
                                    employee.organizationId(),
                                    employee.organizationName(),
                                    departments));
                    row.put("days", days.stream().map(day -> rowMap(
                            "date", day.businessDate(),
                            "dayType", day.dayType(),
                            "leaveType", day.leaveType(),
                            "lateMinutes", day.lateMinutes(),
                            "earlyMinutes", day.earlyDepartureMinutes(),
                            "missingPunches", day.missingPunchCount(),
                            "firstPunchAt", day.firstPunchAt(),
                            "lastPunchAt", day.lastPunchAt(),
                            "paidOvertimeMinutes", day.paidOvertimeMinutes(),
                            "compensatoryOvertimeMinutes",
                                    day.compensatoryOvertimeMinutes())).toList());
                    return row;
                })
                .filter(row -> row != null)
                .toList();
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage leaveSummaryPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        long total = mapper.countLeaveSummary(query);
        List<Map<String, Object>> rows = mapper.listLeaveSummary(query).stream()
                .map(row -> rowMap(
                        "employeeNumber", row.employeeNumber(),
                        "employeeName", row.employeeName(),
                        "department", reportDepartment(
                                row.organizationId(),
                                row.organizationName(),
                                departments),
                        "leaveType", leaveTypeName(
                                row.leaveType(),
                                Math.max(row.recognizedMinutes(), 1)),
                        "hours", minutesToHours(row.recognizedMinutes()),
                        "documentCount", row.documentCount()))
                .toList();
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private QueryPage journalPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        boolean overtimeOnly = "overtime-daily".equals(command.sheet());
        boolean timeOffOnly = "time-off-daily".equals(command.sheet());
        long total = overtimeOnly
                ? mapper.countOvertimeDaily(query)
                : timeOffOnly
                        ? mapper.countTimeOffDaily(query)
                        : mapper.countDailyJournal(query);
        List<QueryPageRows.DailyJournalRow> facts = overtimeOnly
                ? mapper.listOvertimeDaily(query)
                : timeOffOnly
                        ? mapper.listTimeOffDaily(query)
                        : mapper.listDailyJournal(query);
        int startIndex = page * size;
        List<String> pageEmployeeIds = new ArrayList<>();
        for (QueryPageRows.DailyJournalRow row : facts) {
            if (row.employeeId() != null
                    && !row.employeeId().isBlank()
                    && !pageEmployeeIds.contains(row.employeeId())) {
                pageEmployeeIds.add(row.employeeId());
            }
        }
        Map<String, List<OvernightOvertimeFold.OvertimeSpan>> overtimeSpans =
                overtimeOnly || !timeOffOnly
                        ? overtimeSpansByEmployee(query, pageEmployeeIds)
                        : Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            QueryPageRows.DailyJournalRow row = facts.get(i);
            Map<String, Object> mapped = overtimeOnly
                    ? overtimeDailyRow(
                            row,
                            departments,
                            overtimeSpans,
                            command.overtimeTreatment())
                    : timeOffOnly
                            ? timeOffDailyRow(row, departments)
                            : dailyJournalRow(
                                    row,
                                    startIndex + i + 1,
                                    departments,
                                    overtimeSpans);
            rows.add(mapped);
        }
        return pageOf(command, resolved, pins, omitted, window, total, size, page, rows, allowedActions());
    }

    private Map<String, Object> dailyJournalRow(
            QueryPageRows.DailyJournalRow row,
            int sequence,
            Map<String, String> departments,
            Map<String, List<OvernightOvertimeFold.OvertimeSpan>> overtimeSpans) {
        boolean coveredByLeave = row.leaveOrTimeOffMinutes() > 0;
        boolean restDay = restDayWithoutWork(row);
        boolean requirePunch = !coveredByLeave
                && !restDay
                && (row.scheduledMinutes() > 0
                        || row.missingPunchCount() > 0
                        || row.recognizedOvertimeMinutes() > 0);
        Instant first = row.firstPunchAt();
        Instant last = row.lastPunchAt();
        boolean samePunch = first != null && first.equals(last);
        String onDuty = first != null
                ? PunchClockFormat.format(row.businessDate(), first)
                : (requirePunch ? "漏刷" : "");
        String offDuty;
        if (last != null && !samePunch) {
            offDuty = PunchClockFormat.format(row.businessDate(), last);
        } else if (requirePunch) {
            offDuty = "漏刷";
        } else {
            offDuty = "";
        }
        List<OvernightOvertimeFold.OvertimeSpan> spans = overtimeSpans.getOrDefault(
                row.employeeId(), List.of());
        long displayOvertime = OvernightOvertimeFold.displayMinutes(
                row.businessDate(),
                row.recognizedOvertimeMinutes(),
                spans,
                OvernightOvertimeFold.DEFAULT_SHIFT_START);
        Map<String, Object> mapped = rowMap(
                "sequence", sequence,
                "employeeId", row.employeeId(),
                "employeeNumber", row.employeeNumber(),
                "employeeName", row.employeeName(),
                "department", reportDepartment(
                        row.organizationId(),
                        row.organizationName(),
                        departments),
                "businessDate", row.businessDate(),
                "shiftLabel", blankIfNull(row.shiftLabel()),
                "onDuty", onDuty,
                "offDuty", offDuty,
                "lateHours", hoursOrBlank(row.lateMinutes()),
                "earlyHours", hoursOrBlank(row.earlyDepartureMinutes()),
                "absenceHours", hoursOrBlank(row.absenceMinutes()),
                "leaveType", leaveTypeName(row.leaveType(), row.leaveOrTimeOffMinutes()),
                "overtimeHours", hoursOrBlank(displayOvertime),
                "remark", journalRemark(row, requirePunch, samePunch, spans));
        return mapped;
    }

    private Map<String, Object> overtimeDailyRow(
            QueryPageRows.DailyJournalRow row,
            Map<String, String> departments,
            Map<String, List<OvernightOvertimeFold.OvertimeSpan>> overtimeSpans,
            String overtimeTreatment) {
        long display = OvernightOvertimeFold.displayMinutes(
                row.businessDate(),
                row.recognizedOvertimeMinutes(),
                overtimeSpans.getOrDefault(row.employeeId(), List.of()),
                OvernightOvertimeFold.DEFAULT_SHIFT_START);
        long paid = scaleMinutes(
                row.paidOvertimeMinutes(),
                row.recognizedOvertimeMinutes(),
                display);
        long compensatory = scaleMinutes(
                row.compensatoryOvertimeMinutes(),
                row.recognizedOvertimeMinutes(),
                display);
        long voluntary = scaleMinutes(
                row.voluntaryOvertimeMinutes(),
                row.recognizedOvertimeMinutes(),
                display);
        long typedMinutes = overtimeDisplayMinutes(
                overtimeTreatment,
                paid,
                compensatory,
                voluntary,
                paid + compensatory);
        boolean filtered = overtimeTreatment != null && !overtimeTreatment.isBlank();
        long bucketMinutes = filtered || paid + compensatory + voluntary > 0
                ? typedMinutes
                : display;
        double hours = minutesToHours(bucketMinutes);
        double weekday = 0;
        double weekend = 0;
        double holiday = 0;
        switch (overtimeBucket(row.dayType())) {
            case "weekend" -> weekend = hours;
            case "holiday" -> holiday = hours;
            default -> weekday = hours;
        }
        return rowMap(
                "employeeNumber", row.employeeNumber(),
                "employeeName", row.employeeName(),
                "department", reportDepartment(
                        row.organizationId(),
                        row.organizationName(),
                        departments),
                "businessDate", row.businessDate(),
                "weekdayOvertimeHours", weekday,
                "weekendOvertimeHours", weekend,
                "holidayOvertimeHours", holiday,
                "paidOvertimeHours", minutesToHours(paid),
                "compensatoryOvertimeHours", minutesToHours(compensatory),
                "voluntaryOvertimeHours", minutesToHours(voluntary));
    }

    private Map<String, Object> timeOffDailyRow(
            QueryPageRows.DailyJournalRow row,
            Map<String, String> departments) {
        return rowMap(
                "employeeNumber", row.employeeNumber(),
                "employeeName", row.employeeName(),
                "department", reportDepartment(
                        row.organizationId(),
                        row.organizationName(),
                        departments),
                "businessDate", row.businessDate(),
                "hours", minutesToHours(row.leaveOrTimeOffMinutes()),
                "leaveType", leaveTypeName(
                        row.leaveType(), row.leaveOrTimeOffMinutes()));
    }

    private QueryPage financeOvertimePage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        long total = mapper.countFinanceOvertimePeople(query);
        List<QueryPageRows.DirectoryEmployeeRow> employees =
                mapper.listFinanceOvertimePeople(query);
        if (employees.isEmpty()) {
            return pageOf(
                    command, resolved, pins, omitted, window, total, size, page,
                    List.of(), allowedActions());
        }
        List<String> pageEmployeeIds = employees.stream()
                .map(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .toList();
        FactQuery cellsQuery = new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                pageEmployeeIds,
                query.employeeNumber(),
                query.employeeId(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                0,
                pageEmployeeIds.size());
        Map<String, List<QueryPageRows.FinanceOvertimeCellRow>> cells =
                new LinkedHashMap<>();
        for (QueryPageRows.FinanceOvertimeCellRow cell
                : mapper.listFinanceOvertimeCells(cellsQuery)) {
            cells.computeIfAbsent(cell.employeeId(), ignored -> new ArrayList<>())
                    .add(cell);
        }
        List<Map<String, Object>> rows = employees.stream()
                .map(employee -> financeOvertimeRow(
                        employee,
                        cells.getOrDefault(employee.employeeId(), List.of()),
                        departments,
                        command.overtimeTreatment()))
                .toList();
        return pageOf(
                command, resolved, pins, omitted, window, total, size, page, rows,
                allowedActions());
    }

    private QueryPage metricStatPage(
            QueryCommand command,
            FactQuery query,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            Resolved resolved,
            int size,
            int page,
            Map<String, String> departments) {
        boolean absence = "absence-stat".equals(command.sheet());
        long total = absence
                ? mapper.countAbsenceStatPeople(query)
                : mapper.countLeaveStatPeople(query);
        List<QueryPageRows.DirectoryEmployeeRow> employees = absence
                ? mapper.listAbsenceStatPeople(query)
                : mapper.listLeaveStatPeople(query);
        if (employees.isEmpty()) {
            return pageOf(
                    command, resolved, pins, omitted, window, total, size, page,
                    List.of(), allowedActions());
        }
        List<String> pageEmployeeIds = employees.stream()
                .map(QueryPageRows.DirectoryEmployeeRow::employeeId)
                .toList();
        FactQuery cellsQuery = withEmployeeIds(query, pageEmployeeIds);
        List<QueryPageRows.DailyMetricCellRow> cells = absence
                ? mapper.listAbsenceStatCells(cellsQuery)
                : mapper.listLeaveStatCells(cellsQuery);
        Map<String, List<QueryPageRows.DailyMetricCellRow>> byEmployee =
                new LinkedHashMap<>();
        for (QueryPageRows.DailyMetricCellRow cell : cells) {
            byEmployee.computeIfAbsent(cell.employeeId(), ignored -> new ArrayList<>())
                    .add(cell);
        }
        List<Map<String, Object>> rows = employees.stream()
                .map(employee -> metricStatRow(
                        employee,
                        byEmployee.getOrDefault(employee.employeeId(), List.of()),
                        departments,
                        absence ? "absenceHours" : "leaveHours"))
                .toList();
        return pageOf(
                command, resolved, pins, omitted, window, total, size, page, rows,
                allowedActions());
    }

    private Map<String, Object> metricStatRow(
            QueryPageRows.DirectoryEmployeeRow employee,
            List<QueryPageRows.DailyMetricCellRow> days,
            Map<String, String> departments,
            String totalKey) {
        double total = 0;
        List<Map<String, Object>> cells = new ArrayList<>();
        for (QueryPageRows.DailyMetricCellRow cell : days) {
            double hours = minutesToHours(cell.minutes());
            total += hours;
            cells.add(rowMap(
                    "date", cell.businessDate(),
                    "dayType", cell.dayType(),
                    "hours", hours));
        }
        return rowMap(
                "employeeId", employee.employeeId(),
                "employeeNumber", employee.employeeNumber(),
                "employeeName", employee.employeeName(),
                "department", reportDepartment(
                        employee.organizationId(),
                        employee.organizationName(),
                        departments),
                totalKey, total,
                "days", List.copyOf(cells));
    }

    private Map<String, Object> financeOvertimeRow(
            QueryPageRows.DirectoryEmployeeRow employee,
            List<QueryPageRows.FinanceOvertimeCellRow> days,
            Map<String, String> departments,
            String overtimeTreatment) {
        long weekdayMinutes = 0;
        long weekendMinutes = 0;
        long holidayMinutes = 0;
        long paidMinutes = 0;
        long compensatoryMinutes = 0;
        long voluntaryMinutes = 0;
        Map<LocalDate, long[]> byDateMinutes = new LinkedHashMap<>();
        Map<LocalDate, String> byDateType = new LinkedHashMap<>();
        for (QueryPageRows.FinanceOvertimeCellRow cell : days) {
            paidMinutes += cell.paidOvertimeMinutes();
            compensatoryMinutes += cell.compensatoryOvertimeMinutes();
            voluntaryMinutes += cell.voluntaryOvertimeMinutes();
            long paid = cell.paidOvertimeMinutes();
            long compensatory = cell.compensatoryOvertimeMinutes();
            long voluntary = cell.voluntaryOvertimeMinutes();
            long feeMinutes = paid + compensatory;
            long displayMinutes = overtimeDisplayMinutes(
                    overtimeTreatment,
                    paid,
                    compensatory,
                    voluntary,
                    feeMinutes);
            boolean filtered = overtimeTreatment != null && !overtimeTreatment.isBlank();
            if (!filtered && paid + compensatory + voluntary == 0) {
                displayMinutes = cell.recognizedOvertimeMinutes();
            }
            switch (overtimeBucket(cell.dayType())) {
                case "weekend" -> weekendMinutes += displayMinutes;
                case "holiday" -> holidayMinutes += displayMinutes;
                default -> weekdayMinutes += displayMinutes;
            }
            long[] minutes = byDateMinutes.computeIfAbsent(
                    cell.businessDate(),
                    ignored -> new long[4]);
            minutes[0] += displayMinutes;
            minutes[1] += cell.paidOvertimeMinutes();
            minutes[2] += cell.compensatoryOvertimeMinutes();
            minutes[3] += cell.voluntaryOvertimeMinutes();
            byDateType.putIfAbsent(cell.businessDate(), cell.dayType());
        }
        List<Map<String, Object>> dayCells = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : byDateMinutes.entrySet()) {
            long[] minutes = entry.getValue();
            double hours = minutesToHours(minutes[0]);
            double paidHours = minutesToHours(minutes[1]);
            double compensatoryHours = minutesToHours(minutes[2]);
            double voluntaryHours = minutesToHours(minutes[3]);
            dayCells.add(rowMap(
                    "date", entry.getKey(),
                    "dayType", byDateType.get(entry.getKey()),
                    "hours", hours,
                    "treatment", overtimeTreatmentFromHours(
                            overtimeTreatment,
                            paidHours, compensatoryHours, voluntaryHours),
                    "paidHours", paidHours,
                    "compensatoryHours", compensatoryHours,
                    "voluntaryHours", voluntaryHours));
        }
        return rowMap(
                "employeeId", employee.employeeId(),
                "employeeNumber", employee.employeeNumber(),
                "employeeName", employee.employeeName(),
                "department", reportDepartment(
                        employee.organizationId(),
                        employee.organizationName(),
                        departments),
                "weekdayOvertimeHours", minutesToHours(weekdayMinutes),
                "weekendOvertimeHours", minutesToHours(weekendMinutes),
                "holidayOvertimeHours", minutesToHours(holidayMinutes),
                "paidOvertimeHours", minutesToHours(paidMinutes),
                "compensatoryOvertimeHours", minutesToHours(compensatoryMinutes),
                "voluntaryOvertimeHours", minutesToHours(voluntaryMinutes),
                "days", List.copyOf(dayCells));
    }

    private static long overtimeDisplayMinutes(
            String overtimeTreatment,
            long paid,
            long compensatory,
            long voluntary,
            long feeMinutes) {
        if (isVoluntaryOvertimeFilter(overtimeTreatment)) {
            return voluntary;
        }
        if (isPaidOvertimeFilter(overtimeTreatment)) {
            return paid;
        }
        if (isCompensatoryOvertimeFilter(overtimeTreatment)) {
            return compensatory;
        }
        return feeMinutes > 0 ? feeMinutes : voluntary;
    }

    private static boolean isVoluntaryOvertimeFilter(String overtimeTreatment) {
        return "义务加班".equals(overtimeTreatment);
    }

    private static boolean isPaidOvertimeFilter(String overtimeTreatment) {
        return "加班费".equals(overtimeTreatment)
                || "计薪加班".equals(overtimeTreatment);
    }

    private static boolean isCompensatoryOvertimeFilter(String overtimeTreatment) {
        return "转调休".equals(overtimeTreatment)
                || "转调休加班".equals(overtimeTreatment);
    }

    private static String overtimeTreatmentFromHours(
            String overtimeTreatment,
            double paidHours,
            double compensatoryHours,
            double voluntaryHours) {
        if (isVoluntaryOvertimeFilter(overtimeTreatment) && voluntaryHours > 0) {
            return "VOLUNTARY";
        }
        if (isPaidOvertimeFilter(overtimeTreatment) && paidHours > 0) {
            return "PAID";
        }
        if (isCompensatoryOvertimeFilter(overtimeTreatment) && compensatoryHours > 0) {
            return "COMPENSATORY";
        }
        if (voluntaryHours > 0 && paidHours <= 0 && compensatoryHours <= 0) {
            return "VOLUNTARY";
        }
        if (paidHours >= compensatoryHours
                && paidHours >= voluntaryHours
                && paidHours > 0) {
            return "PAID";
        }
        if (compensatoryHours >= voluntaryHours && compensatoryHours > 0) {
            return "COMPENSATORY";
        }
        if (voluntaryHours > 0) {
            return "VOLUNTARY";
        }
        return "";
    }

    private static String overtimeBucket(String dayType) {
        String type = dayType == null ? "" : dayType.toUpperCase();
        return switch (type) {
            case "SATURDAY", "SUNDAY" -> "weekend";
            case "PUBLIC_HOLIDAY" -> "holiday";
            default -> "weekday";
        };
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static boolean restDayWithoutWork(QueryPageRows.DailyJournalRow row) {
        if (row == null || row.scheduledMinutes() > 0) {
            return false;
        }
        String type = row.dayType() == null ? "" : row.dayType().toUpperCase();
        return switch (type) {
            case "SATURDAY", "SUNDAY", "PUBLIC_HOLIDAY", "WEEKEND" -> true;
            default -> false;
        };
    }

    private static Object hoursOrBlank(long minutes) {
        return minutes <= 0 ? "" : minutesToHours(minutes);
    }

    private static String leaveTypeName(String leaveType, long minutes) {
        if (minutes <= 0 || leaveType == null || leaveType.isBlank()) {
            return "";
        }
        return switch (leaveType.toUpperCase()) {
            case "ANNUAL", "ANNUAL_LEAVE" -> "年假";
            case "PERSONAL", "PERSONAL_LEAVE" -> "事假";
            case "SICK", "SICK_LEAVE" -> "病假";
            case "TIME_OFF", "COMPENSATORY" -> "调休";
            case "BREASTFEEDING", "BREASTFEEDING_TIME" -> "哺乳假";
            case "PRENATAL_NURSING", "PRENATAL_EXAM_TIME" -> "孕检假";
            case "MARRIAGE" -> "婚假";
            case "MATERNITY" -> "产假";
            case "PATERNITY" -> "陪产假";
            case "BEREAVEMENT" -> "丧假";
            case "WORK_INJURY" -> "工伤假";
            default -> leaveType;
        };
    }

    private static String journalRemark(
            QueryPageRows.DailyJournalRow row,
            boolean requirePunch,
            boolean samePunch,
            List<OvernightOvertimeFold.OvertimeSpan> spans) {
        if (row.firstPunchAt() == null && row.lastPunchAt() == null) {
            return requirePunch ? "漏刷" : "";
        }
        if (row.firstPunchAt() == null) {
            return "上班漏刷";
        }
        if (row.lastPunchAt() == null
                || (samePunch && row.firstPunchAt() != null)) {
            return requirePunch ? "下班漏刷" : "";
        }
        if (spans != null) {
            for (OvernightOvertimeFold.OvertimeSpan span : spans) {
                if (!span.effective()) {
                    continue;
                }
                if (OvernightOvertimeFold.continuationMinutes(
                        span, OvernightOvertimeFold.DEFAULT_SHIFT_START) <= 0) {
                    continue;
                }
                LocalDate startDate = span.start()
                        .atZone(BUSINESS_ZONE)
                        .toLocalDate();
                if (row.businessDate().equals(startDate)) {
                    return "加班 "
                            + PunchClockFormat.format(
                                    row.businessDate(), span.start())
                            + "–"
                            + PunchClockFormat.format(
                                    row.businessDate(), span.endExclusive());
                }
            }
        }
        return "";
    }

    private Map<String, List<OvernightOvertimeFold.OvertimeSpan>>
            overtimeSpansByEmployee(FactQuery query) {
        return overtimeSpansByEmployee(query, query.employeeIds());
    }

    private Map<String, List<OvernightOvertimeFold.OvertimeSpan>>
            overtimeSpansByEmployee(
                    FactQuery query, List<String> pageEmployeeIds) {
        if (pageEmployeeIds != null && pageEmployeeIds.isEmpty()) {
            return Map.of();
        }
        List<String> employeeIds = pageEmployeeIds == null
                        || pageEmployeeIds.isEmpty()
                ? query.employeeIds()
                : pageEmployeeIds;
        FactQuery oaQuery = new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate() == null
                        ? null
                        : query.fromDate().minusDays(1),
                query.toDateExclusive() == null
                        ? null
                        : query.toDateExclusive().plusDays(1),
                query.organizationIds(),
                employeeIds,
                query.employeeNumber(),
                query.employeeId(),
                query.exceptionType(),
                query.severity(),
                query.state(),
                "OVERTIME",
                query.leaveType(),
                query.sourceStatus(),
                query.overtimeTreatment(),
                query.occurrenceDay(),
                query.lateCountBand(),
                query.lateMinuteBand(),
                query.punchSide(),
                query.employmentStatus(),
                query.attendanceType(),
                query.rateBelow(),
                query.annualBalanceBand(),
                query.annualLevelOne(),
                query.annualLevelTwo(),
                query.accountType(),
                0,
                MAX_EXPORT_ROWS);
        List<QueryPageRows.OaRow> documents = mapper.listOaDocuments(oaQuery);
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        Map<String, List<OvernightOvertimeFold.OvertimeSpan>> result =
                new HashMap<>();
        for (QueryPageRows.OaRow row : documents) {
            boolean effective = row.sourceStatus() != null
                    && switch (row.sourceStatus().toUpperCase()) {
                        case "APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN" -> true;
                        default -> false;
                    };
            result.computeIfAbsent(row.employeeId(), ignored -> new ArrayList<>())
                    .add(new OvernightOvertimeFold.OvertimeSpan(
                            row.startAt(),
                            row.endExclusive(),
                            effective));
        }
        return result;
    }

    private static long scaleMinutes(
            long part, long calendarTotal, long displayTotal) {
        if (calendarTotal <= 0) {
            return displayTotal > 0 && part > 0 ? displayTotal : 0;
        }
        return Math.max(0, Math.round(part * (double) displayTotal / calendarTotal));
    }

    private Resolved resolve(
            String companyId,
            String organizationId,
            YearMonth period,
            Instant at) {
        String principalId = principals.currentPrincipalId();
        List<AttendanceReportSourceRepository.CompanyOption> companies =
                sources.listAuthorizedCompanies(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        period,
                        at);
        String resolvedCompany = companyId;
        if (resolvedCompany == null && companies.size() == 1) {
            resolvedCompany = companies.getFirst().companyId();
        }
        final String selectedCompany = resolvedCompany;
        if (selectedCompany == null
                || companies.stream().noneMatch(option ->
                        option.companyId().equals(selectedCompany))) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                    "当前用户没有该报表范围的访问权限");
        }
        RealtimeAuthorization authorization = sources.resolveRealtimeAuthorization(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                        selectedCompany,
                        at)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.FORBIDDEN,
                        "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                        "当前用户没有该报表范围的访问权限"));
        return new Resolved(selectedCompany, authorization, organizationId);
    }

    private boolean hasSourceEvidence(String companyId, YearMonth month, Instant at) {
        return Boolean.TRUE.equals(mapper.companyHasSourceEvidence(
                companyId,
                month.atDay(1),
                month.plusMonths(1).atDay(1),
                at));
    }

    private DateWindow dateWindow(QueryCommand command, Instant at) {
        LocalDate today = at.atZone(BUSINESS_ZONE).toLocalDate();
        if (command.fromDate() != null && command.toDate() != null) {
            if (command.toDate().isBefore(command.fromDate())) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "ATTENDANCE_REPORT_QUERY_INVALID_RANGE",
                        "结束日期不能早于开始日期");
            }
            return DateWindow.of(command.fromDate(), command.toDate());
        }
        YearMonth month = command.period() == null
                ? YearMonth.from(today)
                : command.period();
        return DateWindow.of(month.atDay(1), month.atEndOfMonth());
    }

    private List<String> allowedActions() {
        List<String> actions = new ArrayList<>();
        actions.add("REPORT_QUERY");
        if (capabilities.currentCapabilities().contains(
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH)) {
            actions.add("REPORT_RECALCULATE");
        }
        if (capabilities.currentCapabilities().contains(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)) {
            actions.add("REPORT_EXPORT_CREATE");
        }
        return actions;
    }

    private static String documentType(String sheet) {
        return switch (sheet) {
            case "leave" -> "LEAVE";
            case "overtime" -> "OVERTIME";
            case "makeup" -> "PUNCH_CORRECTION";
            default -> null;
        };
    }

    private static String accountType(String sheet) {
        return switch (sheet) {
            case "annual-leave", "annual-leave-stat" -> "ANNUAL_LEAVE";
            case "time-off", "time-off-stat" -> "TIME_OFF";
            default -> null;
        };
    }

    private static FactQuery withOffsetLimit(
            FactQuery query, int offset, int limit) {
        return new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                query.employeeIds(),
                query.employeeNumber(),
                query.employeeId(),
                query.exceptionType(),
                query.severity(),
                query.state(),
                query.documentType(),
                query.leaveType(),
                query.sourceStatus(),
                query.overtimeTreatment(),
                query.occurrenceDay(),
                query.lateCountBand(),
                query.lateMinuteBand(),
                query.punchSide(),
                query.employmentStatus(),
                query.attendanceType(),
                query.rateBelow(),
                query.annualBalanceBand(),
                query.annualLevelOne(),
                query.annualLevelTwo(),
                query.accountType(),
                offset,
                limit,
                query.sheet());
    }

    private static FactQuery withEmployeeIds(FactQuery query, List<String> employeeIds) {
        return new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                employeeIds,
                query.employeeNumber(),
                query.employeeId(),
                query.exceptionType(),
                query.severity(),
                query.state(),
                query.documentType(),
                query.leaveType(),
                query.sourceStatus(),
                query.overtimeTreatment(),
                query.occurrenceDay(),
                query.lateCountBand(),
                query.lateMinuteBand(),
                query.punchSide(),
                query.employmentStatus(),
                query.attendanceType(),
                query.rateBelow(),
                query.annualBalanceBand(),
                query.annualLevelOne(),
                query.annualLevelTwo(),
                query.accountType(),
                0,
                employeeIds.size(),
                query.sheet());
    }

    private static FactQuery withProjectionIds(
            FactQuery query, List<String> projectionIds) {
        return new FactQuery(
                projectionIds,
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                query.employeeIds(),
                query.employeeNumber(),
                query.employeeId(),
                query.exceptionType(),
                query.severity(),
                query.state(),
                query.documentType(),
                query.leaveType(),
                query.sourceStatus(),
                query.overtimeTreatment(),
                query.occurrenceDay(),
                query.lateCountBand(),
                query.lateMinuteBand(),
                query.punchSide(),
                query.employmentStatus(),
                query.attendanceType(),
                query.rateBelow(),
                query.annualBalanceBand(),
                query.annualLevelOne(),
                query.annualLevelTwo(),
                query.accountType(),
                query.offset(),
                query.limit(),
                query.sheet());
    }

    private static FactQuery withExceptionType(FactQuery query, String type) {
        return new FactQuery(
                query.projectionIds(),
                query.companyId(),
                query.fromDate(),
                query.toDateExclusive(),
                query.organizationIds(),
                query.employeeIds(),
                query.employeeNumber(),
                query.employeeId(),
                type,
                query.severity(),
                query.state(),
                query.documentType(),
                query.leaveType(),
                query.sourceStatus(),
                query.overtimeTreatment(),
                query.occurrenceDay(),
                query.lateCountBand(),
                query.lateMinuteBand(),
                query.punchSide(),
                query.employmentStatus(),
                query.attendanceType(),
                query.rateBelow(),
                query.annualBalanceBand(),
                query.annualLevelOne(),
                query.annualLevelTwo(),
                query.accountType(),
                query.offset(),
                query.limit(),
                query.sheet());
    }

    private static Map<String, Object> dailyRow(
            String sheet, QueryPageRows.EmployeeDailyAggregateRow row) {
        return switch (sheet) {
            case "late" -> rowMap(
                    "employeeNumber", row.employeeNumber(),
                    "employeeName", row.employeeName(),
                    "department", row.organizationName(),
                    "lateEvents", row.lateEvents(),
                    "lateMinutes", row.lateMinutes(),
                    "penalizedLateMinutes", row.penalizedLateMinutes());
            case "work-hours" -> workHoursRow(row, List.of());
            default -> rowMap(
                    "employeeNumber", row.employeeNumber(),
                    "employeeName", row.employeeName(),
                    "department", row.organizationName(),
                    "scheduledDays", row.scheduledAttendanceDays(),
                    "actualDays", row.actualAttendanceDays(),
                    "attendanceRate", rate(row.actualAttendanceDays(), row.scheduledAttendanceDays()));
        };
    }

    static Map<String, Object> workHoursRow(
            QueryPageRows.EmployeeDailyAggregateRow row) {
        return workHoursRow(row, List.of());
    }

    static Map<String, Object> workHoursRow(
            QueryPageRows.EmployeeDailyAggregateRow row,
            List<QueryPageRows.OaRow> oaDocuments) {
        long annual = 0;
        long timeOff = 0;
        long otherLeave = 0;
        boolean oaLeaveApplied = false;
        List<QueryPageRows.OaRow> documents = oaDocuments == null
                ? List.of()
                : oaDocuments;
        for (QueryPageRows.OaRow document : documents) {
            String documentType = document.documentType() == null
                    ? ""
                    : document.documentType().toUpperCase();
            if (!Set.of("LEAVE", "TIME_OFF").contains(documentType)) {
                continue;
            }
            String status = document.sourceStatus() == null
                    ? ""
                    : document.sourceStatus().toUpperCase();
            if (!Set.of("APPROVED", "MODIFIED", "SUPPLEMENTED", "UNKNOWN")
                    .contains(status)) {
                continue;
            }
            oaLeaveApplied = true;
            String classified = document.leaveType() == null
                    ? ""
                    : document.leaveType().toUpperCase();
            long minutes = document.recognizedMinutes();
            if ("TIME_OFF".equals(documentType)
                    || classified.equals("COMPENSATORY")
                    || classified.equals("TIME_OFF")
                    || classified.contains("调休")) {
                timeOff += minutes;
            } else if (classified.equals("ANNUAL")
                    || classified.equals("ANNUAL_LEAVE")
                    || classified.contains("年假")) {
                annual += minutes;
            } else {
                otherLeave += minutes;
            }
        }
        if (!oaLeaveApplied) {
            otherLeave = row.leaveMinutes();
        }
        long exchanged = row.compensatoryOvertimeMinutes();
        long actual = row.scheduledMinutes()
                + row.paidOvertimeMinutes()
                + row.voluntaryOvertimeMinutes()
                - otherLeave
                - annual
                + exchanged
                - timeOff;
        String note = employmentNote(row);
        return rowMap(
                "employeeNumber", row.employeeNumber(),
                "employeeName", row.employeeName(),
                "department", row.organizationName(),
                "scheduledHours", minutesToHours(row.scheduledMinutes()),
                "paidOvertimeHours", minutesToHours(row.paidOvertimeMinutes()),
                "voluntaryOvertimeHours", minutesToHours(row.voluntaryOvertimeMinutes()),
                "leaveHours", minutesToHours(otherLeave),
                "annualLeaveHours", minutesToHours(annual),
                "compensatoryOvertimeHours", minutesToHours(exchanged),
                "timeOffHours", minutesToHours(timeOff),
                "actualHours", minutesToHours(actual),
                "note", note);
    }

    private static String employmentNote(
            QueryPageRows.EmployeeDailyAggregateRow row) {
        String note = row.employmentNote();
        if (note == null || note.isBlank() || "无班次".equals(note)) {
            return null;
        }
        return note;
    }

    private static String formatExceptionDetails(QueryPageRows.ExceptionRow row) {
        String type = row.exceptionType() == null
                ? ""
                : row.exceptionType().toUpperCase();
        Instant morning = morningPunch(row.firstPunchAt(), row.lastPunchAt());
        Instant afternoon = afternoonPunch(row.firstPunchAt(), row.lastPunchAt());
        long minutes = row.exceptionMinutes();
        if (type.startsWith("MISSING_PUNCH")
                || type.contains("MISSING_ON")
                || type.contains("MISSING_OFF")) {
            if (morning == null && afternoon != null) {
                return "无上班卡，下班 " + CLOCK.format(afternoon);
            }
            if (morning != null && afternoon == null) {
                return "上班 " + CLOCK.format(morning) + "，无下班卡";
            }
            return "无上班卡，无下班卡";
        }
        return switch (type) {
            case "LATE", "LATE_CONVERTED_TO_ABSENCE" -> morning == null
                    ? "计罚 " + minutes + " 分钟"
                    : "上班 " + CLOCK.format(morning) + "，计罚 " + minutes + " 分钟";
            case "EARLY_DEPARTURE" -> afternoon == null
                    ? "早退 " + minutes + " 分钟"
                    : "下班 " + CLOCK.format(afternoon) + "，早退 " + minutes + " 分钟";
            case "ABSENCE" -> "应出勤，无打卡无单据";
            case "OUTING_OVERTIME_UNDECLARED" -> "外出超时未报加班";
            case "FAKE_OVERTIME" -> "加班时段盖住未请假的上班时段";
            case "OVERTIME_DOCUMENT_MISSING_OR_LATE" -> row.details() == null
                    || row.details().isBlank()
                    || row.details().startsWith("原因码=")
                    ? "未报加班"
                    : row.details();
            case "OVERTIME_FORM_BEYOND_LAST_PUNCH" -> row.details() == null
                    || row.details().isBlank()
                    || row.details().startsWith("原因码=")
                    ? "加班结束晚于打卡"
                    : row.details();
            case "LONG_PUNCH_SPAN_REVIEW" -> row.details() == null
                    || row.details().isBlank()
                    || row.details().startsWith("原因码=")
                    ? "长时在岗待审"
                    : row.details();
            default -> readableSummary(row.details(), type);
        };
    }

    private static Instant morningPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            int hour = only.atZone(BUSINESS_ZONE).getHour();
            return hour < 12 ? only : null;
        }
        return first;
    }

    private static Instant afternoonPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            int hour = only.atZone(BUSINESS_ZONE).getHour();
            return hour >= 12 ? only : null;
        }
        return last;
    }

    private static String readableSummary(String details, String type) {
        if (details == null || details.isBlank()) {
            return switch (type) {
                case "LEAVE_PUNCH_CONFLICT" -> "请假与打卡冲突";
                case "EVIDENCE_CONFLICT" -> "证据冲突";
                default -> "—";
            };
        }
        if (!details.startsWith("原因码=")) {
            return details;
        }
        return switch (type) {
            case "LEAVE_PUNCH_CONFLICT" -> "请假与打卡冲突";
            case "OUTING_OVERTIME_UNDECLARED" -> "外出超时未报加班";
            case "OVERTIME_DOCUMENT_MISSING_OR_LATE" -> "未报加班";
            case "OVERTIME_FORM_BEYOND_LAST_PUNCH" -> "加班结束晚于打卡";
            case "LONG_PUNCH_SPAN_REVIEW" -> "长时在岗待审";
            case "ABSENCE" -> "应出勤，无打卡无单据";
            case "NO_SHIFT_OR_CALENDAR" -> "当天无班次或日历";
            case "NO_ATTENDANCE_GROUP" -> "未分配考勤组";
            default -> "—";
        };
    }

    private static boolean matchesStatus(
            QueryPageRows.DailyCellRow day, String status) {
        return switch (status) {
            case "late" -> day.lateMinutes() > 0;
            case "early" -> day.earlyDepartureMinutes() > 0;
            case "missed" -> day.missingPunchCount() > 0;
            case "annual-leave" -> "ANNUAL".equals(day.leaveType());
            case "rest-day" -> "SATURDAY".equals(day.dayType())
                    || "SUNDAY".equals(day.dayType())
                    || "PUBLIC_HOLIDAY".equals(day.dayType());
            default -> status.equalsIgnoreCase(day.leaveType());
        };
    }

    private static QueryPage pageOf(
            QueryCommand command,
            Resolved resolved,
            List<PinRow> pins,
            List<OmittedMonth> omitted,
            DateWindow window,
            long total,
            int size,
            int page,
            List<Map<String, Object>> rows,
            List<String> actions) {
        String hint = omitted.stream().anyMatch(item -> "NO_DATA".equals(item.reason()))
                ? "部分月份暂无打卡或核算数据，未计入："
                    + omitted.stream()
                            .filter(item -> "NO_DATA".equals(item.reason()))
                            .map(OmittedMonth::period)
                            .reduce((left, right) -> left + "、" + right)
                            .orElse("")
                : (total == 0 ? "当前筛选条件下没有记录" : null);
        PinRow latest = pins.getFirst();
        ReportType reportType = exportReportType(command.sheet());
        String fingerprint = AttendanceReportQueryService.fingerprint(
                reportType,
                new ReportFilter(
                        YearMonth.from(window.from()),
                        resolved.companyId(),
                        command.organizationId(),
                        command.employeeId(),
                        null),
                latest.projectionVersion(),
                resolved.authorization().scope().authorizationDigest(),
                AttendanceReportCalculator.formulaVersion(reportType));
        return new QueryPage(
                command.sheet(),
                resolved.companyId(),
                window.from().toString(),
                window.to().toString(),
                latest.projectionVersion(),
                latest.dataAsOf(),
                latest.periodState(),
                omitted,
                hint,
                total,
                page,
                size,
                rows,
                actions,
                fingerprint,
                resolved.authorization().scope().reference(),
                exportFieldKeys(reportType));
    }

    private static ReportType exportReportType(String sheet) {
        return switch (sheet) {
            case "matrix", "daily-journal" -> ReportType.ATTENDANCE_DETAIL;
            case "work-hours" -> ReportType.WORK_HOURS;
            case "missed-punch", "missed-punch-stat" -> ReportType.MISSED_PUNCH;
            case "attendance-rate" -> ReportType.ATTENDANCE_RATE;
            case "annual-leave", "time-off", "annual-leave-stat", "time-off-stat" ->
                    ReportType.ANNUAL_LEAVE;
            case "leave", "leave-summary" -> ReportType.LEAVE;
            case "overtime",
                    "overtime-daily",
                    "finance-overtime",
                    "overtime-fee-daily",
                    "overtime-comp-daily",
                    "overtime-voluntary-daily" -> ReportType.OVERTIME;
            case "absence-stat" -> ReportType.EXCEPTIONS;
            case "leave-stat" -> ReportType.LEAVE;
            case "makeup" -> ReportType.LEAVE;
            case "late" -> ReportType.LATE;
            case "exceptions" -> ReportType.EXCEPTIONS;
            default -> ReportType.EXCEPTIONS;
        };
    }

    private static List<String> exportFieldKeys(ReportType type) {
        return switch (type) {
            case WORK_HOURS -> List.of(
                    "employee-number",
                    "employee-name",
                    "organization",
                    "scheduled-hours",
                    "paid-overtime-hours",
                    "leave-hours",
                    "annual-leave-hours",
                    "compensatory-overtime-hours",
                    "time-off-hours",
                    "actual-work-hours");
            case OVERTIME -> List.of(
                    "employee-number",
                    "employee-name",
                    "organization",
                    "paid-overtime-hours",
                    "compensatory-overtime-hours",
                    "voluntary-overtime-hours",
                    "total-overtime-hours",
                    "recognized-overtime-hours");
            case LEAVE -> List.of(
                    "employee-number",
                    "employee-name",
                    "organization",
                    "document-type",
                    "document-start",
                    "document-end",
                    "recognized-hours",
                    "approval-state");
            case LATE -> List.of(
                    "employee-number",
                    "employee-name",
                    "organization",
                    "late-event-count",
                    "late-minutes",
                    "penalized-late-minutes");
            case MISSED_PUNCH -> List.of(
                    "employee-number",
                    "employee-name",
                    "organization",
                    "missing-punch-count");
            case EXCEPTIONS -> List.of(
                    "business-date",
                    "employee-number",
                    "employee-name",
                    "organization",
                    "exception-type",
                    "exception-severity",
                    "exception-state",
                    "exception-minutes",
                    "exception-details");
            default -> List.of("employee-number", "employee-name", "organization");
        };
    }

    private Map<String, String> departmentPaths(String companyId) {
        Map<String, String> paths = sources.reportDepartmentPaths(companyId);
        return paths == null ? Map.of() : paths;
    }

    private static String reportDepartment(
            String organizationId,
            String fallback,
            Map<String, String> departments) {
        if (organizationId == null || departments == null || departments.isEmpty()) {
            return fallback;
        }
        String display = departments.get(organizationId);
        return display == null || display.isBlank() ? fallback : display;
    }

    private static QueryPageRows.DirectoryEmployeeRow withDirectoryDepartment(
            QueryPageRows.DirectoryEmployeeRow row,
            Map<String, String> departments) {
        String display = reportDepartment(
                row.organizationId(), row.organizationName(), departments);
        if (Objects.equals(display, row.organizationName())) {
            return row;
        }
        return new QueryPageRows.DirectoryEmployeeRow(
                row.employeeId(),
                row.employeeNumber(),
                row.employeeName(),
                row.organizationId(),
                display);
    }

    private static Map<String, Object> withDepartment(
            Map<String, Object> row,
            String organizationId,
            String fallback,
            Map<String, String> departments) {
        row.put("department", reportDepartment(
                organizationId, fallback, departments));
        return row;
    }

    private static Map<String, Object> rowMap(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    private static double minutesToHours(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(BigDecimal.valueOf(60), 1, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static BigDecimal hoursToDays(BigDecimal hours) {
        if (hours == null) {
            return BigDecimal.ZERO;
        }
        return hours.divide(BigDecimal.valueOf(8), 2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal actual, long scheduled) {
        if (scheduled <= 0 || actual == null) {
            return BigDecimal.ZERO;
        }
        return actual.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(scheduled), 1, java.math.RoundingMode.HALF_UP);
    }

    private static int normalizeSize(int size, int max) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, max);
    }

    public record QueryCommand(
            String sheet,
            String companyId,
            String organizationId,
            String employeeId,
            String employeeNumber,
            YearMonth period,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size,
            String exceptionType,
            String severity,
            String state,
            String leaveType,
            String approvalState,
            String overtimeTreatment,
            Integer occurrenceDay,
            String lateCountBand,
            String lateMinuteBand,
            String punchSide,
            String employmentStatus,
            String attendanceType,
            BigDecimal rateBelow,
            String annualBalanceBand,
            String annualLevelOne,
            String annualLevelTwo,
            String attendanceStatus) {

        QueryCommand withPaging(int page, int size) {
            return new QueryCommand(
                    sheet,
                    companyId,
                    organizationId,
                    employeeId,
                    employeeNumber,
                    period,
                    fromDate,
                    toDate,
                    page,
                    size,
                    exceptionType,
                    severity,
                    state,
                    leaveType,
                    approvalState,
                    overtimeTreatment,
                    occurrenceDay,
                    lateCountBand,
                    lateMinuteBand,
                    punchSide,
                    employmentStatus,
                    attendanceType,
                    rateBelow,
                    annualBalanceBand,
                    annualLevelOne,
                    annualLevelTwo,
                    attendanceStatus);
        }

        QueryCommand withLockedOvertimeTreatment() {
            String locked = switch (sheet) {
                case "overtime-fee-daily" -> "加班费";
                case "overtime-comp-daily" -> "转调休";
                case "overtime-voluntary-daily" -> "义务加班";
                default -> null;
            };
            if (locked == null || locked.equals(overtimeTreatment)) {
                return this;
            }
            return new QueryCommand(
                    sheet,
                    companyId,
                    organizationId,
                    employeeId,
                    employeeNumber,
                    period,
                    fromDate,
                    toDate,
                    page,
                    size,
                    exceptionType,
                    severity,
                    state,
                    leaveType,
                    approvalState,
                    locked,
                    occurrenceDay,
                    lateCountBand,
                    lateMinuteBand,
                    punchSide,
                    employmentStatus,
                    attendanceType,
                    rateBelow,
                    annualBalanceBand,
                    annualLevelOne,
                    annualLevelTwo,
                    attendanceStatus);
        }
    }

    public record DirectoryPage(
            String period,
            String companyId,
            List<AttendanceReportSourceRepository.CompanyOption> companies,
            List<QueryPageRows.DirectoryEmployeeRow> employees) {
    }

    public record OmittedMonth(String period, String reason) {
    }

    public record QueryPage(
            String sheet,
            String companyId,
            String fromDate,
            String toDate,
            String projectionVersion,
            Instant dataAsOf,
            String periodState,
            List<OmittedMonth> omittedMonths,
            String hint,
            long rowCount,
            int page,
            int size,
            List<Map<String, Object>> rows,
            List<String> allowedActions,
            String queryFingerprint,
            String scopeReference,
            List<String> exportFieldAllowlist) {

        static QueryPage empty(
                String sheet,
                String companyId,
                DateWindow window,
                List<OmittedMonth> omitted,
                String hint,
                List<String> actions) {
            return new QueryPage(
                    sheet,
                    companyId,
                    window.from().toString(),
                    window.to().toString(),
                    null,
                    null,
                    null,
                    omitted,
                    hint,
                    0,
                    0,
                    DEFAULT_PAGE_SIZE,
                    List.of(),
                    actions,
                    null,
                    null,
                    List.of());
        }
    }

    record DateWindow(LocalDate from, LocalDate to, List<YearMonth> months) {
        static DateWindow of(LocalDate from, LocalDate to) {
            List<YearMonth> months = new ArrayList<>();
            YearMonth cursor = YearMonth.from(from);
            YearMonth last = YearMonth.from(to);
            while (!cursor.isAfter(last)) {
                months.add(cursor);
                cursor = cursor.plusMonths(1);
            }
            return new DateWindow(from, to, List.copyOf(months));
        }

        LocalDate toExclusive() {
            return to.plusDays(1);
        }
    }

    final class Resolved {
        private final String companyId;
        private final RealtimeAuthorization authorization;
        private final String selectedOrganizationId;

        Resolved(
                String companyId,
                RealtimeAuthorization authorization,
                String selectedOrganizationId) {
            this.companyId = companyId;
            this.authorization = authorization;
            this.selectedOrganizationId = selectedOrganizationId;
        }

        String companyId() {
            return companyId;
        }

        RealtimeAuthorization authorization() {
            return authorization;
        }

        List<String> employeeIdList() {
            return List.copyOf(authorization.employeeIds());
        }

        List<String> selectedOrAuthorizedOrgs(String requestedOrganizationId) {
            String selected = requestedOrganizationId == null
                    ? selectedOrganizationId
                    : requestedOrganizationId;
            if (selected != null) {
                List<String> descendants = mapper.listDescendantOrganizationIds(selected);
                if (!authorization.companyWide()
                        && descendants.stream().noneMatch(
                                authorization.organizationIds()::contains)
                        && !authorization.organizationIds().contains(selected)) {
                    return List.of("__none__");
                }
                if (authorization.companyWide()) {
                    return descendants;
                }
                return descendants.stream()
                        .filter(authorization.organizationIds()::contains)
                        .toList();
            }
            if (authorization.companyWide()) {
                return List.of();
            }
            return List.copyOf(authorization.organizationIds());
        }
    }
}

package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedDashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedScope;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DailyTrendPoint;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardAnalytics;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.DashboardSnapshot;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.ExceptionSummary;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.OrganizationRankingItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.SeverityDistributionItem;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.TypeDistributionItem;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;

import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AttendanceDashboardService {

    private static final Logger log =
            LoggerFactory.getLogger(AttendanceDashboardService.class);
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceDashboardRepository repository;
    private final AttendanceReportSourceRepository reportSources;
    private final AttendanceDashboardWorkbenchAssembler workbench;
    private final RealtimeAttendanceReportSnapshotService realtimeSnapshots;
    private final Clock clock;

    public AttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceDashboardRepository repository,
            Clock clock) {
        this(capabilities, principalProvider, repository, clock, null, null, null);
    }

    public AttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceDashboardRepository repository,
            Clock clock,
            AttendanceReportSourceRepository reportSources,
            AttendanceDashboardWorkbenchAssembler workbench) {
        this(
                capabilities,
                principalProvider,
                repository,
                clock,
                reportSources,
                workbench,
                null);
    }

    @Autowired
    public AttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceDashboardRepository repository,
            Clock clock,
            @Autowired(required = false)
                    AttendanceReportSourceRepository reportSources,
            @Autowired(required = false)
                    AttendanceDashboardWorkbenchAssembler workbench,
            @Autowired(required = false)
                    RealtimeAttendanceReportSnapshotService realtimeSnapshots) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.reportSources = reportSources;
        this.workbench = workbench;
        this.realtimeSnapshots = realtimeSnapshots;
        this.clock = clock;
    }

    public QueryResult query(String companyId) {
        return query(companyId, null, "MONTH");
    }

    public QueryResult query(String companyId, YearMonth period, String window) {
        String requestedCompanyId = normalizeCompanyId(companyId);
        capabilities.require(CapabilityCodes.ATTENDANCE_DASHBOARD_READ);

        var authorizationTime = clock.instant();
        LocalDate today = authorizationTime.atZone(BUSINESS_ZONE).toLocalDate();
        YearMonth resolvedPeriod = period == null ? YearMonth.from(today) : period;
        boolean dayWindow = "DAY".equalsIgnoreCase(window);
        LocalDate businessDate = dayWindow
                ? today
                : (resolvedPeriod.equals(YearMonth.from(today))
                        ? today
                        : resolvedPeriod.atEndOfMonth());
        YearMonth queryPeriod = resolvedPeriod;
        String principalId = principalProvider.currentPrincipalId();
        if (workbench != null && reportSources != null) {
            try {
                return queryWorkbench(
                        principalId,
                        requestedCompanyId,
                        queryPeriod,
                        businessDate,
                        dayWindow,
                        authorizationTime);
            } catch (ApiProblemException problem) {
                throw problem;
            } catch (RuntimeException failed) {
                log.error("workbench query failed", failed);
                throw sourceNotReady();
            }
        }
        if (realtimeSnapshots != null && reportSources != null) {
            try {
                return queryPinned(
                        principalId,
                        requestedCompanyId,
                        queryPeriod,
                        businessDate,
                        dayWindow,
                        authorizationTime);
            } catch (ApiProblemException problem) {
                throw problem;
            } catch (RuntimeException failed) {
                log.error("pinned dashboard query failed", failed);
                throw sourceNotReady();
            }
        }
        List<CompanyOption> companies =
                repository.listAuthorizedCompanies(
                        principalId, queryPeriod, authorizationTime);
        if (companies.isEmpty()) {
            throw projectionNotReady();
        }
        CompanyOption selected = selectCompany(
                requestedCompanyId, companies, AttendanceDashboardService::projectionNotReady);
        AuthorizedDashboardSnapshot authorizedSnapshot =
                repository.loadAuthorizedToday(
                                principalId,
                                selected.companyId(),
                                businessDate,
                                authorizationTime)
                        .orElseThrow(
                                AttendanceDashboardService
                                        ::projectionNotReady);
        DashboardSnapshot snapshot = authorizedSnapshot.snapshot();
        if (!selected.companyId().equals(snapshot.companyId())) {
            throw projectionNotReady();
        }
        var businessDayStart =
                businessDate.atStartOfDay(BUSINESS_ZONE).toInstant();
        if (snapshot.dataAsOf().isBefore(businessDayStart)) {
            throw projectionNotReady();
        }
        List<String> allowedActions = authorizedSnapshot
                        .employeeDetailsAuthorized()
                ? List.of("DASHBOARD_DRILL_DOWN")
                : List.of();
        return new Ready(
                businessDate,
                selected,
                companies,
                snapshot,
                allowedActions,
                List.of(),
                List.of());
    }

    private QueryResult queryPinned(
            String principalId,
            String requestedCompanyId,
            YearMonth period,
            LocalDate businessDate,
            boolean dayWindow,
            java.time.Instant authorizationTime) {
        List<CompanyOption> companies = authorizedCompanies(
                principalId,
                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                period,
                authorizationTime);
        if (companies.isEmpty()) {
            companies = authorizedCompanies(
                    principalId,
                    CapabilityCodes.ATTENDANCE_REPORT_READ,
                    period,
                    authorizationTime);
        }
        if (companies.isEmpty()) {
            throw sourceNotReady();
        }
        CompanyOption selected = selectCompany(
                requestedCompanyId, companies, AttendanceDashboardService::sourceNotReady);
        var snapshot = reportSources.loadAuthorizedSnapshot(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter(
                                period,
                                selected.companyId(),
                                null,
                                null,
                                null),
                        authorizationTime)
                .orElseThrow(AttendanceDashboardService::sourceNotReady);
        boolean employeeDetailsAuthorized = capabilities.currentCapabilities()
                .contains(CapabilityCodes.ATTENDANCE_REPORT_READ);
        LocalDate from = dayWindow ? businessDate : period.atDay(1);
        List<ExceptionItem> exceptions = snapshot.exceptionFacts().stream()
                .filter(fact -> !fact.businessDate().isBefore(from)
                        && !fact.businessDate().isAfter(businessDate))
                .sorted(Comparator
                        .comparing(ExceptionFact::severity)
                        .thenComparing(ExceptionFact::employeeNumber))
                .limit(500)
                .map(fact -> new ExceptionItem(
                        fact.caseId(),
                        fact.employeeNumber(),
                        fact.employeeName(),
                        fact.organizationName(),
                        fact.businessDate(),
                        fact.exceptionType(),
                        fact.severity().name(),
                        fact.state().name(),
                        fact.minutes(),
                        fact.safeEvidenceSummary()))
                .toList();
        DashboardSnapshot dashboard = snapshotFromFacts(
                selected.companyId(),
                snapshot.projectionVersion(),
                snapshot.sourceVersions(),
                snapshot.dataAsOf(),
                snapshot.periodState(),
                new AuthorizedScope(
                        Enum.valueOf(
                                ScopeType.class,
                                snapshot.scope().type().name()),
                        snapshot.scope().reference(),
                        snapshot.scope().label(),
                        snapshot.scope().authorizationDigest()),
                snapshot.exceptionFacts(),
                from,
                businessDate,
                exceptions);
        List<String> allowedActions = employeeDetailsAuthorized
                ? List.of("DASHBOARD_DRILL_DOWN")
                : List.of();
        return new Ready(
                businessDate,
                selected,
                companies,
                dashboard,
                allowedActions,
                List.of(),
                List.of());
    }

    private QueryResult queryWorkbench(
            String principalId,
            String requestedCompanyId,
            YearMonth period,
            LocalDate businessDate,
            boolean dayWindow,
            java.time.Instant authorizationTime) {
        if (requestedCompanyId != null) {
            normalizeCompanyId(requestedCompanyId);
        }
        boolean employeeDetailsAuthorized = capabilities.currentCapabilities()
                .contains(CapabilityCodes.ATTENDANCE_REPORT_READ);
        AttendanceDashboardWorkbenchAssembler.Assembled assembled =
                workbench.assemble(
                                principalId,
                                period,
                                businessDate,
                                dayWindow,
                                authorizationTime,
                                employeeDetailsAuthorized)
                        .orElseGet(() -> emptyWorkbench(
                                businessDate, authorizationTime));
        List<String> allowedActions = employeeDetailsAuthorized
                ? List.of("DASHBOARD_DRILL_DOWN")
                : List.of();
        return new Ready(
                workbenchBusinessDate(assembled, businessDate),
                assembled.selected(),
                assembled.companies(),
                assembled.snapshot(),
                allowedActions,
                assembled.metrics(),
                assembled.todayPunches());
    }

    static LocalDate workbenchBusinessDate(
            AttendanceDashboardWorkbenchAssembler.Assembled assembled,
            LocalDate fallback) {
        if (assembled == null || assembled.snapshot() == null) {
            return fallback;
        }
        var analytics = assembled.snapshot().analytics();
        if (analytics == null || analytics.dailyTrend() == null
                || analytics.dailyTrend().isEmpty()) {
            return fallback;
        }
        LocalDate last = analytics.dailyTrend()
                .get(analytics.dailyTrend().size() - 1)
                .businessDate();
        return last == null ? fallback : last;
    }

    static DashboardSnapshot snapshotFromFacts(
            String companyId,
            String projectionVersion,
            List<String> sourceVersions,
            java.time.Instant dataAsOf,
            String periodState,
            AuthorizedScope scope,
            List<ExceptionFact> facts,
            LocalDate businessDate,
            List<ExceptionItem> exceptions) {
        return snapshotFromFacts(
                companyId,
                projectionVersion,
                sourceVersions,
                dataAsOf,
                periodState,
                scope,
                facts,
                businessDate,
                businessDate,
                exceptions);
    }

    static DashboardSnapshot snapshotFromFacts(
            String companyId,
            String projectionVersion,
            List<String> sourceVersions,
            java.time.Instant dataAsOf,
            String periodState,
            AuthorizedScope scope,
            List<ExceptionFact> facts,
            LocalDate fromDate,
            LocalDate businessDate,
            List<ExceptionItem> exceptions) {
        List<ExceptionFact> unresolved = facts.stream()
                .filter(fact -> !fact.businessDate().isBefore(fromDate)
                        && !fact.businessDate().isAfter(businessDate))
                .filter(fact -> fact.state() == ExceptionState.OPEN
                        || fact.state() == ExceptionState.PENDING_EVIDENCE
                        || fact.state() == ExceptionState.PENDING_REVIEW)
                .toList();
        Set<String> affectedEmployees = new HashSet<>();
        long blocking = 0;
        for (ExceptionFact fact : unresolved) {
            affectedEmployees.add(fact.employeeId());
            if ("ERROR".equals(fact.severity().name())) {
                blocking++;
            }
        }
        ExceptionSummary summary = new ExceptionSummary(
                unresolved.size(),
                affectedEmployees.size(),
                blocking);
        return new DashboardSnapshot(
                companyId,
                projectionVersion,
                sourceVersions,
                dataAsOf,
                periodState,
                scope,
                summary,
                analytics(facts, fromDate, businessDate, summary),
                exceptions);
    }

    private static DashboardAnalytics analytics(
            List<ExceptionFact> monthExceptions,
            LocalDate fromDate,
            LocalDate businessDate,
            ExceptionSummary summary) {
        List<DailyTrendPoint> trend = new ArrayList<>();
        LocalDate monthStart = YearMonth.from(businessDate).atDay(1);
        LocalDate trendStart = businessDate.minusDays(6);
        if (trendStart.isBefore(monthStart)) {
            trendStart = monthStart;
        }
        for (LocalDate cursor = trendStart;
                !cursor.isAfter(businessDate);
                cursor = cursor.plusDays(1)) {
            LocalDate day = cursor;
            List<ExceptionFact> dayFacts = monthExceptions.stream()
                    .filter(fact -> fact.businessDate().equals(day))
                    .filter(fact -> fact.state() != ExceptionState.RESOLVED)
                    .toList();
            Set<String> employees = new HashSet<>();
            long blocking = 0;
            for (ExceptionFact fact : dayFacts) {
                employees.add(fact.employeeId());
                if ("ERROR".equals(fact.severity().name())) {
                    blocking++;
                }
            }
            trend.add(new DailyTrendPoint(
                    day, dayFacts.size(), blocking, employees.size()));
        }
        Map<String, Long> severityCounts = new HashMap<>();
        severityCounts.put("INFO", 0L);
        severityCounts.put("WARNING", 0L);
        severityCounts.put("ERROR", 0L);
        Map<String, Long> typeCounts = new HashMap<>();
        Map<String, long[]> organizationCounts = new HashMap<>();
        for (ExceptionFact fact : monthExceptions) {
            if (fact.businessDate().isBefore(fromDate)
                    || fact.businessDate().isAfter(businessDate)
                    || fact.state() == ExceptionState.RESOLVED
                    || (fact.state() != ExceptionState.OPEN
                            && fact.state() != ExceptionState.PENDING_EVIDENCE
                            && fact.state() != ExceptionState.PENDING_REVIEW)) {
                continue;
            }
            severityCounts.merge(fact.severity().name(), 1L, Long::sum);
            typeCounts.merge(fact.exceptionType(), 1L, Long::sum);
            long[] org = organizationCounts.computeIfAbsent(
                    fact.organizationName(), ignored -> new long[2]);
            org[0]++;
            if ("ERROR".equals(fact.severity().name())) {
                org[1]++;
            }
        }
        List<SeverityDistributionItem> severities = List.of(
                new SeverityDistributionItem("INFO", severityCounts.get("INFO")),
                new SeverityDistributionItem(
                        "WARNING", severityCounts.get("WARNING")),
                new SeverityDistributionItem(
                        "ERROR", severityCounts.get("ERROR")));
        List<TypeDistributionItem> types = typeCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator
                        .<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .map(entry -> new TypeDistributionItem(
                        entry.getKey(), entry.getValue()))
                .toList();
        List<OrganizationRankingItem> ranking = organizationCounts.entrySet()
                .stream()
                .filter(entry -> entry.getValue()[0] > 0)
                .sorted(Comparator
                        .<Map.Entry<String, long[]>>comparingLong(
                                entry -> entry.getValue()[0])
                        .reversed()
                        .thenComparing(
                                Comparator.<Map.Entry<String, long[]>>comparingLong(
                                                entry -> entry.getValue()[1])
                                        .reversed())
                        .thenComparing(Map.Entry::getKey))
                .limit(5)
                .map(entry -> new OrganizationRankingItem(
                        entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1]))
                .toList();
        if (summary.unresolvedCount() == 0 && types.isEmpty()) {
            return new DashboardAnalytics(trend, severities, List.of(), List.of());
        }
        return new DashboardAnalytics(trend, severities, types, ranking);
    }

    private static AttendanceDashboardWorkbenchAssembler.Assembled emptyWorkbench(
            LocalDate businessDate,
            java.time.Instant authorizationTime) {
        CompanyOption selected = new CompanyOption("current-scope", "当前授权范围");
        DashboardSnapshot snapshot = snapshotFromFacts(
                selected.companyId(),
                "LIVE-WB-" + businessDate,
                List.of("WORKBENCH-PUNCH:V1"),
                authorizationTime,
                "OPEN",
                new AuthorizedScope(
                        ScopeType.COMPANY,
                        selected.companyId(),
                        selected.companyName(),
                        "b".repeat(64)),
                List.of(),
                businessDate,
                List.of());
        return new AttendanceDashboardWorkbenchAssembler.Assembled(
                selected,
                List.of(selected),
                snapshot,
                List.of(),
                List.of());
    }

    private List<CompanyOption> authorizedCompanies(
            String principalId,
            String capabilityCode,
            YearMonth period,
            java.time.Instant authorizationTime) {
        return reportSources.listAuthorizedCompanies(
                        principalId,
                        capabilityCode,
                        period,
                        authorizationTime)
                .stream()
                .map(option -> new CompanyOption(
                        option.companyId(), option.companyName()))
                .toList();
    }

    static final List<String> PREFERRED_COMPANY_NAME_MARKERS = List.of(
            "江苏神州半导体",
            "神州半导体");

    private static CompanyOption selectCompany(
            String requestedCompanyId,
            List<CompanyOption> companies,
            java.util.function.Supplier<ApiProblemException> missing) {
        if (requestedCompanyId != null) {
            return companies.stream()
                    .filter(company -> company.companyId()
                            .equals(requestedCompanyId))
                    .findFirst()
                    .orElseThrow(missing);
        }
        if (companies.size() == 1) {
            return companies.getFirst();
        }
        for (String marker : PREFERRED_COMPANY_NAME_MARKERS) {
            for (CompanyOption company : companies) {
                if (company.companyName() != null
                        && company.companyName().contains(marker)) {
                    return company;
                }
            }
        }
        return companies.getFirst();
    }

    private static String normalizeCompanyId(String companyId) {
        if (companyId == null) {
            return null;
        }
        String normalized = companyId.trim();
        if (normalized.isEmpty()
                || normalized.length() > 36
                || !normalized.equals(companyId)) {
            throw new IllegalArgumentException(
                    "companyId must be a trimmed non-blank value"
                            + " of at most 36 characters");
        }
        return normalized;
    }

    private static ApiProblemException projectionNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY",
                "今日考勤结果尚未生成或发布",
                true);
    }

    private static ApiProblemException sourceNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_DASHBOARD_SOURCE_NOT_READY",
                "今日考勤看板还不能按当前授权范围汇总。请确认得力/OA 已同步后再刷新。",
                true);
    }

    public sealed interface QueryResult
            permits Ready, CompanySelection {

        LocalDate businessDate();

        List<CompanyOption> companies();
    }

    public record Ready(
            LocalDate businessDate,
            CompanyOption selectedCompany,
            List<CompanyOption> companies,
            DashboardSnapshot snapshot,
            List<String> allowedActions,
            List<AttendanceDashboardWorkbenchAssembler.WorkbenchMetric> metrics,
            List<AttendanceDashboardWorkbenchAssembler.TodayPunch> todayPunches)
            implements QueryResult {

        public Ready {
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(
                    selectedCompany, "selectedCompany");
            companies = List.copyOf(
                    Objects.requireNonNull(companies, "companies"));
            Objects.requireNonNull(snapshot, "snapshot");
            allowedActions = List.copyOf(Objects.requireNonNull(
                    allowedActions, "allowedActions"));
            metrics = List.copyOf(metrics == null ? List.of() : metrics);
            todayPunches = List.copyOf(
                    todayPunches == null ? List.of() : todayPunches);
        }
    }

    public record CompanySelection(
            LocalDate businessDate,
            List<CompanyOption> companies)
            implements QueryResult {

        public CompanySelection {
            Objects.requireNonNull(businessDate, "businessDate");
            companies = List.copyOf(
                    Objects.requireNonNull(companies, "companies"));
            if (companies.size() < 2) {
                throw new IllegalArgumentException(
                        "company selection requires multiple companies");
            }
        }
    }
}

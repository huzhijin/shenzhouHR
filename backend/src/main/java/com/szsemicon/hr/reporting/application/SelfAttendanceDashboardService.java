package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.DailyFact;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.ExceptionTypeCount;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository.RecentException;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelfAttendanceDashboardService {

    private static final Logger log =
            LoggerFactory.getLogger(SelfAttendanceDashboardService.class);

    public static final ZoneId BUSINESS_ZONE =
            AttendanceDashboardService.BUSINESS_ZONE;

    private static final Comparator<ExceptionTypeCount> TYPE_ORDER =
            Comparator.comparingLong(ExceptionTypeCount::count)
                    .reversed()
                    .thenComparing(ExceptionTypeCount::type);

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final SelfAttendanceDashboardRepository repository;
    private final RealtimeAttendanceReportSnapshotService realtimeSnapshots;
    private final Clock clock;

    public SelfAttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            SelfAttendanceDashboardRepository repository,
            Clock clock) {
        this(capabilities, principalProvider, repository, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SelfAttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            SelfAttendanceDashboardRepository repository,
            Clock clock,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    RealtimeAttendanceReportSnapshotService realtimeSnapshots) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.realtimeSnapshots = realtimeSnapshots;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard query() {
        return query(null, "MONTH");
    }

    @Transactional(readOnly = true)
    public Dashboard query(java.time.YearMonth period, String window) {
        capabilities.require(CapabilityCodes.ATTENDANCE_SELF_READ);
        Instant authorizationTime = clock.instant();
        LocalDate today = authorizationTime
                .atZone(BUSINESS_ZONE)
                .toLocalDate();
        java.time.YearMonth resolved = period == null ? java.time.YearMonth.from(today) : period;
        LocalDate businessDate = "DAY".equalsIgnoreCase(window)
                ? today
                : (resolved.equals(java.time.YearMonth.from(today))
                        ? today
                        : resolved.atEndOfMonth());
        String principalId = principalProvider.currentPrincipalId();
        var authorizedSelf = repository.resolveAuthorizedSelf(
                        principalId,
                        businessDate,
                        authorizationTime)
                .orElseThrow(
                        SelfAttendanceDashboardService
                                ::selfScopeRequired);
        var source = loadSelfSource(
                principalId,
                authorizedSelf,
                businessDate,
                authorizationTime);
        if (!authorizedSelf.employeeId().equals(source.employeeId())
                || !authorizedSelf.companyId().equals(
                        source.companyId())) {
            throw projectionNotReady();
        }

        LocalDate periodStart = "DAY".equalsIgnoreCase(window)
                ? businessDate
                : resolved.atDay(1);
        Map<LocalDate, DailyFact> dailyFacts = dailyFacts(
                source.dailyFacts(), periodStart, businessDate);
        Map<LocalDate, Long> issueCounts = dailyIssueCounts(
                source.dailyIssueCounts(), periodStart, businessDate);
        List<ExceptionTypeCount> typeDistribution =
                validateTypeDistribution(
                        source.exceptionTypeDistribution());
        long unresolvedExceptionCount = typeDistribution.stream()
                .mapToLong(ExceptionTypeCount::count)
                .reduce(0L, Math::addExact);

        Summary summary = summary(
                dailyFacts.values(), unresolvedExceptionCount);
        List<DailyTrendPoint> dailyTrend = dailyTrend(
                dailyFacts, issueCounts, periodStart, businessDate);
        List<RecentException> recent = preferYesterdayExceptions(
                source.recentExceptions(),
                today,
                authorizationTime);
        Today todayCard = today(
                dailyFacts.get(businessDate),
                source.todayIssueLabels());
        return new Dashboard(
                businessDate,
                source.projectionVersion(),
                source.sourceVersions(),
                source.dataAsOf(),
                source.periodState(),
                summary,
                dailyTrend,
                todayCard,
                typeDistribution,
                recent);
    }

    private SelfAttendanceDashboardRepository.SourceSnapshot loadSelfSource(
            String principalId,
            SelfAttendanceDashboardRepository.AuthorizedSelf authorizedSelf,
            LocalDate businessDate,
            Instant authorizationTime) {
        var published = repository.loadLatestPublished(
                principalId,
                authorizedSelf,
                businessDate,
                authorizationTime);
        if (published.isPresent()) {
            return published.orElseThrow();
        }
        if (realtimeSnapshots == null) {
            throw projectionNotReady();
        }
        try {
            return loadRealtimeSelf(
                    principalId,
                    authorizedSelf,
                    businessDate,
                    authorizationTime);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "realtime self attendance dashboard failed; published snapshot is not ready",
                    exception);
            throw sourceNotReady();
        }
    }

    private SelfAttendanceDashboardRepository.SourceSnapshot loadRealtimeSelf(
            String principalId,
            SelfAttendanceDashboardRepository.AuthorizedSelf authorizedSelf,
            LocalDate businessDate,
            Instant authorizationTime) {
        var snapshot = realtimeSnapshots.loadAuthorizedSnapshot(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        new com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter(
                                java.time.YearMonth.from(businessDate),
                                authorizedSelf.companyId(),
                                null,
                                authorizedSelf.employeeId(),
                                null),
                        null,
                        authorizationTime)
                .orElseThrow(SelfAttendanceDashboardService::sourceNotReady);
        List<DailyFact> dailyFacts = snapshot.dailyFacts().stream()
                .filter(fact -> authorizedSelf.employeeId()
                        .equals(fact.employeeId()))
                .map(fact -> new DailyFact(
                        fact.businessDate(),
                        fact.shiftLabel(),
                        fact.scheduledMinutes(),
                        fact.actualWorkMinutes(),
                        fact.recognizedOvertimeMinutes(),
                        fact.leaveOrTimeOffMinutes(),
                        fact.firstPunchAt(),
                        fact.lastPunchAt()))
                .toList();
        Map<LocalDate, Long> issueCounts = new HashMap<>();
        Map<String, Long> typeCounts = new HashMap<>();
        List<RecentException> recent = new ArrayList<>();
        List<String> todayLabels = new ArrayList<>();
        snapshot.exceptionFacts().stream()
                .filter(fact -> authorizedSelf.employeeId()
                        .equals(fact.employeeId()))
                .filter(fact -> fact.state() != com.szsemicon.hr.reporting.domain
                        .AttendanceReportModels.ExceptionState.RESOLVED)
                .forEach(fact -> {
                    issueCounts.merge(fact.businessDate(), 1L, Long::sum);
                    typeCounts.merge(fact.exceptionType(), 1L, Long::sum);
                    if (recent.size() < 8) {
                        try {
                            recent.add(new RecentException(
                                    fact.businessDate(),
                                    fact.exceptionType(),
                                    fact.severity() == null
                                            ? "WARNING"
                                            : fact.severity().name(),
                                    fact.state() == null
                                            ? "OPEN"
                                            : fact.state().name(),
                                    fact.minutes(),
                                    fact.safeEvidenceSummary() == null
                                            || fact.safeEvidenceSummary()
                                                    .isBlank()
                                            ? "本人异常"
                                            : fact.safeEvidenceSummary()));
                        } catch (RuntimeException ignored) {
                            // Skip a malformed exception row rather than
                            // failing the whole self workbench.
                        }
                    }
                    if (fact.businessDate().equals(businessDate)) {
                        todayLabels.add(fact.exceptionType());
                    }
                });
        List<ExceptionTypeCount> types = typeCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new ExceptionTypeCount(
                        entry.getKey(), entry.getValue()))
                .sorted(TYPE_ORDER)
                .toList();
        List<SelfAttendanceDashboardRepository.DailyIssueCount> dailyIssues =
                issueCounts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 0)
                        .map(entry -> new SelfAttendanceDashboardRepository
                                .DailyIssueCount(
                                        entry.getKey(), entry.getValue()))
                        .toList();
        return new SelfAttendanceDashboardRepository.SourceSnapshot(
                authorizedSelf.employeeId(),
                authorizedSelf.companyId(),
                snapshot.projectionVersion(),
                snapshot.sourceVersions(),
                snapshot.dataAsOf(),
                snapshot.periodState(),
                dailyFacts,
                dailyIssues,
                todayLabels,
                types,
                recent);
    }

    private static List<RecentException> preferYesterdayExceptions(
            List<RecentException> source,
            LocalDate today,
            Instant authorizationTime) {
        LocalDate yesterday = today.minusDays(1);
        boolean afterNoon = !authorizationTime.atZone(BUSINESS_ZONE)
                .toLocalTime()
                .isBefore(LocalTime.NOON);
        List<RecentException> preferred = new ArrayList<>();
        for (RecentException item : source) {
            if (yesterday.equals(item.businessDate())) {
                preferred.add(item);
                continue;
            }
            if (afterNoon
                    && today.equals(item.businessDate())
                    && isMorningException(item.type())) {
                preferred.add(item);
            }
        }
        if (!preferred.isEmpty()) {
            return List.copyOf(preferred);
        }
        return List.copyOf(source);
    }

    private static boolean isMorningException(String type) {
        return "LATE".equals(type)
                || "MISSING_ON_DUTY".equals(type)
                || "MISSING_PUNCH".equals(type)
                || "MISSING_PUNCH_OVERDUE".equals(type);
    }

    private static Map<LocalDate, DailyFact> dailyFacts(
            List<DailyFact> source,
            LocalDate periodStart,
            LocalDate businessDate) {
        Map<LocalDate, DailyFact> result = new HashMap<>();
        for (DailyFact fact : source) {
            if (fact.businessDate().isBefore(periodStart)
                    || fact.businessDate().isAfter(businessDate)) {
                continue;
            }
            result.putIfAbsent(fact.businessDate(), fact);
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, Long> dailyIssueCounts(
            List<SelfAttendanceDashboardRepository.DailyIssueCount> source,
            LocalDate periodStart,
            LocalDate businessDate) {
        Map<LocalDate, Long> result = new HashMap<>();
        for (var item : source) {
            if (item.businessDate().isBefore(periodStart)
                    || item.businessDate().isAfter(businessDate)) {
                continue;
            }
            result.putIfAbsent(item.businessDate(), item.issueCount());
        }
        return Map.copyOf(result);
    }

    private static List<ExceptionTypeCount> validateTypeDistribution(
            List<ExceptionTypeCount> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        var types = new HashSet<String>();
        List<ExceptionTypeCount> result = new ArrayList<>();
        for (ExceptionTypeCount item : values) {
            if (item == null
                    || item.type() == null
                    || item.type().isBlank()
                    || item.count() <= 0
                    || !types.add(item.type())) {
                continue;
            }
            result.add(item);
        }
        result.sort(TYPE_ORDER);
        return List.copyOf(result);
    }

    private static Summary summary(
            java.util.Collection<DailyFact> dailyFacts,
            long unresolvedExceptionCount) {
        long scheduledMinutes = 0;
        long confirmedMinutes = 0;
        long recognizedOvertimeMinutes = 0;
        long leaveMinutes = 0;
        for (DailyFact fact : dailyFacts) {
            scheduledMinutes = Math.addExact(
                    scheduledMinutes, fact.scheduledMinutes());
            confirmedMinutes = Math.addExact(
                    confirmedMinutes, fact.confirmedMinutes());
            recognizedOvertimeMinutes = Math.addExact(
                    recognizedOvertimeMinutes,
                    fact.recognizedOvertimeMinutes());
            leaveMinutes = Math.addExact(
                    leaveMinutes, fact.leaveMinutes());
        }
        return new Summary(
                scheduledMinutes,
                confirmedMinutes,
                recognizedOvertimeMinutes,
                leaveMinutes,
                unresolvedExceptionCount);
    }

    private static List<DailyTrendPoint> dailyTrend(
            Map<LocalDate, DailyFact> dailyFacts,
            Map<LocalDate, Long> issueCounts,
            LocalDate periodStart,
            LocalDate businessDate) {
        LocalDate trendStart = businessDate.minusDays(6);
        if (trendStart.isBefore(periodStart)) {
            trendStart = periodStart;
        }
        List<DailyTrendPoint> result = new ArrayList<>();
        for (LocalDate date = trendStart;
                !date.isAfter(businessDate);
                date = date.plusDays(1)) {
            DailyFact fact = dailyFacts.get(date);
            result.add(new DailyTrendPoint(
                    date,
                    fact == null ? 0 : fact.scheduledMinutes(),
                    fact == null ? 0 : fact.confirmedMinutes(),
                    fact == null
                            ? 0
                            : fact.recognizedOvertimeMinutes(),
                    fact == null ? 0 : fact.leaveMinutes(),
                    issueCounts.getOrDefault(date, 0L),
                    fact == null ? null : fact.firstPunchAt(),
                    fact == null ? null : fact.lastPunchAt()));
        }
        return List.copyOf(result);
    }

    private static Today today(
            DailyFact fact, List<String> issueLabels) {
        if (fact == null) {
            return null;
        }
        List<String> labels = issueLabels.stream()
                .distinct()
                .sorted()
                .toList();
        String statusLabel = !labels.isEmpty()
                ? "存在未解决异常"
                : fact.scheduledMinutes() == 0
                        ? "无需出勤"
                        : "正常";
        return new Today(
                fact.shiftLabel(),
                fact.firstPunchAt(),
                fact.lastPunchAt(),
                statusLabel,
                fact.confirmedMinutes(),
                labels);
    }

    private static ApiProblemException selfScopeRequired() {
        return new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ATTENDANCE_SELF_SCOPE_REQUIRED",
                "当前账号未绑定有效员工本人数据范围");
    }

    private static ApiProblemException projectionNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "SELF_ATTENDANCE_DASHBOARD_PROJECTION_NOT_READY",
                "本人当月考勤结果尚未生成或发布",
                true);
    }

    private static ApiProblemException sourceNotReady() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "SELF_ATTENDANCE_DASHBOARD_SOURCE_NOT_READY",
                "本人当月考勤来源尚未同步或当前账号没有本人范围",
                true);
    }

    public record Dashboard(
            LocalDate businessDate,
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String periodState,
            Summary summary,
            List<DailyTrendPoint> dailyTrend,
            Today today,
            List<ExceptionTypeCount> exceptionTypeDistribution,
            List<RecentException> recentExceptions) {

        public Dashboard {
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(
                    projectionVersion, "projectionVersion");
            sourceVersions = List.copyOf(
                    Objects.requireNonNull(
                            sourceVersions, "sourceVersions"));
            Objects.requireNonNull(dataAsOf, "dataAsOf");
            Objects.requireNonNull(periodState, "periodState");
            Objects.requireNonNull(summary, "summary");
            dailyTrend = List.copyOf(
                    Objects.requireNonNull(dailyTrend, "dailyTrend"));
            exceptionTypeDistribution = List.copyOf(
                    Objects.requireNonNull(
                            exceptionTypeDistribution,
                            "exceptionTypeDistribution"));
            recentExceptions = List.copyOf(Objects.requireNonNull(
                    recentExceptions, "recentExceptions"));
        }
    }

    public record Summary(
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            long unresolvedExceptionCount) {
    }

    public record DailyTrendPoint(
            LocalDate businessDate,
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            long issueCount,
            Instant firstPunchAt,
            Instant lastPunchAt) {
    }

    public record Today(
            String shiftLabel,
            Instant firstPunchAt,
            Instant lastPunchAt,
            String statusLabel,
            long confirmedMinutes,
            List<String> issueLabels) {

        public Today {
            issueLabels = List.copyOf(
                    Objects.requireNonNull(issueLabels, "issueLabels"));
        }
    }
}

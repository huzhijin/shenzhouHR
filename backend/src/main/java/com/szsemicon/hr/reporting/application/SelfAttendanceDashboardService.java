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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelfAttendanceDashboardService {

    public static final ZoneId BUSINESS_ZONE =
            AttendanceDashboardService.BUSINESS_ZONE;

    private static final Comparator<ExceptionTypeCount> TYPE_ORDER =
            Comparator.comparingLong(ExceptionTypeCount::count)
                    .reversed()
                    .thenComparing(ExceptionTypeCount::type);

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final SelfAttendanceDashboardRepository repository;
    private final Clock clock;

    public SelfAttendanceDashboardService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            SelfAttendanceDashboardRepository repository,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard query() {
        capabilities.require(CapabilityCodes.ATTENDANCE_SELF_READ);
        Instant authorizationTime = clock.instant();
        LocalDate businessDate = authorizationTime
                .atZone(BUSINESS_ZONE)
                .toLocalDate();
        String principalId = principalProvider.currentPrincipalId();
        var authorizedSelf = repository.resolveAuthorizedSelf(
                        principalId,
                        businessDate,
                        authorizationTime)
                .orElseThrow(
                        SelfAttendanceDashboardService
                                ::selfScopeRequired);
        var source = repository.loadLatestPublished(
                        principalId,
                        authorizedSelf,
                        businessDate,
                        authorizationTime)
                .orElseThrow(
                        SelfAttendanceDashboardService
                                ::projectionNotReady);
        if (!authorizedSelf.employeeId().equals(source.employeeId())
                || !authorizedSelf.companyId().equals(
                        source.companyId())
                || source.dataAsOf().isBefore(businessDate
                        .atStartOfDay(BUSINESS_ZONE)
                        .toInstant())) {
            throw projectionNotReady();
        }

        LocalDate periodStart = businessDate.withDayOfMonth(1);
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
        Today today = today(
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
                today,
                typeDistribution,
                source.recentExceptions());
    }

    private static Map<LocalDate, DailyFact> dailyFacts(
            List<DailyFact> source,
            LocalDate periodStart,
            LocalDate businessDate) {
        Map<LocalDate, DailyFact> result = new HashMap<>();
        for (DailyFact fact : source) {
            if (fact.businessDate().isBefore(periodStart)
                    || fact.businessDate().isAfter(businessDate)
                    || result.put(fact.businessDate(), fact) != null) {
                throw new IllegalStateException(
                        "self daily facts are inconsistent");
            }
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
                    || item.businessDate().isAfter(businessDate)
                    || result.put(
                                    item.businessDate(),
                                    item.issueCount())
                            != null) {
                throw new IllegalStateException(
                        "self daily issue counts are inconsistent");
            }
        }
        return Map.copyOf(result);
    }

    private static List<ExceptionTypeCount> validateTypeDistribution(
            List<ExceptionTypeCount> values) {
        List<ExceptionTypeCount> result = List.copyOf(values);
        var types = new HashSet<String>();
        for (int index = 0; index < result.size(); index++) {
            ExceptionTypeCount item = result.get(index);
            if (!types.add(item.type())
                    || (index > 0
                            && TYPE_ORDER.compare(
                                            result.get(index - 1),
                                            item)
                                    > 0)) {
                throw new IllegalStateException(
                        "self exception type distribution"
                                + " is inconsistent");
            }
        }
        return result;
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
                    issueCounts.getOrDefault(date, 0L)));
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
            long issueCount) {
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

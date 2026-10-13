package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface AttendanceDashboardRepository {

    List<CompanyOption> listAuthorizedCompanies(
            String principalId,
            YearMonth period,
            Instant authorizationTime);

    Optional<AuthorizedDashboardSnapshot> loadAuthorizedToday(
            String principalId,
            String companyId,
            LocalDate businessDate,
            Instant authorizationTime);

    record AuthorizedDashboardSnapshot(
            DashboardSnapshot snapshot,
            boolean employeeDetailsAuthorized) {

        public AuthorizedDashboardSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            if (!employeeDetailsAuthorized
                    && !snapshot.exceptions().isEmpty()) {
                throw new IllegalArgumentException(
                        "unauthorized dashboard snapshot"
                                + " must not contain employee details");
            }
        }
    }

    record CompanyOption(String companyId, String companyName) {

        public CompanyOption {
            companyId = requireText(companyId, "companyId", 36);
            companyName = requireText(companyName, "companyName", 200);
        }
    }

    record AuthorizedScope(
            ScopeType type,
            String reference,
            String label,
            String authorizationDigest) {

        public AuthorizedScope {
            Objects.requireNonNull(type, "type");
            reference = requireText(reference, "reference", 128);
            label = requireText(label, "label", 100);
            authorizationDigest = requireText(
                    authorizationDigest, "authorizationDigest", 64);
        }
    }

    record ExceptionSummary(
            long unresolvedCount,
            long affectedEmployeeCount,
            long blockingCount) {

        public ExceptionSummary {
            if (unresolvedCount < 0
                    || affectedEmployeeCount < 0
                    || blockingCount < 0
                    || affectedEmployeeCount > unresolvedCount
                    || blockingCount > unresolvedCount) {
                throw new IllegalArgumentException(
                        "dashboard exception counts are inconsistent");
            }
        }
    }

    record DailyTrendPoint(
            LocalDate businessDate,
            long exceptionCount,
            long blockingCount,
            long affectedEmployeeCount) {

        public DailyTrendPoint {
            Objects.requireNonNull(businessDate, "businessDate");
            validateCounts(
                    exceptionCount,
                    blockingCount,
                    affectedEmployeeCount,
                    "daily trend");
        }
    }

    record SeverityDistributionItem(String severity, long count) {

        private static final List<String> SEVERITIES =
                List.of("INFO", "WARNING", "ERROR");

        public SeverityDistributionItem {
            severity = requireText(severity, "severity", 16);
            if (!SEVERITIES.contains(severity) || count < 0) {
                throw new IllegalArgumentException(
                        "severity distribution is inconsistent");
            }
        }
    }

    record TypeDistributionItem(String exceptionType, long count) {

        public TypeDistributionItem {
            exceptionType = requireText(
                    exceptionType, "exceptionType", 64);
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "type distribution count must be positive");
            }
        }
    }

    record OrganizationRankingItem(
            String organizationName,
            long exceptionCount,
            long blockingCount) {

        public OrganizationRankingItem {
            organizationName = requireText(
                    organizationName, "organizationName", 200);
            validateCounts(
                    exceptionCount,
                    blockingCount,
                    0,
                    "organization ranking");
            if (exceptionCount == 0) {
                throw new IllegalArgumentException(
                        "organization ranking count must be positive");
            }
        }
    }

    record DashboardAnalytics(
            List<DailyTrendPoint> dailyTrend,
            List<SeverityDistributionItem> severityDistribution,
            List<TypeDistributionItem> typeDistribution,
            List<OrganizationRankingItem> organizationRanking) {

        private static final Comparator<TypeDistributionItem> TYPE_ORDER =
                Comparator.comparingLong(TypeDistributionItem::count)
                        .reversed()
                        .thenComparing(
                                TypeDistributionItem::exceptionType);
        private static final Comparator<OrganizationRankingItem>
                ORGANIZATION_ORDER =
                        Comparator.comparingLong(
                                        OrganizationRankingItem
                                                ::exceptionCount)
                                .reversed()
                                .thenComparing(
                                        Comparator.comparingLong(
                                                        OrganizationRankingItem
                                                                ::blockingCount)
                                                .reversed())
                                .thenComparing(
                                        OrganizationRankingItem
                                                ::organizationName);

        public DashboardAnalytics {
            dailyTrend = List.copyOf(
                    Objects.requireNonNull(dailyTrend, "dailyTrend"));
            severityDistribution = List.copyOf(Objects.requireNonNull(
                    severityDistribution, "severityDistribution"));
            typeDistribution = List.copyOf(Objects.requireNonNull(
                    typeDistribution, "typeDistribution"));
            organizationRanking = List.copyOf(Objects.requireNonNull(
                    organizationRanking, "organizationRanking"));
            if (dailyTrend.isEmpty()
                    || dailyTrend.size() > 7
                    || !isStrictlyIncreasing(dailyTrend)
                    || !severityDistribution.stream()
                            .map(SeverityDistributionItem::severity)
                            .toList()
                            .equals(List.of(
                                    "INFO", "WARNING", "ERROR"))
                    || typeDistribution.size() > 10
                    || !isOrdered(typeDistribution, TYPE_ORDER)
                    || organizationRanking.size() > 5
                    || !isOrdered(
                            organizationRanking,
                            ORGANIZATION_ORDER)) {
                throw new IllegalArgumentException(
                        "dashboard analytics are inconsistent");
            }
        }

        private static boolean isStrictlyIncreasing(
                List<DailyTrendPoint> points) {
            for (int index = 1; index < points.size(); index++) {
                if (!points.get(index - 1).businessDate()
                        .isBefore(points.get(index).businessDate())) {
                    return false;
                }
            }
            return true;
        }

        private static <T> boolean isOrdered(
                List<T> values, Comparator<T> comparator) {
            for (int index = 1; index < values.size(); index++) {
                if (comparator.compare(
                                values.get(index - 1),
                                values.get(index))
                        > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    record ExceptionItem(
            String exceptionReference,
            String employeeNumber,
            String employeeName,
            String organizationName,
            LocalDate businessDate,
            String exceptionType,
            String severity,
            String state,
            long exceptionMinutes,
            String evidenceSummary) {

        private static final List<String> SEVERITIES =
                List.of("INFO", "WARNING", "ERROR");
        private static final List<String> UNRESOLVED_STATES = List.of(
                "OPEN", "PENDING_EVIDENCE", "PENDING_REVIEW");

        public ExceptionItem {
            exceptionReference = requireText(
                    exceptionReference, "exceptionReference", 128);
            employeeNumber = requireText(
                    employeeNumber, "employeeNumber", 128);
            employeeName = requireText(
                    employeeName, "employeeName", 200);
            organizationName = requireText(
                    organizationName, "organizationName", 200);
            Objects.requireNonNull(businessDate, "businessDate");
            exceptionType = requireText(
                    exceptionType, "exceptionType", 64);
            severity = requireText(severity, "severity", 16);
            state = requireText(state, "state", 32);
            if (!SEVERITIES.contains(severity)
                    || !UNRESOLVED_STATES.contains(state)) {
                throw new IllegalArgumentException(
                        "dashboard exception enum is unsupported");
            }
            if (exceptionMinutes < 0) {
                throw new IllegalArgumentException(
                        "exceptionMinutes must be non-negative");
            }
            evidenceSummary = requireText(
                    evidenceSummary, "evidenceSummary", 500);
        }
    }

    record DashboardSnapshot(
            String companyId,
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String periodState,
            AuthorizedScope scope,
            ExceptionSummary summary,
            DashboardAnalytics analytics,
            List<ExceptionItem> exceptions) {

        public DashboardSnapshot {
            companyId = requireText(companyId, "companyId", 36);
            projectionVersion = requireText(
                    projectionVersion, "projectionVersion", 128);
            sourceVersions = List.copyOf(Objects.requireNonNull(
                    sourceVersions, "sourceVersions"));
            if (sourceVersions.stream().anyMatch(
                    value -> value == null
                            || value.isBlank()
                            || value.length() > 256)) {
                throw new IllegalArgumentException(
                        "sourceVersions contains an invalid value");
            }
            Objects.requireNonNull(dataAsOf, "dataAsOf");
            periodState = requireText(
                    periodState, "periodState", 32);
            if (!List.of("OPEN", "FROZEN", "CLOSED", "REOPENED")
                    .contains(periodState)) {
                throw new IllegalArgumentException(
                        "periodState is unsupported");
            }
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(analytics, "analytics");
            DailyTrendPoint currentDay =
                    analytics.dailyTrend().getLast();
            long severityTotal = analytics.severityDistribution().stream()
                    .mapToLong(SeverityDistributionItem::count)
                    .sum();
            if (currentDay.exceptionCount()
                            > summary.unresolvedCount()
                    || currentDay.affectedEmployeeCount()
                            > summary.affectedEmployeeCount()
                    || currentDay.blockingCount()
                            > summary.blockingCount()
                    || severityTotal != summary.unresolvedCount()) {
                throw new IllegalArgumentException(
                        "dashboard analytics do not match summary");
            }
            exceptions = List.copyOf(Objects.requireNonNull(
                    exceptions, "exceptions"));
            if (exceptions.size() > 500) {
                throw new IllegalArgumentException(
                        "dashboard exceptions must be limited to 500");
            }
        }
    }

    private static void validateCounts(
            long exceptionCount,
            long blockingCount,
            long affectedEmployeeCount,
            String label) {
        if (exceptionCount < 0
                || blockingCount < 0
                || affectedEmployeeCount < 0
                || blockingCount > exceptionCount
                || affectedEmployeeCount > exceptionCount) {
            throw new IllegalArgumentException(
                    label + " counts are inconsistent");
        }
    }

    private static String requireText(
            String value, String label, int maximumLength) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    label + " must be non-blank and at most "
                            + maximumLength + " characters");
        }
        return normalized;
    }
}

package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface SelfAttendanceDashboardRepository {

    Optional<AuthorizedSelf> resolveAuthorizedSelf(
            String principalId,
            LocalDate businessDate,
            Instant authorizationTime);

    Optional<SourceSnapshot> loadLatestPublished(
            String principalId,
            AuthorizedSelf authorizedSelf,
            LocalDate businessDate,
            Instant authorizationTime);

    record AuthorizedSelf(String employeeId, String companyId) {

        public AuthorizedSelf {
            employeeId = requireText(employeeId, "employeeId", 36);
            companyId = requireText(companyId, "companyId", 36);
        }
    }

    record DailyFact(
            LocalDate businessDate,
            String shiftLabel,
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            Instant firstPunchAt,
            Instant lastPunchAt) {

        public DailyFact {
            Objects.requireNonNull(businessDate, "businessDate");
            shiftLabel = requireText(
                    shiftLabel, "shiftLabel", 200);
            requireNonNegative(
                    scheduledMinutes,
                    confirmedMinutes,
                    recognizedOvertimeMinutes,
                    leaveMinutes);
            if (firstPunchAt != null
                    && lastPunchAt != null
                    && lastPunchAt.isBefore(firstPunchAt)) {
                throw new IllegalArgumentException(
                        "lastPunchAt cannot precede firstPunchAt");
            }
        }
    }

    record DailyIssueCount(LocalDate businessDate, long issueCount) {

        public DailyIssueCount {
            Objects.requireNonNull(businessDate, "businessDate");
            if (issueCount <= 0) {
                throw new IllegalArgumentException(
                        "issueCount must be positive");
            }
        }
    }

    record ExceptionTypeCount(String type, long count) {

        public ExceptionTypeCount {
            type = requireText(type, "type", 64);
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "exception type count must be positive");
            }
        }
    }

    record RecentException(
            LocalDate businessDate,
            String type,
            String severity,
            String state,
            long minutes,
            String safeEvidenceSummary) {

        private static final List<String> SEVERITIES =
                List.of("INFO", "WARNING", "ERROR");
        private static final List<String> STATES = List.of(
                "OPEN", "PENDING_EVIDENCE", "PENDING_REVIEW");

        public RecentException {
            Objects.requireNonNull(businessDate, "businessDate");
            type = requireText(type, "type", 64);
            severity = requireText(severity, "severity", 16);
            state = requireText(state, "state", 32);
            if (!SEVERITIES.contains(severity)
                    || !STATES.contains(state)
                    || minutes < 0) {
                throw new IllegalArgumentException(
                        "recent self exception is inconsistent");
            }
            safeEvidenceSummary = requireText(
                    safeEvidenceSummary,
                    "safeEvidenceSummary",
                    500);
        }
    }

    record SourceSnapshot(
            String employeeId,
            String companyId,
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String periodState,
            List<DailyFact> dailyFacts,
            List<DailyIssueCount> dailyIssueCounts,
            List<String> todayIssueLabels,
            List<ExceptionTypeCount> exceptionTypeDistribution,
            List<RecentException> recentExceptions) {

        public SourceSnapshot {
            employeeId = requireText(employeeId, "employeeId", 36);
            companyId = requireText(companyId, "companyId", 36);
            projectionVersion = requireText(
                    projectionVersion, "projectionVersion", 128);
            sourceVersions = List.copyOf(Objects.requireNonNull(
                    sourceVersions, "sourceVersions"));
            if (sourceVersions.stream().anyMatch(value ->
                    value == null
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
            dailyFacts = List.copyOf(
                    Objects.requireNonNull(dailyFacts, "dailyFacts"));
            dailyIssueCounts = List.copyOf(Objects.requireNonNull(
                    dailyIssueCounts, "dailyIssueCounts"));
            todayIssueLabels = List.copyOf(Objects.requireNonNull(
                    todayIssueLabels, "todayIssueLabels"));
            if (todayIssueLabels.stream().anyMatch(value ->
                    value == null
                            || value.isBlank()
                            || value.length() > 64)) {
                throw new IllegalArgumentException(
                        "todayIssueLabels contains an invalid value");
            }
            exceptionTypeDistribution = List.copyOf(
                    Objects.requireNonNull(
                            exceptionTypeDistribution,
                            "exceptionTypeDistribution"));
            recentExceptions = List.copyOf(Objects.requireNonNull(
                    recentExceptions, "recentExceptions"));
            if (recentExceptions.size() > 10) {
                throw new IllegalArgumentException(
                        "recentExceptions must be limited to ten");
            }
        }
    }

    private static void requireNonNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "self attendance minutes must be non-negative");
            }
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

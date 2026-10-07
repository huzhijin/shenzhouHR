package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisSelfAttendanceDashboardRepository
        implements SelfAttendanceDashboardRepository {

    private final SelfAttendanceDashboardMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisSelfAttendanceDashboardRepository(
            SelfAttendanceDashboardMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AuthorizedSelf> resolveAuthorizedSelf(
            String principalId,
            LocalDate businessDate,
            Instant authorizationTime) {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null || principalId.isBlank()) {
            return Optional.empty();
        }
        List<SelfDashboardRows.AuthorizationRow> rows =
                mapper.resolveAuthorizedSelf(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        businessDate,
                        authorizationTime);
        if (rows == null || rows.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst().toDomain());
    }

    @Override
    public Optional<SourceSnapshot> loadLatestPublished(
            String principalId,
            AuthorizedSelf authorizedSelf,
            LocalDate businessDate,
            Instant authorizationTime) {
        Objects.requireNonNull(authorizedSelf, "authorizedSelf");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null || principalId.isBlank()) {
            return Optional.empty();
        }
        YearMonth period = YearMonth.from(businessDate);
        List<SelfDashboardRows.ProjectionRow> projections =
                mapper.listLatestPublishedSelfProjection(
                        principalId,
                        CapabilityCodes.ATTENDANCE_SELF_READ,
                        authorizedSelf.companyId(),
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        businessDate,
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return Optional.empty();
        }
        SelfDashboardRows.ProjectionRow projection =
                projections.getFirst();
        if (!authorizedSelf.companyId().equals(
                projection.companyId())) {
            return Optional.empty();
        }
        LocalDate periodStart = period.atDay(1);
        LocalDate trendStart = businessDate.minusDays(6);
        if (trendStart.isBefore(periodStart)) {
            trendStart = periodStart;
        }
        List<DailyFact> dailyFacts = nullSafe(
                        mapper.listSelfDailyFacts(
                                principalId,
                                CapabilityCodes.ATTENDANCE_SELF_READ,
                                projection.projectionId(),
                                projection.companyId(),
                                periodStart,
                                businessDate,
                                authorizationTime))
                .stream()
                .map(SelfDashboardRows.DailyRow::toDomain)
                .toList();
        List<DailyIssueCount> dailyIssueCounts = nullSafe(
                        mapper.listSelfDailyIssueCounts(
                                principalId,
                                CapabilityCodes.ATTENDANCE_SELF_READ,
                                projection.projectionId(),
                                projection.companyId(),
                                trendStart,
                                businessDate,
                                authorizationTime))
                .stream()
                .map(SelfDashboardRows.DailyIssueCountRow::toDomain)
                .toList();
        List<String> todayIssueLabels = nullSafe(
                        mapper.listSelfTodayIssueLabels(
                                principalId,
                                CapabilityCodes.ATTENDANCE_SELF_READ,
                                projection.projectionId(),
                                projection.companyId(),
                                businessDate,
                                authorizationTime))
                .stream()
                .map(SelfDashboardRows.TodayIssueLabelRow::issueLabel)
                .toList();
        List<ExceptionTypeCount> typeDistribution = nullSafe(
                        mapper.listSelfExceptionTypeDistribution(
                                principalId,
                                CapabilityCodes.ATTENDANCE_SELF_READ,
                                projection.projectionId(),
                                projection.companyId(),
                                periodStart,
                                businessDate,
                                authorizationTime))
                .stream()
                .map(SelfDashboardRows.ExceptionTypeCountRow::toDomain)
                .toList();
        List<RecentException> recentExceptions = nullSafe(
                        mapper.listSelfRecentExceptions(
                                principalId,
                                CapabilityCodes.ATTENDANCE_SELF_READ,
                                projection.projectionId(),
                                projection.companyId(),
                                periodStart,
                                businessDate,
                                authorizationTime))
                .stream()
                .map(SelfDashboardRows.RecentExceptionRow::toDomain)
                .toList();
        return Optional.of(new SourceSnapshot(
                authorizedSelf.employeeId(),
                authorizedSelf.companyId(),
                projection.projectionVersion(),
                sourceVersions(projection.sourceVersionsJson()),
                projection.dataAsOf(),
                projection.periodState(),
                dailyFacts,
                dailyIssueCounts,
                todayIssueLabels,
                typeDistribution,
                recentExceptions));
    }

    private List<String> sourceVersions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException(
                        "self dashboard source versions are invalid");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()
                        || value.asText().isBlank()
                        || value.asText().length() > 256) {
                    throw new IllegalStateException(
                            "self dashboard source versions are invalid");
                }
                result.add(value.asText());
            }
            return List.copyOf(result);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "self dashboard source versions are invalid");
        }
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}

package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository;
import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository.AuthorizedDashboardSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceDashboardRepository
        implements AttendanceDashboardRepository {

    private final AttendanceReportMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisAttendanceDashboardRepository(
            AttendanceReportMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CompanyOption> listAuthorizedCompanies(
            String principalId,
            YearMonth period,
            Instant authorizationTime) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null || principalId.isBlank()) {
            return List.of();
        }
        return mapper.listDashboardAuthorizedCompanies(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        authorizationTime)
                .stream()
                .map(DashboardRows.CompanyRow::toDomain)
                .distinct()
                .toList();
    }

    @Override
    public Optional<AuthorizedDashboardSnapshot> loadAuthorizedToday(
            String principalId,
            String companyId,
            LocalDate businessDate,
            Instant authorizationTime) {
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null
                || principalId.isBlank()
                || companyId == null
                || companyId.isBlank()) {
            return Optional.empty();
        }
        YearMonth period = YearMonth.from(businessDate);
        List<DashboardRows.ProjectionRow> projections =
                mapper.listLatestDashboardAuthorizedProjections(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        period.atDay(1),
                        period.plusMonths(1).atDay(1),
                        companyId,
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return Optional.empty();
        }
        DashboardRows.ProjectionRow projection =
                projections.getFirst();
        if (!companyId.equals(projection.companyId())) {
            return Optional.empty();
        }
        List<DashboardRows.ScopeRow> scopeRows =
                mapper.listDashboardAuthorizedScopes(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        projection.projectionId(),
                        companyId,
                        authorizationTime);
        if (scopeRows == null || scopeRows.isEmpty()) {
            return Optional.empty();
        }
        DashboardRows.SummaryRow summary =
                Objects.requireNonNull(
                        mapper.summarizeDashboardExceptions(
                                principalId,
                                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                                projection.projectionId(),
                                companyId,
                                businessDate,
                                authorizationTime),
                        "dashboard summary");
        DashboardAnalytics analytics = loadAnalytics(
                principalId,
                projection.projectionId(),
                companyId,
                businessDate,
                authorizationTime,
                summary.toDomain());
        List<ReportRows.ScopeRow> reportScopes = nullSafe(
                mapper.listAuthorizedScopes(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        projection.projectionId(),
                        companyId,
                        authorizationTime));
        boolean employeeDetailsAuthorized = !reportScopes.isEmpty()
                && !nullSafe(mapper
                        .listAuthorizedEmployeeIdsInScopeIntersection(
                                companyId,
                                reportScopes(scopeRows),
                                reportScopes,
                                authorizationTime))
                        .isEmpty();
        List<ExceptionItem> exceptions = employeeDetailsAuthorized
                ? nullSafe(mapper.listDashboardExceptions(
                                principalId,
                                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                                projection.projectionId(),
                                companyId,
                                businessDate,
                                authorizationTime,
                                reportScopes))
                        .stream()
                        .map(DashboardRows.ExceptionRow::toDomain)
                        .toList()
                : List.of();
        return Optional.of(new AuthorizedDashboardSnapshot(
                new DashboardSnapshot(
                        companyId,
                        projection.projectionVersion(),
                        sourceVersions(projection.sourceVersionsJson()),
                        projection.dataAsOf(),
                        projection.periodState(),
                        authorizedScope(scopeRows),
                        summary.toDomain(),
                        analytics,
                        exceptions),
                employeeDetailsAuthorized));
    }

    private static List<ReportRows.ScopeRow> reportScopes(
            List<DashboardRows.ScopeRow> rows) {
        return nullSafe(rows).stream()
                .map(row -> new ReportRows.ScopeRow(
                        row.scopeId(),
                        row.scopeType(),
                        row.companyId(),
                        row.organizationId(),
                        row.includeDescendants(),
                        row.principalEmployeeId()))
                .toList();
    }

    private DashboardAnalytics loadAnalytics(
            String principalId,
            String projectionId,
            String companyId,
            LocalDate businessDate,
            Instant authorizationTime,
            ExceptionSummary summary) {
        LocalDate trendStart = trendStart(businessDate);
        List<DashboardRows.DailyTrendRow> dailyRows =
                nullSafe(mapper.listDashboardDailyTrend(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        projectionId,
                        companyId,
                        trendStart,
                        businessDate,
                        authorizationTime));
        Map<LocalDate, DailyTrendPoint> dailyByDate =
                new HashMap<>();
        for (DashboardRows.DailyTrendRow row : dailyRows) {
            DailyTrendPoint point = row.toDomain();
            if (point.businessDate().isBefore(trendStart)
                    || point.businessDate().isAfter(businessDate)
                    || dailyByDate.put(
                                    point.businessDate(), point)
                            != null) {
                throw new IllegalStateException(
                        "dashboard daily trend is inconsistent");
            }
        }
        List<DailyTrendPoint> dailyTrend = trendStart
                .datesUntil(businessDate.plusDays(1))
                .map(date -> dailyByDate.getOrDefault(
                        date,
                        new DailyTrendPoint(date, 0, 0, 0)))
                .toList();
        if (!dailyTrend.getLast().equals(new DailyTrendPoint(
                businessDate,
                summary.unresolvedCount(),
                summary.blockingCount(),
                summary.affectedEmployeeCount()))) {
            throw new IllegalStateException(
                    "dashboard today trend does not match summary");
        }

        Map<String, Long> severityCounts = new HashMap<>();
        for (DashboardRows.SeverityDistributionRow row
                : nullSafe(mapper.listDashboardSeverityDistribution(
                        principalId,
                        CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                        projectionId,
                        companyId,
                        businessDate,
                        authorizationTime))) {
            SeverityDistributionItem item = row.toDomain();
            if (severityCounts.put(item.severity(), item.count())
                    != null) {
                throw new IllegalStateException(
                        "dashboard severity distribution is inconsistent");
            }
        }
        List<SeverityDistributionItem> severityDistribution =
                List.of("INFO", "WARNING", "ERROR").stream()
                        .map(severity ->
                                new SeverityDistributionItem(
                                        severity,
                                        severityCounts.getOrDefault(
                                                severity, 0L)))
                        .toList();
        long severityTotal = severityDistribution.stream()
                .mapToLong(SeverityDistributionItem::count)
                .sum();
        if (severityTotal != summary.unresolvedCount()) {
            throw new IllegalStateException(
                    "dashboard severity distribution"
                            + " does not match summary");
        }

        List<TypeDistributionItem> typeDistribution =
                nullSafe(mapper.listDashboardTypeDistribution(
                                principalId,
                                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                                projectionId,
                                companyId,
                                businessDate,
                                authorizationTime))
                        .stream()
                        .map(DashboardRows.TypeDistributionRow::toDomain)
                        .toList();
        List<OrganizationRankingItem> organizationRanking =
                nullSafe(mapper.listDashboardOrganizationRanking(
                                principalId,
                                CapabilityCodes.ATTENDANCE_DASHBOARD_READ,
                                projectionId,
                                companyId,
                                businessDate,
                                authorizationTime))
                        .stream()
                        .map(DashboardRows.OrganizationRankingRow::toDomain)
                        .toList();
        return new DashboardAnalytics(
                dailyTrend,
                severityDistribution,
                typeDistribution,
                organizationRanking);
    }

    static LocalDate trendStart(LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "businessDate");
        LocalDate start = businessDate.minusDays(6);
        LocalDate periodStart = YearMonth.from(businessDate).atDay(1);
        if (start.isBefore(periodStart)) {
            return periodStart;
        }
        return start;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    static AuthorizedScope authorizedScope(
            List<DashboardRows.ScopeRow> inputRows) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one authorized dashboard scope is required");
        }
        List<DashboardRows.ScopeRow> rows = inputRows.stream()
                .distinct()
                .sorted(Comparator.comparing(
                        MyBatisAttendanceDashboardRepository
                                ::canonicalScope))
                .toList();
        String digest = authorizationDigest(rows);
        ScopeType type = rows.stream().anyMatch(
                row -> ScopeType.COMPANY.name().equals(row.scopeType()))
                        ? ScopeType.COMPANY
                        : rows.stream().anyMatch(
                                row -> ScopeType.ORGANIZATION.name().equals(
                                        row.scopeType()))
                                ? ScopeType.ORGANIZATION
                                : ScopeType.SELF;
        String label = rows.size() > 1
                ? "组合授权范围"
                : switch (type) {
                    case COMPANY -> "公司授权范围";
                    case ORGANIZATION -> "组织授权范围";
                    case SELF -> "本人授权范围";
                };
        return new AuthorizedScope(
                type,
                "authorized-scope-set:" + digest,
                label,
                digest);
    }

    private List<String> sourceVersions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException(
                        "dashboard source versions are invalid");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()
                        || value.asText().isBlank()
                        || value.asText().length() > 256) {
                    throw new IllegalStateException(
                            "dashboard source versions are invalid");
                }
                result.add(value.asText());
            }
            return List.copyOf(result);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "dashboard source versions are invalid");
        }
    }

    private static String authorizationDigest(
            List<DashboardRows.ScopeRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.stream()
                    .map(MyBatisAttendanceDashboardRepository::canonicalScope)
                    .forEach(value -> {
                        byte[] bytes =
                                value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(Integer.BYTES)
                                .putInt(bytes.length)
                                .array());
                        digest.update(bytes);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required dashboard authorization digest"
                            + " is unavailable",
                    exception);
        }
    }

    private static String canonicalScope(DashboardRows.ScopeRow row) {
        return String.join(
                "\u001f",
                required(row.scopeId()),
                required(row.scopeType()),
                nullable(row.companyId()),
                nullable(row.organizationId()),
                Boolean.toString(row.includeDescendants()),
                nullable(row.principalEmployeeId()));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "authorized dashboard scope contains an invalid field");
        }
        return value;
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}

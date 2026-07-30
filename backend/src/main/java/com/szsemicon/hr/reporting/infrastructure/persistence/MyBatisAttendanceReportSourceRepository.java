package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceReportSourceRepository
        implements AttendanceReportSourceRepository {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AttendanceReportMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisAttendanceReportSourceRepository(
            AttendanceReportMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CompanyOption> listAuthorizedCompanies(
            String principalId,
            String capabilityCode,
            YearMonth period,
            Instant authorizationTime) {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null
                || principalId.isBlank()
                || !CapabilityCodes.ATTENDANCE_REPORT_READ.equals(
                        capabilityCode)) {
            return List.of();
        }
        var periodStart = period.atDay(1);
        var periodEndExclusive = period.plusMonths(1).atDay(1);
        List<ReportRows.CompanyRow> rows =
                mapper.listAuthorizedCompanies(
                        principalId,
                        capabilityCode,
                        periodStart,
                        periodEndExclusive,
                        authorizationTime);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .map(ReportRows.CompanyRow::toDomain)
                .distinct()
                .toList();
    }

    @Override
    public Optional<ReportSourceSnapshot> loadAuthorizedSnapshot(
            String principalId,
            String capabilityCode,
            ReportFilter filter,
            Instant authorizationTime) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null
                || principalId.isBlank()
                || !CapabilityCodes.ATTENDANCE_REPORT_READ.equals(
                        capabilityCode)) {
            return Optional.empty();
        }
        var periodStart = filter.period().atDay(1);
        var periodEndExclusive = filter.period().plusMonths(1).atDay(1);
        var projections = mapper.listLatestAuthorizedProjections(
                principalId,
                capabilityCode,
                periodStart,
                periodEndExclusive,
                filter.companyId(),
                authorizationTime);
        /*
         * A missing selector is retained only for single-company compatibility.
         * Never pick one of several authorized companies implicitly.
         */
        if (projections == null || projections.size() != 1) {
            return Optional.empty();
        }
        var projection = projections.getFirst();
        if (filter.companyId() != null
                && !filter.companyId().equals(
                        projection.companyId())) {
            return Optional.empty();
        }
        ReportFilter resolvedFilter = new ReportFilter(
                filter.period(),
                projection.companyId(),
                filter.organizationId(),
                filter.employeeId(),
                filter.status());
        List<ReportRows.ScopeRow> scopeRows = mapper.listAuthorizedScopes(
                principalId,
                capabilityCode,
                projection.projectionId(),
                projection.companyId(),
                authorizationTime);
        if (scopeRows.isEmpty()) {
            return Optional.empty();
        }
        var periodStartAt = periodStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        var periodEndExclusiveAt =
                periodEndExclusive.atStartOfDay(BUSINESS_ZONE).toInstant();
        return Optional.of(new ReportSourceSnapshot(
                authorizedScope(scopeRows),
                resolvedFilter,
                projection.projectionVersion(),
                projection.periodState(),
                projection.dataAsOf(),
                sourceVersions(projection.sourceVersionsJson()),
                mapper.listAuthorizedDailyFacts(
                                principalId,
                                capabilityCode,
                                projection.projectionId(),
                                periodStart,
                                periodEndExclusive,
                                projection.companyId(),
                                filter.organizationId(),
                                filter.employeeId(),
                                authorizationTime)
                        .stream()
                        .map(ReportRows.DailyRow::toDomain)
                        .toList(),
                mapper.listAuthorizedOaFacts(
                                principalId,
                                capabilityCode,
                                projection.projectionId(),
                                periodStartAt,
                                periodEndExclusiveAt,
                                projection.companyId(),
                                filter.organizationId(),
                                filter.employeeId(),
                                authorizationTime)
                        .stream()
                        .map(ReportRows.OaDocumentRow::toDomain)
                        .toList(),
                mapper.listAuthorizedExceptionFacts(
                                principalId,
                                capabilityCode,
                                projection.projectionId(),
                                periodStart,
                                periodEndExclusive,
                                projection.companyId(),
                                filter.organizationId(),
                                filter.employeeId(),
                                filter.status(),
                                authorizationTime)
                        .stream()
                        .map(ReportRows.ExceptionRow::toDomain)
                        .toList(),
                mapper.listAuthorizedTimeAccountFacts(
                                principalId,
                                capabilityCode,
                                projection.projectionId(),
                                projection.companyId(),
                                filter.organizationId(),
                                filter.employeeId(),
                                authorizationTime)
                        .stream()
                        .map(ReportRows.TimeAccountRow::toDomain)
                        .toList()));
    }

    static AuthorizedScope authorizedScope(
            List<ReportRows.ScopeRow> inputRows) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one authorized report scope is required");
        }
        List<ReportRows.ScopeRow> rows = inputRows.stream()
                .distinct()
                .sorted(Comparator.comparing(
                        MyBatisAttendanceReportSourceRepository::canonicalScope))
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
                        "report projection source versions are invalid");
            }
            List<String> result = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalStateException(
                            "report projection source versions are invalid");
                }
                result.add(value.asText());
            }
            return List.copyOf(result);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "report projection source versions are invalid");
        }
    }

    private static String authorizationDigest(
            List<ReportRows.ScopeRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.stream()
                    .map(MyBatisAttendanceReportSourceRepository::canonicalScope)
                    .forEach(value -> {
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(Integer.BYTES)
                                .putInt(bytes.length)
                                .array());
                        digest.update(bytes);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required report authorization digest is unavailable");
        }
    }

    private static String canonicalScope(ReportRows.ScopeRow row) {
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
                    "authorized scope contains an invalid field");
        }
        return value;
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}

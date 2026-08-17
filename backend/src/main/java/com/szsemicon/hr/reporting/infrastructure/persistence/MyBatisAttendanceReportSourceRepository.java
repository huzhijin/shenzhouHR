package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.CompanyOption;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.DepartmentAttendanceRate;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.EmployeeDepartmentAttendancePeriod;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRepository.RealtimeAuthorization;
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
import java.util.Set;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceReportSourceRepository
        implements AttendanceReportSourceRepository {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REALTIME_SCOPE_CAPABILITIES = Set.of(
            CapabilityCodes.ATTENDANCE_REPORT_READ,
            CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
            CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD);

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
        List<ReportRows.CompanyRow> rows =
                mapper.listAuthorizedCompanies(
                        principalId,
                        capabilityCode,
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
    public Optional<RealtimeAuthorization> resolveRealtimeAuthorization(
            String principalId,
            String capabilityCode,
            String companyId,
            Instant authorizationTime) {
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        String normalizedCompanyId = normalizedReference(companyId);
        if (principalId == null
                || principalId.isBlank()
                || !REALTIME_SCOPE_CAPABILITIES.contains(capabilityCode)
                || normalizedCompanyId == null) {
            return Optional.empty();
        }
        List<ReportRows.ScopeRow> scopeRows = nullSafe(
                mapper.listRealtimeAuthorizedScopes(
                        principalId,
                        capabilityCode,
                        normalizedCompanyId,
                        authorizationTime));
        if (!validRealtimeScopeRows(scopeRows, normalizedCompanyId)) {
            return Optional.empty();
        }
        Optional<Set<String>> employeeIds = immutableReferenceSet(nullSafe(
                mapper.listAuthorizedEmployeeIdsInScopeIntersection(
                        normalizedCompanyId,
                        scopeRows,
                        scopeRows,
                        authorizationTime)));
        Optional<Set<String>> organizationIds = immutableReferenceSet(nullSafe(
                mapper.listAuthorizedOrganizationIds(
                        normalizedCompanyId,
                        scopeRows,
                        authorizationTime)));
        if (employeeIds.isEmpty() || organizationIds.isEmpty()) {
            return Optional.empty();
        }
        Set<String> resolvedEmployeeIds = employeeIds.orElseThrow();
        Set<String> resolvedOrganizationIds = organizationIds.orElseThrow();
        boolean companyWide = scopeRows.stream().anyMatch(row ->
                ScopeType.COMPANY.name().equals(row.scopeType()));
        return Optional.of(new RealtimeAuthorization(
                authorizedScope(
                        scopeRows,
                        resolvedEmployeeIds,
                        resolvedOrganizationIds),
                normalizedCompanyId,
                companyWide,
                scopeRows.getFirst().principalEmployeeId(),
                resolvedEmployeeIds,
                resolvedOrganizationIds));
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

    @Override
    public List<DepartmentAttendanceRate>
            listAuthorizedDepartmentAttendanceRates(
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
            return List.of();
        }
        var periodStart = filter.period().atDay(1);
        var periodEndExclusive = filter.period().plusMonths(1).atDay(1);
        List<ReportRows.ProjectionRow> projections =
                mapper.listLatestAuthorizedProjections(
                        principalId,
                        capabilityCode,
                        periodStart,
                        periodEndExclusive,
                        filter.companyId(),
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return List.of();
        }
        ReportRows.ProjectionRow projection = projections.getFirst();
        if (filter.companyId() != null
                && !filter.companyId().equals(projection.companyId())) {
            return List.of();
        }
        return nullSafe(mapper.listAuthorizedDepartmentAttendanceRates(
                        principalId,
                        capabilityCode,
                        projection.projectionId(),
                        periodStart,
                        periodEndExclusive,
                        projection.companyId(),
                        filter.organizationId(),
                        authorizationTime))
                .stream()
                .map(ReportRows.DepartmentAttendanceRateRow::toDomain)
                .toList();
    }

    @Override
    public List<EmployeeSickLeaveDays> listAuthorizedEmployeeSickLeaveDays(
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
            return List.of();
        }
        var periodStart = filter.period().atDay(1);
        var periodEndExclusive = filter.period().plusMonths(1).atDay(1);
        List<ReportRows.ProjectionRow> projections =
                mapper.listLatestAuthorizedProjections(
                        principalId,
                        capabilityCode,
                        periodStart,
                        periodEndExclusive,
                        filter.companyId(),
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return List.of();
        }
        ReportRows.ProjectionRow projection = projections.getFirst();
        if (filter.companyId() != null
                && !filter.companyId().equals(projection.companyId())) {
            return List.of();
        }
        return nullSafe(mapper.listAuthorizedEmployeeSickLeaveDays(
                        principalId,
                        capabilityCode,
                        projection.projectionId(),
                        periodStart,
                        periodEndExclusive,
                        projection.companyId(),
                        filter.organizationId(),
                        filter.employeeId(),
                        authorizationTime))
                .stream()
                .map(ReportRows.EmployeeSickLeaveDaysRow::toDomain)
                .toList();
    }

    @Override
    public List<EmployeeDepartmentAttendancePeriod>
            listAuthorizedEmployeeDepartmentAttendancePeriods(
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
            return List.of();
        }
        var periodStart = filter.period().atDay(1);
        var periodEndExclusive = filter.period().plusMonths(1).atDay(1);
        List<ReportRows.ProjectionRow> projections =
                mapper.listLatestAuthorizedProjections(
                        principalId,
                        capabilityCode,
                        periodStart,
                        periodEndExclusive,
                        filter.companyId(),
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return List.of();
        }
        ReportRows.ProjectionRow projection = projections.getFirst();
        if (filter.companyId() != null
                && !filter.companyId().equals(projection.companyId())) {
            return List.of();
        }
        return nullSafe(
                        mapper.listAuthorizedEmployeeDepartmentAttendancePeriods(
                                principalId,
                                capabilityCode,
                                projection.projectionId(),
                                periodStart,
                                periodEndExclusive,
                                projection.companyId(),
                                filter.organizationId(),
                                filter.employeeId(),
                                authorizationTime))
                .stream()
                .map(ReportRows.EmployeeDepartmentAttendancePeriodRow::toDomain)
                .toList();
    }

    @Override
    public Optional<ReportSourceSnapshot> loadAuthorizedSnapshotIntersection(
            String principalId,
            String additionalCapabilityCode,
            ReportSourceSnapshot readSnapshot,
            boolean requireFullReadScopeCoverage,
            Instant authorizationTime) {
        Objects.requireNonNull(readSnapshot, "readSnapshot");
        Objects.requireNonNull(authorizationTime, "authorizationTime");
        if (principalId == null
                || principalId.isBlank()
                || !Set.of(
                                CapabilityCodes
                                        .ATTENDANCE_REPORT_EXPORT_CREATE,
                                CapabilityCodes
                                        .ATTENDANCE_REPORT_EXPORT_DOWNLOAD)
                        .contains(additionalCapabilityCode)) {
            return Optional.empty();
        }
        var periodStart = readSnapshot.filter().period().atDay(1);
        var periodEndExclusive =
                readSnapshot.filter().period().plusMonths(1).atDay(1);
        List<ReportRows.ProjectionRow> projections =
                mapper.listLatestAuthorizedProjections(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        periodStart,
                        periodEndExclusive,
                        readSnapshot.filter().companyId(),
                        authorizationTime);
        if (projections == null || projections.size() != 1) {
            return Optional.empty();
        }
        ReportRows.ProjectionRow projection = projections.getFirst();
        if (!readSnapshot.filter()
                        .companyId()
                        .equals(projection.companyId())
                || !readSnapshot.projectionVersion().equals(
                        projection.projectionVersion())) {
            return Optional.empty();
        }
        List<ReportRows.ScopeRow> readScopes = nullSafe(
                mapper.listAuthorizedScopes(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        projection.projectionId(),
                        projection.companyId(),
                        authorizationTime));
        List<ReportRows.ScopeRow> additionalScopes = nullSafe(
                mapper.listAuthorizedScopes(
                        principalId,
                        additionalCapabilityCode,
                        projection.projectionId(),
                        projection.companyId(),
                        authorizationTime));
        if (readScopes.isEmpty() || additionalScopes.isEmpty()) {
            return Optional.empty();
        }
        Set<String> readEmployeeIds = Set.copyOf(nullSafe(
                mapper.listAuthorizedEmployeeIdsInScopeIntersection(
                        projection.companyId(),
                        readScopes,
                        readScopes,
                        authorizationTime)));
        Set<String> authorizedEmployeeIds = Set.copyOf(nullSafe(
                mapper.listAuthorizedEmployeeIdsInScopeIntersection(
                        projection.companyId(),
                        readScopes,
                        additionalScopes,
                        authorizationTime)));
        if (requireFullReadScopeCoverage
                && (!authorizedEmployeeIds.equals(readEmployeeIds)
                        || !fullyCoversReadScopes(
                                projection.companyId(),
                                readScopes,
                                additionalScopes,
                                authorizedEmployeeIds,
                                authorizationTime))) {
            return Optional.empty();
        }
        if (!requireFullReadScopeCoverage
                && authorizedEmployeeIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReportSourceSnapshot(
                readSnapshot.scope(),
                readSnapshot.filter(),
                readSnapshot.projectionVersion(),
                readSnapshot.periodState(),
                readSnapshot.dataAsOf(),
                readSnapshot.sourceVersions(),
                readSnapshot.dailyFacts().stream()
                        .filter(fact -> authorizedEmployeeIds.contains(
                                fact.employeeId()))
                        .toList(),
                readSnapshot.oaDocumentFacts().stream()
                        .filter(fact -> authorizedEmployeeIds.contains(
                                fact.employeeId()))
                        .toList(),
                readSnapshot.exceptionFacts().stream()
                        .filter(fact -> authorizedEmployeeIds.contains(
                                fact.employeeId()))
                        .toList(),
                readSnapshot.timeAccountFacts().stream()
                        .filter(fact -> authorizedEmployeeIds.contains(
                                fact.employeeId()))
                        .toList()));
    }

    private boolean fullyCoversReadScopes(
            String companyId,
            List<ReportRows.ScopeRow> readScopes,
            List<ReportRows.ScopeRow> additionalScopes,
            Set<String> intersectionEmployeeIds,
            Instant authorizationTime) {
        return readScopes.stream().allMatch(readScope ->
                additionalScopes.stream().anyMatch(additionalScope ->
                        scopeCovers(
                                companyId,
                                additionalScope,
                                readScope,
                                intersectionEmployeeIds,
                                authorizationTime)));
    }

    private boolean scopeCovers(
            String companyId,
            ReportRows.ScopeRow additionalScope,
            ReportRows.ScopeRow readScope,
            Set<String> intersectionEmployeeIds,
            Instant authorizationTime) {
        if (ScopeType.COMPANY.name().equals(
                additionalScope.scopeType())) {
            return companyId.equals(additionalScope.companyId());
        }
        if (ScopeType.COMPANY.name().equals(readScope.scopeType())) {
            return false;
        }
        if (ScopeType.SELF.name().equals(readScope.scopeType())) {
            if (ScopeType.SELF.name().equals(
                    additionalScope.scopeType())) {
                return Objects.equals(
                        readScope.principalEmployeeId(),
                        additionalScope.principalEmployeeId());
            }
            return intersectionEmployeeIds.contains(
                    readScope.principalEmployeeId());
        }
        if (!ScopeType.ORGANIZATION.name().equals(
                additionalScope.scopeType())) {
            return false;
        }
        if (!additionalScope.includeDescendants()) {
            return !readScope.includeDescendants()
                    && Objects.equals(
                            additionalScope.organizationId(),
                            readScope.organizationId());
        }
        return isOrganizationAncestor(
                companyId,
                additionalScope.organizationId(),
                readScope.organizationId(),
                authorizationTime);
    }

    private boolean isOrganizationAncestor(
            String companyId,
            String ancestorOrganizationId,
            String descendantOrganizationId,
            Instant authorizationTime) {
        return mapper.countCurrentOrganizationAncestor(
                        companyId,
                        ancestorOrganizationId,
                        descendantOrganizationId,
                        authorizationTime)
                > 0;
    }

    private static boolean validRealtimeScopeRows(
            List<ReportRows.ScopeRow> rows, String companyId) {
        if (rows.isEmpty()) {
            return false;
        }
        ReportRows.ScopeRow firstRow = rows.getFirst();
        if (firstRow == null) {
            return false;
        }
        String principalEmployeeId = firstRow.principalEmployeeId();
        if (principalEmployeeId != null
                && normalizedReference(principalEmployeeId) == null) {
            return false;
        }
        for (ReportRows.ScopeRow row : rows) {
            if (row == null
                    || normalizedReference(row.scopeId()) == null
                    || !Objects.equals(
                            principalEmployeeId, row.principalEmployeeId())) {
                return false;
            }
            if (ScopeType.COMPANY.name().equals(row.scopeType())) {
                if (!companyId.equals(row.companyId())
                        || row.organizationId() != null) {
                    return false;
                }
            } else if (ScopeType.ORGANIZATION.name().equals(
                    row.scopeType())) {
                if (row.companyId() != null
                        || normalizedReference(row.organizationId()) == null) {
                    return false;
                }
            } else if (ScopeType.SELF.name().equals(row.scopeType())) {
                if (row.companyId() != null
                        || row.organizationId() != null
                        || principalEmployeeId == null) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static Optional<Set<String>> immutableReferenceSet(
            List<String> values) {
        if (values.stream().anyMatch(
                value -> normalizedReference(value) == null)) {
            return Optional.empty();
        }
        return Optional.of(Set.copyOf(values));
    }

    private static String normalizedReference(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() || normalized.length() > 36
                ? null
                : normalized;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    static AuthorizedScope authorizedScope(
            List<ReportRows.ScopeRow> inputRows) {
        return authorizedScope(inputRows, Set.of(), Set.of());
    }

    static AuthorizedScope authorizedScope(
            List<ReportRows.ScopeRow> inputRows,
            Set<String> employeeIds,
            Set<String> organizationIds) {
        if (inputRows == null || inputRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one authorized report scope is required");
        }
        Objects.requireNonNull(employeeIds, "employeeIds");
        Objects.requireNonNull(organizationIds, "organizationIds");
        List<ReportRows.ScopeRow> rows = inputRows.stream()
                .distinct()
                .sorted(Comparator.comparing(
                        MyBatisAttendanceReportSourceRepository::canonicalScope))
                .toList();
        String digest = authorizationDigest(
                rows, employeeIds, organizationIds);
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
            List<ReportRows.ScopeRow> rows,
            Set<String> employeeIds,
            Set<String> organizationIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.stream()
                    .map(MyBatisAttendanceReportSourceRepository::canonicalScope)
                    .forEach(value -> updateDigest(digest, value));
            employeeIds.stream()
                    .sorted()
                    .map(MyBatisAttendanceReportSourceRepository::required)
                    .map(value -> "EMPLOYEE\u001f" + value)
                    .forEach(value -> updateDigest(digest, value));
            organizationIds.stream()
                    .sorted()
                    .map(MyBatisAttendanceReportSourceRepository::required)
                    .map(value -> "ORGANIZATION\u001f" + value)
                    .forEach(value -> updateDigest(digest, value));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "required report authorization digest is unavailable");
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
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

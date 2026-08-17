package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceReportQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_PAGE_NUMBER = 1_000_000;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceReportSourceRepository repository;
    private final RealtimeAttendanceReportSnapshotService realtimeSnapshots;
    private final Clock clock;
    private final AttendanceReportCalculator calculator;

    @Autowired
    public AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            RealtimeAttendanceReportSnapshotService realtimeSnapshots,
            Clock clock) {
        this(
                capabilities,
                principalProvider,
                repository,
                realtimeSnapshots,
                clock,
                new AttendanceReportCalculator());
    }

    AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            Clock clock) {
        this(
                capabilities,
                principalProvider,
                repository,
                null,
                clock,
                new AttendanceReportCalculator());
    }

    AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            Clock clock,
            AttendanceReportCalculator calculator) {
        this(
                capabilities,
                principalProvider,
                repository,
                null,
                clock,
                calculator);
    }

    AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            RealtimeAttendanceReportSnapshotService realtimeSnapshots,
            Clock clock,
            AttendanceReportCalculator calculator) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.realtimeSnapshots = realtimeSnapshots;
        this.clock = clock;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public List<AttendanceReportSourceRepository.CompanyOption>
            companies(YearMonth period) {
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        return repository.listAuthorizedCompanies(
                principalProvider.currentPrincipalId(),
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                period,
                currentDatabaseInstant());
    }

    @Transactional(readOnly = true)
    public AttendanceReportPage query(
            ReportType reportType,
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String status,
            int page,
            int size) {
        return query(
                reportType,
                period,
                companyId,
                organizationId,
                employeeId,
                status,
                null,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public AttendanceReportPage query(
            ReportType reportType,
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String status,
            String expectedProjectionVersion,
            int page,
            int size) {
        requirePage(page, size);
        String normalizedExpectedProjectionVersion =
                requireExpectedProjectionVersion(expectedProjectionVersion);
        if (reportType == null || period == null) {
            throw new IllegalArgumentException(
                    "reportType and period are required");
        }
        if (status != null && reportType != ReportType.EXCEPTIONS) {
            throw new IllegalArgumentException(
                    "status is only supported by the exception report");
        }
        ReportFilter filter =
                new ReportFilter(
                        period,
                        companyId,
                        organizationId,
                        employeeId,
                        status);
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        Set<String> currentCapabilities = capabilities.currentCapabilities();
        String principalId = principalProvider.currentPrincipalId();
        var at = currentDatabaseInstant();
        var snapshot = loadSnapshot(
                        principalId,
                        filter,
                        normalizedExpectedProjectionVersion,
                        at)
                .orElseThrow(this::snapshotUnavailable);
        requireSameProjectionVersion(
                normalizedExpectedProjectionVersion,
                snapshot.projectionVersion(),
                realtimeSnapshots != null);
        var dataSet = calculator.calculate(reportType, snapshot);
        int from = Math.min(
                Math.multiplyExact(page, size), dataSet.rows().size());
        int to = Math.min(from + size, dataSet.rows().size());
        long total = dataSet.rows().size();
        int totalPages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);
        var actions = allowedActions(
                principalId,
                currentCapabilities,
                reportType,
                snapshot,
                dataSet,
                at);
        String fingerprint = fingerprint(
                reportType,
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                dataSet.calculationFormulaVersion());
        return new AttendanceReportPage(
                reportType,
                dataSet.title(),
                snapshot.projectionVersion(),
                fingerprint,
                dataSet.calculationFormulaVersion(),
                snapshot.periodState(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                snapshot.scope(),
                snapshot.filter(),
                dataSet.columns(),
                dataSet.exportAllowlist(),
                actions,
                dataSet.rows().subList(from, to),
                page,
                size,
                total,
                totalPages);
    }

    @Transactional(readOnly = true)
    public AttendanceMonthMatrixPage queryMonthMatrix(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            int page,
            int size) {
        return queryMonthMatrix(
                period,
                companyId,
                organizationId,
                employeeId,
                null,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public AttendanceMonthMatrixPage queryMonthMatrix(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String expectedProjectionVersion,
            int page,
            int size) {
        requirePage(page, size);
        String normalizedExpectedProjectionVersion =
                requireExpectedProjectionVersion(expectedProjectionVersion);
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        ReportFilter filter = new ReportFilter(
                period,
                companyId,
                organizationId,
                employeeId,
                null);
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        Set<String> currentCapabilities = capabilities.currentCapabilities();
        String principalId = principalProvider.currentPrincipalId();
        var at = currentDatabaseInstant();
        var snapshot = loadSnapshot(
                        principalId,
                        filter,
                        normalizedExpectedProjectionVersion,
                        at)
                .orElseThrow(this::snapshotUnavailable);
        requireSameProjectionVersion(
                normalizedExpectedProjectionVersion,
                snapshot.projectionVersion(),
                realtimeSnapshots != null);
        var matrix = AttendanceMonthMatrixAssembler.assemble(snapshot);
        int from = Math.min(
                Math.multiplyExact(page, size), matrix.rows().size());
        int to = Math.min(from + size, matrix.rows().size());
        long total = matrix.rows().size();
        int totalPages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);
        var actions = allowedActions(
                principalId,
                currentCapabilities,
                ReportType.ATTENDANCE_DETAIL,
                snapshot,
                calculator.calculate(
                        ReportType.ATTENDANCE_DETAIL, snapshot),
                at);
        String fingerprint = fingerprint(
                ReportType.ATTENDANCE_DETAIL,
                snapshot.filter(),
                snapshot.projectionVersion(),
                snapshot.scope().authorizationDigest(),
                AttendanceMonthMatrixAssembler.FORMULA_VERSION);
        return new AttendanceMonthMatrixPage(
                snapshot.projectionVersion(),
                fingerprint,
                AttendanceMonthMatrixAssembler.FORMULA_VERSION,
                snapshot.periodState(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                snapshot.scope(),
                snapshot.filter(),
                actions,
                matrix.dates(),
                matrix.rows().subList(from, to),
                page,
                size,
                total,
                totalPages);
    }

    private List<String> allowedActions(
            String principalId,
            Set<String> currentCapabilities,
            ReportType reportType,
            ReportSourceSnapshot snapshot,
            ReportDataSet dataSet,
            java.time.Instant authorizationTime) {
        var actions = new ArrayList<String>();
        actions.add("REPORT_DRILL_DOWN");
        if (realtimeSnapshots != null) {
            return actions;
        }
        if (currentCapabilities.contains(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)
                && capabilityCoversVisibleReport(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE,
                        reportType,
                        snapshot,
                        dataSet,
                        authorizationTime)) {
            actions.add("REPORT_EXPORT_CREATE");
        }
        if (currentCapabilities.contains(
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD)
                && capabilityCoversVisibleReport(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD,
                        reportType,
                        snapshot,
                        dataSet,
                        authorizationTime)) {
            actions.add("REPORT_EXPORT_DOWNLOAD");
        }
        return actions;
    }

    private java.util.Optional<ReportSourceSnapshot> loadSnapshot(
            String principalId,
            ReportFilter filter,
            String expectedSnapshotVersion,
            java.time.Instant authorizationTime) {
        if (realtimeSnapshots != null) {
            return realtimeSnapshots.loadAuthorizedSnapshot(
                    principalId,
                    CapabilityCodes.ATTENDANCE_REPORT_READ,
                    filter,
                    expectedSnapshotVersion,
                    authorizationTime);
        }
        return repository.loadAuthorizedSnapshot(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                filter,
                authorizationTime);
    }

    private Instant currentDatabaseInstant() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private ApiProblemException snapshotUnavailable() {
        if (realtimeSnapshots == null) {
            return new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_PROJECTION_NOT_READY",
                    "当前期间尚无已发布的报表投影",
                    true);
        }
        return new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ATTENDANCE_REPORT_SCOPE_NOT_AVAILABLE",
                "当前用户没有该报表范围的访问权限");
    }

    private boolean capabilityCoversVisibleReport(
            String principalId,
            String capabilityCode,
            ReportType reportType,
            ReportSourceSnapshot snapshot,
            ReportDataSet dataSet,
            java.time.Instant authorizationTime) {
        return repository.loadAuthorizedSnapshotIntersection(
                        principalId,
                        capabilityCode,
                        snapshot,
                        dataSet.rows().isEmpty(),
                        authorizationTime)
                .map(candidate -> {
                    ReportDataSet candidateDataSet = calculator.calculate(
                            reportType, candidate);
                    return AttendanceReportVisibilityDigest
                            .calculateScopeIndependent(
                                    reportType, snapshot, dataSet)
                            .equals(AttendanceReportVisibilityDigest
                                    .calculateScopeIndependent(
                                            reportType,
                                            candidate,
                                            candidateDataSet));
                })
                .orElse(false);
    }

    static String fingerprint(
            ReportType type,
            ReportFilter filter,
            String projectionVersion,
            String authorizationDigest,
            String formulaVersion) {
        try {
            String canonical = String.join(
                    "\n",
                    type.name(),
                    filter.period().toString(),
                    value(filter.companyId()),
                    value(filter.organizationId()),
                    value(filter.employeeId()),
                    value(filter.status()),
                    projectionVersion,
                    authorizationDigest,
                    formulaVersion);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "unable to fingerprint report query", exception);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void requirePage(int page, int size) {
        if (page < 0
                || page > MAX_PAGE_NUMBER
                || size < 1
                || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page must be between 0 and 1000000"
                            + " and size must be between 1 and 200");
        }
    }

    private static String requireExpectedProjectionVersion(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > 128
                || value.isBlank()
                || !value.equals(value.trim())
                || value.codePoints().anyMatch(codePoint ->
                        codePoint <= 0x1f
                                || (codePoint >= 0x7f
                                && codePoint <= 0x9f))) {
            throw new IllegalArgumentException(
                    "expectedProjectionVersion is invalid");
        }
        return value;
    }

    private static void requireSameProjectionVersion(
            String expectedProjectionVersion,
            String actualProjectionVersion,
            boolean realtime) {
        if (expectedProjectionVersion == null
                || expectedProjectionVersion.equals(
                        actualProjectionVersion)) {
            return;
        }
        if (realtime) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ATTENDANCE_REPORT_SNAPSHOT_CHANGED",
                    "考勤输入快照已更新，请返回第一页重新加载",
                    true);
        }
        throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_PROJECTION_CHANGED",
                "报表数据版本已更新，请返回第一页重新加载");
    }
}

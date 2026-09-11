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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
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
        return query(
                reportType,
                period,
                companyId,
                organizationId,
                employeeId,
                status,
                expectedProjectionVersion,
                null,
                null,
                page,
                size);
    }

    public AttendanceReportPage query(
            ReportType reportType,
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String status,
            String expectedProjectionVersion,
            LocalDate fromDate,
            LocalDate toDate,
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
                        status,
                        fromDate,
                        toDate);
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        Set<String> currentCapabilities = capabilities.currentCapabilities();
        String principalId = principalProvider.currentPrincipalId();
        var at = currentDatabaseInstant();
        var snapshot = loadDisplaySnapshot(
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
        boolean sourcesNewerThanPin = sourcesNewerThanPin(snapshot, at);
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
                totalPages,
                sourcesNewerThanPin);
    }

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

    public AttendanceMonthMatrixPage queryMonthMatrix(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String expectedProjectionVersion,
            int page,
            int size) {
        return queryMonthMatrix(
                period,
                companyId,
                organizationId,
                employeeId,
                expectedProjectionVersion,
                null,
                null,
                page,
                size);
    }

    public AttendanceMonthMatrixPage queryMonthMatrix(
            YearMonth period,
            String companyId,
            String organizationId,
            String employeeId,
            String expectedProjectionVersion,
            LocalDate fromDate,
            LocalDate toDate,
            int page,
            int size) {
        requirePage(page, size);
        String normalizedExpectedProjectionVersion =
                requireExpectedProjectionVersion(expectedProjectionVersion);
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        LocalDate rangeStart = fromDate;
        LocalDate rangeEnd = toDate;
        if (rangeStart != null
                && rangeEnd != null
                && rangeStart.equals(period.atDay(1))
                && rangeEnd.equals(period.atEndOfMonth())) {
            rangeStart = null;
            rangeEnd = null;
        }
        ReportFilter filter = new ReportFilter(
                period,
                companyId,
                organizationId,
                employeeId,
                null,
                rangeStart,
                rangeEnd);
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        Set<String> currentCapabilities = capabilities.currentCapabilities();
        String principalId = principalProvider.currentPrincipalId();
        var at = currentDatabaseInstant();
        ReportSourceSnapshot snapshot = null;
        long total = -1;
        boolean usedPublishedPage = false;
        // Official month-matrix must page the published pin even when the
        // realtime engine is present. Loading the whole Jiangsu month into
        // memory for page 0 (50 rows) is what made the sheet spin until
        // timeout after the 2026-08 rematch grew the pin.
        if (!spansMonths(filter)) {
            var paged = repository.loadAuthorizedPagedSnapshot(
                    principalId,
                    CapabilityCodes.ATTENDANCE_REPORT_READ,
                    filter,
                    page,
                    size,
                    at);
            if (paged.isPresent()) {
                snapshot = withFilter(paged.orElseThrow().snapshot(), filter);
                total = paged.orElseThrow().totalEmployees();
                usedPublishedPage = true;
            }
        }
        if (!usedPublishedPage) {
            snapshot = loadDisplaySnapshot(
                            principalId,
                            filter,
                            normalizedExpectedProjectionVersion,
                            at)
                    .orElseThrow(this::snapshotUnavailable);
            total = -1;
        }
        requireSameProjectionVersion(
                normalizedExpectedProjectionVersion,
                snapshot.projectionVersion(),
                realtimeSnapshots != null);
        var matrix = AttendanceMonthMatrixAssembler.assemble(snapshot);
        if (total < 0) {
            total = matrix.rows().size();
        }
        List<AttendanceMonthMatrixPage.EmployeeRow> pageRows = usedPublishedPage
                ? matrix.rows()
                : matrix.rows().subList(
                        Math.min(
                                Math.multiplyExact(page, size),
                                matrix.rows().size()),
                        Math.min(
                                Math.multiplyExact(page, size) + size,
                                matrix.rows().size()));
        int totalPages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);
        var actions = matrixAllowedActions(currentCapabilities);
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
                pageRows,
                page,
                size,
                total,
                totalPages,
                sourcesNewerThanPin(snapshot, at));
    }

    public AttendanceReportRecalculateResult recalculate(
            YearMonth period, String companyId) {
        return recalculate(period, companyId, RecalcWindow.MONTH);
    }

    public AttendanceReportRecalculateResult recalculate(
            YearMonth period, String companyId, RecalcWindow window) {
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        RecalcWindow resolved = window == null ? RecalcWindow.MONTH : window;
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (realtimeSnapshots == null) {
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CALCULATION_ENGINE_NOT_AVAILABLE",
                    "考勤核算引擎尚未接入，无法重新计算报表",
                    false);
        }
        ReportFilter filter = new ReportFilter(
                period, companyId, null, null, null);
        String principalId = principalProvider.currentPrincipalId();
        var at = currentDatabaseInstant();
        var snapshot = realtimeSnapshots.recalculate(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                filter,
                at,
                resolved);
        return new AttendanceReportRecalculateResult(
                snapshot.projectionVersion(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                realtimeSnapshots.sourcesNewerThanPin(snapshot, at));
    }

    public AttendanceReportRecalculateResult recalculateEmployeeDays(
            YearMonth period,
            String companyId,
            String employeeId,
            LocalDate businessDate,
            Instant dataAsOf) {
        if (period == null || employeeId == null || employeeId.isBlank()
                || businessDate == null) {
            throw new IllegalArgumentException("employee day recalc requires period, employee, and date");
        }
        if (realtimeSnapshots == null) {
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CALCULATION_ENGINE_NOT_AVAILABLE",
                    "考勤核算引擎尚未接入，无法重新计算报表",
                    false);
        }
        ReportFilter filter = new ReportFilter(
                period, companyId, null, employeeId, null);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = dataAsOf == null ? currentDatabaseInstant() : dataAsOf;
        LocalDate writeStart = businessDate.minusDays(1);
        if (writeStart.isBefore(period.atDay(1))) {
            writeStart = period.atDay(1);
        }
        LocalDate writeEndExclusive = businessDate.plusDays(2);
        LocalDate monthEnd = period.plusMonths(1).atDay(1);
        if (writeEndExclusive.isAfter(monthEnd)) {
            writeEndExclusive = monthEnd;
        }
        var snapshot = realtimeSnapshots.recalculateEmployee(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_QUERY_READ,
                filter,
                at,
                employeeId,
                writeStart,
                writeEndExclusive);
        return new AttendanceReportRecalculateResult(
                snapshot.projectionVersion(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                realtimeSnapshots.sourcesNewerThanPin(snapshot, at));
    }

    public AttendanceReportRecalculateResult recalculateEmployees(
            YearMonth period,
            String companyId,
            List<String> employeeIds,
            LocalDate fromDate,
            LocalDate toDateInclusive,
            Instant dataAsOf) {
        if (period == null || companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("employee recalc requires period and company");
        }
        if (employeeIds == null || employeeIds.isEmpty()) {
            throw new IllegalArgumentException("employeeIds is required");
        }
        if (employeeIds.size() > 200) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "单次最多重算 200 人",
                    false);
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_REFRESH);
        if (realtimeSnapshots == null) {
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CALCULATION_ENGINE_NOT_AVAILABLE",
                    "考勤核算引擎尚未接入，无法重新计算报表",
                    false);
        }
        LinkedHashSet<String> scoped = new LinkedHashSet<>();
        for (String employeeId : employeeIds) {
            if (employeeId != null && !employeeId.isBlank()) {
                scoped.add(employeeId.trim());
            }
        }
        if (scoped.isEmpty()) {
            throw new IllegalArgumentException("employeeIds is required");
        }
        LocalDate monthStart = period.atDay(1);
        LocalDate monthEnd = period.plusMonths(1).atDay(1);
        LocalDate writeStart = fromDate == null ? monthStart : fromDate.minusDays(1);
        if (writeStart.isBefore(monthStart)) {
            writeStart = monthStart;
        }
        LocalDate writeEndExclusive = toDateInclusive == null
                ? monthEnd
                : toDateInclusive.plusDays(2);
        if (writeEndExclusive.isAfter(monthEnd)) {
            writeEndExclusive = monthEnd;
        }
        ReportFilter filter = new ReportFilter(
                period, companyId, null, null, null);
        String principalId = principalProvider.currentPrincipalId();
        Instant at = dataAsOf == null ? currentDatabaseInstant() : dataAsOf;
        var snapshot = realtimeSnapshots.recalculateEmployees(
                principalId,
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH,
                filter,
                at,
                List.copyOf(scoped),
                writeStart,
                writeEndExclusive);
        return new AttendanceReportRecalculateResult(
                snapshot.projectionVersion(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                realtimeSnapshots.sourcesNewerThanPin(snapshot, at));
    }

    private List<String> matrixAllowedActions(
            Set<String> currentCapabilities) {
        var actions = new ArrayList<String>();
        actions.add("REPORT_DRILL_DOWN");
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH)) {
            actions.add("REPORT_RECALCULATE");
        }
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)) {
            actions.add("REPORT_EXPORT_CREATE");
        }
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD)) {
            actions.add("REPORT_EXPORT_DOWNLOAD");
        }
        return actions;
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
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_REFRESH)) {
            actions.add("REPORT_RECALCULATE");
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

    private java.util.Optional<ReportSourceSnapshot> loadDisplaySnapshot(
            String principalId,
            ReportFilter filter,
            String expectedSnapshotVersion,
            Instant authorizationTime) {
        List<YearMonth> months = monthsCovered(filter);
        List<ReportSourceSnapshot> loaded = new ArrayList<>();
        for (YearMonth month : months) {
            ReportFilter monthFilter = new ReportFilter(
                    month,
                    filter.companyId(),
                    filter.organizationId(),
                    filter.employeeId(),
                    filter.status(),
                    filter.fromDate(),
                    filter.toDate());
            loadSnapshot(
                    principalId,
                    monthFilter,
                    expectedSnapshotVersion,
                    authorizationTime)
                    .map(snapshot -> withFilter(snapshot, monthFilter))
                    .ifPresent(loaded::add);
        }
        if (loaded.isEmpty()) {
            return java.util.Optional.empty();
        }
        if (loaded.size() == 1) {
            return java.util.Optional.of(
                    withFilter(loaded.getFirst(), filter));
        }
        return java.util.Optional.of(mergeSnapshots(loaded, filter));
    }

    private static boolean spansMonths(ReportFilter filter) {
        return filter.fromDate() != null
                && filter.toDate() != null
                && !YearMonth.from(filter.fromDate())
                        .equals(YearMonth.from(filter.toDate()));
    }

    private static List<YearMonth> monthsCovered(ReportFilter filter) {
        if (filter.fromDate() == null || filter.toDate() == null) {
            return List.of(filter.period());
        }
        YearMonth start = YearMonth.from(filter.fromDate());
        YearMonth end = YearMonth.from(filter.toDate());
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static ReportSourceSnapshot withFilter(
            ReportSourceSnapshot snapshot, ReportFilter filter) {
        return new ReportSourceSnapshot(
                snapshot.scope(),
                filter,
                snapshot.projectionVersion(),
                snapshot.periodState(),
                snapshot.dataAsOf(),
                snapshot.sourceVersions(),
                snapshot.dailyFacts(),
                snapshot.oaDocumentFacts(),
                snapshot.exceptionFacts(),
                snapshot.timeAccountFacts(),
                snapshot.workWindows());
    }

    private static ReportSourceSnapshot mergeSnapshots(
            List<ReportSourceSnapshot> snapshots, ReportFilter filter) {
        ReportSourceSnapshot first = snapshots.getFirst();
        List<com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact> daily =
                new ArrayList<>();
        List<com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact> oa =
                new ArrayList<>();
        List<com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact> exceptions =
                new ArrayList<>();
        List<com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact> accounts =
                new ArrayList<>();
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        List<String> sourceVersions = new ArrayList<>();
        for (ReportSourceSnapshot snapshot : snapshots) {
            daily.addAll(snapshot.dailyFacts());
            oa.addAll(snapshot.oaDocumentFacts());
            exceptions.addAll(snapshot.exceptionFacts());
            accounts.addAll(snapshot.timeAccountFacts());
            versions.add(snapshot.projectionVersion());
            sourceVersions.addAll(snapshot.sourceVersions());
        }
        return new ReportSourceSnapshot(
                first.scope(),
                filter,
                String.join("+", versions),
                first.periodState(),
                first.dataAsOf(),
                sourceVersions.stream().distinct().toList(),
                daily,
                oa,
                exceptions,
                accounts);
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

    private boolean sourcesNewerThanPin(
            ReportSourceSnapshot snapshot, Instant authorizationTime) {
        return realtimeSnapshots != null
                && realtimeSnapshots.sourcesNewerThanPin(
                        snapshot, authorizationTime);
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
                HttpStatus.CONFLICT,
                "ATTENDANCE_REPORT_PIN_NOT_READY",
                "该月核算尚未完成",
                true);
    }

    private boolean capabilityCoversVisibleReport(
            String principalId,
            String capabilityCode,
            ReportType reportType,
            ReportSourceSnapshot snapshot,
            ReportDataSet dataSet,
            java.time.Instant authorizationTime) {
        if (realtimeSnapshots != null
                && snapshot.projectionVersion() != null
                && snapshot.projectionVersion().startsWith("LIVE-")) {
            return realtimeSnapshots.loadAuthorizedSnapshot(
                            principalId,
                            capabilityCode,
                            snapshot.filter(),
                            snapshot.projectionVersion(),
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
                    value(filter.fromDate() == null
                            ? null
                            : filter.fromDate().toString()),
                    value(filter.toDate() == null
                            ? null
                            : filter.toDate().toString()),
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

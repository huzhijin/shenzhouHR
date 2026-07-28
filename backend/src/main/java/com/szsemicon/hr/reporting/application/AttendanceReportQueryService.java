package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.domain.AttendanceReportCalculator;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.YearMonth;
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
    private final Clock clock;
    private final AttendanceReportCalculator calculator;

    @Autowired
    public AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            Clock clock) {
        this(
                capabilities,
                principalProvider,
                repository,
                clock,
                new AttendanceReportCalculator());
    }

    AttendanceReportQueryService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceReportSourceRepository repository,
            Clock clock,
            AttendanceReportCalculator calculator) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
        this.calculator = calculator;
    }

    @Transactional(readOnly = true)
    public List<AttendanceReportSourceRepository.LegalEntityOption>
            legalEntities(YearMonth period) {
        if (period == null) {
            throw new IllegalArgumentException("period is required");
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        return repository.listAuthorizedLegalEntities(
                principalProvider.currentPrincipalId(),
                CapabilityCodes.ATTENDANCE_REPORT_READ,
                period,
                clock.instant());
    }

    @Transactional(readOnly = true)
    public AttendanceReportPage query(
            ReportType reportType,
            YearMonth period,
            String legalEntityId,
            String organizationId,
            String employeeId,
            String status,
            int page,
            int size) {
        requirePage(page, size);
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
                        legalEntityId,
                        organizationId,
                        employeeId,
                        status);
        capabilities.require(CapabilityCodes.ATTENDANCE_REPORT_READ);
        Set<String> currentCapabilities = capabilities.currentCapabilities();
        String principalId = principalProvider.currentPrincipalId();
        var at = clock.instant();
        var snapshot = repository.loadAuthorizedSnapshot(
                        principalId,
                        CapabilityCodes.ATTENDANCE_REPORT_READ,
                        filter,
                        at)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.CONFLICT,
                        "ATTENDANCE_REPORT_PROJECTION_NOT_READY",
                        "当前期间尚无已发布的报表投影",
                        true));
        var dataSet = calculator.calculate(reportType, snapshot);
        int from = Math.min(
                Math.multiplyExact(page, size), dataSet.rows().size());
        int to = Math.min(from + size, dataSet.rows().size());
        long total = dataSet.rows().size();
        int totalPages = total == 0
                ? 0
                : (int) Math.ceil((double) total / size);
        var actions = new ArrayList<String>();
        actions.add("REPORT_DRILL_DOWN");
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_CREATE)) {
            actions.add("REPORT_EXPORT_CREATE");
        }
        if (currentCapabilities.contains(
                CapabilityCodes.ATTENDANCE_REPORT_EXPORT_DOWNLOAD)) {
            actions.add("REPORT_EXPORT_DOWNLOAD");
        }
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
                    value(filter.legalEntityId()),
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
}

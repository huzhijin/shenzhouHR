package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage;
import com.szsemicon.hr.reporting.application.AttendanceReportPage;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.application.RecalcWindow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.AttendanceReportRecalculateResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-reports")
public class AttendanceReportController {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> COMPANY_DIRECTORY_PARAMETERS =
            Set.of("period");
    private static final Set<String> REPORT_PARAMETERS = Set.of(
            "reportType",
            "period",
            "companyId",
            "organizationId",
            "employeeId",
            "status",
            "expectedProjectionVersion",
            "fromDate",
            "toDate",
            "page",
            "size");
    private static final Set<String> MONTH_MATRIX_PARAMETERS = Set.of(
            "period",
            "companyId",
            "organizationId",
            "employeeId",
            "expectedProjectionVersion",
            "fromDate",
            "toDate",
            "page",
            "size");

    private final AttendanceReportQueryService queryService;
    private final Clock clock;

    public AttendanceReportController(
            AttendanceReportQueryService queryService, Clock clock) {
        this.queryService = queryService;
        this.clock = clock;
    }

    @GetMapping("/companies")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:READ')")
    ResponseEntity<CompanyDirectoryResponse> companies(
            @RequestParam(required = false) YearMonth period,
            @RequestParam MultiValueMap<String, String> requestParameters) {
        rejectUnknownParameters(
                requestParameters, COMPANY_DIRECTORY_PARAMETERS);
        YearMonth resolvedPeriod = period == null
                ? YearMonth.from(clock.instant().atZone(BUSINESS_ZONE))
                : period;
        List<CompanyDirectoryResponse.Item> items =
                queryService.companies(resolvedPeriod).stream()
                        .map(option ->
                                new CompanyDirectoryResponse.Item(
                                        option.companyId(),
                                        option.companyName()))
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CompanyDirectoryResponse(
                        resolvedPeriod.toString(), items));
    }

    @PostMapping("/recalculate")
    @PreAuthorize(
            "hasAuthority('"
                    + CapabilityCodes.ATTENDANCE_REPORT_REFRESH
                    + "')")
    ResponseEntity<RecalculateResponse> recalculate(
            @Valid @RequestBody RecalculateRequest request) {
        AttendanceReportRecalculateResult result;
        if (request.employeeIds() != null && !request.employeeIds().isEmpty()) {
            result = queryService.recalculateEmployees(
                    request.period(),
                    request.companyId(),
                    request.employeeIds(),
                    request.fromDate(),
                    request.toDate(),
                    null);
        } else {
            result = queryService.recalculate(
                    request.period(),
                    request.companyId(),
                    RecalcWindow.from(request.window()));
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RecalculateResponse(
                        result.projectionVersion(),
                        result.dataAsOf(),
                        result.sourceVersions(),
                        result.sourcesNewerThanPin(),
                        result.skippedMonths()));
    }

    record RecalculateRequest(
            @NotBlank @Size(max = 36) String companyId,
            @NotNull YearMonth period,
            @Size(max = 32) String window,
            @Size(max = 200) List<@Size(max = 36) String> employeeIds,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate) {
    }

    record RecalculateResponse(
            String projectionVersion,
            java.time.Instant dataAsOf,
            List<String> sourceVersions,
            boolean sourcesNewerThanPin,
            List<String> skippedMonths) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:READ')")
    ResponseEntity<AttendanceReportResponse> report(
            @RequestParam(defaultValue = "ATTENDANCE_DETAIL")
                    ReportType reportType,
            @RequestParam(required = false) YearMonth period,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam MultiValueMap<String, String> requestParameters) {
        rejectUnknownParameters(requestParameters, REPORT_PARAMETERS);
        YearMonth resolvedPeriod = period == null
                ? YearMonth.from(clock.instant().atZone(BUSINESS_ZONE))
                : period;
        AttendanceReportPage result = queryService.query(
                reportType,
                resolvedPeriod,
                companyId,
                organizationId,
                employeeId,
                status,
                parameterValue(
                        requestParameters,
                        "expectedProjectionVersion"),
                fromDate,
                toDate,
                page,
                size);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(result));
    }

    @GetMapping("/month-matrix")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:READ')")
    ResponseEntity<AttendanceMonthMatrixResponse> monthMatrix(
            @RequestParam(required = false) YearMonth period,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam MultiValueMap<String, String> requestParameters) {
        rejectUnknownParameters(
                requestParameters, MONTH_MATRIX_PARAMETERS);
        YearMonth resolvedPeriod = period == null
                ? YearMonth.from(clock.instant().atZone(BUSINESS_ZONE))
                : period;
        var result = queryService.queryMonthMatrix(
                resolvedPeriod,
                companyId,
                organizationId,
                employeeId,
                parameterValue(
                        requestParameters,
                        "expectedProjectionVersion"),
                fromDate,
                toDate,
                page,
                size);
        var metadata = new AttendanceMonthMatrixResponse.ProjectionMetadata(
                result.projectionVersion(),
                result.sourceVersions(),
                result.dataAsOf(),
                BUSINESS_ZONE.getId(),
                result.filters().period().toString(),
                result.periodState(),
                new AttendanceMonthMatrixResponse.ProjectionScope(
                        result.scope().type().name(),
                        result.scope().reference(),
                        result.scope().label()),
                result.allowedActions(),
                result.sourcesNewerThanPin());
        var filters = new AttendanceMonthMatrixResponse.ReportFilters(
                result.filters().period().toString(),
                result.scope().reference(),
                result.filters().companyId(),
                result.filters().organizationId(),
                result.filters().employeeId(),
                result.filters().fromDate() == null
                        ? null
                        : result.filters().fromDate().toString(),
                result.filters().toDate() == null
                        ? null
                        : result.filters().toDate().toString());
        var rows = result.rows().stream()
                .map(row -> new AttendanceMonthMatrixResponse.EmployeeRow(
                        row.employeeId(),
                        row.employeeNumber(),
                        row.employeeName(),
                        row.organizationId(),
                        row.organizationName(),
                        row.days().stream()
                                .map(day -> new AttendanceMonthMatrixResponse.DayCell(
                                        day.date(),
                                        day.organizationName(),
                                        day.shiftLabel(),
                                        day.firstPunchAt(),
                                        day.lastPunchAt(),
                                        day.badges().stream()
                                                .map(Enum::name)
                                                .toList(),
                                        toSlot(day.morning()),
                                        toSlot(day.afternoon()),
                                        day.merged(),
                                        day.hover()))
                                .toList()))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AttendanceMonthMatrixResponse(
                        "ATTENDANCE_MONTH_MATRIX",
                        metadata,
                        result.queryFingerprint(),
                        result.formulaVersion(),
                        filters,
                        result.dates(),
                        result.totalEmployees(),
                        rows,
                        result.page(),
                        result.size(),
                        result.totalPages()));
    }

    private AttendanceReportResponse toResponse(AttendanceReportPage page) {
        var metadata = new AttendanceReportResponse.ProjectionMetadata(
                page.projectionVersion(),
                page.sourceVersions(),
                page.dataAsOf(),
                BUSINESS_ZONE.getId(),
                page.filters().period().toString(),
                page.periodState(),
                new AttendanceReportResponse.ProjectionScope(
                        page.scope().type().name(),
                        page.scope().reference(),
                        page.scope().label()),
                page.allowedActions(),
                page.sourcesNewerThanPin());
        var filters = new AttendanceReportResponse.ReportFilters(
                page.filters().period().toString(),
                page.scope().reference(),
                page.filters().companyId(),
                page.filters().organizationId(),
                page.filters().employeeId(),
                page.filters().status(),
                page.filters().fromDate() == null
                        ? null
                        : page.filters().fromDate().toString(),
                page.filters().toDate() == null
                        ? null
                        : page.filters().toDate().toString());
        var columns = page.columns().stream()
                .map(column -> new AttendanceReportResponse.ReportColumn(
                        column.field().key(),
                        column.field().label()))
                .toList();
        var rows = page.rows().stream()
                .map(row -> {
                    var values = new LinkedHashMap<String, String>();
                    row.values().forEach(
                            (key, value) -> values.put(key.key(), value));
                    return new AttendanceReportResponse.ReportRow(
                            row.rowReference(),
                            values,
                            row.drillDownReference());
                })
                .toList();
        return new AttendanceReportResponse(
                "REPORT",
                metadata,
                page.reportType().name(),
                page.title(),
                page.queryFingerprint(),
                page.formulaVersion(),
                filters,
                columns,
                page.exportAllowlist().stream()
                        .map(field -> field.key())
                        .toList(),
                page.totalRows(),
                rows,
                page.page(),
                page.size(),
                page.totalPages());
    }

    private static AttendanceMonthMatrixResponse.SlotDisplay toSlot(
            AttendanceMonthMatrixPage.SlotDisplay slot) {
        return new AttendanceMonthMatrixResponse.SlotDisplay(
                slot.text(),
                slot.tone(),
                slot.punchAt());
    }

    private static void rejectUnknownParameters(
            MultiValueMap<String, String> requestParameters,
            Set<String> allowedParameters) {
        if (requestParameters == null || requestParameters.entrySet().stream()
                .allMatch(entry -> allowedParameters.contains(entry.getKey())
                        && entry.getValue() != null
                        && entry.getValue().size() == 1)) {
            return;
        }
        throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求包含不支持的查询参数");
    }

    private static String parameterValue(
            MultiValueMap<String, String> requestParameters,
            String name) {
        return requestParameters == null
                ? null
                : requestParameters.getFirst(name);
    }

    record CompanyDirectoryResponse(
            String period, List<Item> companies) {

        record Item(String companyId, String companyName) {
        }
    }
}

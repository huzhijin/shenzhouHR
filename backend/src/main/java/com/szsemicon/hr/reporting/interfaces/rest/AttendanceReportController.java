package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceReportPage;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryService;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ScopeType;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-reports")
public class AttendanceReportController {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AttendanceReportQueryService queryService;
    private final Clock clock;

    public AttendanceReportController(
            AttendanceReportQueryService queryService, Clock clock) {
        this.queryService = queryService;
        this.clock = clock;
    }

    @GetMapping("/legal-entities")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:READ')")
    ResponseEntity<LegalEntityDirectoryResponse> legalEntities(
            @RequestParam(required = false) YearMonth period) {
        YearMonth resolvedPeriod = period == null
                ? YearMonth.from(clock.instant().atZone(BUSINESS_ZONE))
                : period;
        List<LegalEntityDirectoryResponse.Item> items =
                queryService.legalEntities(resolvedPeriod).stream()
                        .map(option ->
                                new LegalEntityDirectoryResponse.Item(
                                        option.legalEntityId(),
                                        option.name()))
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LegalEntityDirectoryResponse(
                        resolvedPeriod.toString(), items));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:READ')")
    ResponseEntity<AttendanceReportResponse> report(
            @RequestParam(defaultValue = "ATTENDANCE_DETAIL")
                    ReportType reportType,
            @RequestParam(required = false) YearMonth period,
            @RequestParam(required = false) String legalEntityId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        YearMonth resolvedPeriod = period == null
                ? YearMonth.from(clock.instant().atZone(BUSINESS_ZONE))
                : period;
        AttendanceReportPage result = queryService.query(
                reportType,
                resolvedPeriod,
                legalEntityId,
                organizationId,
                employeeId,
                status,
                page,
                size);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(result));
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
                        wireScope(page.scope().type()),
                        page.scope().reference(),
                        page.scope().label()),
                page.allowedActions());
        var filters = new AttendanceReportResponse.ReportFilters(
                page.filters().period().toString(),
                page.scope().reference(),
                page.filters().legalEntityId(),
                page.filters().organizationId(),
                page.filters().employeeId(),
                page.filters().status());
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

    private String wireScope(ScopeType type) {
        return type == ScopeType.LEGAL_ENTITY ? "COMPANY" : type.name();
    }

    record LegalEntityDirectoryResponse(
            String period, List<Item> legalEntities) {

        record Item(String legalEntityId, String name) {
        }
    }
}

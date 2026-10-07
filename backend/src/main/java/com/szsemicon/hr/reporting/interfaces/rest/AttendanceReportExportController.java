package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceReportExportService;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService.ExportView;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService.RequestedExportBinding;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-reports/exports")
public class AttendanceReportExportController {

    private final AttendanceReportExportService exportService;

    public AttendanceReportExportController(
            AttendanceReportExportService exportService) {
        this.exportService = exportService;
    }

    @PostMapping
    @PreAuthorize(
            "hasAuthority('ATTENDANCE_REPORT:EXPORT_CREATE')")
    ResponseEntity<ExportView> create(
            @Valid @RequestBody CreateExportRequest request) {
        ExportView result = exportService.create(
                request.reportType(),
                new ReportFilter(
                        request.filters().period(),
                        request.filters().companyId(),
                        request.filters().organizationId(),
                        request.filters().employeeId(),
                        request.filters().status(),
                        request.filters().fromDate(),
                        request.filters().toDate()),
                new RequestedExportBinding(
                        request.projectionVersion(),
                        request.queryFingerprint(),
                        request.scopeReference(),
                        request.filters().scopeReference(),
                        request.selectedFields()),
                request.purpose());
        HttpStatus responseStatus = "READY".equals(result.status())
                ? HttpStatus.CREATED
                : HttpStatus.ACCEPTED;
        return ResponseEntity.status(responseStatus)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @GetMapping("/{exportId}")
    @PreAuthorize(
            "hasAuthority('ATTENDANCE_REPORT:EXPORT_CREATE')")
    ResponseEntity<ExportView> status(
            @PathVariable String exportId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(exportService.status(exportId));
    }

    @PostMapping("/{exportId}/download")
    @PreAuthorize(
            "hasAuthority('ATTENDANCE_REPORT:EXPORT_DOWNLOAD')")
    ResponseEntity<byte[]> download(
            @PathVariable String exportId) {
        var result = exportService.download(exportId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(result.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        disposition.toString())
                .header(
                        HttpHeaders.CONTENT_TYPE,
                        result.contentType())
                .cacheControl(CacheControl.noStore())
                .body(result.content());
    }

    record CreateExportRequest(
            @NotNull ReportType reportType,
            @NotBlank @Size(max = 128) String projectionVersion,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$")
                    String queryFingerprint,
            @NotBlank @Size(max = 128) String scopeReference,
            @Valid @NotNull ExportFilters filters,
            @NotEmpty @Size(max = 64)
                    List<@NotBlank @Size(max = 64) String> selectedFields,
            @NotBlank @Size(min = 2, max = 200) String purpose) {

        @Override
        public String toString() {
            return "CreateExportRequest[reportType="
                    + reportType
                    + ", projectionVersion="
                    + projectionVersion
                    + ", queryFingerprint="
                    + queryFingerprint
                    + ", scopeReference="
                    + scopeReference
                    + ", filters="
                    + filters
                    + ", selectedFields="
                    + selectedFields
                    + ", purpose=<redacted>]";
        }
    }

    record ExportFilters(
            @NotNull YearMonth period,
            @NotBlank @Size(max = 128) String scopeReference,
            @NotBlank @Size(max = 36) String companyId,
            @Size(max = 36) String organizationId,
            @Size(max = 36) String employeeId,
            @Size(max = 32) String status,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate) {

        ExportFilters(
                YearMonth period,
                String scopeReference,
                String companyId,
                String organizationId,
                String employeeId,
                String status) {
            this(
                    period,
                    scopeReference,
                    companyId,
                    organizationId,
                    employeeId,
                    status,
                    null,
                    null);
        }
    }
}

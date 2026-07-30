package com.szsemicon.hr.reporting.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService;
import com.szsemicon.hr.reporting.application.AttendanceReportExportService.ExportView;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
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
                request.period(),
                request.companyId(),
                request.organizationId(),
                request.employeeId(),
                request.status(),
                request.purpose(),
                request.currentPassword());
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
            @PathVariable String exportId,
            @Valid @RequestBody ReauthenticationRequest request) {
        var result = exportService.download(
                exportId, request.currentPassword());
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
            @NotNull YearMonth period,
            @Size(max = 36) String companyId,
            @Size(max = 36) String organizationId,
            @Size(max = 36) String employeeId,
            @Size(max = 32) String status,
            @NotBlank @Size(min = 2, max = 200) String purpose,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
                    @NotBlank @Size(max = 256)
                    String currentPassword) {

        @Override
        public String toString() {
            return "CreateExportRequest[reportType="
                    + reportType
                    + ", period="
                    + period
                    + ", companyId="
                    + companyId
                    + ", organizationId="
                    + organizationId
                    + ", employeeId="
                    + employeeId
                    + ", status="
                    + status
                    + ", purpose=<redacted>, currentPassword=<redacted>]";
        }
    }

    record ReauthenticationRequest(
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
                    @NotBlank @Size(max = 256)
                    String currentPassword) {

        @Override
        public String toString() {
            return "ReauthenticationRequest[currentPassword=<redacted>]";
        }
    }
}

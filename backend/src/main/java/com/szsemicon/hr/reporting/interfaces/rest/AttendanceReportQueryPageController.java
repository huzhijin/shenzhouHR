package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService;
import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService.QueryCommand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-report-queries")
public class AttendanceReportQueryPageController {

    private final AttendanceReportQueryPageService service;

    public AttendanceReportQueryPageController(
            AttendanceReportQueryPageService service) {
        this.service = service;
    }

    @GetMapping("/directory")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT_QUERY:READ')")
    ResponseEntity<AttendanceReportQueryPageService.DirectoryPage> directory(
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) YearMonth period) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.directory(companyId, period));
    }

    @GetMapping("/{sheet}/export")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT:EXPORT_DOWNLOAD')")
    ResponseEntity<byte[]> export(
            @PathVariable String sheet,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String employeeNumber,
            @RequestParam(required = false) YearMonth period,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String exceptionType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String leaveType,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) String overtimeTreatment,
            @RequestParam(required = false) Integer occurrenceDay,
            @RequestParam(required = false) String lateCountBand,
            @RequestParam(required = false) String lateMinuteBand,
            @RequestParam(required = false) String punchSide,
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) String attendanceType,
            @RequestParam(required = false) BigDecimal rateBelow,
            @RequestParam(required = false) String annualBalanceBand,
            @RequestParam(required = false) String annualLevelOne,
            @RequestParam(required = false) String annualLevelTwo,
            @RequestParam(required = false) String attendanceStatus,
            @RequestParam(defaultValue = "ALL") String exportScope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var file = service.export(new QueryCommand(
                sheet,
                companyId,
                organizationId,
                employeeId,
                employeeNumber,
                period,
                fromDate,
                toDate,
                page,
                size,
                exceptionType,
                severity,
                state,
                leaveType,
                approvalState,
                overtimeTreatment,
                occurrenceDay,
                lateCountBand,
                lateMinuteBand,
                punchSide,
                employmentStatus,
                attendanceType,
                rateBelow,
                annualBalanceBand,
                annualLevelOne,
                annualLevelTwo,
                attendanceStatus), exportScope);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }

    @GetMapping("/{sheet}")
    @PreAuthorize("hasAuthority('ATTENDANCE_REPORT_QUERY:READ')")
    ResponseEntity<AttendanceReportQueryPageService.QueryPage> query(
            @PathVariable String sheet,
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String employeeNumber,
            @RequestParam(required = false) YearMonth period,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String exceptionType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String leaveType,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) String overtimeTreatment,
            @RequestParam(required = false) Integer occurrenceDay,
            @RequestParam(required = false) String lateCountBand,
            @RequestParam(required = false) String lateMinuteBand,
            @RequestParam(required = false) String punchSide,
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) String attendanceType,
            @RequestParam(required = false) BigDecimal rateBelow,
            @RequestParam(required = false) String annualBalanceBand,
            @RequestParam(required = false) String annualLevelOne,
            @RequestParam(required = false) String annualLevelTwo,
            @RequestParam(required = false) String attendanceStatus) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.query(new QueryCommand(
                        sheet,
                        companyId,
                        organizationId,
                        employeeId,
                        employeeNumber,
                        period,
                        fromDate,
                        toDate,
                        page,
                        size,
                        exceptionType,
                        severity,
                        state,
                        leaveType,
                        approvalState,
                        overtimeTreatment,
                        occurrenceDay,
                        lateCountBand,
                        lateMinuteBand,
                        punchSide,
                        employmentStatus,
                        attendanceType,
                        rateBelow,
                        annualBalanceBand,
                        annualLevelOne,
                        annualLevelTwo,
                        attendanceStatus)));
    }
}

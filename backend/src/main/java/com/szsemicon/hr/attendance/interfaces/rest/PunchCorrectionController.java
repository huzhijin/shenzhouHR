package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.PunchCorrectionApplicationService;
import com.szsemicon.hr.attendance.application.PunchCorrectionApplicationService.SubmitCommand;
import com.szsemicon.hr.attendance.application.PunchCorrectionQuotaService.QuotaStatus;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({
        "/api/v1/attendance/punch-corrections",
        "/api/v1/attendance/punch-supplement",
        "/api/attendance/punch-corrections",
        "/api/attendance/punch-supplement"
})
public class PunchCorrectionController {

    private final PunchCorrectionApplicationService service;

    public PunchCorrectionController(
            PunchCorrectionApplicationService service) {
        this.service = service;
    }

    @PostMapping({"", "/apply"})
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_CREATE + "')")
    ResponseEntity<RequestView> submit(
            @Valid @RequestBody SubmitRequest request) {
        PunchCorrectionRequest created = service.submit(new SubmitCommand(
                request.employeeId(),
                request.businessDate(),
                request.punchSide(),
                request.reason()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(view(created));
    }

    @GetMapping("/quota/{employeeId}/{month}")
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_READ + "')")
    ResponseEntity<QuotaStatus> quota(
            @PathVariable String employeeId,
            @PathVariable String month) {
        return noStore(service.quota(employeeId, parseMonth(month)));
    }

    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_READ + "')")
    ResponseEntity<QuotaStatus> quotaByQuery(
            @RequestParam String employeeId,
            @RequestParam String month) {
        return noStore(service.quota(employeeId, parseMonth(month)));
    }

    @PutMapping("/{requestId}/approve")
    @PreAuthorize("hasAuthority('"
            + CapabilityCodes.ATTENDANCE_PUNCH_CORRECTION_APPROVE + "')")
    ResponseEntity<RequestView> approve(
            @PathVariable String requestId,
            @Valid @RequestBody(required = false) ApprovalRequest request) {
        PunchCorrectionRequest approved = service.approve(
                requestId, request == null ? null : request.notes());
        return noStore(view(approved));
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (RuntimeException exception) {
            throw new com.szsemicon.hr.shared.web.ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "月份格式必须为 yyyy-MM");
        }
    }

    private static RequestView view(PunchCorrectionRequest value) {
        return new RequestView(
                value.requestId(),
                value.employeeId(),
                value.requestMonth().toString(),
                value.businessDate(),
                value.punchSide(),
                value.reason(),
                value.status().name(),
                value.requestedAt(),
                value.requestedBy(),
                value.reviewedAt(),
                value.reviewedBy(),
                value.reviewNotes());
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(value);
    }

    public record SubmitRequest(
            @NotBlank @Size(max = 36) String employeeId,
            @NotNull LocalDate businessDate,
            @NotNull PunchSide punchSide,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ApprovalRequest(@Size(max = 500) String notes) {
    }

    public record RequestView(
            String requestId,
            String employeeId,
            String requestMonth,
            LocalDate businessDate,
            PunchSide punchSide,
            String reason,
            String status,
            java.time.Instant requestedAt,
            String requestedBy,
            java.time.Instant reviewedAt,
            String reviewedBy,
            String reviewNotes) {
    }
}

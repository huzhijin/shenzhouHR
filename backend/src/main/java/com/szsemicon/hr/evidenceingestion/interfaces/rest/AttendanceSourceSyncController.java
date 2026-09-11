package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncDispatchService;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import com.szsemicon.hr.shared.web.StrongEtag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-source-jobs")
public class AttendanceSourceSyncController {

    private final AttendanceSourceSyncDispatchService startService;
    private final DeliPunchSyncApplicationService jobService;

    public AttendanceSourceSyncController(
            AttendanceSourceSyncDispatchService startService,
            DeliPunchSyncApplicationService jobService) {
        this.startService = startService;
        this.jobService = jobService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_RUN + "')")
    ResponseEntity<AttendanceSourceSyncModels.JobStatus> run(
            @Valid @RequestBody StartSyncRequest request,
            HttpServletRequest servletRequest) {
        var result = startService.run(
                request.sourceId(),
                correlationId(servletRequest),
                request.throughDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<AttendanceSourceSyncModels.JobStatus> get(
            @PathVariable String jobId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(jobService.get(jobId));
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_RETRY + "')")
    ResponseEntity<AttendanceSourceSyncModels.JobStatus> retry(
            @PathVariable String jobId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        var result = jobService.retry(
                jobId,
                StrongEtag.parseVersion(ifMatch),
                idempotencyKey,
                ChangeReasonHeader.decodeAndValidate(changeReason),
                correlationId(servletRequest));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(result);
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(
                CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (value == null) {
            throw new IllegalArgumentException(
                    "correlation ID is unavailable");
        }
        return value.toString();
    }

    public record StartSyncRequest(
            @NotBlank @Size(max = 36) String sourceId,
            LocalDate throughDate) {
        public StartSyncRequest(String sourceId) {
            this(sourceId, null);
        }
    }
}

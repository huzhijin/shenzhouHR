package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-sources")
public class OaWindowRematchController {

    private final OaDocumentSyncApplicationService oaSyncService;

    public OaWindowRematchController(
            OaDocumentSyncApplicationService oaSyncService) {
        this.oaSyncService = oaSyncService;
    }

    @PostMapping("/oa-window-rematch")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_RUN + "')")
    ResponseEntity<AttendanceSourceSyncModels.JobStatus> rematch(
            @Valid @RequestBody RematchRequest request,
            HttpServletRequest servletRequest) {
        Object correlation = servletRequest.getAttribute(
                CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (correlation == null) {
            throw new IllegalArgumentException("correlation ID is unavailable");
        }
        var result = oaSyncService.rematchWindow(
                request.sourceId(),
                request.fromDate(),
                request.toDate(),
                correlation.toString());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    public record RematchRequest(
            @NotBlank @Size(max = 36) String sourceId,
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate) {
    }
}

package com.szsemicon.hr.audit.interfaces.rest;

import com.szsemicon.hr.audit.application.AuditQueryService;
import com.szsemicon.hr.audit.application.AuditQueryService.AuditDetail;
import com.szsemicon.hr.audit.application.AuditQueryService.AuditPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access/audit-events")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT:READ')")
    AuditPage list(
            @RequestParam(defaultValue = "") @Size(max = 96) String action,
            @RequestParam(defaultValue = "") String result,
            @RequestParam(defaultValue = "") @Size(max = 64) String resourceType,
            @RequestParam(defaultValue = "") @Size(max = 128) String resourceId,
            @RequestParam(defaultValue = "occurredAt")
                    @Pattern(regexp = "occurredAt") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return auditQueryService.list(
                action, result, resourceType, resourceId, sort, page, size);
    }

    @GetMapping("/{eventId}")
    @PreAuthorize("hasAuthority('AUDIT:READ')")
    AuditDetail get(@PathVariable String eventId) {
        return auditQueryService.get(eventId);
    }
}

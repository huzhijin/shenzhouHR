package com.szsemicon.hr.leavetimeaccount.interfaces.rest;

import com.szsemicon.hr.leavetimeaccount.application.LeaveAccountOaRecalculateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leave-accounts")
public class LeaveAccountOaRecalculateController {

    private final LeaveAccountOaRecalculateService service;

    public LeaveAccountOaRecalculateController(
            LeaveAccountOaRecalculateService service) {
        this.service = service;
    }

    @PostMapping("/oa-recalculate")
    @PreAuthorize("hasAuthority('ANNUAL_LEAVE:ADJUST')")
    ResponseEntity<LeaveAccountOaRecalculateService.RecalcResult> recalculate(
            @Valid @RequestBody RecalcRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.recalculate(request.companyId(), request.year()));
    }

    public record RecalcRequest(
            @NotBlank @Size(max = 36) String companyId,
            @Min(2000) @Max(2100) Integer year) {
    }
}

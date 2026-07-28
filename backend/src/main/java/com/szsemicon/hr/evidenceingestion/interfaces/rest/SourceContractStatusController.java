package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.evidenceingestion.application.SourceIntegrationStatus;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-sources")
public class SourceContractStatusController {

    @GetMapping("/integration-status")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<SourceIntegrationStatus> integrationStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SourceIntegrationStatus.syntheticPass());
    }
}

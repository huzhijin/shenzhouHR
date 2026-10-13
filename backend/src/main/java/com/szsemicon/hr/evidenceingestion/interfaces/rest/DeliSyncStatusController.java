package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncStatusApplicationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeliSyncStatusController {

    private final DeliSyncStatusApplicationService service;

    public DeliSyncStatusController(DeliSyncStatusApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/attendance/deli-sync/status")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_READ + "')")
    ResponseEntity<DeliSyncStatusDto> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(DeliSyncStatusDto.from(service.latestStatus()));
    }
}

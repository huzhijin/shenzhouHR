package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.CutoverResult;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverModels.Diagnosis;
import com.szsemicon.hr.people.application.IdentityEffectiveFromCutoverService;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity-effective-from-cutover")
public class IdentityEffectiveFromCutoverController {

    private final IdentityEffectiveFromCutoverService service;

    public IdentityEffectiveFromCutoverController(
            IdentityEffectiveFromCutoverService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + CapabilityCodes.IDENTITY_EFFECTIVE_FROM_CUTOVER + "')")
    ResponseEntity<Diagnosis> diagnose() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.diagnose());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + CapabilityCodes.IDENTITY_EFFECTIVE_FROM_CUTOVER + "')")
    ResponseEntity<CutoverResult> execute(
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestHeader("X-Change-Reason") String reason) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.execute(
                        requestId,
                        ChangeReasonHeader.decodeAndValidate(reason)));
    }
}

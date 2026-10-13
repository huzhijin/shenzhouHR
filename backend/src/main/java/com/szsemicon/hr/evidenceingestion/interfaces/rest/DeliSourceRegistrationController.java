package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-sources")
public class DeliSourceRegistrationController {

    private final DeliSourceRegistrationService service;

    public DeliSourceRegistrationController(
            DeliSourceRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(
            "hasAuthority('"
                    + CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE
                    + "')")
    ResponseEntity<DeliSourceRegistrationModels.SourceView> register(
            @Valid @RequestBody RegisterDeliSourceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var source = service.register(request.toCommand(), idempotencyKey);
        return ResponseEntity.status(
                        source.replayed()
                                ? HttpStatus.OK
                                : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(source);
    }

    public record RegisterDeliSourceRequest(
            @NotBlank @Size(max = 36) String companyId,
            @NotBlank
                    @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{1,63}")
                    String sourceCode,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(max = 64) String sourceTimeZone,
            @Min(1) @Max(500) int pageSize,
            @Min(60) @Max(10_000) int rateLimitPerMinute,
            @Min(0) @Max(5) int backoffSeconds,
            @NotBlank
                    @Pattern(regexp = "[A-Z][A-Z0-9_]{2,127}")
                    String secretReferenceName,
            @NotBlank @Size(min = 2, max = 500) String reason) {

        DeliSourceRegistrationModels.Command toCommand() {
            return new DeliSourceRegistrationModels.Command(
                    companyId,
                    sourceCode,
                    displayName,
                    sourceTimeZone,
                    pageSize,
                    rateLimitPerMinute,
                    backoffSeconds,
                    secretReferenceName,
                    reason);
        }
    }
}

package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.AttendancePolicyLifecycleFacade;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedPolicyVersion;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import com.szsemicon.hr.shared.web.StrongEtag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-setup/policy-lifecycle")
public class AttendancePolicyLifecycleController {

    private final AttendancePolicyLifecycleFacade service;

    public AttendancePolicyLifecycleController(
            AttendancePolicyLifecycleFacade service) {
        this.service = service;
    }

    @GetMapping("/{templateId}/versions")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionPage> listVersions(
            @PathVariable String templateId,
            @RequestParam String legalEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendancePolicyLifecycleDtos.page(
                service.listVersions(templateId, legalEntityId, page, size)));
    }

    @GetMapping("/{templateId}/versions/{versionId}")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> getVersion(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId) {
        return versioned(service.getVersion(
                templateId, versionId, legalEntityId));
    }

    @GetMapping("/versions/{versionId}/context")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> getVersionContext(
            @PathVariable String versionId) {
        return versioned(service.getVersionContext(versionId));
    }

    @PostMapping("/{templateId}/versions")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> createDraft(
            @PathVariable String templateId,
            @RequestParam String legalEntityId,
            @Valid @RequestBody DraftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        var result = service.createDraft(
                templateId,
                legalEntityId,
                request.basedOnVersionId(),
                request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()),
                idempotencyKey,
                requestId(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendancePolicyLifecycleDtos.version(result));
    }

    @PatchMapping("/{templateId}/versions/{versionId}")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> updateDraft(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId,
            @Valid @RequestBody UpdateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        List<ParameterValue> parameters = request.parameters().stream()
                .map(value -> new ParameterValue(value.key(), value.value()))
                .toList();
        var result = service.updateDraft(
                templateId,
                versionId,
                legalEntityId,
                parameters,
                request.effectiveFrom(),
                request.effectiveTo(),
                reason(changeReason, request.reason()),
                StrongEtag.parseVersion(ifMatch),
                idempotencyKey,
                requestId(servletRequest));
        return versioned(result);
    }

    @PostMapping("/{templateId}/versions/{versionId}/validate")
    ResponseEntity<AttendancePolicyLifecycleDtos.ValidationView> validate(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        String reason = ChangeReasonHeader.decodeAndValidate(changeReason);
        long expectedVersion = StrongEtag.parseVersion(ifMatch);
        var result = service.validate(
                templateId,
                versionId,
                legalEntityId,
                expectedVersion,
                reason,
                idempotencyKey,
                requestId(servletRequest));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(
                        result.valid()
                                ? Math.addExact(expectedVersion, 1)
                                : expectedVersion))
                .body(AttendancePolicyLifecycleDtos.validation(result));
    }

    @PostMapping("/{templateId}/versions/{versionId}/publish")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> publish(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId,
            @Valid @RequestBody ReasonRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        return versioned(service.publish(
                templateId,
                versionId,
                legalEntityId,
                reason(changeReason, request.reason()),
                StrongEtag.parseVersion(ifMatch),
                idempotencyKey,
                requestId(servletRequest)));
    }

    @PostMapping("/{templateId}/versions/{versionId}/deactivate")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> deactivate(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId,
            @Valid @RequestBody DeactivateRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        return versioned(service.deactivate(
                templateId,
                versionId,
                legalEntityId,
                request.effectiveFrom(),
                reason(changeReason, request.reason()),
                StrongEtag.parseVersion(ifMatch),
                idempotencyKey,
                requestId(servletRequest)));
    }

    @PostMapping("/{templateId}/versions/{versionId}/rollback")
    ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> rollback(
            @PathVariable String templateId,
            @PathVariable String versionId,
            @RequestParam String legalEntityId,
            @Valid @RequestBody RollbackRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason,
            HttpServletRequest servletRequest) {
        var result = service.rollback(
                templateId,
                versionId,
                legalEntityId,
                request.targetVersionId(),
                request.effectiveFrom(),
                reason(changeReason, request.reason()),
                StrongEtag.parseVersion(ifMatch),
                idempotencyKey,
                requestId(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendancePolicyLifecycleDtos.version(result));
    }

    private ResponseEntity<AttendancePolicyLifecycleDtos.VersionView> versioned(
            ScopedPolicyVersion result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendancePolicyLifecycleDtos.version(result));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static String reason(String encodedHeader, String bodyReason) {
        return ChangeReasonHeader.requireMatches(encodedHeader, bodyReason);
    }

    record ParameterRequest(@NotBlank String key, @NotNull Object value) {
    }

    record DraftRequest(
            String basedOnVersionId,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record UpdateRequest(
            @NotNull List<@Valid ParameterRequest> parameters,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record ReasonRequest(
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record DeactivateRequest(
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record RollbackRequest(
            @NotBlank String targetVersionId,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }
}

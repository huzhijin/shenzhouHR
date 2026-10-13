package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionIntent;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V74 OA evidence exclusion REST adapter.
 *
 * <p>Provides Spring Security + CSRF protected HTTP entry for OaEvidenceExclusionWriteService.
 * Records HR's decision to exclude an annual leave OA document from attendance calculation.
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class OaEvidenceExclusionController {

    private final OaEvidenceExclusionWriteService service;

    public OaEvidenceExclusionController(OaEvidenceExclusionWriteService service) {
        this.service = service;
    }

    /**
     * Record exclusion decision for an OA document.
     *
     * <p>Caller must have ATTENDANCE_REPORT:REFRESH permission and data access to the employee.
     * The decision is idempotent by requestId and persists in the same transaction as the audit trail.
     *
     * @param request exclusion intent with all validation gates
     * @return exclusion receipt with knowledge timestamp and digest
     */
    @PostMapping("/oa-evidence-exclusion")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_REPORT_REFRESH + "')")
    ResponseEntity<ExclusionResponse> exclude(
            @Valid @RequestBody ExclusionRequest request) {

        ExclusionIntent intent = new ExclusionIntent(
                request.requestId(),
                request.exclusionId(),
                request.companyId(),
                request.employeeId(),
                request.sourceBusinessKey(),
                request.sourceVersion(),
                request.oaAttendanceDocumentId(),
                request.expectedBusinessDate(),
                request.expectedIntervalStart(),
                request.expectedIntervalEnd(),
                request.reason(),
                request.userConfirmationText(),
                request.userConfirmationSha256(),
                request.evidenceSha256());

        ExclusionReceipt receipt = service.exclude(intent);

        ExclusionResponse response = new ExclusionResponse(
                receipt.exclusionId(),
                receipt.knowledgeAt(),
                receipt.exclusionDigest(),
                receipt.reversedAt(),
                receipt.reversedBy());

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Reverse an existing evidence exclusion decision.
     *
     * <p>Caller must have ATTENDANCE_REPORT:REFRESH permission and data access to the employee.
     * The reversal is idempotent by reversalRequestId and performs a CAS update on reversed_at.
     *
     * @param request reversal intent with expected digest
     * @return exclusion receipt with reversal timestamp
     */
    @PostMapping("/oa-evidence-exclusion/reverse")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_REPORT_REFRESH + "')")
    ResponseEntity<ExclusionResponse> reverse(
            @Valid @RequestBody ReversalRequest request) {

        OaEvidenceExclusionWriteService.ReversalIntent intent =
                new OaEvidenceExclusionWriteService.ReversalIntent(
                        request.reversalRequestId(),
                        request.exclusionId(),
                        request.companyId(),
                        request.employeeId(),
                        request.expectedExclusionDigest(),
                        request.reversalReason());

        ExclusionReceipt receipt = service.reverse(intent);

        ExclusionResponse response = new ExclusionResponse(
                receipt.exclusionId(),
                receipt.knowledgeAt(),
                receipt.exclusionDigest(),
                receipt.reversedAt(),
                receipt.reversedBy());

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Record exclusion decision for a confirmed source-deleted OA overtime document.
     *
     * <p>This endpoint handles OVERTIME documents that have been explicitly confirmed as
     * deleted from the source system. Unlike the standard exclusion endpoint, this accepts
     * PENDING status and cross-month intervals, but requires strict source absence verification.
     *
     * <p>Caller must have ATTENDANCE_REPORT:REFRESH permission and data access to the employee.
     * The decision is idempotent by requestId and persists in the same transaction as the audit trail.
     *
     * @param request confirmed source deletion intent with all validation gates
     * @return exclusion receipt with knowledge timestamp and digest
     */
    @PostMapping("/oa-evidence-exclusion/confirmed-source-deletion")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_REPORT_REFRESH + "')")
    ResponseEntity<ExclusionResponse> excludeConfirmedSourceDeletion(
            @Valid @RequestBody ConfirmedSourceDeletionRequest request) {

        OaEvidenceExclusionWriteService.ConfirmedSourceDeletionIntent intent =
                new OaEvidenceExclusionWriteService.ConfirmedSourceDeletionIntent(
                        request.requestId(),
                        request.exclusionId(),
                        request.companyId(),
                        request.employeeId(),
                        request.sourceBusinessKey(),
                        request.sourceVersion(),
                        request.oaAttendanceDocumentId(),
                        request.expectedIntervalStart(),
                        request.expectedIntervalEnd(),
                        request.reason(),
                        request.userConfirmationText(),
                        request.userConfirmationSha256(),
                        request.evidenceSha256());

        ExclusionReceipt receipt = service.excludeConfirmedSourceDeletion(intent);

        ExclusionResponse response = new ExclusionResponse(
                receipt.exclusionId(),
                receipt.knowledgeAt(),
                receipt.exclusionDigest(),
                receipt.reversedAt(),
                receipt.reversedBy());

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    public record ExclusionRequest(
            @NotBlank @Size(min = 36, max = 36) String requestId,
            @NotBlank @Size(min = 36, max = 36) String exclusionId,
            @NotBlank @Size(min = 36, max = 36) String companyId,
            @NotBlank @Size(min = 36, max = 36) String employeeId,
            @NotBlank @Size(max = 191) String sourceBusinessKey,
            @NotBlank @Size(max = 128) String sourceVersion,
            @NotBlank @Size(min = 36, max = 36) String oaAttendanceDocumentId,
            @NotNull LocalDate expectedBusinessDate,
            @NotNull Instant expectedIntervalStart,
            @NotNull Instant expectedIntervalEnd,
            @NotBlank @Size(min = 1, max = 1000) String reason,
            @NotBlank String userConfirmationText,
            @NotBlank @Size(min = 64, max = 64) String userConfirmationSha256,
            @NotBlank @Size(min = 64, max = 64) String evidenceSha256) {}

    public record ReversalRequest(
            @NotBlank @Size(min = 36, max = 36) String reversalRequestId,
            @NotBlank @Size(min = 36, max = 36) String exclusionId,
            @NotBlank @Size(min = 36, max = 36) String companyId,
            @NotBlank @Size(min = 36, max = 36) String employeeId,
            @NotBlank @Size(min = 64, max = 64) String expectedExclusionDigest,
            @NotBlank @Size(min = 1, max = 1000) String reversalReason) {}

    public record ConfirmedSourceDeletionRequest(
            @NotBlank @Size(min = 36, max = 36) String requestId,
            @NotBlank @Size(min = 36, max = 36) String exclusionId,
            @NotBlank @Size(min = 36, max = 36) String companyId,
            @NotBlank @Size(min = 36, max = 36) String employeeId,
            @NotBlank @Size(max = 191) String sourceBusinessKey,
            @NotBlank @Size(max = 128) String sourceVersion,
            @NotBlank @Size(min = 36, max = 36) String oaAttendanceDocumentId,
            @NotNull Instant expectedIntervalStart,
            @NotNull Instant expectedIntervalEnd,
            @NotBlank @Size(min = 1, max = 1000) String reason,
            @NotBlank String userConfirmationText,
            @NotBlank @Size(min = 64, max = 64) String userConfirmationSha256,
            @NotBlank @Size(min = 64, max = 64) String evidenceSha256) {}

    public record ExclusionResponse(
            String exclusionId,
            Instant knowledgeAt,
            String exclusionDigest,
            Instant reversedAt,
            String reversedBy) {}
}

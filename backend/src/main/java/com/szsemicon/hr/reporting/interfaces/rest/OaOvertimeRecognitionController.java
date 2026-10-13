package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService;
import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService.RecognitionIntent;
import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService.RecognitionReceipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 * V75 OA overtime recognition REST adapter.
 *
 * <p>Provides Spring Security + CSRF protected HTTP entry for OaOvertimeRecognitionWriteService.
 * Records HR's decision to recognize compensatory overtime minutes from an approved OA document.
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class OaOvertimeRecognitionController {

    private final OaOvertimeRecognitionWriteService service;

    public OaOvertimeRecognitionController(OaOvertimeRecognitionWriteService service) {
        this.service = service;
    }

    /**
     * Record overtime recognition decision for an OA document.
     *
     * <p>Caller must have ATTENDANCE_REPORT:REFRESH permission and data access to the employee.
     * The decision is idempotent by requestId and persists in the same transaction as the audit trail.
     *
     * @param request recognition intent with all validation gates
     * @return recognition receipt with knowledge timestamp and digest
     */
    @PostMapping("/oa-overtime-recognition")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_REPORT_REFRESH + "')")
    ResponseEntity<RecognitionResponse> recognize(
            @Valid @RequestBody RecognitionRequest request) {

        RecognitionIntent intent = new RecognitionIntent(
                request.requestId(),
                request.recognitionId(),
                request.companyId(),
                request.employeeId(),
                request.sourceBusinessKey(),
                request.sourceVersion(),
                request.oaAttendanceDocumentId(),
                request.businessDate(),
                request.expectedIntervalStart(),
                request.expectedIntervalEnd(),
                request.recognizedMinutes(),
                request.reason(),
                request.userConfirmationText(),
                request.userConfirmationSha256(),
                request.evidenceSha256());

        RecognitionReceipt receipt = service.recognize(intent);

        RecognitionResponse response = new RecognitionResponse(
                receipt.recognitionId(),
                receipt.knowledgeAt(),
                receipt.recognitionDigest(),
                receipt.reversedAt(),
                receipt.reversedBy());

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Reverse an existing overtime recognition decision.
     *
     * <p>Caller must have ATTENDANCE_REPORT:REFRESH permission and data access to the employee.
     * The reversal is idempotent by reversalRequestId and performs a CAS update on reversed_at.
     *
     * @param request reversal intent with expected digest
     * @return recognition receipt with reversal timestamp
     */
    @PostMapping("/oa-overtime-recognition/reverse")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_REPORT_REFRESH + "')")
    ResponseEntity<RecognitionResponse> reverse(
            @Valid @RequestBody ReversalRequest request) {

        OaOvertimeRecognitionWriteService.ReversalIntent intent =
                new OaOvertimeRecognitionWriteService.ReversalIntent(
                        request.reversalRequestId(),
                        request.recognitionId(),
                        request.companyId(),
                        request.employeeId(),
                        request.expectedRecognitionDigest(),
                        request.reversalReason());

        RecognitionReceipt receipt = service.reverse(intent);

        RecognitionResponse response = new RecognitionResponse(
                receipt.recognitionId(),
                receipt.knowledgeAt(),
                receipt.recognitionDigest(),
                receipt.reversedAt(),
                receipt.reversedBy());

        return ResponseEntity.status(HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    public record RecognitionRequest(
            @NotBlank @Size(min = 36, max = 36) String requestId,
            @NotBlank @Size(min = 36, max = 36) String recognitionId,
            @NotBlank @Size(min = 36, max = 36) String companyId,
            @NotBlank @Size(min = 36, max = 36) String employeeId,
            @NotBlank @Size(max = 191) String sourceBusinessKey,
            @NotBlank @Size(max = 128) String sourceVersion,
            @NotBlank @Size(min = 36, max = 36) String oaAttendanceDocumentId,
            @NotNull LocalDate businessDate,
            @NotNull Instant expectedIntervalStart,
            @NotNull Instant expectedIntervalEnd,
            @Positive int recognizedMinutes,
            @NotBlank @Size(min = 1, max = 1000) String reason,
            @NotBlank String userConfirmationText,
            @NotBlank @Size(min = 64, max = 64) String userConfirmationSha256,
            @NotBlank @Size(min = 64, max = 64) String evidenceSha256) {}

    public record ReversalRequest(
            @NotBlank @Size(min = 36, max = 36) String reversalRequestId,
            @NotBlank @Size(min = 36, max = 36) String recognitionId,
            @NotBlank @Size(min = 36, max = 36) String companyId,
            @NotBlank @Size(min = 36, max = 36) String employeeId,
            @NotBlank @Size(min = 64, max = 64) String expectedRecognitionDigest,
            @NotBlank @Size(min = 1, max = 1000) String reversalReason) {}

    public record RecognitionResponse(
            String recognitionId,
            Instant knowledgeAt,
            String recognitionDigest,
            Instant reversedAt,
            String reversedBy) {}
}

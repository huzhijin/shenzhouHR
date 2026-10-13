package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * OA overtime recognition data operations port.
 * Abstracts V75 recognition persistence from application service.
 */
public interface OaOvertimeRecognitionPort {

    /**
     * Find recognition by request ID and document ID (idempotency check).
     */
    RecognitionRow findByRequest(String requestId, String oaAttendanceDocumentId);

    /**
     * Find recognition by primary key.
     */
    RecognitionRow findById(String recognitionId);

    /**
     * Find validated OA document with complete joins and gates.
     * Gates: validation_status = VALID, match_status = MATCHED
     */
    ValidatedOaDocumentRow findValidatedOaDocument(
            String oaAttendanceDocumentId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion);

    /**
     * Check if document has active V74 exclusion.
     */
    boolean hasActiveExclusion(
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            Instant asOf);

    /**
     * Check for existing V75 recognition overlap (same employee, date, interval).
     */
    boolean hasExistingV75Overlap(
            String companyId,
            String employeeId,
            String attendanceSourceId,
            LocalDate businessDate,
            Instant intervalStart,
            Instant intervalEnd,
            String excludeDocId,
            Instant asOf);

    /**
     * Check for other consumable OA overtime document overlap.
     * Prevents double-recognition of overlapping OA documents.
     */
    boolean hasOtherConsumableOaOverlap(
            String companyId,
            String attendanceSourceId,
            String employeeId,
            Instant intervalStart,
            Instant intervalEnd,
            String excludeDocId,
            String excludeKey,
            Instant asOf);

    /**
     * Lock active OA source for serialized version reads.
     * Returns non-null if source exists and status = ACTIVE.
     */
    String lockActiveOaSource(String sourceId);

    /**
     * Insert new recognition record.
     * @return rows affected (should be 1)
     */
    int insertRecognition(
            String recognitionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            LocalDate businessDate,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            String overtimeType,
            int recognizedMinutes,
            Instant knowledgeAt,
            String reason,
            String createdBy,
            String auditReference,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256,
            String requestId);

    /**
     * Reverse existing recognition (CAS update).
     * @return rows affected (should be 1)
     */
    int reverseRecognition(
            String recognitionId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId);

    /**
     * Recognition row from persistence.
     */
    record RecognitionRow(
            String recognitionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            LocalDate businessDate,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            String overtimeType,
            int recognizedMinutes,
            Instant knowledgeAt,
            String reason,
            String createdBy,
            String auditReference,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256,
            String requestId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId) {}

    /**
     * Validated OA document with complete geometry and gates.
     */
    record ValidatedOaDocumentRow(
            String oaAttendanceDocumentId,
            String documentType,
            String sourceStatus,
            String validationStatus,
            String matchStatus,
            String activationDecision,
            String overtimeType,
            Instant intervalStart,
            Instant intervalEnd,
            String employeeId,
            String companyId) {}
}

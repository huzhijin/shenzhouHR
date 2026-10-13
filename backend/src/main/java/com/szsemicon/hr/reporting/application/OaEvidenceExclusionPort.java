package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;

/**
 * OA evidence exclusion data operations port.
 * Abstracts V74 exclusion persistence from application service.
 */
public interface OaEvidenceExclusionPort {

    /**
     * Find exclusion by request ID and document ID (idempotency check).
     */
    ExclusionRow findByRequest(String requestId, String oaAttendanceDocumentId);

    /**
     * Find exclusion by primary key.
     */
    ExclusionRow findById(String exclusionId);

    /**
     * Find document geometry from normalized record (exact version).
     * Gates: validation_status = VALID, match_status = MATCHED
     */
    DocumentGeometryRow findDocumentGeometry(
            String oaAttendanceDocumentId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion);

    /**
     * Lock active OA source for serialized version reads.
     * Returns non-null if source exists and status = ACTIVE.
     */
    String lockActiveOaSource(String sourceId);

    /**
     * Insert new exclusion record.
     * @return rows affected (should be 1)
     */
    int insertExclusion(
            String exclusionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            Instant knowledgeAt,
            String reason,
            String createdBy,
            String auditReference,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256,
            String requestId);

    /**
     * Reverse existing exclusion (CAS update).
     * @return rows affected (should be 1)
     */
    int reverseExclusion(
            String exclusionId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId);

    /**
     * Exclusion row from persistence.
     */
    record ExclusionRow(
            String exclusionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
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
     * Document geometry from normalized OA record.
     */
    record DocumentGeometryRow(
            String oaAttendanceDocumentId,
            String documentType,
            String leaveType,
            String sourceStatus,
            String validationStatus,
            Instant intervalStart,
            Instant intervalEnd,
            String matchStatus,
            String employeeId,
            String companyId,
            String employeeNumber) {}
}

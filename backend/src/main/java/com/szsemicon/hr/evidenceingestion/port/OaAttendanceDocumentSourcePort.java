package com.szsemicon.hr.evidenceingestion.port;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.Instant;
import java.util.List;

public interface OaAttendanceDocumentSourcePort {

    OaPage fetchPage(String sourceId, String committedCursor);

    /**
     * Occurrence-window read for reconciliation. Never advances the durable scan
     * cursor; persisting changed observations updates the evidence knowledge watermark.
     */
    default List<OaDocumentRecord> fetchOverlapping(
            String sourceId, Instant fromInclusive, Instant toExclusive) {
        return List.of();
    }

    /** Re-read known identities even when their current dates moved outside the window. */
    default List<OaDocumentRecord> fetchByBusinessKeys(String sourceId, List<String> sourceBusinessKeys) {
        return List.of();
    }

    /** Strict current-source absence check; unsupported adapters cannot prove absence. */
    default boolean hasNoLeaveRevocationsForSerial(String sourceId, String leaveSerial) {
        return false;
    }

    /**
     * Verify whether a specific overtime detail document exists in the source.
     * Returns an explicit result: PRESENT when the row exists, ABSENT when verified missing,
     * or UNSUPPORTED/ERROR when the adapter cannot prove absence.
     *
     * @param sourceId the attendance source identifier
     * @param overtimeDetailId the numeric overtime detail table ID from sourceBusinessKey
     * @return presence status - only ABSENT is safe for exclusion
     */
    default OvertimeDocumentPresence checkOvertimeDocumentPresence(String sourceId, long overtimeDetailId) {
        return OvertimeDocumentPresence.UNSUPPORTED;
    }

    enum OvertimeDocumentPresence {
        PRESENT,
        ABSENT,
        UNSUPPORTED,
        ERROR
    }

    enum DocumentType {
        LEAVE,
        LEAVE_REVOCATION,
        OVERTIME,
        TRIP,
        OUTING,
        PUNCH_CORRECTION,
        TIME_OFF,
        EXEMPT_PUNCH
    }

    enum SourceStatus {
        APPROVED,
        PENDING,
        DRAFT,
        REJECTED,
        UNKNOWN,
        MODIFIED,
        SUPPLEMENTED,
        REVOKED
    }

    record OaPage(
            List<OaDocumentRecord> records,
            String inputCursor,
            String nextCursor,
            String pageDigest) {

        public OaPage {
            records = List.copyOf(records);
        }
    }

    record OaDocumentRecord(
            String sourceBusinessKey,
            String sourceVersion,
            String externalPersonRef,
            String employeeNumber,
            DocumentType documentType,
            SourceStatus sourceStatus,
            Instant start,
            Instant end,
            String sourceTimeZone,
            Instant firstSubmittedAt,
            Instant approvedAt,
            Instant modifiedAt,
            Instant revokedAt,
            String sourceBatch,
            boolean effectiveCandidate,
            OvertimeType overtimeType,
            LeaveType leaveType,
            String leaveSerial,
            String originalLeaveSerial) {

        /**
         * Compatibility constructor for OA document types that have no
         * overtime classification.
         */
        public OaDocumentRecord(
                String sourceBusinessKey,
                String sourceVersion,
                String externalPersonRef,
                String employeeNumber,
                DocumentType documentType,
                SourceStatus sourceStatus,
                Instant start,
                Instant end,
                String sourceTimeZone,
                Instant firstSubmittedAt,
                Instant approvedAt,
                Instant modifiedAt,
                Instant revokedAt,
                String sourceBatch,
                boolean effectiveCandidate) {
            this(
                    sourceBusinessKey,
                    sourceVersion,
                    externalPersonRef,
                    employeeNumber,
                    documentType,
                    sourceStatus,
                    start,
                    end,
                    sourceTimeZone,
                    firstSubmittedAt,
                    approvedAt,
                    modifiedAt,
                    revokedAt,
                    sourceBatch,
                    effectiveCandidate,
                    null,
                    null,
                    null,
                    null);
        }

        /** Compatibility constructor for records with overtime metadata only. */
        public OaDocumentRecord(
                String sourceBusinessKey,
                String sourceVersion,
                String externalPersonRef,
                String employeeNumber,
                DocumentType documentType,
                SourceStatus sourceStatus,
                Instant start,
                Instant end,
                String sourceTimeZone,
                Instant firstSubmittedAt,
                Instant approvedAt,
                Instant modifiedAt,
                Instant revokedAt,
                String sourceBatch,
                boolean effectiveCandidate,
                OvertimeType overtimeType) {
            this(
                    sourceBusinessKey,
                    sourceVersion,
                    externalPersonRef,
                    employeeNumber,
                    documentType,
                    sourceStatus,
                    start,
                    end,
                    sourceTimeZone,
                    firstSubmittedAt,
                    approvedAt,
                    modifiedAt,
                    revokedAt,
                    sourceBatch,
                    effectiveCandidate,
                    overtimeType,
                    null,
                    null,
                    null);
        }

        /**
         * Compatibility constructor for classified leave without serials.
         */
        public OaDocumentRecord(
                String sourceBusinessKey,
                String sourceVersion,
                String externalPersonRef,
                String employeeNumber,
                DocumentType documentType,
                SourceStatus sourceStatus,
                Instant start,
                Instant end,
                String sourceTimeZone,
                Instant firstSubmittedAt,
                Instant approvedAt,
                Instant modifiedAt,
                Instant revokedAt,
                String sourceBatch,
                boolean effectiveCandidate,
                OvertimeType overtimeType,
                LeaveType leaveType) {
            this(
                    sourceBusinessKey,
                    sourceVersion,
                    externalPersonRef,
                    employeeNumber,
                    documentType,
                    sourceStatus,
                    start,
                    end,
                    sourceTimeZone,
                    firstSubmittedAt,
                    approvedAt,
                    modifiedAt,
                    revokedAt,
                    sourceBatch,
                    effectiveCandidate,
                    overtimeType,
                    leaveType,
                    null,
                    null);
        }

        public OaDocumentRecord withSourceVersion(String version) {
            return new OaDocumentRecord(sourceBusinessKey, version, externalPersonRef, employeeNumber,
                    documentType, sourceStatus, start, end, sourceTimeZone, firstSubmittedAt, approvedAt,
                    modifiedAt, revokedAt, sourceBatch, effectiveCandidate, overtimeType, leaveType,
                    leaveSerial, originalLeaveSerial);
        }

        public boolean hasValidOvertimeClassification() {
            return documentType != DocumentType.OVERTIME
                    || overtimeType != null;
        }

        public boolean hasValidLeaveClassification() {
            return documentType != DocumentType.LEAVE || leaveType != null;
        }
    }
}

package com.szsemicon.hr.evidenceingestion.port;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.Instant;
import java.util.List;

public interface OaAttendanceDocumentSourcePort {

    OaPage fetchPage(String sourceId, String committedCursor);

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
            LeaveType leaveType) {

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
                    null);
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

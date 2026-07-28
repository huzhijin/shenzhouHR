package com.szsemicon.hr.evidenceingestion.port;

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
            boolean effectiveCandidate) {
    }
}

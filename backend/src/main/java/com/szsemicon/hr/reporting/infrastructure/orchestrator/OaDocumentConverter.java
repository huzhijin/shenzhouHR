package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import java.time.Instant;
import java.util.List;

/**
 * Converts OA attendance documents to calculation engine evidence.
 */
final class OaDocumentConverter {

    static final String UNRESOLVED_LEAVE_REVOCATION =
            "OA_LEAVE_REVOCATION_UNRESOLVED";

    private OaDocumentConverter() {
    }

    /**
     * Converts a list of OA document rows to interval evidence for the
     * calculation engine. Each document becomes one evidence record with
     * the appropriate kind based on document type.
     */
    static List<IntervalEvidence> toIntervalEvidence(
            List<OaDocumentRow> documents) {
        rejectUnresolvedLeaveRevocation(documents);
        return documents.stream()
                .filter(OaDocumentRow::effectiveCandidate)
                .filter(document -> !"TRIP".equals(document.documentType()))
                .map(OaDocumentConverter::toEvidence)
                .toList();
    }

    /**
     * A leave revocation cannot be interpreted as standalone interval
     * evidence and cannot simply be discarded: doing so would leave its
     * original leave interval fully effective. Until the ingestion model can
     * supply the exact original-leave key, the complete current revocation
     * set and the signed system-hour values to the effective-interval
     * resolver, calculation must fail closed.
     */
    private static void rejectUnresolvedLeaveRevocation(
            List<OaDocumentRow> documents) {
        boolean unresolved = documents.stream()
                .filter(OaDocumentRow::effectiveCandidate)
                .anyMatch(document -> "LEAVE_REVOCATION"
                        .equals(document.documentType()));
        if (unresolved) {
            throw new IllegalStateException(
                    UNRESOLVED_LEAVE_REVOCATION
                            + ": resolved leave fragments are required");
        }
    }

    private static IntervalEvidence toEvidence(OaDocumentRow doc) {
        EvidenceKind kind = mapDocumentType(doc.documentType());
        TimeInterval interval = new TimeInterval(
                doc.startInstant(),
                doc.endInstant());

        return new IntervalEvidence(
                "oa:" + doc.sourceBusinessKey(),
                kind,
                interval,
                doc.sourceBusinessKey(),
                doc.firstSubmittedAt(),
                doc.effectiveCandidate(),
                doc.overtimeType(),
                requiredLeaveType(kind, doc));
    }

    private static com.szsemicon.hr.attendance.domain.LeaveType requiredLeaveType(
            EvidenceKind kind, OaDocumentRow doc) {
        if (kind != EvidenceKind.LEAVE) {
            return null;
        }
        if (doc.leaveType() == null) {
            throw new IllegalArgumentException(
                    "LEAVE document has no classified leave type: "
                            + doc.sourceBusinessKey());
        }
        return doc.leaveType();
    }

    private static EvidenceKind mapDocumentType(String documentType) {
        return switch (documentType) {
            case "LEAVE" -> EvidenceKind.LEAVE;
            case "OVERTIME" -> EvidenceKind.OVERTIME;
            case "OUTING" -> EvidenceKind.OUTING;
            case "TIME_OFF" -> EvidenceKind.TIME_OFF;
            case "EXEMPT_PUNCH" -> EvidenceKind.EXEMPT_PUNCH;
            case "PUNCH_CORRECTION" -> EvidenceKind.PUNCH_CORRECTION;
            default -> throw new IllegalArgumentException(
                    "Unknown OA document type: " + documentType);
        };
    }
}

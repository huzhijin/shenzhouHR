package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.Instant;
import java.time.LocalDate;

public final class EvidenceRows {

    private EvidenceRows() {
    }

    /** Every raw/normalization history row is retained, including incomplete rows.
     * hasTargetReportFact also rejects any linked effective evidence: such events
     * can be consumed independently of the OA document's matched identity.
     */
    public record MissingOaKeyScopeRow(
            String sourceBusinessKey, String rawAttendanceFactId, String factKind,
            String normalizedAttendanceRecordId, String validationStatus, String recordKind,
            String oaAttendanceDocumentId, String documentType, Boolean documentIdentityConsistent,
            String matchStatus, String employeeId, String employeeCompanyId,
            String employeeNumber, String historicalEmployeeNumber, Boolean hasTargetReportFact) { }

    /** Latest-reader metadata supplements, but never replaces, the complete identity history. */
    public record MissingOaCurrentEligibilityRow(
            String sourceBusinessKey, String oaAttendanceDocumentId, String documentType,
            Instant createdAt, String activationDecision, String overtimeType,
            Boolean hasIndependentEvidenceLink, Boolean hasConflictingReportIdentity,
            Boolean hasApplicableReportExclusion, Boolean hasFutureDocument,
            Boolean hasConsumableOvertimeContext) { }

    /** Company refresh additionally requires a known source-status decision from the reader's SQL. */
    public record MissingOaCompanyCurrentEligibilityRow(
            String sourceBusinessKey, String oaAttendanceDocumentId, String documentType,
            Instant createdAt, String activationDecision, String overtimeType,
            Boolean hasIndependentEvidenceLink, Boolean hasConflictingReportIdentity,
            Boolean hasApplicableReportExclusion, Boolean hasFutureDocument,
            Boolean hasConsumableOvertimeContext, Boolean hasConsumableSourceStatus,
            Boolean hasOnlyExplicitDraftLeaveHistory, Boolean hasAnyReportFact) {
        public MissingOaCurrentEligibilityRow readerEligibility() {
            Boolean consumable = hasConsumableSourceStatus == null ? null
                    : Boolean.FALSE.equals(hasConsumableSourceStatus)
                            || Boolean.FALSE.equals(hasConsumableOvertimeContext) ? Boolean.FALSE
                            : hasConsumableOvertimeContext;
            return new MissingOaCurrentEligibilityRow(sourceBusinessKey, oaAttendanceDocumentId, documentType,
                    createdAt, activationDecision, overtimeType, hasIndependentEvidenceLink,
                    hasConflictingReportIdentity, hasApplicableReportExclusion, hasFutureDocument, consumable);
        }
    }

    /** Full history for an unparseable pending leave; never a claim about current ownership. */
    public record UnparseableLeaveScopeRow(
            String sourceBusinessKey, String rawAttendanceFactId, String factKind,
            String normalizedAttendanceRecordId, String validationStatus, String recordKind,
            String oaAttendanceDocumentId, String documentType, Boolean documentIdentityConsistent,
            String matchStatus, String employeeId, String employeeCompanyId,
            String employeeNumber, String historicalEmployeeNumber, Boolean hasTargetReportFact,
            String sourceVersion, String sourceStatus, String leaveSerial, Boolean hasRelatedRevocation) {
        public MissingOaKeyScopeRow identityHistory() {
            return new MissingOaKeyScopeRow(sourceBusinessKey, rawAttendanceFactId, factKind,
                    normalizedAttendanceRecordId, validationStatus, recordKind, oaAttendanceDocumentId,
                    documentType, documentIdentityConsistent, matchStatus, employeeId, employeeCompanyId,
                    employeeNumber, historicalEmployeeNumber, hasTargetReportFact);
        }
    }

    /** Last immutable observation of one source business key, including its safe prior attribution. */
    public record OaObservationRow(
            String rawAttendanceFactId, String sourceVersion, String canonicalPayloadDigest,
            Instant receivedAt, Long knowledgeRank, String oaAttendanceDocumentId,
            String documentType, String sourceStatus, Instant startInstant, Instant endInstant,
            String employeeId, String employmentPeriodId, String resolverSnapshotDigest,
            OvertimeType overtimeType, LeaveType leaveType,
            String leaveSerial, String originalLeaveSerial) {
    }

    public record RawFactRow(
            String rawAttendanceFactId,
            String attendanceSourceId,
            String companyId,
            String factKind,
            String sourceBusinessKey,
            String sourceVersion,
            String stableFingerprint,
            String sourceTimeText,
            String sourceTimeZone,
            Instant sourceInstant,
            Instant intervalStart,
            Instant intervalEnd,
            String canonicalPayloadDigest,
            String rawObjectRef,
            String requestId,
            Instant receivedAt,
            String createdBy,
            String verificationMethod,
            String locationSummary,
            String longitudeRaw,
            String latitudeRaw,
            String sourceCoordinateSystem,
            String coordinateValidationStatus,
            String coordinateConversionStatus,
            java.math.BigDecimal mapLongitude,
            java.math.BigDecimal mapLatitude,
            String mapCoordinateSystem) {

        public RawFactRow(
                String rawAttendanceFactId,
                String attendanceSourceId,
                String companyId,
                String factKind,
                String sourceBusinessKey,
                String sourceVersion,
                String stableFingerprint,
                String sourceTimeText,
                String sourceTimeZone,
                Instant sourceInstant,
                Instant intervalStart,
                Instant intervalEnd,
                String canonicalPayloadDigest,
                String rawObjectRef,
                String requestId,
                Instant receivedAt,
                String createdBy) {
            this(
                    rawAttendanceFactId,
                    attendanceSourceId,
                    companyId,
                    factKind,
                    sourceBusinessKey,
                    sourceVersion,
                    stableFingerprint,
                    sourceTimeText,
                    sourceTimeZone,
                    sourceInstant,
                    intervalStart,
                    intervalEnd,
                    canonicalPayloadDigest,
                    rawObjectRef,
                    requestId,
                    receivedAt,
                    createdBy,
                    null,
                    null,
                    null,
                    null,
                    "UNKNOWN",
                    "MISSING",
                    "NOT_APPLICABLE",
                    null,
                    null,
                    null);
        }
    }

    public record NormalizedRecordRow(
            String normalizedAttendanceRecordId,
            String rawAttendanceFactId,
            int normalizationRevision,
            String schemaVersion,
            String recordKind,
            String normalizedDirection,
            Instant pointInstant,
            Instant intervalStart,
            Instant intervalEnd,
            String validationStatus,
            String issueCode,
            String canonicalDigest,
            String supersedesNormalizedRecordId,
            Instant createdAt) {
    }

    public record MatchDecisionRow(
            String employeeMatchDecisionId,
            String normalizedAttendanceRecordId,
            String matchStatus,
            String matchReason,
            String employeeId,
            String employmentPeriodId,
            String devicePersonBindingId,
            String resolverSnapshotDigest,
            Instant createdAt) {
    }

    public record EffectiveEventRow(
            String effectiveAttendanceEventId,
            String companyId,
            String employeeId,
            String eventKind,
            String normalizedDirection,
            Instant pointInstant,
            Instant intervalStart,
            Instant intervalEnd,
            String canonicalDigest,
            Instant createdAt) {
    }

    public record LifecycleFactRow(
            String effectiveEventLifecycleFactId,
            String effectiveAttendanceEventId,
            String lifecycleType,
            String relatedEventId,
            String sourceReversalRecordId,
            Instant knowledgeAt,
            String actorId,
            String requestId,
            String changeReason,
            String factDigest) {
    }

    public record EvidenceLinkRow(
            String evidenceLinkId,
            String effectiveAttendanceEventId,
            String rawAttendanceFactId,
            String normalizedAttendanceRecordId,
            String employeeMatchDecisionId,
            String linkType,
            Instant createdAt) {
    }

    public record RecalculationIntentRow(
            String attendanceRecalculationIntentId,
            String companyId,
            String employeeId,
            LocalDate businessDate,
            String reasonCode,
            String effectiveAttendanceEventId,
            String resolverSnapshotDigest,
            String periodVersion,
            String requestId,
            String intentDigest,
            Instant createdAt) {
    }

    /**
     * One row to be inserted into {@code oa_attendance_document}.
     * The normalizedAttendanceRecordId is the FK into the normalized table
     * that must already exist before this row is inserted.
     */
    public record OaDocumentRow(
            String oaAttendanceDocumentId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String documentType,
            OvertimeType overtimeType,
            LeaveType leaveType,
            String sourceStatus,
            String normalizedAttendanceRecordId,
            long knowledgeRank,
            String supersedesDocumentId,
            Instant firstSubmittedAt,
            Instant approvedAt,
            Instant modifiedAt,
            Instant revokedAt,
            Instant createdAt,
            String leaveSerial,
            String originalLeaveSerial) {
    }

    /**
     * Trusted classification context for one approved OA overtime document.
     * The runtime-contract id must resolve to a real published revision for
     * the same attendance source before this row is constructed.
     */
    public record OaDocumentContextRow(
            String oaAttendanceDocumentContextId,
            String oaAttendanceDocumentId,
            String oaRuntimeContractRevisionId,
            String attendanceGroupRevisionId,
            String activationDecision,
            String rawStatusValue,
            String overtimeTreatment,
            OvertimeType overtimeType,
            int recognizedWorkMinutes,
            int payrollCreditMinutes,
            int timeOffCreditMinutes,
            String authorizedContextJson,
            String contextDigest,
            Instant createdAt) {
    }

    public record ReplayStateRow(
            String rawAttendanceFactId,
            String normalizedAttendanceRecordId,
            Integer normalizationRevision,
            String validationStatus,
            String issueCode,
            String matchStatus,
            String matchReason,
            String matchEmployeeId,
            String employmentPeriodId,
            String effectiveAttendanceEventId,
            String eventEmployeeId) {
    }

    public record EvidenceTraceRow(
            String effectiveAttendanceEventId,
            String lifecycleType,
            Instant knowledgeAt,
            String evidenceLinkId,
            String rawAttendanceFactId,
            String attendanceSourceId,
            String factKind,
            String sourceBusinessKey,
            String sourceVersion,
            String canonicalPayloadDigest,
            String normalizedAttendanceRecordId,
            String normalizationStatus,
            String employeeMatchDecisionId,
            String matchStatus,
            String matchReason) {
    }
}

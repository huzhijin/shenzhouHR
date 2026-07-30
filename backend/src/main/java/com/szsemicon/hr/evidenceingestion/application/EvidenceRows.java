package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.time.LocalDate;

public final class EvidenceRows {

    private EvidenceRows() {
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
            String createdBy) {
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

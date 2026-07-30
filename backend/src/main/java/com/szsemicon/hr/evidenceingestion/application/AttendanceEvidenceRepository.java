package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface AttendanceEvidenceRepository {

    void lockSubject(String companyId, String employeeId, Instant touchedAt);

    EvidenceRows.RawFactRow findRawBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion);

    EvidenceRows.RawFactRow findRawByFingerprint(
            String sourceId,
            String stableFingerprint);

    void insertRawFact(EvidenceRows.RawFactRow row);

    void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row);

    void insertMatchDecision(EvidenceRows.MatchDecisionRow row);

    List<EvidenceRows.EffectiveEventRow> findExactEvents(
            String companyId,
            String employeeId,
            Instant pointInstant,
            String normalizedDirection);

    List<EvidenceRows.EffectiveEventRow> findNearEvents(
            String companyId,
            String employeeId,
            Instant windowStart,
            Instant windowEnd,
            String normalizedDirection);

    void insertEffectiveEvent(EvidenceRows.EffectiveEventRow row);

    void insertLifecycleFact(EvidenceRows.LifecycleFactRow row);

    void insertEvidenceLink(EvidenceRows.EvidenceLinkRow row);

    void insertRecalculationIntent(EvidenceRows.RecalculationIntentRow row);

    List<EvidenceRows.EvidenceTraceRow> evidenceTrace(
            String companyId,
            String eventId);

    record AffectedDate(
            String companyId,
            String employeeId,
            LocalDate businessDate) {
    }
}

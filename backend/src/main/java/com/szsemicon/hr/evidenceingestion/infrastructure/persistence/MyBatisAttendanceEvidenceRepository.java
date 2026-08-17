package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.AttendanceEvidenceRepository;
import com.szsemicon.hr.evidenceingestion.application.EvidenceRows;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAttendanceEvidenceRepository
        implements AttendanceEvidenceRepository {

    private final AttendanceEvidenceMapper mapper;

    public MyBatisAttendanceEvidenceRepository(AttendanceEvidenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockSubject(String companyId, String employeeId, Instant touchedAt) {
        mapper.insertSubjectLock(companyId, employeeId, touchedAt);
        mapper.lockSubject(companyId, employeeId);
    }

    @Override
    public EvidenceRows.RawFactRow findRawBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        return mapper.findRawBySourceIdentity(
                sourceId, sourceBusinessKey, sourceVersion);
    }

    @Override
    public EvidenceRows.RawFactRow findRawByFingerprint(
            String sourceId,
            String stableFingerprint) {
        return mapper.findRawByFingerprint(sourceId, stableFingerprint);
    }

    @Override
    public void insertRawFact(EvidenceRows.RawFactRow row) {
        mapper.insertRawFact(row);
    }

    @Override
    public void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row) {
        mapper.insertNormalizedRecord(row);
    }

    @Override
    public void insertMatchDecision(EvidenceRows.MatchDecisionRow row) {
        mapper.insertMatchDecision(row);
    }

    @Override
    public List<EvidenceRows.EffectiveEventRow> findExactEvents(
            String companyId,
            String employeeId,
            Instant pointInstant,
            String normalizedDirection) {
        return mapper.findExactEvents(
                companyId, employeeId, pointInstant, normalizedDirection);
    }

    @Override
    public List<EvidenceRows.EffectiveEventRow> findNearEvents(
            String companyId,
            String employeeId,
            Instant windowStart,
            Instant windowEnd,
            String normalizedDirection) {
        return mapper.findNearEvents(
                companyId,
                employeeId,
                windowStart,
                windowEnd,
                normalizedDirection);
    }

    @Override
    public void insertEffectiveEvent(EvidenceRows.EffectiveEventRow row) {
        mapper.insertEffectiveEvent(row);
    }

    @Override
    public void insertLifecycleFact(EvidenceRows.LifecycleFactRow row) {
        mapper.insertLifecycleFact(row);
    }

    @Override
    public void insertEvidenceLink(EvidenceRows.EvidenceLinkRow row) {
        mapper.insertEvidenceLink(row);
    }

    @Override
    public void insertRecalculationIntent(EvidenceRows.RecalculationIntentRow row) {
        mapper.insertRecalculationIntent(row);
    }

    @Override
    public List<EvidenceRows.EvidenceTraceRow> evidenceTrace(
            String companyId,
            String eventId) {
        return List.copyOf(mapper.evidenceTrace(companyId, eventId));
    }

    @Override
    public EvidenceRows.RawFactRow findRawOaBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        return mapper.findRawOaBySourceIdentity(sourceId, sourceBusinessKey, sourceVersion);
    }

    @Override
    public String findLatestPublishedOaRuntimeContractRevisionId(
            String sourceId) {
        return mapper.findLatestPublishedOaRuntimeContractRevisionId(
                sourceId);
    }

    @Override
    public void insertOaAttendanceDocument(EvidenceRows.OaDocumentRow row) {
        mapper.insertOaAttendanceDocument(row);
    }

    @Override
    public void insertOaAttendanceDocumentContext(
            EvidenceRows.OaDocumentContextRow row) {
        mapper.insertOaAttendanceDocumentContext(row);
    }
}

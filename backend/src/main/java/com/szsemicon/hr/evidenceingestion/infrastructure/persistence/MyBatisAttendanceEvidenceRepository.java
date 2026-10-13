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
    public EvidenceRows.OaObservationRow findLatestOaObservation(
            String sourceId, String sourceBusinessKey, boolean resolvedOnly) {
        return mapper.findLatestOaObservation(sourceId, sourceBusinessKey, resolvedOnly);
    }

    @Override
    public List<String> findOaBusinessKeysOverlapping(
            String sourceId, Instant fromInclusive, Instant toExclusive) {
        return mapper.findOaBusinessKeysOverlapping(sourceId, fromInclusive, toExclusive);
    }

    @Override
    public List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyScopeRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys) {
        if (sourceBusinessKeys == null || sourceBusinessKeys.isEmpty()) return List.of();
        return mapper.findMissingOaKeyScopeRows(sourceId, targetCompanyId, sourceBusinessKeys);
    }

    @Override
    public List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyEmployeeScopeRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys) {
        if (sourceBusinessKeys == null || sourceBusinessKeys.isEmpty() || invalidEmployeeScope(targetEmployeeIds)) {
            return List.of();
        }
        return mapper.findMissingOaKeyEmployeeScopeRows(sourceId, targetCompanyId, targetEmployeeIds, sourceBusinessKeys);
    }

    @Override
    public List<String> findReportEmployeeNumbers(String companyId, List<String> targetEmployeeIds) {
        if (invalidEmployeeScope(targetEmployeeIds)) return List.of();
        return mapper.findReportEmployeeNumbers(companyId, targetEmployeeIds);
    }

    private static boolean invalidEmployeeScope(List<String> employeeIds) {
        return employeeIds == null || employeeIds.isEmpty()
                || employeeIds.stream().anyMatch(id -> id == null || id.isBlank());
    }

    @Override
    public List<EvidenceRows.MissingOaCurrentEligibilityRow> findMissingOaCurrentEligibilityRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys, Instant dataAsOf) {
        if (dataAsOf == null || sourceBusinessKeys == null || sourceBusinessKeys.isEmpty()
                || invalidEmployeeScope(targetEmployeeIds)) return List.of();
        return mapper.findMissingOaCurrentEligibilityRows(sourceId, targetCompanyId,
                targetEmployeeIds, sourceBusinessKeys, dataAsOf);
    }

    @Override
    public List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> findMissingOaCompanyCurrentEligibilityRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys, Instant dataAsOf) {
        if (sourceId == null || sourceId.isBlank() || targetCompanyId == null || targetCompanyId.isBlank()
                || dataAsOf == null || sourceBusinessKeys == null || sourceBusinessKeys.isEmpty()) return List.of();
        return mapper.findMissingOaCompanyCurrentEligibilityRows(sourceId, targetCompanyId, sourceBusinessKeys, dataAsOf);
    }

    @Override
    public List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveEmployeeScopeRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys) {
        if (sourceBusinessKeys == null || sourceBusinessKeys.isEmpty() || invalidEmployeeScope(targetEmployeeIds)) {
            return List.of();
        }
        return mapper.findUnparseableLeaveEmployeeScopeRows(sourceId, targetCompanyId,
                targetEmployeeIds, sourceBusinessKeys);
    }

    @Override
    public List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveScopeRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys) {
        if (sourceBusinessKeys == null || sourceBusinessKeys.isEmpty()) return List.of();
        return mapper.findUnparseableLeaveScopeRows(sourceId, targetCompanyId, sourceBusinessKeys);
    }

    @Override
    public List<String> findCompanyEmployeeNumbers(String companyId) {
        return mapper.findCompanyEmployeeNumbers(companyId);
    }

    @Override
    public EvidenceRows.ReplayStateRow findReplayStateBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        return mapper.findReplayStateBySourceIdentity(
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
    public void insertRawFacts(List<EvidenceRows.RawFactRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        mapper.insertRawFacts(rows);
    }

    @Override
    public void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row) {
        mapper.insertNormalizedRecord(row);
    }

    @Override
    public void insertNormalizedRecords(
            List<EvidenceRows.NormalizedRecordRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        mapper.insertNormalizedRecords(rows);
    }

    @Override
    public void insertMatchDecision(EvidenceRows.MatchDecisionRow row) {
        mapper.insertMatchDecision(row);
    }

    @Override
    public void insertMatchDecisions(List<EvidenceRows.MatchDecisionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        mapper.insertMatchDecisions(rows);
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
    public boolean hasEvidenceLink(
            String effectiveAttendanceEventId, String rawAttendanceFactId) {
        return mapper.countEvidenceLink(
                effectiveAttendanceEventId, rawAttendanceFactId) > 0;
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
    public String findOaAttendanceDocumentId(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        return mapper.findOaAttendanceDocumentId(
                sourceId, sourceBusinessKey, sourceVersion);
    }

    @Override
    public boolean hasOaAttendanceDocumentContext(
            String oaAttendanceDocumentId) {
        return mapper.countOaAttendanceDocumentContext(
                oaAttendanceDocumentId) > 0;
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

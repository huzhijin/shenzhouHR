package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.EvidenceRows;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceEvidenceMapper {

    void insertSubjectLock(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("touchedAt") Instant touchedAt);

    void lockSubject(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId);

    EvidenceRows.RawFactRow findRawBySourceIdentity(
            @Param("sourceId") String sourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    EvidenceRows.OaObservationRow findLatestOaObservation(
            @Param("sourceId") String sourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("resolvedOnly") boolean resolvedOnly);

    List<String> findOaBusinessKeysOverlapping(
            @Param("sourceId") String sourceId,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive);

    List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyScopeRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys);

    List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyEmployeeScopeRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("targetEmployeeIds") List<String> targetEmployeeIds,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys);

    List<String> findReportEmployeeNumbers(
            @Param("companyId") String companyId,
            @Param("targetEmployeeIds") List<String> targetEmployeeIds);

    List<EvidenceRows.MissingOaCurrentEligibilityRow> findMissingOaCurrentEligibilityRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("targetEmployeeIds") List<String> targetEmployeeIds,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys,
            @Param("dataAsOf") Instant dataAsOf);

    List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> findMissingOaCompanyCurrentEligibilityRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys,
            @Param("dataAsOf") Instant dataAsOf);

    List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveScopeRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys);

    List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveEmployeeScopeRows(
            @Param("sourceId") String sourceId,
            @Param("targetCompanyId") String targetCompanyId,
            @Param("targetEmployeeIds") List<String> targetEmployeeIds,
            @Param("sourceBusinessKeys") List<String> sourceBusinessKeys);

    List<String> findCompanyEmployeeNumbers(@Param("companyId") String companyId);

    EvidenceRows.ReplayStateRow findReplayStateBySourceIdentity(
            @Param("sourceId") String sourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    EvidenceRows.RawFactRow findRawByFingerprint(
            @Param("sourceId") String sourceId,
            @Param("stableFingerprint") String stableFingerprint);

    void insertRawFact(EvidenceRows.RawFactRow row);

    int insertRawFacts(@Param("rows") List<EvidenceRows.RawFactRow> rows);

    void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row);

    int insertNormalizedRecords(
            @Param("rows") List<EvidenceRows.NormalizedRecordRow> rows);

    void insertMatchDecision(EvidenceRows.MatchDecisionRow row);

    int insertMatchDecisions(
            @Param("rows") List<EvidenceRows.MatchDecisionRow> rows);

    List<EvidenceRows.EffectiveEventRow> findExactEvents(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("pointInstant") Instant pointInstant,
            @Param("normalizedDirection") String normalizedDirection);

    List<EvidenceRows.EffectiveEventRow> findNearEvents(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("normalizedDirection") String normalizedDirection);

    void insertEffectiveEvent(EvidenceRows.EffectiveEventRow row);

    void insertLifecycleFact(EvidenceRows.LifecycleFactRow row);

    void insertEvidenceLink(EvidenceRows.EvidenceLinkRow row);

    int countEvidenceLink(
            @Param("effectiveAttendanceEventId") String effectiveAttendanceEventId,
            @Param("rawAttendanceFactId") String rawAttendanceFactId);

    void insertRecalculationIntent(EvidenceRows.RecalculationIntentRow row);

    List<EvidenceRows.EvidenceTraceRow> evidenceTrace(
            @Param("companyId") String companyId,
            @Param("eventId") String eventId);

    /** Check whether an OA document with this source identity already exists. */
    EvidenceRows.RawFactRow findRawOaBySourceIdentity(
            @Param("sourceId") String sourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    String findLatestPublishedOaRuntimeContractRevisionId(
            @Param("sourceId") String sourceId);

    String findOaAttendanceDocumentId(
            @Param("sourceId") String sourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    int countOaAttendanceDocumentContext(
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId);

    /** Insert the oa_attendance_document row after raw + normalized are persisted. */
    void insertOaAttendanceDocument(EvidenceRows.OaDocumentRow row);

    /** Insert the classification context in the same transaction as its OA document. */
    void insertOaAttendanceDocumentContext(
            EvidenceRows.OaDocumentContextRow row);
}

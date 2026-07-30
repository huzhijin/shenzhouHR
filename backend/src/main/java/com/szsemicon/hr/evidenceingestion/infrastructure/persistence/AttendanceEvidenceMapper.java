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

    EvidenceRows.RawFactRow findRawByFingerprint(
            @Param("sourceId") String sourceId,
            @Param("stableFingerprint") String stableFingerprint);

    void insertRawFact(EvidenceRows.RawFactRow row);

    void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row);

    void insertMatchDecision(EvidenceRows.MatchDecisionRow row);

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

    void insertRecalculationIntent(EvidenceRows.RecalculationIntentRow row);

    List<EvidenceRows.EvidenceTraceRow> evidenceTrace(
            @Param("companyId") String companyId,
            @Param("eventId") String eventId);
}

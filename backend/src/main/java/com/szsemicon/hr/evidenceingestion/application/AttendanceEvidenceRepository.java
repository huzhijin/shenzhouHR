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

    default EvidenceRows.OaObservationRow findLatestOaObservation(
            String sourceId, String sourceBusinessKey, boolean resolvedOnly) {
        return null;
    }

    /** Historical window identities plus their same-source, matched-person leave amendments. */
    default List<String> findOaBusinessKeysOverlapping(
            String sourceId, Instant fromInclusive, Instant toExclusive) {
        return List.of();
    }

    /** Fail-closed optional capability; used only for already-missing report dependencies. */
    default List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyScopeRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys) {
        return List.of();
    }

    /** Same complete history, with old report dependencies scoped to explicitly requested employees. */
    default List<EvidenceRows.MissingOaKeyScopeRow> findMissingOaKeyEmployeeScopeRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys) {
        return List.of();
    }

    /** All current/historical aliases; no result unless every requested identity belongs to this company. */
    default List<String> findReportEmployeeNumbers(String companyId, List<String> targetEmployeeIds) {
        return List.of();
    }

    /** Current reader precedence plus exact, knowledge-bounded report exclusions; empty is not proof. */
    default List<EvidenceRows.MissingOaCurrentEligibilityRow> findMissingOaCurrentEligibilityRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys, Instant dataAsOf) {
        return List.of();
    }

    /** Explicit whole-company capability, never inferred from an empty employee selection. */
    default List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> findMissingOaCompanyCurrentEligibilityRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys, Instant dataAsOf) {
        return List.of();
    }

    default List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveEmployeeScopeRows(
            String sourceId, String targetCompanyId, List<String> targetEmployeeIds,
            List<String> sourceBusinessKeys) {
        return List.of();
    }

    default List<EvidenceRows.UnparseableLeaveScopeRow> findUnparseableLeaveScopeRows(
            String sourceId, String targetCompanyId, List<String> sourceBusinessKeys) {
        return List.of();
    }

    default List<String> findCompanyEmployeeNumbers(String companyId) {
        return List.of();
    }

    EvidenceRows.ReplayStateRow findReplayStateBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion);

    EvidenceRows.RawFactRow findRawByFingerprint(
            String sourceId,
            String stableFingerprint);

    void insertRawFact(EvidenceRows.RawFactRow row);

    default void insertRawFacts(List<EvidenceRows.RawFactRow> rows) {
        for (EvidenceRows.RawFactRow row : rows) {
            insertRawFact(row);
        }
    }

    void insertNormalizedRecord(EvidenceRows.NormalizedRecordRow row);

    default void insertNormalizedRecords(
            List<EvidenceRows.NormalizedRecordRow> rows) {
        for (EvidenceRows.NormalizedRecordRow row : rows) {
            insertNormalizedRecord(row);
        }
    }

    void insertMatchDecision(EvidenceRows.MatchDecisionRow row);

    default void insertMatchDecisions(List<EvidenceRows.MatchDecisionRow> rows) {
        for (EvidenceRows.MatchDecisionRow row : rows) {
            insertMatchDecision(row);
        }
    }

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

    boolean hasEvidenceLink(String effectiveAttendanceEventId, String rawAttendanceFactId);

    void insertRecalculationIntent(EvidenceRows.RecalculationIntentRow row);

    List<EvidenceRows.EvidenceTraceRow> evidenceTrace(
            String companyId,
            String eventId);

    EvidenceRows.RawFactRow findRawOaBySourceIdentity(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion);

    String findLatestPublishedOaRuntimeContractRevisionId(String sourceId);

    String findOaAttendanceDocumentId(
            String sourceId,
            String sourceBusinessKey,
            String sourceVersion);

    boolean hasOaAttendanceDocumentContext(String oaAttendanceDocumentId);

    void insertOaAttendanceDocument(EvidenceRows.OaDocumentRow row);

    void insertOaAttendanceDocumentContext(
            EvidenceRows.OaDocumentContextRow row);

    record AffectedDate(
            String companyId,
            String employeeId,
            LocalDate businessDate) {
    }
}

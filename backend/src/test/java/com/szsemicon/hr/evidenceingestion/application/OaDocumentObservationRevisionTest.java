package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.*;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OaDocumentObservationRevisionTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final Instant START = Instant.parse("2026-09-09T00:30:00Z");
    private static final Instant END = START.plusSeconds(10800);
    private final AttendanceSourceSyncRepository sync = mock(AttendanceSourceSyncRepository.class);
    private final AttendanceEvidenceRepository evidence = mock(AttendanceEvidenceRepository.class);
    private final EmployeeEmploymentResolverPort resolver = mock(EmployeeEmploymentResolverPort.class);
    private final Map<String, EvidenceRows.RawFactRow> raw = new LinkedHashMap<>();
    private final Map<String, EvidenceRows.NormalizedRecordRow> normalized = new HashMap<>();
    private final Map<String, EvidenceRows.MatchDecisionRow> matched = new HashMap<>();
    private final Map<String, EvidenceRows.OaDocumentRow> documents = new LinkedHashMap<>();
    private final OaDocumentPageTransaction transaction = new OaDocumentPageTransaction(sync, evidence, resolver,
            mock(OaOrgMemberDirectoryPort.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void repository() {
        var state = new AttendanceSourceSyncModels.PageState("job", "source", "company", "RUNNING", 0, "cursor", 7L);
        when(sync.lockOaPageForCommit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(state);
        when(sync.lockSystemOaPageForCommit("job", "source")).thenReturn(state);
        when(resolver.resolveByEmployeeNumber(eq("company"), eq("E1"), any())).thenReturn(List.of(
                new EmployeeEmploymentResolverPort.Resolution("employee", "assignment", "d".repeat(64))));
        when(evidence.findRawBySourceIdentity(anyString(), anyString(), anyString())).thenAnswer(call -> raw.get(call.getArgument(2)));
        when(evidence.findOaAttendanceDocumentId(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var doc = documents.get(call.getArgument(2)); return doc == null ? null : doc.oaAttendanceDocumentId();
        });
        when(evidence.findLatestOaObservation(anyString(), anyString(), anyBoolean())).thenAnswer(call -> latest(call.getArgument(1), call.getArgument(2)));
        doAnswer(call -> { var row = call.<EvidenceRows.RawFactRow>getArgument(0); raw.put(row.sourceVersion(), row); return null; }).when(evidence).insertRawFact(any());
        doAnswer(call -> { var row = call.<EvidenceRows.NormalizedRecordRow>getArgument(0); normalized.put(row.normalizedAttendanceRecordId(), row); return null; }).when(evidence).insertNormalizedRecord(any());
        doAnswer(call -> { var row = call.<EvidenceRows.MatchDecisionRow>getArgument(0); matched.put(row.normalizedAttendanceRecordId(), row); return null; }).when(evidence).insertMatchDecision(any());
        doAnswer(call -> { var row = call.<EvidenceRows.OaDocumentRow>getArgument(0); documents.put(row.sourceVersion(), row); return null; }).when(evidence).insertOaAttendanceDocument(any());
    }

    @Test
    void unchangedTimestampEditsAndContentReversionAppendVersionsAndRepeatedCurrentContentIsIdempotent() {
        var original = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1");
        var amended = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END.minusSeconds(3600), "E1", LeaveType.PERSONAL, "L1");
        assertThat(refresh(original).changedCount()).isEqualTo(1);
        assertThat(refresh(amended).changedCount()).isEqualTo(1);
        var reverted = refresh(original);
        assertThat(reverted.changedCount()).isEqualTo(1);
        assertThat(refresh(original).changedCount()).isZero();
        assertThat(raw).hasSize(3);
        assertThat(documents.values()).extracting(EvidenceRows.OaDocumentRow::knowledgeRank)
                .containsExactly(NOW.toEpochMilli(), NOW.toEpochMilli() + 1, NOW.toEpochMilli() + 2);
        assertThat(latest("LEAVE:1", false).endInstant()).isEqualTo(END);
        assertThat(reverted.evidenceAsOf()).isAfter(NOW);
        verify(sync, times(3)).advanceWatermark(eq("source"), eq(7L), eq("cursor"), anyString(), any());
    }

    @Test
    void revokedKnownIdentityWithBlankFieldsKeepsPriorAttributionOnlyToInvalidateOldApproval() {
        refresh(record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1"));
        assertThat(refresh(record(DocumentType.LEAVE, SourceStatus.REVOKED, null, null, null, null, null)).changedCount()).isEqualTo(1);
        var current = latest("LEAVE:1", false);
        assertThat(current.sourceStatus()).isEqualTo("REVOKED");
        assertThat(current.employeeId()).isEqualTo("employee");
        assertThat(current.startInstant()).isEqualTo(START);
        assertThat(current.endInstant()).isEqualTo(END);
        assertThat(documents.values().iterator().next().sourceStatus()).isEqualTo("APPROVED");
        assertThat(refresh(record(DocumentType.LEAVE, SourceStatus.REVOKED, null, null, null, null, null)).changedCount()).isZero();
    }

    @Test
    void unsafeChangeToKnownApprovedDocumentBlocksPublicationButUnrelatedNewUnmatchedRowCanStayQuarantined() {
        var original = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1");
        refresh(original);
        assertThatThrownBy(() -> refresh(record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "missing", LeaveType.PERSONAL, "L1")))
                .isInstanceOf(AttendanceSourceSyncFailure.class).hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
        assertThatThrownBy(() -> refresh(original.withSourceVersion(null)))
                .isInstanceOf(AttendanceSourceSyncFailure.class).hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
        assertThat(raw).hasSize(1);
        raw.clear(); documents.clear(); normalized.clear(); matched.clear();
        var unrelated = refresh(record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "missing", LeaveType.PERSONAL, "L1"));
        assertThat(unrelated.quarantinedCount()).isEqualTo(1);
        assertThat(documents).isEmpty();
    }

    @Test
    void approvedNewRevocationWithMissingActualEndBlocksInsteadOfPretendingAllLeaveWasCancelled() {
        assertThatThrownBy(() -> refresh(record(DocumentType.LEAVE_REVOCATION, SourceStatus.APPROVED, START, null, "E1", null, "L1")))
                .isInstanceOf(AttendanceSourceSyncFailure.class).hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
        assertThat(raw).isEmpty();
    }

    @Test
    void revocationActualRangeAndOriginalSerialAreBothVersioned() {
        var threeHours = record(DocumentType.LEAVE_REVOCATION, SourceStatus.APPROVED, START, END, "E1", null, "L1");
        refresh(threeHours);
        refresh(record(DocumentType.LEAVE_REVOCATION, SourceStatus.APPROVED, START, END.minusSeconds(3600), "E1", null, "L1"));
        refresh(record(DocumentType.LEAVE_REVOCATION, SourceStatus.APPROVED, START, END.minusSeconds(3600), "E1", null, "L2"));
        assertThat(raw).hasSize(3);
        assertThat(latest("LEAVE_REVOCATION:1", false).originalLeaveSerial()).isEqualTo("L2");
        assertThat(latest("LEAVE_REVOCATION:1", false).endInstant()).isEqualTo(END.minusSeconds(3600));
    }

    @Test
    void refreshRequiresManualOrSystemSourceLockBeforeAnyEvidenceWrite() {
        when(sync.lockOaPageForCommit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(null);
        var record = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1");
        assertThatThrownBy(() -> refresh(record)).isInstanceOf(AttendanceSourceSyncFailure.class);
        assertThat(raw).isEmpty();
        transaction.rematchRecords(job(), "SYSTEM", "request", CapabilityCodes.ATTENDANCE_SOURCE_RUN, List.of(record));
        verify(sync).lockSystemOaPageForCommit("job", "source");
        assertThat(raw).hasSize(1);
    }

    @Test
    void rereadingLegacyPendingSeptemberOvertimeRepairsMissingClassificationWithoutApprovingIt() {
        Instant start = Instant.parse("2026-09-30T10:30:00Z");
        Instant end = Instant.parse("2026-09-30T12:00:00Z");
        String version = "2026-09-30T18:12:00:state=0:Asia/Shanghai";
        var legacy = new OaDocumentRecord("OVERTIME:1", version, "member", "E1",
                DocumentType.OVERTIME, SourceStatus.UNKNOWN, start, end, "Asia/Shanghai",
                NOW, null, null, null, "OA_OVERTIME_BATCH", true, OvertimeType.PAID);
        assertThat(refresh(legacy).changedCount()).isEqualTo(1);
        verify(evidence, never()).insertOaAttendanceDocumentContext(any());
        String oldDocument = latest("OVERTIME:1", false).oaAttendanceDocumentId();

        Set<String> contextualized = new HashSet<>();
        when(evidence.findLatestPublishedOaRuntimeContractRevisionId("source")).thenReturn("contract");
        when(evidence.hasOaAttendanceDocumentContext(anyString()))
                .thenAnswer(call -> contextualized.contains(call.getArgument(0)));
        doAnswer(call -> {
            var context = call.<EvidenceRows.OaDocumentContextRow>getArgument(0);
            assertThat(context.activationDecision()).isEqualTo("ACTIVATED");
            assertThat(context.rawStatusValue()).isEqualTo("PENDING");
            assertThat(context.overtimeType()).isEqualTo(OvertimeType.PAID);
            contextualized.add(context.oaAttendanceDocumentId());
            return null;
        }).when(evidence).insertOaAttendanceDocumentContext(any());
        var current = new OaDocumentRecord("OVERTIME:1", version, "member", "E1",
                DocumentType.OVERTIME, SourceStatus.PENDING, start, end, "Asia/Shanghai",
                NOW, null, null, null, "OA_OVERTIME_BATCH", true, OvertimeType.PAID);

        assertThat(refresh(current).changedCount()).isEqualTo(1);
        var repaired = latest("OVERTIME:1", false);
        assertThat(repaired.sourceStatus()).isEqualTo("PENDING");
        assertThat(repaired.oaAttendanceDocumentId()).isNotEqualTo(oldDocument);
        assertThat(contextualized).containsExactly(repaired.oaAttendanceDocumentId());
        assertThat(documents.values()).extracting(EvidenceRows.OaDocumentRow::sourceStatus)
                .containsExactly("UNKNOWN", "PENDING");
        assertThat(refresh(current).changedCount()).isZero();
        verify(evidence, times(1)).insertOaAttendanceDocumentContext(any());
    }

    @Test
    void preflightRejectsUnsafeGeometryBeforeAnyNewRawWrite() {
        refresh(record(DocumentType.LEAVE, SourceStatus.PENDING, START, END, "E1", LeaveType.PERSONAL, "L1"));
        clearInvocations(evidence, sync);
        var invalid = record(DocumentType.LEAVE, SourceStatus.PENDING, START, null, null, null, "L1");
        assertThatThrownBy(() -> transaction.preflightUnsafeChanges("source", List.of(invalid)))
                .isInstanceOf(AttendanceSourceSyncFailure.class).hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
        verify(evidence, never()).insertRawFact(any());
        verify(sync, never()).lockOaPageForCommit(any(), any(), any(), any(), any());
        assertThat(raw).hasSize(1);
    }

    @Test
    void preflightPreservesNonEffectiveTombstonesWithMissingGeometryAndClassification() {
        refresh(record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1"));
        var revoked = record(DocumentType.LEAVE, SourceStatus.REVOKED, null, null, null, null, null);
        transaction.preflightUnsafeChanges("source", List.of(revoked));
        assertThat(refresh(revoked).changedCount()).isEqualTo(1);
        assertThat(latest("LEAVE:1", true).sourceStatus()).isEqualTo("REVOKED");
        assertThat(latest("LEAVE:1", true).startInstant()).isEqualTo(START);
    }

    @Test
    void preflightDistinguishesNewApprovedFromPendingMalformedRevocation() {
        var pending = record(DocumentType.LEAVE_REVOCATION, SourceStatus.PENDING, START, null, "E1", null, "L1");
        transaction.preflightUnsafeChanges("source", List.of(pending));
        assertThat(refresh(pending).quarantinedCount()).isEqualTo(1);
        var approved = record(DocumentType.LEAVE_REVOCATION, SourceStatus.APPROVED, START, null, "E1", null, "L1");
        assertThatThrownBy(() -> transaction.preflightUnsafeChanges("source", List.of(approved)))
                .isInstanceOf(AttendanceSourceSyncFailure.class).hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
        assertThatThrownBy(() -> refresh(approved)).isInstanceOf(AttendanceSourceSyncFailure.class);
    }

    @Test
    void validRowsDoNotTriggerPreflightHistoryReadsAndRuntimeStillProtectsUnsafeIdentityChanges() {
        var original = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "E1", LeaveType.PERSONAL, "L1");
        transaction.preflightUnsafeChanges("source", List.of(original));
        verify(evidence, never()).findLatestOaObservation(any(), any(), anyBoolean());
        refresh(original);
        var unmatched = record(DocumentType.LEAVE, SourceStatus.APPROVED, START, END, "missing", LeaveType.PERSONAL, "L1");
        transaction.preflightUnsafeChanges("source", List.of(unmatched));
        assertThatThrownBy(() -> refresh(unmatched)).isInstanceOf(AttendanceSourceSyncFailure.class)
                .hasMessageContaining("OA_REPORT_SOURCE_QUARANTINED");
    }

    @Test
    void preflightRejectsInvalidClassificationButDoesNotInventARevocation() {
        refresh(record(DocumentType.LEAVE, SourceStatus.PENDING, START, END, "E1", LeaveType.PERSONAL, "L1"));
        var invalid = record(DocumentType.LEAVE, SourceStatus.PENDING, START, END, "E1", null, "L1");
        assertThatThrownBy(() -> transaction.preflightUnsafeChanges("source", List.of(invalid)))
                .isInstanceOf(AttendanceSourceSyncFailure.class);
        assertThat(latest("LEAVE:1", true).sourceStatus()).isEqualTo("PENDING");
    }

    private OaDocumentPageTransaction.PageCommitResult refresh(OaDocumentRecord record) {
        return transaction.rematchRecords(job(), "principal", "request", CapabilityCodes.ATTENDANCE_SOURCE_RUN, List.of(record));
    }
    private SourceJobStart job() { return new SourceJobStart("job", "source", "company", "OA", "OA_MYSQL", "Asia/Shanghai", 200, 1000, 0, "cursor"); }
    private OaDocumentRecord record(DocumentType type, SourceStatus status, Instant start, Instant end, String number, LeaveType leave, String serial) {
        return new OaDocumentRecord(type.name() + ":1", "2026-09-01T12:00:00:state=3:Asia/Shanghai", "member", number,
                type, status, start, end, "Asia/Shanghai", NOW, NOW, null, null, "batch", true,
                null, leave, type == DocumentType.LEAVE ? serial : "R1", type == DocumentType.LEAVE_REVOCATION ? serial : null);
    }
    private EvidenceRows.OaObservationRow latest(String key, boolean resolvedOnly) {
        return raw.values().stream().filter(value -> key.equals(value.sourceBusinessKey()))
                .filter(value -> !resolvedOnly || documents.containsKey(value.sourceVersion()))
                .max(Comparator.comparing(EvidenceRows.RawFactRow::receivedAt)).map(value -> {
                    var doc = documents.get(value.sourceVersion());
                    var match = doc == null ? null : matched.get(doc.normalizedAttendanceRecordId());
                    return new EvidenceRows.OaObservationRow(value.rawAttendanceFactId(), value.sourceVersion(), value.canonicalPayloadDigest(), value.receivedAt(),
                            doc == null ? null : doc.knowledgeRank(), doc == null ? null : doc.oaAttendanceDocumentId(),
                            doc == null ? null : doc.documentType(), doc == null ? null : doc.sourceStatus(),
                            value.intervalStart() == null ? value.sourceInstant() : value.intervalStart(),
                            value.intervalEnd() == null ? value.sourceInstant() : value.intervalEnd(),
                            match == null ? null : match.employeeId(), match == null ? null : match.employmentPeriodId(),
                            match == null ? null : match.resolverSnapshotDigest(), doc == null ? null : doc.overtimeType(),
                            doc == null ? null : doc.leaveType(), doc == null ? null : doc.leaveSerial(), doc == null ? null : doc.originalLeaveSerial());
                }).orElse(null);
    }
}

package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class OaDocumentPageTransactionOvertimeTest {

    private static final Instant NOW =
            Instant.parse("2026-08-17T02:00:00Z");
    private static final Instant START =
            Instant.parse("2026-08-15T10:00:00Z");
    private static final Instant END =
            Instant.parse("2026-08-15T12:30:00Z");

    private final AttendanceSourceSyncRepository syncRepository =
            mock(AttendanceSourceSyncRepository.class);
    private final AttendanceEvidenceRepository evidenceRepository =
            mock(AttendanceEvidenceRepository.class);
    private final EmployeeEmploymentResolverPort employeeResolver =
            mock(EmployeeEmploymentResolverPort.class);
    private final OaOrgMemberDirectoryPort memberDirectory =
            mock(OaOrgMemberDirectoryPort.class);
    private final OaDocumentPageTransaction transaction =
            new OaDocumentPageTransaction(
                    syncRepository,
                    evidenceRepository,
                    employeeResolver,
                    memberDirectory,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void pageCanCommit() {
        when(syncRepository.lockOaPageForCommit(
                        "job-1",
                        "source-1",
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        NOW))
                .thenReturn(new AttendanceSourceSyncModels.PageState(
                        "job-1",
                        "source-1",
                        "company-1",
                        "RUNNING",
                        0,
                        null,
                        7L));
    }

    @Test
    void classifiedOvertimeTypeReachesEvidenceRow() {
        employeeMatches();
        publishedRuntimeContract();

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(OvertimeType.PAID)));

        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isZero();
        ArgumentCaptor<EvidenceRows.RawFactRow> raw =
                ArgumentCaptor.forClass(EvidenceRows.RawFactRow.class);
        verify(evidenceRepository).insertRawFact(raw.capture());
        assertThat(raw.getValue().factKind()).isEqualTo("OA_DOCUMENT");
        assertThat(raw.getValue().sourceInstant()).isNull();
        assertThat(raw.getValue().intervalStart()).isEqualTo(START);
        assertThat(raw.getValue().intervalEnd()).isEqualTo(END);
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(EvidenceRows.NormalizedRecordRow.class);
        verify(evidenceRepository).insertNormalizedRecord(normalized.capture());
        assertThat(normalized.getValue().recordKind()).isEqualTo("OA_INTERVAL");
        assertThat(normalized.getValue().pointInstant()).isNull();
        assertThat(normalized.getValue().intervalStart()).isEqualTo(START);
        assertThat(normalized.getValue().intervalEnd()).isEqualTo(END);
        ArgumentCaptor<EvidenceRows.OaDocumentRow> row =
                ArgumentCaptor.forClass(EvidenceRows.OaDocumentRow.class);
        verify(evidenceRepository).insertOaAttendanceDocument(row.capture());
        assertThat(row.getValue().documentType()).isEqualTo("OVERTIME");
        assertThat(row.getValue().overtimeType()).isEqualTo(OvertimeType.PAID);
        ArgumentCaptor<EvidenceRows.OaDocumentContextRow> context =
                ArgumentCaptor.forClass(
                        EvidenceRows.OaDocumentContextRow.class);
        verify(evidenceRepository)
                .insertOaAttendanceDocumentContext(context.capture());
        assertThat(context.getValue().oaAttendanceDocumentId())
                .isEqualTo(row.getValue().oaAttendanceDocumentId());
        assertThat(context.getValue().oaRuntimeContractRevisionId())
                .isEqualTo("oa-runtime-contract-9");
        assertThat(context.getValue().activationDecision())
                .isEqualTo("ACTIVATED");
        assertThat(context.getValue().rawStatusValue())
                .isEqualTo("APPROVED");
        assertThat(context.getValue().overtimeTreatment())
                .isEqualTo("UNKNOWN");
        assertThat(context.getValue().overtimeType())
                .isEqualTo(OvertimeType.PAID);
        assertThat(context.getValue().recognizedWorkMinutes()).isZero();
        assertThat(context.getValue().payrollCreditMinutes()).isZero();
        assertThat(context.getValue().timeOffCreditMinutes()).isZero();
        assertThat(context.getValue().authorizedContextJson())
                .isEqualTo(
                        "{\"overtimeType\":\"PAID\","
                                + "\"sourceStatus\":\"APPROVED\"}");
        assertThat(context.getValue().contextDigest())
                .matches("[0-9a-f]{64}");
        InOrder writeOrder = inOrder(evidenceRepository);
        writeOrder.verify(evidenceRepository).insertOaAttendanceDocument(any());
        writeOrder.verify(evidenceRepository)
                .insertOaAttendanceDocumentContext(any());
        verify(syncRepository).insertCommittedPage(any());
        verify(syncRepository).advanceWatermark(
                "source-1", 7L, "next-1", "e".repeat(64), NOW);
        verify(syncRepository).incrementJobCounters("job-1", 1, 0);
    }

    @Test
    void equalStartAndEndUsesPointTemporalInsteadOfViolatingIntervalCheck() {
        employeeMatches();
        OaDocumentRecord point = new OaDocumentRecord(
                "PUNCH_CORRECTION:204",
                "2026-08-15T12:00:00:state=3",
                "member-204",
                "E001",
                DocumentType.PUNCH_CORRECTION,
                SourceStatus.APPROVED,
                START,
                START,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_PUNCH_CORRECTION_BATCH",
                true);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(point));

        assertThat(result.acceptedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.RawFactRow> raw =
                ArgumentCaptor.forClass(EvidenceRows.RawFactRow.class);
        verify(evidenceRepository).insertRawFact(raw.capture());
        assertThat(raw.getValue().sourceInstant()).isEqualTo(START);
        assertThat(raw.getValue().intervalStart()).isNull();
        assertThat(raw.getValue().intervalEnd()).isNull();
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(EvidenceRows.NormalizedRecordRow.class);
        verify(evidenceRepository).insertNormalizedRecord(normalized.capture());
        assertThat(normalized.getValue().pointInstant()).isEqualTo(START);
        assertThat(normalized.getValue().intervalStart()).isNull();
        assertThat(normalized.getValue().intervalEnd()).isNull();
    }

    @Test
    void invertedIntervalIsQuarantinedWithoutEvidenceWrite() {
        OaDocumentRecord inverted = new OaDocumentRecord(
                "LEAVE:170",
                "2026-08-15T12:00:00:state=3",
                "member-170",
                "E001",
                DocumentType.LEAVE,
                SourceStatus.APPROVED,
                END,
                START,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_LEAVE_BATCH",
                true,
                null,
                LeaveType.ANNUAL,
                "L-1",
                null);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(inverted));

        assertThat(result.quarantinedCount()).isEqualTo(1);
        verify(evidenceRepository, never()).insertRawFact(any());
    }

    @Test
    void missingPublishedRuntimeContractStillWritesTheOvertimeDocument() {
        employeeMatches();

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(OvertimeType.PAID)));

        assertThat(result.acceptedCount()).isEqualTo(1);
        verify(evidenceRepository).insertOaAttendanceDocument(any());
        verify(evidenceRepository, never())
                .insertOaAttendanceDocumentContext(any());
        verify(syncRepository).incrementJobCounters("job-1", 1, 0);
    }

    @Test
    void missingSourceVersionIsFailClosedBeforeEvidenceWrites() {
        OaDocumentRecord missingVersion = overtime(
                SourceStatus.APPROVED,
                null,
                true,
                OvertimeType.PAID);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(missingVersion));

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.quarantinedCount()).isEqualTo(1);
        verify(evidenceRepository, never()).findRawBySourceIdentity(
                any(), any(), any());
        verify(evidenceRepository, never()).insertRawFact(any());
        verify(evidenceRepository, never())
                .findLatestPublishedOaRuntimeContractRevisionId(any());
        verify(syncRepository).incrementJobCounters("job-1", 0, 1);
    }

    @Test
    void authorizedContextJsonAndDigestAreStableAcrossReplayInputs() {
        employeeMatches();
        publishedRuntimeContract();

        transaction.commitPage(
                job(),
                "principal-1",
                "request-1",
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(overtime(OvertimeType.COMPENSATORY)));
        transaction.commitPage(
                job(),
                "principal-1",
                "request-1",
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(overtime(OvertimeType.COMPENSATORY)));

        ArgumentCaptor<EvidenceRows.OaDocumentContextRow> contexts =
                ArgumentCaptor.forClass(
                        EvidenceRows.OaDocumentContextRow.class);
        verify(evidenceRepository, times(2))
                .insertOaAttendanceDocumentContext(contexts.capture());
        assertThat(contexts.getAllValues().stream()
                        .map(EvidenceRows.OaDocumentContextRow::authorizedContextJson)
                        .distinct())
                .hasSize(1);
        assertThat(contexts.getAllValues().stream()
                        .map(EvidenceRows.OaDocumentContextRow::contextDigest)
                        .distinct())
                .hasSize(1);
    }

    @Test
    void atomicReplayDoesNotRequireRuntimeContractToRemainAvailable() {
        employeeMatches();
        publishedRuntimeContract();
        AtomicReference<EvidenceRows.RawFactRow> stored =
                new AtomicReference<>();
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "OVERTIME:172", "1"))
                .thenAnswer(ignored -> stored.get());
        doAnswer(invocation -> {
                    stored.set(invocation.getArgument(0));
                    return null;
                })
                .when(evidenceRepository)
                .insertRawFact(any());

        transaction.commitPage(
                job(),
                "principal-1",
                "request-1",
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(overtime(OvertimeType.PAID)));
        when(evidenceRepository.findOaAttendanceDocumentId(
                        "source-1", "OVERTIME:172", "1"))
                .thenReturn("oa-1");
        when(evidenceRepository
                        .findLatestPublishedOaRuntimeContractRevisionId(
                                "source-1"))
                .thenReturn(null);

        OaDocumentPageTransaction.PageCommitResult replay =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(OvertimeType.PAID)));

        assertThat(replay.acceptedCount()).isEqualTo(1);
        verify(evidenceRepository, times(2))
                .findLatestPublishedOaRuntimeContractRevisionId("source-1");
        verify(evidenceRepository, times(1)).insertRawFact(any());
        verify(evidenceRepository, times(1))
                .insertOaAttendanceDocumentContext(any());
    }

    @Test
    void replayBackfillsMissingContextForNegativeSeeyonOvertimeId() {
        employeeMatches();
        publishedRuntimeContract();
        OaDocumentRecord negative = new OaDocumentRecord(
                "OVERTIME:-388371210472787123",
                "2026-08-15T13:00:00:state=3",
                "member-172",
                "E001",
                DocumentType.OVERTIME,
                SourceStatus.APPROVED,
                START,
                END,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_OVERTIME_BATCH",
                true,
                OvertimeType.PAID);
        String digest = AttendanceEvidenceDigests.sha256(
                "OA_RAW_FACT_V1",
                "source-1",
                "company-1",
                negative.sourceBusinessKey(),
                negative.sourceVersion(),
                "member-172",
                "E001",
                START.toString(),
                END.toString(),
                "Asia/Shanghai",
                "OVERTIME",
                "APPROVED",
                "PAID",
                "");
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1",
                        negative.sourceBusinessKey(),
                        negative.sourceVersion()))
                .thenReturn(new EvidenceRows.RawFactRow(
                        "raw-1",
                        "source-1",
                        "company-1",
                        "OA_DOCUMENT",
                        negative.sourceBusinessKey(),
                        negative.sourceVersion(),
                        null,
                        START.toString(),
                        "Asia/Shanghai",
                        null,
                        START,
                        END,
                        digest,
                        null,
                        "request-1",
                        NOW,
                        "principal-1"));
        when(evidenceRepository.findOaAttendanceDocumentId(
                        "source-1",
                        negative.sourceBusinessKey(),
                        negative.sourceVersion()))
                .thenReturn("oa-doc-neg");
        when(evidenceRepository.hasOaAttendanceDocumentContext("oa-doc-neg"))
                .thenReturn(false);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(negative));

        assertThat(result.acceptedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.OaDocumentContextRow> context =
                ArgumentCaptor.forClass(
                        EvidenceRows.OaDocumentContextRow.class);
        verify(evidenceRepository)
                .insertOaAttendanceDocumentContext(context.capture());
        assertThat(context.getValue().oaAttendanceDocumentId())
                .isEqualTo("oa-doc-neg");
        assertThat(context.getValue().activationDecision())
                .isEqualTo("ACTIVATED");
        assertThat(context.getValue().overtimeType())
                .isEqualTo(OvertimeType.PAID);
        verify(evidenceRepository, never()).insertRawFact(any());
    }

    @Test
    void pendingToApprovedStatusUpgradeUsesDistinctSourceVersions() {
        employeeMatches();
        publishedRuntimeContract();
        OaDocumentRecord pending = overtime(
                SourceStatus.UNKNOWN,
                "2026-08-15T12:00:00:state=0",
                false,
                OvertimeType.PAID);
        OaDocumentRecord approved = overtime(
                SourceStatus.APPROVED,
                "2026-08-15T13:00:00:state=3",
                true,
                OvertimeType.PAID);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        new OaPage(
                                List.of(pending, approved),
                                null,
                                "next-1",
                                "e".repeat(64)));

        assertThat(result.acceptedCount()).isEqualTo(2);
        ArgumentCaptor<EvidenceRows.OaDocumentRow> rows =
                ArgumentCaptor.forClass(EvidenceRows.OaDocumentRow.class);
        verify(evidenceRepository, times(2))
                .insertOaAttendanceDocument(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(
                        EvidenceRows.OaDocumentRow::sourceVersion,
                        EvidenceRows.OaDocumentRow::sourceStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "2026-08-15T12:00:00:state=0",
                                "UNKNOWN"),
                        org.assertj.core.groups.Tuple.tuple(
                                "2026-08-15T13:00:00:state=3",
                                "APPROVED"));
        verify(evidenceRepository, times(1))
                .insertOaAttendanceDocumentContext(any());
    }

    @Test
    void leaveAndRevocationSerialsReachEvidenceRow() {
        employeeMatches();

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        new OaPage(
                                List.of(leave("L-100"), revocation("L-100")),
                                null,
                                "next-1",
                                "e".repeat(64)));

        assertThat(result.acceptedCount()).isEqualTo(2);
        ArgumentCaptor<EvidenceRows.OaDocumentRow> rows =
                ArgumentCaptor.forClass(EvidenceRows.OaDocumentRow.class);
        verify(evidenceRepository, times(2))
                .insertOaAttendanceDocument(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(
                        EvidenceRows.OaDocumentRow::documentType,
                        EvidenceRows.OaDocumentRow::leaveSerial,
                        EvidenceRows.OaDocumentRow::originalLeaveSerial)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "LEAVE", "L-100", null),
                        org.assertj.core.groups.Tuple.tuple(
                                "LEAVE_REVOCATION", "R-100", "L-100"));
    }

    @Test
    void unclassifiedOvertimeIsQuarantinedBeforeEffectiveEvidence() {
        employeeMatches();

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(null)));

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.quarantinedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(
                        EvidenceRows.NormalizedRecordRow.class);
        verify(evidenceRepository).insertRawFact(any());
        verify(evidenceRepository).insertNormalizedRecord(
                normalized.capture());
        verify(evidenceRepository).insertMatchDecision(any());
        assertThat(normalized.getValue().validationStatus())
                .isEqualTo("QUARANTINED");
        assertThat(normalized.getValue().issueCode())
                .isEqualTo("OVERTIME_TYPE_UNCLASSIFIED");
        verify(evidenceRepository, never()).insertOaAttendanceDocument(any());
        verify(evidenceRepository, never())
                .insertOaAttendanceDocumentContext(any());
        verify(syncRepository).insertCommittedPage(any());
        verify(syncRepository).advanceWatermark(
                "source-1", 7L, "next-1", "e".repeat(64), NOW);
        verify(syncRepository).incrementJobCounters("job-1", 0, 1);
    }

    @Test
    void digestChangeOnExistingDocumentKeepsFirstWriteAndContinuesPage() {
        employeeMatches();
        publishedRuntimeContract();
        OaDocumentRecord record = overtime(OvertimeType.PAID);
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1",
                        record.sourceBusinessKey(),
                        record.sourceVersion()))
                .thenReturn(new EvidenceRows.RawFactRow(
                        "raw-1",
                        "source-1",
                        "company-1",
                        "OA_DOCUMENT",
                        record.sourceBusinessKey(),
                        record.sourceVersion(),
                        null,
                        START.toString(),
                        "Asia/Shanghai",
                        null,
                        START,
                        END,
                        "a".repeat(64),
                        null,
                        "request-1",
                        NOW,
                        "principal-1"));
        when(evidenceRepository.findOaAttendanceDocumentId(
                        "source-1",
                        record.sourceBusinessKey(),
                        record.sourceVersion()))
                .thenReturn("oa-doc-1");
        when(evidenceRepository.hasOaAttendanceDocumentContext("oa-doc-1"))
                .thenReturn(true);

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(record));

        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.quarantinedCount()).isZero();
        verify(evidenceRepository, never()).insertRawFact(any());
        verify(evidenceRepository, never()).insertOaAttendanceDocument(any());
        verify(syncRepository).insertCommittedPage(any());
        verify(syncRepository).advanceWatermark(
                "source-1", 7L, "next-1", "e".repeat(64), NOW);
        verify(syncRepository).incrementJobCounters("job-1", 1, 0);
    }

    @Test
    void staleWatermarkFailsBeforeAnyEvidenceOrPageWrite() {
        when(syncRepository.lockOaPageForCommit(
                        "job-1",
                        "source-1",
                        "principal-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        NOW))
                .thenReturn(new AttendanceSourceSyncModels.PageState(
                        "job-1",
                        "source-1",
                        "company-1",
                        "RUNNING",
                        1,
                        "already-advanced",
                        8L));

        assertThatThrownBy(() -> transaction.commitPage(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(OvertimeType.PAID))))
                .isInstanceOf(AttendanceSourceSyncFailure.class)
                .hasMessage("OA_WATERMARK_STALE");

        verify(evidenceRepository, never()).insertRawFact(any());
        verify(syncRepository, never()).insertCommittedPage(any());
        verify(syncRepository, never()).advanceWatermark(
                any(), anyLong(), any(), any(), any());
    }

    @Test
    void systemScheduledCommitUsesSystemPageLock() {
        employeeMatches();
        publishedRuntimeContract();
        when(syncRepository.lockSystemOaPageForCommit(
                        "job-1", "source-1"))
                .thenReturn(new AttendanceSourceSyncModels.PageState(
                        "job-1",
                        "source-1",
                        "company-1",
                        "RUNNING",
                        0,
                        null,
                        9L));

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.commitPage(
                        job(),
                        "SYSTEM",
                        "scheduled-job-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(overtime(OvertimeType.PAID)));

        assertThat(result.acceptedCount()).isEqualTo(1);
        verify(syncRepository).lockSystemOaPageForCommit(
                "job-1", "source-1");
        verify(syncRepository, never()).lockOaPageForCommit(
                any(), any(), any(), any(), any());
        verify(syncRepository).advanceWatermark(
                "source-1", 9L, "next-1", "e".repeat(64), NOW);
    }

    @Test
    void rematchRecordsPromotesQuarantinedOvertimeWithoutWatermark() {
        employeeMatches();
        publishedRuntimeContract();
        OaDocumentRecord record = overtime(OvertimeType.PAID);
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1",
                        record.sourceBusinessKey(),
                        record.sourceVersion()))
                .thenReturn(new EvidenceRows.RawFactRow(
                        "raw-1",
                        "source-1",
                        "company-1",
                        "OA_DOCUMENT",
                        record.sourceBusinessKey(),
                        record.sourceVersion(),
                        null,
                        START.toString(),
                        "Asia/Shanghai",
                        null,
                        START,
                        END,
                        "a".repeat(64),
                        null,
                        "request-1",
                        NOW,
                        "principal-1"));
        when(evidenceRepository.findOaAttendanceDocumentId(
                        "source-1",
                        record.sourceBusinessKey(),
                        record.sourceVersion()))
                .thenReturn(null);
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1",
                        record.sourceBusinessKey(),
                        record.sourceVersion()))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-old",
                        1,
                        "QUARANTINED",
                        "NO_AUTHORITATIVE_MATCH",
                        "UNMATCHED",
                        "NO_AUTHORITATIVE_MATCH",
                        null,
                        null,
                        null,
                        null));

        OaDocumentPageTransaction.PageCommitResult result =
                transaction.rematchRecords(
                        job(),
                        "principal-1",
                        "request-1",
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        List.of(record));

        assertThat(result.acceptedCount()).isEqualTo(1);
        verify(evidenceRepository).insertOaAttendanceDocument(any());
        verify(syncRepository, never()).advanceWatermark(
                any(), anyLong(), any(), any(), any());
        verify(syncRepository, never()).insertCommittedPage(any());
        verify(syncRepository).incrementJobCounters("job-1", 1, 0);
    }

    private void employeeMatches() {
        when(employeeResolver.resolveByEmployeeNumber(
                        "company-1", "E001", START))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-1",
                                "employment-1",
                                "d".repeat(64))));
    }

    private void publishedRuntimeContract() {
        when(evidenceRepository
                        .findLatestPublishedOaRuntimeContractRevisionId(
                                "source-1"))
                .thenReturn("oa-runtime-contract-9");
    }

    private static SourceJobStart job() {
        return new SourceJobStart(
                "job-1",
                "source-1",
                "company-1",
                "OA",
                "OA_MYSQL",
                "Asia/Shanghai",
                200,
                1_000,
                0,
                null);
    }

    private static OaPage page(OaDocumentRecord record) {
        return new OaPage(
                List.of(record), null, "next-1", "e".repeat(64));
    }

    private static OaDocumentRecord overtime(OvertimeType overtimeType) {
        return overtime(
                SourceStatus.APPROVED,
                "1",
                overtimeType != null,
                overtimeType);
    }

    private static OaDocumentRecord leave(String leaveSerial) {
        return new OaDocumentRecord(
                "LEAVE:170",
                "2026-08-15T12:00:00:state=3",
                "member-170",
                "E001",
                DocumentType.LEAVE,
                SourceStatus.APPROVED,
                START,
                END,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_LEAVE_BATCH",
                true,
                null,
                LeaveType.ANNUAL,
                leaveSerial,
                null);
    }

    private static OaDocumentRecord revocation(String originalLeaveSerial) {
        return new OaDocumentRecord(
                "LEAVE_REVOCATION:370",
                "2026-08-15T12:00:00:state=3",
                "member-170",
                "E001",
                DocumentType.LEAVE_REVOCATION,
                SourceStatus.APPROVED,
                START,
                END,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_LEAVE_REVOCATION_BATCH",
                true,
                null,
                null,
                "R-100",
                originalLeaveSerial);
    }

    private static OaDocumentRecord overtime(
            SourceStatus status,
            String sourceVersion,
            boolean effective,
            OvertimeType overtimeType) {
        return new OaDocumentRecord(
                "OVERTIME:172",
                sourceVersion,
                "member-172",
                "E001",
                DocumentType.OVERTIME,
                status,
                START,
                END,
                "Asia/Shanghai",
                NOW,
                NOW,
                null,
                null,
                "OA_OVERTIME_BATCH",
                effective,
                overtimeType);
    }
}

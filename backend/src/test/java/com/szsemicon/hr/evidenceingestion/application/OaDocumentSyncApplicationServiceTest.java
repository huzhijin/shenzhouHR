package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.JobStatus;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OaDocumentSyncApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRINCIPAL = "principal-1";
    private static final String SOURCE_ID = "oa-source-1";
    private static final String JOB_ID = "oa-job-1";
    private static final String CORRELATION_ID = "request-1";

    private final CurrentCapabilityService capabilities =
            mock(CurrentCapabilityService.class);
    private final CurrentPrincipalProvider principalProvider =
            mock(CurrentPrincipalProvider.class);
    private final AttendanceSourceSyncRepository repository =
            mock(AttendanceSourceSyncRepository.class);
    private final OaDocumentPageTransaction pageTransaction =
            mock(OaDocumentPageTransaction.class);
    private final AuditService auditService = mock(AuditService.class);
    private final OaAttendanceDocumentSourcePort source =
            mock(OaAttendanceDocumentSourcePort.class);

    @Test
    void commitsEveryNonTerminalPageAndFetchesTheNextPageFromItsCursor() {
        OaPage first = page(null, "cursor-1", "a", record("leave-1"));
        OaPage second = page("cursor-1", "cursor-2", "b", record("leave-2"));
        OaPage terminal = terminalPage("cursor-2");
        prepareManualJob("SUCCEEDED", null, null);
        when(source.fetchPage(SOURCE_ID, null)).thenReturn(first);
        when(source.fetchPage(SOURCE_ID, "cursor-1")).thenReturn(second);
        when(source.fetchPage(SOURCE_ID, "cursor-2")).thenReturn(terminal);
        when(pageTransaction.commitPage(
                        any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 0));
        when(pageTransaction.commitPage(
                        any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(2), eq(second)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 0));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.state()).isEqualTo("SUCCEEDED");
        InOrder pagination = inOrder(source, pageTransaction);
        pagination.verify(source).fetchPage(SOURCE_ID, null);
        pagination.verify(pageTransaction).commitPage(
                any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first));
        pagination.verify(source).fetchPage(SOURCE_ID, "cursor-1");
        pagination.verify(pageTransaction).commitPage(
                any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(2), eq(second));
        pagination.verify(source).fetchPage(SOURCE_ID, "cursor-2");
        verify(repository).markFinished(JOB_ID, "SUCCEEDED", NOW);
        verify(pageTransaction, times(2)).commitPage(
                any(), anyString(), anyString(), anyString(), any(Integer.class), any());
    }

    @Test
    void emptyPageWithTheSameCursorEndsNormallyWithoutACommit() {
        prepareManualJob("SUCCEEDED", null, "cursor-7");
        when(source.fetchPage(SOURCE_ID, "cursor-7"))
                .thenReturn(terminalPage("cursor-7"));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.state()).isEqualTo("SUCCEEDED");
        verify(source).fetchPage(SOURCE_ID, "cursor-7");
        verify(pageTransaction, never()).commitPage(
                any(), anyString(), anyString(), anyString(), any(Integer.class), any());
        verify(repository).markFinished(JOB_ID, "SUCCEEDED", NOW);
        verify(repository, never()).markFailed(anyString(), anyString(), any());
    }

    @Test
    void pageTransactionFailureMarksTheJobFailedAndNeverSuccessful() {
        OaPage first = page(null, "cursor-1", "c", record("leave-1"));
        prepareManualJob("FAILED", "OA_WATERMARK_STALE", null);
        when(source.fetchPage(SOURCE_ID, null)).thenReturn(first);
        when(pageTransaction.commitPage(
                        any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first)))
                .thenThrow(new AttendanceSourceSyncFailure("OA_WATERMARK_STALE"));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.safeErrorCode()).isEqualTo("OA_WATERMARK_STALE");
        verify(repository).markFailed(JOB_ID, "OA_WATERMARK_STALE", NOW);
        verify(repository, never()).markFinished(eq(JOB_ID), anyString(), any());
    }

    @Test
    void cursorCycleMarksTheJobFailedBeforeCommittingTheRepeatedPage() {
        OaPage first = page(null, "cursor-1", "d", record("leave-1"));
        OaPage repeated = page("cursor-1", "cursor-1", "e", record("leave-2"));
        prepareManualJob("FAILED", "OA_CURSOR_CYCLE_DETECTED", null);
        when(source.fetchPage(SOURCE_ID, null)).thenReturn(first);
        when(source.fetchPage(SOURCE_ID, "cursor-1")).thenReturn(repeated);
        when(pageTransaction.commitPage(
                        any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 0));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.safeErrorCode()).isEqualTo("OA_CURSOR_CYCLE_DETECTED");
        verify(pageTransaction).commitPage(
                any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first));
        verify(pageTransaction, never()).commitPage(
                any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(2), eq(repeated));
        verify(repository).markFailed(JOB_ID, "OA_CURSOR_CYCLE_DETECTED", NOW);
        verify(repository, never()).markFinished(eq(JOB_ID), anyString(), any());
    }

    @Test
    void sourceQueryFailureMarksTheJobFailedAndNeverSuccessful() {
        prepareManualJob("FAILED", "OA_SYNC_FAILED", null);
        when(source.fetchPage(SOURCE_ID, null))
                .thenThrow(new IllegalStateException("OA connection unavailable"));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.safeErrorCode()).isEqualTo("OA_SYNC_FAILED");
        verify(repository).markFailed(JOB_ID, "OA_SYNC_FAILED", NOW);
        verify(repository, never()).markFinished(eq(JOB_ID), anyString(), any());
        verify(pageTransaction, never()).commitPage(
                any(), anyString(), anyString(), anyString(), any(Integer.class), any());
    }

    @Test
    void quarantinedRecordsFinishAsPartiallyQuarantined() {
        OaPage first = page(null, "cursor-1", "f", record("leave-1"));
        prepareManualJob("PARTIALLY_QUARANTINED", null, null);
        when(source.fetchPage(SOURCE_ID, null)).thenReturn(first);
        when(source.fetchPage(SOURCE_ID, "cursor-1"))
                .thenReturn(terminalPage("cursor-1"));
        when(pageTransaction.commitPage(
                        any(), eq(PRINCIPAL), eq(CORRELATION_ID),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(first)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(0, 2));

        JobStatus result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result.state()).isEqualTo("PARTIALLY_QUARANTINED");
        verify(repository).markFinished(JOB_ID, "PARTIALLY_QUARANTINED", NOW);
        verify(repository, never()).markFailed(anyString(), anyString(), any());
        verify(auditService).record(
                PRINCIPAL,
                "OA_SOURCE_SYNC_COMPLETE",
                "ATTENDANCE_SYNC_JOB",
                JOB_ID,
                "SUCCESS",
                "OA_SYNC_PARTIALLY_QUARANTINED");
    }

    @Test
    void scheduledRunUsesSystemAndProcessesEveryActiveOaSource() {
        SourceJobStart firstJob = job("job-a", "source-a", null);
        SourceJobStart secondJob = job("job-b", "source-b", null);
        OaPage firstPage = page(null, "a-1", "1", record("leave-a"));
        OaPage secondPage = page(null, "b-1", "2", record("leave-b"));
        when(repository.findAllActiveOaSourceIds())
                .thenReturn(List.of("source-a", "source-b"));
        when(repository.createScheduledOaJob(
                        eq("source-a"), anyString(), anyString(), eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(firstJob));
        when(repository.createScheduledOaJob(
                        eq("source-b"), anyString(), anyString(), eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(secondJob));
        when(source.fetchPage("source-a", null)).thenReturn(firstPage);
        when(source.fetchPage("source-a", "a-1"))
                .thenReturn(terminalPage("a-1"));
        when(source.fetchPage("source-b", null)).thenReturn(secondPage);
        when(source.fetchPage("source-b", "b-1"))
                .thenReturn(terminalPage("b-1"));
        when(pageTransaction.commitPage(
                        any(), eq("SYSTEM"), anyString(),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), any()))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 0));
        when(repository.findCreatedJob("job-a", "SYSTEM"))
                .thenReturn(Optional.of(status("job-a", "source-a", "SUCCEEDED", null)));
        when(repository.findCreatedJob("job-b", "SYSTEM"))
                .thenReturn(Optional.of(status("job-b", "source-b", "SUCCEEDED", null)));

        service().runScheduled();

        verify(source).fetchPage("source-a", null);
        verify(source).fetchPage("source-b", null);
        verify(pageTransaction).commitPage(
                eq(firstJob), eq("SYSTEM"), anyString(),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(firstPage));
        verify(pageTransaction).commitPage(
                eq(secondJob), eq("SYSTEM"), anyString(),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_RUN), eq(1), eq(secondPage));
        verify(repository).markFinished("job-a", "SUCCEEDED", NOW);
        verify(repository).markFinished("job-b", "SUCCEEDED", NOW);
        verify(capabilities, never()).require(anyString());
    }

    private OaDocumentSyncApplicationService service() {
        when(principalProvider.currentPrincipalId()).thenReturn(PRINCIPAL);
        return new OaDocumentSyncApplicationService(
                capabilities,
                principalProvider,
                repository,
                pageTransaction,
                auditService,
                List.of(source),
                CLOCK);
    }

    private void prepareManualJob(
            String state, String errorCode, String committedCursor) {
        SourceJobStart job = job(JOB_ID, SOURCE_ID, committedCursor);
        when(repository.createManualOaJob(
                        eq(SOURCE_ID), anyString(), eq(CORRELATION_ID),
                        eq(PRINCIPAL), eq(NOW)))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(job));
        when(repository.findCreatedJob(JOB_ID, PRINCIPAL))
                .thenReturn(Optional.of(status(
                        JOB_ID, SOURCE_ID, state, errorCode)));
    }

    private static SourceJobStart job(
            String jobId, String sourceId, String committedCursor) {
        return new SourceJobStart(
                jobId,
                sourceId,
                "company-1",
                "OA attendance",
                "OA_MYSQL_CREDENTIALS",
                "Asia/Shanghai",
                200,
                1_000,
                0,
                committedCursor);
    }

    private static JobStatus status(
            String jobId, String sourceId, String state, String errorCode) {
        return new JobStatus(
                jobId,
                sourceId,
                "OA attendance",
                "OA_ATTENDANCE",
                state,
                "FAILED".equals(state) ? 0 : 1,
                "SUCCEEDED".equals(state) ? 1 : 0,
                "PARTIALLY_QUARANTINED".equals(state) ? 2 : 0,
                errorCode,
                NOW,
                NOW,
                2);
    }

    private static OaPage terminalPage(String cursor) {
        return new OaPage(List.of(), cursor, cursor, "0".repeat(64));
    }

    private static OaPage page(
            String inputCursor,
            String nextCursor,
            String digestCharacter,
            OaDocumentRecord record) {
        return new OaPage(
                List.of(record),
                inputCursor,
                nextCursor,
                digestCharacter.repeat(64));
    }

    private static OaDocumentRecord record(String businessKey) {
        return new OaDocumentRecord(
                businessKey,
                "version-1",
                "person-1",
                "E001",
                DocumentType.OUTING,
                SourceStatus.APPROVED,
                NOW,
                NOW.plusSeconds(3_600),
                "Asia/Shanghai",
                NOW.minusSeconds(600),
                NOW.minusSeconds(300),
                NOW,
                null,
                "batch-1",
                true);
    }
}

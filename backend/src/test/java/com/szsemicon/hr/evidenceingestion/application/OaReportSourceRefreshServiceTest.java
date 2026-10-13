package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.*;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaReportSourceRefreshServiceTest {
    private static final Instant REQUESTED = Instant.parse("2026-10-02T01:00:00Z");
    private static final Instant COMPLETED = REQUESTED.plusSeconds(5);
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final Instant FROM = Instant.parse("2026-08-31T16:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T16:00:00Z");
    private final CurrentPrincipalProvider principal = mock(CurrentPrincipalProvider.class);
    private final AttendanceSourceSyncRepository jobs = mock(AttendanceSourceSyncRepository.class);
    private final AttendanceEvidenceRepository evidence = mock(AttendanceEvidenceRepository.class);
    private final OaDocumentPageTransaction pages = mock(OaDocumentPageTransaction.class);
    private final OaAttendanceDocumentSourcePort source = mock(OaAttendanceDocumentSourcePort.class);
    private final AtomicReference<String> state = new AtomicReference<>("RUNNING");
    private final AtomicReference<String> error = new AtomicReference<>();
    private final AttendanceSourceSyncModels.SourceJobStart job =
            new AttendanceSourceSyncModels.SourceJobStart("job-1", "oa-1", "company-1", "OA",
                    null, "Asia/Shanghai", 500, 60, 1, "already-past-september");

    private OaDocumentSyncApplicationService prepare() {
        when(jobs.findAllActiveOaSourceIds()).thenReturn(List.of("oa-1"));
        when(jobs.createScheduledOaJob(eq("oa-1"), anyString(), anyString(), any()))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(job));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of());
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), anyList()))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(0, 0));
        doAnswer(call -> { state.set(call.getArgument(1)); return null; })
                .when(jobs).markFinished(eq("job-1"), anyString(), any());
        doAnswer(call -> { state.set("FAILED"); error.set(call.getArgument(1)); return null; })
                .when(jobs).markFailed(eq("job-1"), anyString(), any());
        when(jobs.findCreatedJob("job-1", "SYSTEM")).thenAnswer(call -> Optional.of(
                new AttendanceSourceSyncModels.JobStatus("job-1", "oa-1", "OA", "OA_ATTENDANCE",
                        state.get(), 0, 0, 0, error.get(), REQUESTED, COMPLETED, 1)));
        return new OaDocumentSyncApplicationService(mock(CurrentCapabilityService.class),
                principal, jobs, pages, mock(AuditService.class),
                List.of(source), Clock.fixed(COMPLETED, ZoneOffset.UTC), evidence);
    }

    @Test
    void historicalReportRefreshRereadsMonthEvenWhenIncrementalCursorIsAlreadyLater() {
        var service = prepare();
        var revoked = record("LEAVE:1", SourceStatus.REVOKED, FROM.plusSeconds(3600));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(revoked));
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(revoked)));
        verify(source, never()).fetchPage(anyString(), any());
    }

    @Test
    void knownDocumentMovedOutsideMonthIsRereadByKeyAndReplacesWindowRead() {
        var service = prepare();
        var old = record("LEAVE:1", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        var moved = record("LEAVE:1", SourceStatus.REVOKED, TO.plusSeconds(86400));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(old));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of("LEAVE:1"));
        when(source.fetchByBusinessKeys("oa-1", List.of("LEAVE:1"))).thenReturn(List.of(moved));
        service.refreshMonthForReport("company-1", MONTH, REQUESTED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(moved)));
    }

    @Test
    void missingKnownDocumentDoesNotTreatItsAbsenceAsSuccessfulRefresh() {
        var service = prepare();
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of("LEAVE:1"));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_DOCUMENT_MISSING");
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
        verify(jobs, never()).markFinished(any(), any(), any());
    }

    @Test
    void failedReadAndUnsafeChangedDocumentPreventReportRefresh() {
        var service = prepare();
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        doReturn(List.of()).when(source).fetchOverlapping("oa-1", FROM, TO);
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), anyList()))
                .thenThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_QUARANTINED");
    }

    @Test
    void sourceAlreadyRunningDoesNotPublishUsingStaleFacts() {
        var service = prepare();
        when(jobs.createScheduledOaJob(eq("oa-1"), anyString(), anyString(), any()))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.alreadyRunning());
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_REFRESH_BUSY");
        verify(source, never()).fetchOverlapping(any(), any(), any());
    }

    @Test
    void unrelatedUnmatchedRowsMayStayQuarantinedWithoutBlockingAllEmployees() {
        var service = prepare();
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), anyList()))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 1));
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);
        verify(jobs).markFinished("job-1", "PARTIALLY_QUARANTINED", COMPLETED);
    }

    @Test
    void contradictoryCurrentSummariesCannotSilentlyPickTheLastStatus() {
        var service = prepare();
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(
                record("LEAVE:1", SourceStatus.APPROVED, FROM.plusSeconds(3600)),
                record("LEAVE:1", SourceStatus.REVOKED, FROM.plusSeconds(3600))));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void sourceCutoffIncludesMonotonicObservationTimeBeyondTheWallClock() {
        var service = prepare();
        Instant latestEvidence = COMPLETED.plusNanos(1000);
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), anyList()))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(1, 0, 1, latestEvidence));
        when(jobs.findCreatedJob("job-1", "SYSTEM")).thenAnswer(call -> Optional.of(
                new AttendanceSourceSyncModels.JobStatus("job-1", "oa-1", "OA", "OA_ATTENDANCE",
                        state.get(), 0, 0, 0, error.get(), REQUESTED, latestEvidence, 1)));
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(latestEvidence);
        verify(jobs).markFinished("job-1", "SUCCEEDED", latestEvidence);
    }

    private static OaDocumentRecord record(String key, SourceStatus status, Instant start) {
        return new OaDocumentRecord(key, "same-source-timestamp", "member-1", "E001",
                DocumentType.LEAVE, status, start, start.plusSeconds(3600), "Asia/Shanghai",
                start, start, start, null, null, status == SourceStatus.APPROVED);
    }

    @Test
    void dayRefreshReadsEveryFamilyAndKnownKeysOnlyWithinItsShanghaiEvidenceWindow() {
        var service = prepare();
        LocalDate day = LocalDate.of(2026, 9, 30);
        Instant dayFrom = Instant.parse("2026-09-29T16:00:00Z");
        Instant dayTo = Instant.parse("2026-09-30T16:00:00Z");
        var records = java.util.Arrays.stream(DocumentType.values())
                .map(type -> new OaDocumentRecord(type + ":1", "version-1", "member-1", "E001",
                        type, SourceStatus.APPROVED, dayFrom.plusSeconds(3600),
                        dayFrom.plusSeconds(7200), "Asia/Shanghai", dayFrom, dayFrom,
                        dayFrom, null, null, true))
                .toList();
        when(source.fetchOverlapping("oa-1", dayFrom, dayTo)).thenReturn(records);
        // An unresolved key elsewhere in September must not widen this exact
        // evidence refresh back into a month refresh.
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO))
                .thenReturn(List.of("LEAVE:missing-other-day"));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", dayFrom, dayTo))
                .thenReturn(List.of("LEAVE:1"));
        when(source.fetchByBusinessKeys("oa-1", List.of("LEAVE:1")))
                .thenReturn(List.of(records.getFirst()));

        assertThat(service.refreshRangeForReport("company-1", day, day.plusDays(1), REQUESTED))
                .isEqualTo(COMPLETED);

        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(records));
        verify(evidence).findOaBusinessKeysOverlapping("oa-1", dayFrom, dayTo);
        verify(evidence, never()).findOaBusinessKeysOverlapping("oa-1", FROM, TO);
        verify(source, never()).fetchOverlapping("oa-1", FROM, TO);
        verify(source, never()).fetchByBusinessKeys("oa-1", List.of("LEAVE:missing-other-day"));
        verify(source, never()).fetchPage(anyString(), any());
    }

    @Test
    void missingKnownDocumentInsideTheDayStillRejectsBeforeCommitting() {
        var service = prepare();
        LocalDate day = LocalDate.of(2026, 9, 30);
        Instant start = Instant.parse("2026-09-29T16:00:00Z");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", start, TO))
                .thenReturn(List.of("LEAVE_REVOCATION:missing"));

        assertThatThrownBy(() -> service.refreshRangeForReport(
                "company-1", day, day.plusDays(1), REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_DOCUMENT_MISSING");
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void dayRefreshStillReadsMovedKnownDocumentsByKey() {
        var service = prepare();
        LocalDate day = LocalDate.of(2026, 9, 30);
        Instant start = Instant.parse("2026-09-29T16:00:00Z");
        var moved = record("LEAVE:known-on-day", SourceStatus.REVOKED, TO.plusSeconds(86400));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", start, TO))
                .thenReturn(List.of(moved.sourceBusinessKey()));
        when(source.fetchByBusinessKeys("oa-1", List.of(moved.sourceBusinessKey())))
                .thenReturn(List.of(moved));

        service.refreshRangeForReport("company-1", day, day.plusDays(1), REQUESTED);

        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(moved)));
    }

    @Test
    void anExistingOriginalCannotHideAMissingHistoricallyLinkedAmendmentOutsideTheWindow() {
        var service = prepare();
        LocalDate day = LocalDate.of(2026, 9, 30);
        Instant start = Instant.parse("2026-09-29T16:00:00Z");
        var original = record("LEAVE:930", SourceStatus.APPROVED, start.plusSeconds(3600));
        List<String> dependencies = List.of("LEAVE:930", "LEAVE_REVOCATION:historical-outside-window");
        when(source.fetchOverlapping("oa-1", start, TO)).thenReturn(List.of(original));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", start, TO)).thenReturn(dependencies);
        when(source.fetchByBusinessKeys("oa-1", dependencies)).thenReturn(List.of(original));

        assertThatThrownBy(() -> service.refreshRangeForReport("company-1", day, day.plusDays(1), REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_DOCUMENT_MISSING");
        verify(source).fetchByBusinessKeys("oa-1", dependencies);
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void evidenceRangeMayCrossMonthToCoverAnOvernightShift() {
        var service = prepare();
        LocalDate from = LocalDate.of(2026, 9, 29);
        LocalDate to = LocalDate.of(2026, 10, 2);

        service.refreshRangeForReport("company-1", from, to, REQUESTED);

        verify(source).fetchOverlapping("oa-1", Instant.parse("2026-09-28T16:00:00Z"),
                Instant.parse("2026-10-01T16:00:00Z"));
        verify(evidence).findOaBusinessKeysOverlapping("oa-1", Instant.parse("2026-09-28T16:00:00Z"),
                Instant.parse("2026-10-01T16:00:00Z"));
    }

    @Test
    void emptyOrReversedRangeNeverCreatesAnOaJob() {
        var service = prepare();
        LocalDate day = LocalDate.of(2026, 9, 30);
        assertThatThrownBy(() -> service.refreshRangeForReport("company-1", day, day, REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.refreshRangeForReport("company-1", day, day.minusDays(1), REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jobs, never()).createScheduledOaJob(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(source, pages);
    }
    @Test
    void reportCanExcludeOnlyMissingOvertimeAndMakeupProvenUnrelatedAfterByKeyRead() {
        var service = prepare();
        var present = record("LEAVE:present", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        List<String> allKeys = List.of("LEAVE:present", "OVERTIME:other", "PUNCH_CORRECTION:other");
        List<String> missing = allKeys.subList(1, 3);
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(allKeys);
        when(source.fetchByBusinessKeys("oa-1", allKeys)).thenReturn(List.of(present));
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", missing))
                .thenReturn(missing.stream().map(key -> scopeRow(key, "company-3", false)).toList());
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZJN0001"));

        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);

        var ordered = inOrder(source, evidence, pages);
        ordered.verify(source).fetchByBusinessKeys("oa-1", allKeys);
        ordered.verify(evidence).findMissingOaKeyScopeRows("oa-1", "company-1", missing);
        ordered.verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(present)));
        verify(source, never()).fetchPage(any(), any());
    }

    @Test
    void existingOrLinkedTargetDependencyCannotBeExcluded() {
        var service = prepare();
        mockMissingScope("OVERTIME:other", scopeRow("OVERTIME:other", "company-3", true));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void sameCompanyMissingDocumentRemainsStrict() {
        var service = prepare();
        mockMissingScope("OVERTIME:target", scopeRow("OVERTIME:target", "company-1", false));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void oneUnprovenMissingKeyPreventsCommittingOtherProvenKeys() {
        var service = prepare();
        List<String> missing = List.of("OVERTIME:other", "PUNCH_CORRECTION:unknown");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(missing);
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", missing))
                .thenReturn(List.of(scopeRow(missing.getFirst(), "company-3", false)));
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZJN0001"));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void missingLeaveNeverUsesForeignAttributionAsCancellation() {
        var service = prepare();
        mockMissingScope("LEAVE:other", scopeRow("LEAVE:other", "company-3", false));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void completeByKeyReadDoesNotRunAnyNegativeScopeProof() {
        var service = prepare();
        var present = record("LEAVE:present", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of("LEAVE:present"));
        when(source.fetchByBusinessKeys("oa-1", List.of("LEAVE:present"))).thenReturn(List.of(present));
        service.refreshMonthForReport("company-1", MONTH, REQUESTED);
        verify(evidence, never()).findMissingOaKeyScopeRows(any(), any(), any());
        verify(evidence, never()).findCompanyEmployeeNumbers(any());
    }

    @Test
    void unavailableProofRemainsMissingSourceFailureWithoutCommitting() {
        var service = prepare();
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of("OVERTIME:other"));
        when(evidence.findMissingOaKeyScopeRows(any(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic database failure"));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void overlappingCurrentRecordMissingFromByKeyReadIsAConflictNotProvenAbsence() {
        var service = prepare();
        String key = "OVERTIME:other";
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(
                new OaDocumentRecord(key, "new-version", "new-member", "SZJN0001",
                        DocumentType.OVERTIME, SourceStatus.APPROVED, FROM.plusSeconds(3600),
                        FROM.plusSeconds(7200), "Asia/Shanghai", FROM, FROM, FROM, null, null, true)));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        verify(evidence, never()).findMissingOaKeyScopeRows(any(), any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void manualRematchNeverAppliesReportCompanyMissingKeyExclusion() {
        var service = prepare();
        when(principal.currentPrincipalId()).thenReturn("SYSTEM");
        when(jobs.createManualOaJob(eq("oa-1"), anyString(), eq("manual-request"), eq("SYSTEM"), any()))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(job));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO))
                .thenReturn(List.of("OVERTIME:other"));
        var status = service.rematchWindow("oa-1", MONTH.atDay(1), MONTH.atEndOfMonth(), "manual-request");
        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.safeErrorCode()).isEqualTo("OA_REPORT_SOURCE_DOCUMENT_MISSING");
        verify(source).fetchByBusinessKeys("oa-1", List.of("OVERTIME:other"));
        verify(evidence, never()).findMissingOaKeyScopeRows(any(), any(), any());
        verify(evidence, never()).findCompanyEmployeeNumbers(any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void reportIsolationOmitsOnlyProvenUnparseablePendingLeaveBeforeReadOnlyPreflight() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "foreign");
        when(source.hasNoLeaveRevocationsForSerial("oa-1", "L1")).thenReturn(true);
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()), eq(1)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(0, 1));
        service.refreshMonthForReport("company-1", MONTH, REQUESTED);
        var order = inOrder(source, pages);
        order.verify(source).hasNoLeaveRevocationsForSerial("oa-1", "L1");
        order.verify(pages).preflightUnsafeChanges("oa-1", List.of());
        order.verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()), eq(1));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
        verify(jobs, never()).incrementJobCounters(anyString(), anyInt(), anyInt());
        verify(jobs).markFinished("job-1", "PARTIALLY_QUARANTINED", COMPLETED);
    }

    @Test
    void changedWindowObservationCannotBeHiddenByLaterInvalidByKeyObservation() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "foreign");
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(
                record(candidate.sourceBusinessKey(), SourceStatus.APPROVED, FROM.plusSeconds(3600))));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(candidate.sourceBusinessKey()));
        when(source.fetchByBusinessKeys("oa-1", List.of(candidate.sourceBusinessKey()))).thenReturn(List.of(candidate));
        assertThatThrownBy(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        verify(source, never()).hasNoLeaveRevocationsForSerial(any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void unresolvedCurrentSourceAmendmentProofLeavesTheOriginalRecordForStrictPreflight() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "foreign");
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(candidate));
        assertQuarantined(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void absenceQueryFailureCannotOmitThePendingLeaveOrStartTheWriteTransaction() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "foreign");
        when(source.hasNoLeaveRevocationsForSerial("oa-1", "L1")).thenThrow(new IllegalStateException("unavailable"));
        assertQuarantined(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void targetHistoricalOwnerCannotUseReportIsolation() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "company-1");
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(candidate));
        assertQuarantined(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(source, never()).hasNoLeaveRevocationsForSerial(any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void manualRefreshDoesNotApplyDraftIsolationEvenWithOtherwiseProvableHistory() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        mockPendingScope(candidate, "foreign");
        when(principal.currentPrincipalId()).thenReturn("SYSTEM");
        when(jobs.createManualOaJob(eq("oa-1"), anyString(), eq("manual-request"), eq("SYSTEM"), any()))
                .thenReturn(AttendanceSourceSyncRepository.StartResult.created(job));
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(candidate));
        var result = service.rematchWindow("oa-1", MONTH.atDay(1), MONTH.atEndOfMonth(), "manual-request");
        assertThat(result.safeErrorCode()).isEqualTo("OA_REPORT_SOURCE_QUARANTINED");
        verify(evidence, never()).findUnparseableLeaveScopeRows(any(), any(), any());
        verify(source, never()).hasNoLeaveRevocationsForSerial(any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void absentDraftHistoryCannotBeTreatedAsNoTargetHistory() {
        var service = prepare();
        var candidate = UnparseablePendingLeaveReportScopeProofTest.candidate();
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(candidate));
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(candidate));
        assertQuarantined(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(source, never()).hasNoLeaveRevocationsForSerial(any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    private void mockPendingScope(OaDocumentRecord candidate, String historicalCompany) {
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(candidate));
        when(evidence.findUnparseableLeaveScopeRows("oa-1", "company-1", List.of(candidate.sourceBusinessKey())))
                .thenReturn(List.of(UnparseablePendingLeaveReportScopeProofTest.history(historicalCompany, "employee", "SZST0001", "SZST0001",
                        false, "UNKNOWN", UnparseablePendingLeaveReportScopeProofTest.VERSION, "L1", false)));
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZJN0001"));
    }
    private static void assertQuarantined(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_QUARANTINED");
    }

    private void mockMissingScope(String key, EvidenceRows.MissingOaKeyScopeRow row) {
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key))).thenReturn(List.of(row));
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZJN0001"));
    }

    @Test
    void explicitEmployeeRefreshMayExcludeProvenOtherEmployeesMissingOvertime() {
        var service = prepare();
        String key = "OVERTIME:other-employee";
        var employees = List.of("employee-target");
        var revokedLeave = record("LEAVE:ma-revoked", SourceStatus.REVOKED, FROM.plusSeconds(3600));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(revokedLeave));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", employees, List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findReportEmployeeNumbers("company-1", employees)).thenReturn(List.of("SZSTSX109"));

        assertThat(service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, employees)).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(revokedLeave)));
        verify(evidence, never()).findCompanyEmployeeNumbers(any());
        verify(evidence, never()).findMissingOaKeyScopeRows(any(), any(), any());
    }

    @Test
    void explicitEmployeeRefreshCannotExcludeItsOwnMissingEvidenceOrUnknownProof() {
        var service = prepare();
        String key = "OVERTIME:target-employee";
        var employees = List.of("employee-other");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", employees, List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findReportEmployeeNumbers("company-1", employees)).thenReturn(List.of("SZST0759"));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verifyNoInteractions(pages);

        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", employees, List.of(key)))
                .thenThrow(new IllegalStateException("history unavailable"));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verifyNoInteractions(pages);
    }

    @Test
    void companyRefreshStillRejectsMissingEvidenceForAnotherEmployeeOfTheSameCompany() {
        var service = prepare();
        String key = "OVERTIME:other-employee";
        mockMissingScope(key, scopeRow(key, "company-1", false));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verifyNoInteractions(pages);
        verify(evidence, never()).findMissingOaKeyEmployeeScopeRows(any(), any(), any(), any());
    }

    @Test
    void explicitEmployeeScopeMayExcludeOnlyProvenUnrelatedMissingLeave() {
        var service = prepare();
        String key = "LEAVE:other-employee";
        var employees = List.of("employee-target");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", employees, List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findReportEmployeeNumbers("company-1", employees)).thenReturn(List.of("SZSTSX109"));
        assertThat(service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, employees)).isEqualTo(COMPLETED);
        verify(source).fetchByBusinessKeys("oa-1", List.of(key));
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    private static EvidenceRows.MissingOaKeyScopeRow scopeRow(String key, String companyId, boolean targetDependency) {
        return new EvidenceRows.MissingOaKeyScopeRow(key, "raw-1", "OA_DOCUMENT", "normalized-1", "VALID",
                "OA_INTERVAL", "doc-1", key.substring(0, key.indexOf(':')), true, "MATCHED", "employee-other",
                companyId, "SZST0759", "SZST0759", targetDependency);
    }

    @Test
    void exactEmployeeRefreshMayReplaceOldNonconsumableOvertimeWithCurrentRealFormsWithoutTombstone() {
        var service = prepare();
        String key = "OVERTIME:old";
        mockSelectedMissingKey(key, false);
        var first = record("OVERTIME:morning", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        var second = record("OVERTIME:afternoon", SourceStatus.APPROVED, FROM.plusSeconds(18000));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(first, second));
        assertThat(service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other"))).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(first, second)));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void auditedExactPunchExclusionCanPermitSelectedRefreshButNeverCreatesCancellation() {
        var service = prepare();
        String key = "PUNCH_CORRECTION:old";
        mockSelectedMissingKey(key, true);
        assertThat(service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other"))).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void exactPunchWithoutAuditAndCurrentEligibleOvertimeBothStayMissing() {
        var service = prepare();
        String key = "PUNCH_CORRECTION:old";
        mockSelectedMissingKey(key, false);
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other")));
        key = "OVERTIME:old";
        mockSelectedMissingKey(key, false);
        when(evidence.findMissingOaCurrentEligibilityRows("oa-1", "company-1", List.of("employee-other"),
                List.of(key), COMPLETED)).thenReturn(List.of(new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc-1", "OVERTIME", REQUESTED, "ACTIVATED", "PAID", false, false, false, false, true)));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other")));
        verifyNoInteractions(pages);
    }

    @Test
    void currentEligibilityQueryFailureOrEmptyResultFailsClosed() {
        var service = prepare();
        String key = "OVERTIME:old";
        mockSelectedMissingKey(key, false);
        when(evidence.findMissingOaCurrentEligibilityRows(any(), any(), any(), any(), any())).thenReturn(List.of());
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other")));
        when(evidence.findMissingOaCurrentEligibilityRows(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("proof unavailable"));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other")));
        verifyNoInteractions(pages);
    }

    @Test
    void companyWideRefreshRequiresItsOwnCompleteEligibilityProofNotAnEmptyEmployeeScope() {
        var service = prepare();
        String key = "OVERTIME:old";
        mockSelectedMissingKey(key, false);
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", true)));
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZST0759"));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verify(evidence, never()).findMissingOaCurrentEligibilityRows(any(), any(), any(), any(), any());
        verifyNoInteractions(pages);
    }

    @Test
    void oneProvableMissingKeyDoesNotPermitAnotherUnprovenKey() {
        var service = prepare();
        String key = "OVERTIME:old";
        mockSelectedMissingKey(key, false);
        var keys = List.of(key, "PUNCH_CORRECTION:unproven");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(keys);
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", List.of("employee-other"), keys))
                .thenReturn(List.of(scopeRow(key, "company-1", true)));
        when(evidence.findMissingOaCurrentEligibilityRows("oa-1", "company-1", List.of("employee-other"), keys, COMPLETED))
                .thenReturn(List.of(new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc-1", "OVERTIME", REQUESTED, null, null, false, false, false, false, false)));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1),
                REQUESTED, List.of("employee-other")));
        verifyNoInteractions(pages);
    }

    @Test
    void wholeCompanyRefreshAcceptsProvenInactiveOvertimeButStillRereadsRealReplacementForms() {
        var service = prepare();
        String key = "OVERTIME:revoked";
        mockCompanyMissingKey(key, false, true, false);
        var replacement = record("OVERTIME:new-approved", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(replacement));
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);
        verify(source).fetchByBusinessKeys("oa-1", List.of(key));
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(replacement)));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
        verify(evidence, never()).findMissingOaCurrentEligibilityRows(any(), any(), any(), any(), any());
    }

    @Test
    void wholeCompanyExplicitExclusionPermitsOnlyTheExactPunchAndNoSourceStatusWrite() {
        var service = prepare();
        String key = "PUNCH_CORRECTION:old";
        mockCompanyMissingKey(key, true, true, false);
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void wholeCompanyEligibleOtAndUnauditedPcRemainMissingEvenIfHistoricalReportIsZero() {
        var service = prepare();
        mockCompanyMissingKey("OVERTIME:eligible", false, true, true);
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        mockCompanyMissingKey("PUNCH_CORRECTION:old", false, false, false);
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verifyNoInteractions(pages);
    }

    @Test
    void wholeCompanyEmptyOrUnavailableEligibilityFailsClosedWithoutPartialRematch() {
        var service = prepare();
        mockCompanyMissingKey("OVERTIME:revoked", false, false, true);
        when(evidence.findMissingOaCompanyCurrentEligibilityRows(any(), any(), any(), any())).thenReturn(List.of());
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        when(evidence.findMissingOaCompanyCurrentEligibilityRows(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("query failed"));
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verifyNoInteractions(pages);
    }

    @Test
    void wholeCompanyKnownInactiveOtCannotMaskAnotherMissingPunchOrLeave() {
        var service = prepare();
        String key = "OVERTIME:revoked";
        mockCompanyMissingKey(key, false, false, true);
        var keys = List.of(key, "LEAVE:draft");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(keys);
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", keys))
                .thenReturn(keys.stream().map(k -> scopeRow(k, "company-1", true)).toList());
        assertMissing(() -> service.refreshMonthForReport("company-1", MONTH, REQUESTED));
        verifyNoInteractions(pages);
    }

    private void mockCompanyMissingKey(String key, boolean excluded, boolean statusEligible, boolean contextEligible) {
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", true)));
        when(evidence.findCompanyEmployeeNumbers("company-1")).thenReturn(List.of("SZST0759"));
        when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                .thenReturn(List.of(new EvidenceRows.MissingOaCompanyCurrentEligibilityRow(key, "doc-1",
                        key.substring(0, key.indexOf(':')), REQUESTED, null, null, false, false,
                        excluded, false, contextEligible, statusEligible, false, true)));
    }

    @Test
    void wholeCompanyMissingNeverEffectiveDraftKeepsRealAnnualLeaveAndDoesNotMutateTheDraft() {
        var service = prepare();
        String key = "LEAVE:-2044581916237414171";
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                .thenReturn(List.of(new EvidenceRows.MissingOaCompanyCurrentEligibilityRow(key, "doc-1", "LEAVE",
                        REQUESTED, null, null, false, false, false, false, false, false, true, false)));
        var realAnnualLeave = record("LEAVE:-4577660915740619130", SourceStatus.APPROVED, FROM.plusSeconds(3600));
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(realAnnualLeave));
        assertThat(service.refreshMonthForReport("company-1", MONTH, REQUESTED)).isEqualTo(COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(realAnnualLeave)));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void explicitEmployeeDateRangeUsesGlobalNeverConsumedDraftProofAndPreservesApprovedAnnualLeave() {
        var service = prepare();
        String key = "LEAVE:-2044581916237414171";
        Instant from = Instant.parse("2026-09-28T16:00:00Z");
        mockSelectedNeverConsumedDraft(key, from);
        var annual = record("LEAVE:-4577660915740619130", SourceStatus.APPROVED,
                Instant.parse("2026-09-30T00:30:00Z"));
        when(source.fetchOverlapping("oa-1", from, TO)).thenReturn(List.of(annual));
        assertThat(service.refreshRangeForReport("company-1", LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 1), REQUESTED, List.of("employee-other"))).isEqualTo(COMPLETED);
        verify(source).fetchByBusinessKeys("oa-1", List.of(key));
        verify(evidence).findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key));
        verify(evidence).findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED);
        verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of(annual)));
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"any-old-zero-fact", "independent-link", "conflicting-report-identity",
            "future-document", "ever-approved", "unknown-reader-status", "empty-history",
            "incomplete-history", "missing-current", "history-query-failure", "current-query-failure"})
    void selectedEmployeeDraftProofRejectsEveryMissingGlobalGuard(String obstacle) {
        var service = prepare();
        String key = "LEAVE:-2044581916237414171";
        mockSelectedNeverConsumedDraft(key, FROM);
        var eligibility = new EvidenceRows.MissingOaCompanyCurrentEligibilityRow(key, "doc-1", "LEAVE",
                REQUESTED, null, null, obstacle.equals("independent-link"),
                obstacle.equals("conflicting-report-identity"), false, obstacle.equals("future-document"),
                false, obstacle.equals("unknown-reader-status") ? null : false,
                !obstacle.equals("ever-approved"), obstacle.equals("any-old-zero-fact"));
        when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                .thenReturn(List.of(eligibility));
        if (obstacle.equals("empty-history")) {
            when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key))).thenReturn(List.of());
        } else if (obstacle.equals("incomplete-history")) {
            var unresolved = new EvidenceRows.MissingOaKeyScopeRow(key, "raw-2", "OA_DOCUMENT", null,
                    null, null, null, null, false, null, null, null, null, null, false);
            when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                    .thenReturn(List.of(scopeRow(key, "company-1", false), unresolved));
        } else if (obstacle.equals("missing-current")) {
            when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                    .thenReturn(List.of());
        } else if (obstacle.equals("history-query-failure")) {
            when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                    .thenThrow(new IllegalStateException("unavailable"));
        } else if (obstacle.equals("current-query-failure")) {
            when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                    .thenThrow(new IllegalStateException("unavailable"));
        }
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, List.of("employee-other")));
        verifyNoInteractions(pages);
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
    }

    @Test
    void oneProvenDraftNeverAllowsAnotherUnresolvedLeaveForTheSelectedEmployee() {
        var service = prepare();
        String key = "LEAVE:-2044581916237414171";
        mockSelectedNeverConsumedDraft(key, FROM);
        var keys = List.of(key, "LEAVE:unknown");
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(keys);
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", List.of("employee-other"), keys))
                .thenReturn(keys.stream().map(k -> scopeRow(k, "company-1", false)).toList());
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", keys))
                .thenReturn(keys.stream().map(k -> scopeRow(k, "company-1", false)).toList());
        when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", keys, COMPLETED))
                .thenReturn(List.of(neverConsumedDraft(key)));
        assertMissing(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1),
                MONTH.plusMonths(1).atDay(1), REQUESTED, List.of("employee-other")));
        verifyNoInteractions(pages);
    }

    private void mockSelectedNeverConsumedDraft(String key, Instant from) {
        when(evidence.findOaBusinessKeysOverlapping("oa-1", from, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", List.of("employee-other"), List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findReportEmployeeNumbers("company-1", List.of("employee-other"))).thenReturn(List.of("SZST0759"));
        when(evidence.findMissingOaKeyScopeRows("oa-1", "company-1", List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", false)));
        when(evidence.findMissingOaCompanyCurrentEligibilityRows("oa-1", "company-1", List.of(key), COMPLETED))
                .thenReturn(List.of(neverConsumedDraft(key)));
    }

    private static EvidenceRows.MissingOaCompanyCurrentEligibilityRow neverConsumedDraft(String key) {
        return new EvidenceRows.MissingOaCompanyCurrentEligibilityRow(key, "doc-1", "LEAVE", REQUESTED,
                null, null, false, false, false, false, false, false, true, false);
    }

    private void mockSelectedMissingKey(String key, boolean excluded) {
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(key));
        when(evidence.findMissingOaKeyEmployeeScopeRows("oa-1", "company-1", List.of("employee-other"), List.of(key)))
                .thenReturn(List.of(scopeRow(key, "company-1", true)));
        when(evidence.findReportEmployeeNumbers("company-1", List.of("employee-other"))).thenReturn(List.of("SZST0759"));
        when(evidence.findMissingOaCurrentEligibilityRows("oa-1", "company-1", List.of("employee-other"), List.of(key), COMPLETED))
                .thenReturn(List.of(new EvidenceRows.MissingOaCurrentEligibilityRow(key, "doc-1",
                        key.substring(0, key.indexOf(':')), REQUESTED, null, null, false, false, excluded, false, false)));
    }

    private static void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiProblemException.class).extracting("code")
                .isEqualTo("OA_REPORT_SOURCE_DOCUMENT_MISSING");
    }

    private void mockEmployeePendingScope(OaDocumentRecord candidate, List<String> employees) {
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(candidate));
        when(evidence.findUnparseableLeaveEmployeeScopeRows("oa-1", "company-1", employees,
                List.of(candidate.sourceBusinessKey())))
                .thenReturn(List.of(UnparseablePendingLeaveReportScopeProofTest.history("company-1", "employee-other", "SZST0001", "SZST0001",
                        false, "UNKNOWN", UnparseablePendingLeaveReportScopeProofTest.VERSION, "L1", false)));
        when(evidence.findReportEmployeeNumbers("company-1", employees)).thenReturn(List.of("SZST9001"));
    }

    @Test
    void explicitEmployeeDraftIsolationUsesSelectedHistoryBeforePreflightAndCountsItTransactionally() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-target");
        mockEmployeePendingScope(current, employees);
        when(source.hasNoLeaveRevocationsForSerial("oa-1", "L1")).thenReturn(true);
        when(pages.rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()), eq(1)))
                .thenReturn(new OaDocumentPageTransaction.PageCommitResult(0, 1));
        assertThat(service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees)).isEqualTo(COMPLETED);
        var order = inOrder(evidence, source, pages);
        order.verify(evidence).findUnparseableLeaveEmployeeScopeRows("oa-1", "company-1", employees, List.of(current.sourceBusinessKey()));
        order.verify(evidence).findReportEmployeeNumbers("company-1", employees);
        order.verify(source).hasNoLeaveRevocationsForSerial("oa-1", "L1");
        order.verify(pages).preflightUnsafeChanges("oa-1", List.of());
        order.verify(pages).rematchRecords(eq(job), eq("SYSTEM"), anyString(), anyString(), eq(List.of()), eq(1));
        verify(evidence, never()).findUnparseableLeaveScopeRows(any(), any(), any());
        verify(evidence, never()).findCompanyEmployeeNumbers(any());
        verify(evidence, never()).insertRawFact(any());
        verify(evidence, never()).insertOaAttendanceDocument(any());
        verify(jobs, never()).incrementJobCounters(anyString(), anyInt(), anyInt());
        verify(jobs).markFinished("job-1", "PARTIALLY_QUARANTINED", COMPLETED);
    }

    @Test
    void selectedEmployeeOwnInvalidPendingLeaveStillHitsOriginalPreflight() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-other");
        mockEmployeePendingScope(current, employees);
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(current));
        assertQuarantined(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verify(source, never()).hasNoLeaveRevocationsForSerial(any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void employeeDraftIsolationRequiresSourceSerialAbsenceAndLeavesRuntimeCheckInPlace() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-target");
        mockEmployeePendingScope(current, employees);
        when(source.hasNoLeaveRevocationsForSerial("oa-1", "L1")).thenReturn(false);
        doThrow(new AttendanceSourceSyncFailure("OA_REPORT_SOURCE_QUARANTINED"))
                .when(pages).preflightUnsafeChanges("oa-1", List.of(current));
        assertQuarantined(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verify(source).hasNoLeaveRevocationsForSerial("oa-1", "L1");
        verify(pages, never()).rematchRecords(any(), any(), any(), any(), any());
    }

    @Test
    void employeeDraftIsolationHistoryOrSourceQueryFailureNeverStartsWrites() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-target");
        mockEmployeePendingScope(current, employees);
        when(evidence.findUnparseableLeaveEmployeeScopeRows(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("fixture history unavailable"));
        assertQuarantined(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verifyNoInteractions(pages);
    }

    @Test
    void employeeScopeDoesNotHideConflictingValidWindowAndInvalidByKeyRecord() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-target");
        mockEmployeePendingScope(current, employees);
        when(source.fetchOverlapping("oa-1", FROM, TO)).thenReturn(List.of(record(current.sourceBusinessKey(), SourceStatus.APPROVED, FROM.plusSeconds(3600))));
        when(evidence.findOaBusinessKeysOverlapping("oa-1", FROM, TO)).thenReturn(List.of(current.sourceBusinessKey()));
        when(source.fetchByBusinessKeys("oa-1", List.of(current.sourceBusinessKey()))).thenReturn(List.of(current));
        assertThatThrownBy(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("OA_REPORT_SOURCE_REFRESH_FAILED");
        verify(evidence, never()).findUnparseableLeaveEmployeeScopeRows(any(), any(), any(), any());
        verifyNoInteractions(pages);
    }

    @Test
    void employeeDraftIsolationSourceAbsenceQueryExceptionCannotDiscardTheRecord() {
        var service = prepare();
        var current = UnparseablePendingLeaveReportScopeProofTest.candidate();
        var employees = List.of("employee-target");
        mockEmployeePendingScope(current, employees);
        when(source.hasNoLeaveRevocationsForSerial("oa-1", "L1"))
                .thenThrow(new IllegalStateException("fixture source unavailable"));
        assertQuarantined(() -> service.refreshRangeForReport("company-1", MONTH.atDay(1), MONTH.plusMonths(1).atDay(1), REQUESTED, employees));
        verifyNoInteractions(pages);
    }

}

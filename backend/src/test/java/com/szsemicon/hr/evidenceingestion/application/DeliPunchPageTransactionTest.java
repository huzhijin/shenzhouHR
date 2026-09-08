package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncFailure;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.PageState;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels.SourceJobStart;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.port.AttendanceConfigurationResolverPort;
import com.szsemicon.hr.evidenceingestion.port.AttendancePeriodProtectionPort;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeliPunchPageTransactionTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");
    private static final Instant EARLY_MORNING_PUNCH =
            Instant.parse("2026-07-28T21:30:00Z");
    private static final String ACTOR =
            "10000000-0000-0000-0000-000000000001";
    private static final String REQUEST = "request-1";
    private static final String MATCH_DIGEST = "a".repeat(64);
    private static final String CONFIG_DIGEST = "b".repeat(64);
    private static final String PERIOD_DIGEST = "c".repeat(64);

    private final AttendanceSourceSyncRepository syncRepository =
            mock(AttendanceSourceSyncRepository.class);
    private final AttendanceEvidenceRepository evidenceRepository =
            mock(AttendanceEvidenceRepository.class);
    private final EmployeeEmploymentResolverPort employeeResolver =
            mock(EmployeeEmploymentResolverPort.class);
    private final AttendanceConfigurationResolverPort configurationResolver =
            mock(AttendanceConfigurationResolverPort.class);
    private final AttendancePeriodProtectionPort periodProtection =
            mock(AttendancePeriodProtectionPort.class);

    private final DeliPunchPageTransaction transaction =
            new DeliPunchPageTransaction(
                    syncRepository,
                    evidenceRepository,
                    employeeResolver,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void pageCanBeCommitted() {
        when(syncRepository.lockPageForCommit(
                        eq("job-1"),
                        eq("source-1"),
                        eq(ACTOR),
                        any(),
                        eq(NOW)))
                .thenReturn(new PageState(
                        "job-1",
                        "source-1",
                        "legal-1",
                        "RUNNING",
                        0,
                        null,
                        7));
    }

    @Test
    void matchedEarlyMorningPunchCreatesEffectiveEventAndBothCandidateDates() {
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "deli-ext-1",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-1",
                                "employment-1",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-1", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-1"), any()))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(2);
                    return new AttendancePeriodProtectionPort.Protection(
                            AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                            "period-" + date,
                            PERIOD_DIGEST);
                });
        when(evidenceRepository.findExactEvents(
                        "legal-1",
                        "employee-1",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of());

        var outcome = transaction.commitPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record(null, "deli-ext-1")),
                configurationResolver,
                periodProtection);

        assertThat(outcome.acceptedCount()).isEqualTo(1);
        assertThat(outcome.quarantinedCount()).isZero();
        ArgumentCaptor<EvidenceRows.RawFactRow> raw =
                ArgumentCaptor.forClass(EvidenceRows.RawFactRow.class);
        verify(evidenceRepository).insertRawFact(raw.capture());
        assertThat(raw.getValue().rawObjectRef()).isNull();
        assertThat(raw.getValue().canonicalPayloadDigest())
                .matches("[0-9a-f]{64}");
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(EvidenceRows.NormalizedRecordRow.class);
        ArgumentCaptor<EvidenceRows.MatchDecisionRow> match =
                ArgumentCaptor.forClass(EvidenceRows.MatchDecisionRow.class);
        ArgumentCaptor<EvidenceRows.EffectiveEventRow> event =
                ArgumentCaptor.forClass(EvidenceRows.EffectiveEventRow.class);
        ArgumentCaptor<EvidenceRows.LifecycleFactRow> lifecycle =
                ArgumentCaptor.forClass(EvidenceRows.LifecycleFactRow.class);
        ArgumentCaptor<EvidenceRows.EvidenceLinkRow> link =
                ArgumentCaptor.forClass(EvidenceRows.EvidenceLinkRow.class);
        verify(evidenceRepository).insertNormalizedRecord(normalized.capture());
        verify(evidenceRepository).insertMatchDecision(match.capture());
        verify(evidenceRepository).insertEffectiveEvent(event.capture());
        verify(evidenceRepository).insertLifecycleFact(lifecycle.capture());
        verify(evidenceRepository).insertEvidenceLink(link.capture());
        assertThat(normalized.getValue().rawAttendanceFactId())
                .isEqualTo(raw.getValue().rawAttendanceFactId());
        assertThat(match.getValue().normalizedAttendanceRecordId())
                .isEqualTo(normalized.getValue().normalizedAttendanceRecordId());
        assertThat(match.getValue().matchStatus()).isEqualTo("MATCHED");
        assertThat(link.getValue().effectiveAttendanceEventId())
                .isEqualTo(event.getValue().effectiveAttendanceEventId());
        assertThat(link.getValue().rawAttendanceFactId())
                .isEqualTo(raw.getValue().rawAttendanceFactId());
        assertThat(link.getValue().normalizedAttendanceRecordId())
                .isEqualTo(normalized.getValue().normalizedAttendanceRecordId());
        assertThat(link.getValue().employeeMatchDecisionId())
                .isEqualTo(match.getValue().employeeMatchDecisionId());
        assertThat(link.getValue().linkType()).isEqualTo("PRIMARY");
        assertThat(lifecycle.getValue().effectiveAttendanceEventId())
                .isEqualTo(event.getValue().effectiveAttendanceEventId());
        assertThat(lifecycle.getValue().lifecycleType())
                .isEqualTo("ACTIVATED");

        ArgumentCaptor<EvidenceRows.RecalculationIntentRow> intents =
                ArgumentCaptor.forClass(
                        EvidenceRows.RecalculationIntentRow.class);
        verify(evidenceRepository, org.mockito.Mockito.times(2))
                .insertRecalculationIntent(intents.capture());
        assertThat(intents.getAllValues())
                .extracting(EvidenceRows.RecalculationIntentRow::businessDate)
                .containsExactlyInAnyOrder(
                        LocalDate.parse("2026-07-28"),
                        LocalDate.parse("2026-07-29"));
        verify(syncRepository).advanceWatermark(
                "source-1", 7, "1", "d".repeat(64), NOW);
        verify(syncRepository).incrementJobCounters("job-1", 1, 0);
    }

    @Test
    void unmatchedPunchIsQuarantinedAndWatermarkStillAdvances() {
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "E404", EARLY_MORNING_PUNCH))
                .thenReturn(List.of());
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "deli-user-404",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of());

        var outcome = transaction.commitPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("E404", "deli-user-404")),
                configurationResolver,
                periodProtection);

        assertThat(outcome.acceptedCount()).isZero();
        assertThat(outcome.quarantinedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(
                        EvidenceRows.NormalizedRecordRow.class);
        verify(evidenceRepository).insertNormalizedRecord(
                normalized.capture());
        assertThat(normalized.getValue().validationStatus())
                .isEqualTo("QUARANTINED");
        assertThat(normalized.getValue().issueCode())
                .isEqualTo("EMPLOYEE_UNMATCHED");
        verify(configurationResolver, never()).resolve(any(), any(), any());
        verify(periodProtection, never()).protectionFor(any(), any(), any());
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
        verify(evidenceRepository, never()).insertRecalculationIntent(any());
        verify(syncRepository).advanceWatermark(
                "source-1", 7, "1", "d".repeat(64), NOW);
        verify(syncRepository).incrementJobCounters("job-1", 0, 1);
    }

    @Test
    void missingAttendanceConfigurationQuarantinesAndDoesNotStopThePage() {
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "deli-ext-1",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-1",
                                "employment-1",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-1", EARLY_MORNING_PUNCH))
                .thenReturn(null);

        var outcome = transaction.commitPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record(null, "deli-ext-1")),
                configurationResolver,
                periodProtection);

        assertThat(outcome.acceptedCount()).isZero();
        assertThat(outcome.quarantinedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.NormalizedRecordRow> normalized =
                ArgumentCaptor.forClass(
                        EvidenceRows.NormalizedRecordRow.class);
        verify(evidenceRepository).insertNormalizedRecord(
                normalized.capture());
        assertThat(normalized.getValue().validationStatus())
                .isEqualTo("QUARANTINED");
        assertThat(normalized.getValue().issueCode())
                .isEqualTo("ATTENDANCE_CONFIGURATION_UNAVAILABLE");
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
        verify(syncRepository).advanceWatermark(
                "source-1", 7, "1", "d".repeat(64), NOW);
    }

    @Test
    void protectedPeriodFailsBeforeEvidenceOrWatermarkMutation() {
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "deli-ext-1",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-1",
                                "employment-1",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-1", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-1"), any()))
                .thenReturn(new AttendancePeriodProtectionPort.Protection(
                        AttendancePeriodProtectionPort.PeriodStatus.CLOSED,
                        "period-v1",
                        PERIOD_DIGEST));

        assertThatThrownBy(() -> transaction.commitPage(
                        job(),
                        ACTOR,
                        REQUEST,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(record(null, "deli-ext-1")),
                        configurationResolver,
                        periodProtection))
                .isInstanceOf(AttendanceSourceSyncFailure.class)
                .hasMessage("ATTENDANCE_PERIOD_PROTECTED");

        verify(evidenceRepository, never()).insertRawFact(any());
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
    }

    @Test
    void unavailablePeriodProtectionFailsClosedBeforeWatermarkAdvance() {
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "deli-ext-1",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-1",
                                "employment-1",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-1", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-1"), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> transaction.commitPage(
                        job(),
                        ACTOR,
                        REQUEST,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        page(record(null, "deli-ext-1")),
                        configurationResolver,
                        periodProtection))
                .isInstanceOf(AttendanceSourceSyncFailure.class)
                .hasMessage("ATTENDANCE_PERIOD_PROTECTION_UNAVAILABLE");

        verify(evidenceRepository, never()).insertRawFact(any());
        verify(syncRepository, never()).insertCommittedPage(any());
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
        verify(syncRepository, never())
                .incrementJobCounters(any(), anyInt(), anyInt());
    }

    @Test
    void kqPageAdvancesTheKqWatermarkOnly() {
        when(syncRepository.lockKqPageForCommit(
                        eq("job-1"),
                        eq("source-1"),
                        eq(ACTOR),
                        any(),
                        eq(NOW)))
                .thenReturn(new PageState(
                        "job-1",
                        "source-1",
                        "legal-1",
                        "RUNNING",
                        0,
                        "12848301274",
                        7));
        when(employeeResolver.resolveByConfirmedBinding(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(employeeResolver.resolveByEmployeeNumber(any(), any(), any()))
                .thenReturn(List.of());
        when(evidenceRepository.findRawBySourceIdentity(any(), any(), any()))
                .thenReturn(null);

        DeliPunchSourcePort.DeliPunchRecord kqRecord =
                new DeliPunchSourcePort.DeliPunchRecord(
                        "12851355327",
                        "v".repeat(64),
                        "1191370861845925889",
                        ConfirmedBindingKind.DELI_USER_ID,
                        "SZST0542",
                        null,
                        EARLY_MORNING_PUNCH,
                        "1785274200",
                        "Asia/Shanghai",
                        Direction.AUTO,
                        "fp",
                        "13750C_8D32C1032484A20A",
                        null,
                        "UNKNOWN",
                        true);
        var kqPage = new DeliPunchSourcePort.DeliPage(
                List.of(kqRecord),
                "12848301274",
                "12852026233",
                "d".repeat(64));

        DeliPunchPageTransaction.PageCommitResult result =
                transaction.commitKqPage(
                        job(),
                        ACTOR,
                        REQUEST,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        1,
                        kqPage,
                        configurationResolver,
                        periodProtection);

        assertThat(result.acceptedCount() + result.quarantinedCount())
                .isEqualTo(1);
        verify(syncRepository).advanceKqWatermark(
                eq("source-1"),
                eq(7L),
                eq("12852026233"),
                eq("d".repeat(64)),
                eq(NOW));
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
    }

    @Test
    void identityReplayMovesWrongEmployeeWithoutAdvancingWatermark() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("digest-old"));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        1,
                        "VALID",
                        null,
                        "MATCHED",
                        "EMPLOYEE_NUMBER",
                        "employee-zhao",
                        "employment-zhao",
                        "event-zhao",
                        "employee-zhao"));
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "939805188834107393",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-peng",
                                "employment-peng",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-peng", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-peng"), any()))
                .thenReturn(new AttendancePeriodProtectionPort.Protection(
                        AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                        "period-open",
                        PERIOD_DIGEST));
        when(evidenceRepository.findExactEvents(
                        "legal-1",
                        "employee-peng",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of());

        var outcome = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZST0289", "939805188834107393")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(outcome.identityMovedCount()).isEqualTo(1);
        assertThat(outcome.identityReplayedCount()).isEqualTo(1);
        ArgumentCaptor<EvidenceRows.LifecycleFactRow> lifecycle =
                ArgumentCaptor.forClass(EvidenceRows.LifecycleFactRow.class);
        verify(evidenceRepository, times(2)).insertLifecycleFact(lifecycle.capture());
        assertThat(lifecycle.getAllValues())
                .extracting(EvidenceRows.LifecycleFactRow::lifecycleType)
                .containsExactly("SUPERSEDED", "ACTIVATED");
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
        verify(syncRepository, never()).lockPageForCommit(
                any(), any(), any(), any(), any());
        verify(evidenceRepository).insertEffectiveEvent(any());
    }

    @Test
    void identityReplayMovesWrongEmpnoWhenCheckinMemberNameIsUnique() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("digest-old"));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        1,
                        "VALID",
                        null,
                        "MATCHED",
                        "EMPLOYEE_NUMBER",
                        "employee-zhao",
                        "employment-zhao",
                        "event-zhao",
                        "employee-zhao"));
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "SZST0289", EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-zhao",
                                "employment-zhao",
                                MATCH_DIGEST)));
        when(employeeResolver.resolveByDisplayName(
                        "legal-1", "彭伟", EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-peng",
                                "employment-peng",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-peng", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-peng"), any()))
                .thenReturn(new AttendancePeriodProtectionPort.Protection(
                        AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                        "period-open",
                        PERIOD_DIGEST));
        when(evidenceRepository.findExactEvents(
                        "legal-1",
                        "employee-peng",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of());

        var outcome = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record(
                        "SZST0289",
                        "218",
                        ConfirmedBindingKind.DELI_USER_ID,
                        "彭伟")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(outcome.identityMovedCount()).isEqualTo(1);
        verify(evidenceRepository).insertEffectiveEvent(any());
    }

    @Test
    void identityReplayIsIdempotentWhenEmployeeAlreadyMatches() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("a".repeat(64)));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        2,
                        "VALID",
                        null,
                        "MATCHED",
                        "CONFIRMED_BINDING",
                        "employee-peng",
                        "employment-peng",
                        "event-peng",
                        "employee-peng"));
        when(employeeResolver.resolveByConfirmedBinding(
                        "source-1",
                        "legal-1",
                        null,
                        "terminal-1",
                        ConfirmedBindingKind.DELI_EXT_ID,
                        "939805188834107393",
                        EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-peng",
                                "employment-peng",
                                MATCH_DIGEST)));

        var first = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZST0289", "939805188834107393")),
                configurationResolver,
                periodProtection,
                false);
        var second = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZST0289", "939805188834107393")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(first.identityMovedCount()).isZero();
        assertThat(second.identityMovedCount()).isZero();
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
        verify(evidenceRepository, never()).insertLifecycleFact(any());
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
    }

    @Test
    void identityReplayKeepsStillQuarantinedVisible() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("a".repeat(64)));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        1,
                        "QUARANTINED",
                        "NO_AUTHORITATIVE_MATCH",
                        "UNMATCHED",
                        "NO_AUTHORITATIVE_MATCH",
                        null,
                        null,
                        null,
                        null));
        when(employeeResolver.resolveByConfirmedBinding(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(employeeResolver.resolveByEmployeeNumber(any(), any(), any()))
                .thenReturn(List.of());

        var outcome = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZST9999", "939805188834107393")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(outcome.quarantinedCount()).isEqualTo(1);
        assertThat(outcome.stillQuarantined()).hasSize(1);
        assertThat(outcome.stillQuarantined().getFirst().reason())
                .isEqualTo("NO_AUTHORITATIVE_MATCH");
        assertThat(outcome.stillQuarantined().getFirst().employeeNumber())
                .isEqualTo("SZST9999");
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
    }

    @Test
    void matchedPunchUsesEmployeeCompanyForSetupAndEvent() {
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "SZJN0002", EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-zhou",
                                "employment-zhou",
                                MATCH_DIGEST,
                                "company-jn")));
        when(configurationResolver.resolve(
                        "company-jn", "employee-zhou", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-jn",
                        "group-revision-jn",
                        "shift-version-jn",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("company-jn"), eq("employee-zhou"), any()))
                .thenAnswer(invocation -> {
                    LocalDate date = invocation.getArgument(2);
                    return new AttendancePeriodProtectionPort.Protection(
                            AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                            "period-" + date,
                            PERIOD_DIGEST);
                });
        when(evidenceRepository.findExactEvents(
                        "company-jn",
                        "employee-zhou",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of());

        var outcome = transaction.commitPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZJN0002", "1045290054807404544")),
                configurationResolver,
                periodProtection);

        assertThat(outcome.acceptedCount()).isEqualTo(1);
        verify(configurationResolver, never())
                .resolve(eq("legal-1"), eq("employee-zhou"), any());
        ArgumentCaptor<EvidenceRows.EffectiveEventRow> event =
                ArgumentCaptor.forClass(EvidenceRows.EffectiveEventRow.class);
        verify(evidenceRepository).insertEffectiveEvent(event.capture());
        assertThat(event.getValue().companyId()).isEqualTo("company-jn");
        assertThat(event.getValue().employeeId()).isEqualTo("employee-zhou");
        ArgumentCaptor<EvidenceRows.RecalculationIntentRow> intents =
                ArgumentCaptor.forClass(
                        EvidenceRows.RecalculationIntentRow.class);
        verify(evidenceRepository, times(2))
                .insertRecalculationIntent(intents.capture());
        assertThat(intents.getAllValues())
                .extracting(EvidenceRows.RecalculationIntentRow::companyId)
                .containsOnly("company-jn");
    }

    @Test
    void identityReplayRetriesMatchedConfigQuarantineWithEmployeeCompany() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("a".repeat(64)));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        1,
                        "QUARANTINED",
                        "ATTENDANCE_CONFIGURATION_UNAVAILABLE",
                        "MATCHED",
                        "EMPLOYEE_NUMBER",
                        "employee-zhou",
                        "employment-zhou",
                        null,
                        null));
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "SZJN0002", EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-zhou",
                                "employment-zhou",
                                MATCH_DIGEST,
                                "company-jn")));
        when(configurationResolver.resolve(
                        "company-jn", "employee-zhou", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-jn",
                        "group-revision-jn",
                        "shift-version-jn",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("company-jn"), eq("employee-zhou"), any()))
                .thenReturn(new AttendancePeriodProtectionPort.Protection(
                        AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                        "period-open",
                        PERIOD_DIGEST));
        when(evidenceRepository.findExactEvents(
                        "company-jn",
                        "employee-zhou",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of());

        var outcome = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZJN0002", "1045290054807404544")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(outcome.acceptedCount()).isEqualTo(1);
        assertThat(outcome.quarantinedCount()).isZero();
        assertThat(outcome.identityMovedCount()).isZero();
        verify(configurationResolver, never())
                .resolve(eq("legal-1"), eq("employee-zhou"), any());
        ArgumentCaptor<EvidenceRows.EffectiveEventRow> event =
                ArgumentCaptor.forClass(EvidenceRows.EffectiveEventRow.class);
        verify(evidenceRepository).insertEffectiveEvent(event.capture());
        assertThat(event.getValue().companyId()).isEqualTo("company-jn");
        verify(syncRepository, never()).advanceWatermark(
                any(), any(Long.class), any(), any(), any());
    }

    @Test
    void identityReplaySkipsDuplicateEvidenceLinkForSameRawAndEvent() {
        when(evidenceRepository.findRawBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(existingRaw("a".repeat(64)));
        when(evidenceRepository.findReplayStateBySourceIdentity(
                        "source-1", "record-1", "version-1"))
                .thenReturn(new EvidenceRows.ReplayStateRow(
                        "raw-1",
                        "norm-1",
                        1,
                        "QUARANTINED",
                        "NO_AUTHORITATIVE_MATCH",
                        "UNMATCHED",
                        "NO_AUTHORITATIVE_MATCH",
                        null,
                        null,
                        null,
                        null));
        when(employeeResolver.resolveByConfirmedBinding(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "SZST0560", EARLY_MORNING_PUNCH))
                .thenReturn(List.of(
                        new EmployeeEmploymentResolverPort.Resolution(
                                "employee-han",
                                "employment-han",
                                MATCH_DIGEST)));
        when(configurationResolver.resolve(
                        "legal-1", "employee-han", EARLY_MORNING_PUNCH))
                .thenReturn(new AttendanceConfigurationResolverPort.Resolution(
                        "location-1",
                        "group-revision-1",
                        "shift-version-1",
                        ZoneId.of("Asia/Shanghai"),
                        Set.of(LocalDate.parse("2026-07-29")),
                        CONFIG_DIGEST,
                        true));
        when(periodProtection.protectionFor(
                        eq("legal-1"), eq("employee-han"), any()))
                .thenReturn(new AttendancePeriodProtectionPort.Protection(
                        AttendancePeriodProtectionPort.PeriodStatus.OPEN,
                        "period-open",
                        PERIOD_DIGEST));
        when(evidenceRepository.findExactEvents(
                        "legal-1",
                        "employee-han",
                        EARLY_MORNING_PUNCH,
                        "AUTO"))
                .thenReturn(List.of(new EvidenceRows.EffectiveEventRow(
                        "event-existing",
                        "legal-1",
                        "employee-han",
                        "PUNCH_POINT",
                        "AUTO",
                        EARLY_MORNING_PUNCH,
                        null,
                        null,
                        "digest",
                        NOW)));
        when(evidenceRepository.hasEvidenceLink("event-existing", "raw-1"))
                .thenReturn(true);

        var outcome = transaction.commitReplayPage(
                job(),
                ACTOR,
                REQUEST,
                CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                1,
                page(record("SZST0560", "han-ext-1")),
                configurationResolver,
                periodProtection,
                false);

        assertThat(outcome.identityReplayedCount()).isEqualTo(1);
        verify(evidenceRepository, never()).insertEffectiveEvent(any());
        verify(evidenceRepository, never()).insertEvidenceLink(any());
    }

    private static EvidenceRows.RawFactRow existingRaw(String digest) {
        return new EvidenceRows.RawFactRow(
                "raw-1",
                "source-1",
                "legal-1",
                "PUNCH_POINT",
                "record-1",
                "version-1",
                null,
                "1785274200",
                "Asia/Shanghai",
                EARLY_MORNING_PUNCH,
                null,
                null,
                digest,
                null,
                REQUEST,
                NOW,
                ACTOR);
    }

    private static SourceJobStart job() {
        return new SourceJobStart(
                "job-1",
                "source-1",
                "legal-1",
                "正式得力 E+",
                "DELI_EPLUS_APP_CREDENTIALS",
                "Asia/Shanghai",
                500,
                10_000,
                0,
                null);
    }

    private static DeliPunchSourcePort.DeliPage page(
            DeliPunchSourcePort.DeliPunchRecord record) {
        return new DeliPunchSourcePort.DeliPage(
                List.of(record), "0", "1", "d".repeat(64));
    }

    private static DeliPunchSourcePort.DeliPunchRecord record(
            String employeeNumber, String externalPersonRef) {
        return record(
                employeeNumber,
                externalPersonRef,
                ConfirmedBindingKind.DELI_EXT_ID,
                null);
    }

    private static DeliPunchSourcePort.DeliPunchRecord record(
            String employeeNumber,
            String externalPersonRef,
            ConfirmedBindingKind kind,
            String memberName) {
        return new DeliPunchSourcePort.DeliPunchRecord(
                "record-1",
                "version-1",
                externalPersonRef,
                kind,
                employeeNumber,
                memberName,
                EARLY_MORNING_PUNCH,
                "1785274200",
                "Asia/Shanghai",
                Direction.AUTO,
                "fp",
                "terminal-1",
                null,
                "UNKNOWN",
                true);
    }
}

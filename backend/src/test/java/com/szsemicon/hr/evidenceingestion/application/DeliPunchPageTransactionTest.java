package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
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
        verify(evidenceRepository).insertEffectiveEvent(any());
        verify(evidenceRepository).insertLifecycleFact(any());

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
    void unmatchedPunchIsQuarantinedAndCannotCreateEffectiveData() {
        when(employeeResolver.resolveByEmployeeNumber(
                        "legal-1", "E404", EARLY_MORNING_PUNCH))
                .thenReturn(List.of());
        when(employeeResolver.resolveByConfirmedBinding(
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
        verify(syncRepository).incrementJobCounters("job-1", 0, 1);
    }

    @Test
    void protectedPeriodFailsBeforeEvidenceOrWatermarkMutation() {
        when(employeeResolver.resolveByConfirmedBinding(
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
        return new DeliPunchSourcePort.DeliPunchRecord(
                "record-1",
                "version-1",
                externalPersonRef,
                ConfirmedBindingKind.DELI_EXT_ID,
                employeeNumber,
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

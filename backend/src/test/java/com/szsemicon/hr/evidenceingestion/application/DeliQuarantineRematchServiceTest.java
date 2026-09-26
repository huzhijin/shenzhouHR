package com.szsemicon.hr.evidenceingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.DeliPunchPageTransaction.PromotedPunch;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchReplayApplicationService.ReplayResult;
import com.szsemicon.hr.evidenceingestion.application.EmployeeDeliBindingSeedService.SeedResult;
import com.szsemicon.hr.reporting.application.RealtimeAttendanceReportSnapshotService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeliQuarantineRematchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Mock
    private DeliPunchReplayApplicationService replay;
    @Mock
    private AttendanceSourceSyncRepository syncRepository;
    @Mock
    private RealtimeAttendanceReportSnapshotService snapshots;

    @Test
    void newHireWaitsUntilQuietThenPromotesAndRecalculates() {
        var service = service(Duration.ofMinutes(2));
        service.noteHire(LocalDate.parse("2026-09-22"));
        service.noteHire(LocalDate.parse("2026-09-20"));
        service.runIfDue();
        verify(replay, never()).replayForRecovery(any(), any(), any());

        var later = new DeliQuarantineRematchService(
                true,
                31,
                62,
                Duration.ZERO,
                replay,
                syncRepository,
                snapshots,
                Clock.fixed(NOW.plusSeconds(180), ZoneOffset.UTC));
        later.noteHire(LocalDate.parse("2026-09-22"));
        later.noteHire(LocalDate.parse("2026-09-20"));
        when(syncRepository.findAllActiveDeliSourceIds()).thenReturn(List.of("source-1"));
        when(replay.replayForRecovery(
                        eq("source-1"),
                        eq(LocalDate.parse("2026-09-20")),
                        eq(LocalDate.parse("2026-09-24"))))
                .thenReturn(result(new PromotedPunch(
                        "company-1",
                        "employee-1",
                        Instant.parse("2026-09-20T01:00:00Z"))));

        later.runIfDue();

        verify(snapshots).materializeEmployeeRange(
                "company-1",
                "employee-1",
                LocalDate.parse("2026-09-20"),
                LocalDate.parse("2026-09-24"),
                NOW.plusSeconds(180));
        verify(snapshots, never()).materializeCompanyMonthWindow(any(), any(), any(), any());
    }

    @Test
    void eveningUsesLookbackAndCapsVeryOldHireDates() {
        var service = service(Duration.ofMinutes(2));
        when(syncRepository.findAllActiveDeliSourceIds()).thenReturn(List.of("source-1"));
        when(replay.replayForRecovery(eq("source-1"), any(), eq(LocalDate.parse("2026-09-24"))))
                .thenReturn(result());

        service.noteHire(LocalDate.parse("2026-01-01"));
        service.runEvening();

        verify(replay).replayForRecovery(
                "source-1",
                LocalDate.parse("2026-07-24"),
                LocalDate.parse("2026-09-24"));
        verify(snapshots, never()).materializeEmployeeRange(any(), any(), any(), any(), any());
    }

    private DeliQuarantineRematchService service(Duration quiet) {
        return new DeliQuarantineRematchService(
                true,
                31,
                62,
                quiet,
                replay,
                syncRepository,
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReplayResult result(PromotedPunch... punches) {
        return new ReplayResult(
                "source-1",
                "company-1",
                LocalDate.parse("2026-09-20"),
                LocalDate.parse("2026-09-24"),
                1,
                0,
                1,
                0,
                new SeedResult(List.of(), List.of(), List.of()),
                List.of(),
                false,
                List.of(punches));
    }
}

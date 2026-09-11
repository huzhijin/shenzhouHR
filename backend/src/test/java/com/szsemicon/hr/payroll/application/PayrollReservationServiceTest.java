package com.szsemicon.hr.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort.FrozenAttendanceSnapshot;
import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort.SnapshotState;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit.DenialReason;
import com.szsemicon.hr.payroll.application.PayrollReservationFailure.Code;
import com.szsemicon.hr.payroll.application.PayrollReservationService.InspectFrozenAttendanceSnapshot;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.FrozenAttendanceSnapshotRef;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriod;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriodStatus;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

class PayrollReservationServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 1);
    private static final ExternalPreciseId SNAPSHOT_ID = id("snapshot-1");

    @Test
    void featureOffAuditsAndStopsBeforeCapabilityAndPort() {
        AtomicInteger capabilityCalls = new AtomicInteger();
        AtomicInteger portCalls = new AtomicInteger();
        List<DeniedEvent> events = new ArrayList<>();
        PayrollReservationService service = service(
                false,
                capabilityCalls::incrementAndGet,
                recordingAudit(events),
                Optional.of(snapshotId -> {
                    portCalls.incrementAndGet();
                    return Optional.of(validSnapshot());
                }));

        assertThatThrownBy(() -> service.inspectFrozenAttendanceSnapshot(command()))
                .isInstanceOf(ResourceNotAvailableAccessDeniedException.class);
        assertThat(capabilityCalls).hasValue(0);
        assertThat(portCalls).hasValue(0);
        assertThat(events).containsExactly(
                new DeniedEvent(id("period-1"), DenialReason.FEATURE_DISABLED));
    }

    @Test
    void missingCapabilityAuditsAndStopsBeforePort() {
        AtomicInteger portCalls = new AtomicInteger();
        List<DeniedEvent> events = new ArrayList<>();
        PayrollReservationService service = service(
                true,
                () -> {
                    throw new AccessDeniedException("not granted");
                },
                recordingAudit(events),
                Optional.of(snapshotId -> {
                    portCalls.incrementAndGet();
                    return Optional.of(validSnapshot());
                }));

        assertThatThrownBy(() -> service.inspectFrozenAttendanceSnapshot(command()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(portCalls).hasValue(0);
        assertThat(events).containsExactly(
                new DeniedEvent(
                        id("period-1"),
                        DenialReason.CAPABILITY_NOT_GRANTED));
    }

    @Test
    void enabledAndAuthorizedWithoutW5AdapterFailsClosed() {
        List<DeniedEvent> events = new ArrayList<>();
        PayrollReservationService service = service(
                true,
                () -> {
                },
                recordingAudit(events),
                Optional.empty());

        assertThatThrownBy(() -> service.inspectFrozenAttendanceSnapshot(command()))
                .isInstanceOfSatisfying(
                        PayrollReservationFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(Code.W5_INTEGRATION_UNAVAILABLE));
        assertThat(events).containsExactly(
                new DeniedEvent(
                        id("period-1"),
                        DenialReason.W5_ADAPTER_UNAVAILABLE));
    }

    @Test
    void absentSnapshotFailsClosedWithoutFallback() {
        List<DeniedEvent> events = new ArrayList<>();
        PayrollReservationService service = service(
                true,
                () -> {
                },
                recordingAudit(events),
                Optional.of(snapshotId -> Optional.empty()));

        assertThatThrownBy(() -> service.inspectFrozenAttendanceSnapshot(command()))
                .isInstanceOfSatisfying(
                        PayrollReservationFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(Code.SNAPSHOT_NOT_AVAILABLE));
        assertThat(events).containsExactly(
                new DeniedEvent(
                        id("period-1"),
                        DenialReason.SNAPSHOT_NOT_AVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    void invalidSnapshotMetadataIsAuditedAndRejected(
            FrozenAttendanceSnapshot invalidSnapshot) {
        List<DeniedEvent> events = new ArrayList<>();
        PayrollReservationService service = service(
                true,
                () -> {
                },
                recordingAudit(events),
                Optional.of(snapshotId -> Optional.of(invalidSnapshot)));

        assertThatThrownBy(() -> service.inspectFrozenAttendanceSnapshot(command()))
                .isInstanceOfSatisfying(
                        PayrollReservationFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(Code.INVALID_FROZEN_SNAPSHOT));
        assertThat(events).containsExactly(
                new DeniedEvent(
                        id("period-1"),
                        DenialReason.INVALID_FROZEN_SNAPSHOT));
    }

    @Test
    void validClosedSnapshotReturnsReferenceOnly() {
        List<DeniedEvent> events = new ArrayList<>();
        AtomicInteger portCalls = new AtomicInteger();
        PayrollReservationService service = service(
                true,
                () -> {
                },
                recordingAudit(events),
                Optional.of(snapshotId -> {
                    portCalls.incrementAndGet();
                    assertThat(snapshotId).isEqualTo(SNAPSHOT_ID);
                    return Optional.of(validSnapshot());
                }));

        FrozenAttendanceSnapshotRef result =
                service.inspectFrozenAttendanceSnapshot(command());

        assertThat(result.attendanceSnapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.periodStartInclusive()).isEqualTo(START);
        assertThat(result.periodEndExclusive()).isEqualTo(END);
        assertThat(portCalls).hasValue(1);
        assertThat(events).isEmpty();
    }

    private static Stream<FrozenAttendanceSnapshot> invalidSnapshots() {
        return Stream.of(
                snapshot(id("different"), START, END, SnapshotState.CLOSED, 1,
                        Instant.EPOCH, "a".repeat(64)),
                snapshot(SNAPSHOT_ID, START, END, SnapshotState.REOPENED, 1,
                        Instant.EPOCH, "a".repeat(64)),
                snapshot(SNAPSHOT_ID, START, END, SnapshotState.CLOSED, 0,
                        Instant.EPOCH, "a".repeat(64)),
                snapshot(SNAPSHOT_ID, START, END, SnapshotState.CLOSED, 1,
                        null, "a".repeat(64)),
                snapshot(SNAPSHOT_ID, START.plusDays(1), END,
                        SnapshotState.CLOSED, 1, Instant.EPOCH, "a".repeat(64)),
                snapshot(SNAPSHOT_ID, START, END, SnapshotState.CLOSED, 1,
                        Instant.EPOCH, "INVALID"));
    }

    private static PayrollReservationService service(
            boolean enabled,
            PayrollCapabilityAuthorizer authorizer,
            PayrollReservationAudit audit,
            Optional<FrozenAttendanceSnapshotPort> port) {
        return new PayrollReservationService(
                new PayrollReservationSettings(enabled),
                authorizer,
                audit,
                port);
    }

    private static PayrollReservationAudit recordingAudit(List<DeniedEvent> events) {
        return (periodId, reason) -> events.add(new DeniedEvent(periodId, reason));
    }

    private static InspectFrozenAttendanceSnapshot command() {
        PayrollPeriod period = new PayrollPeriod(
                id("period-1"),
                "P202607",
                START,
                END,
                PayrollPeriodStatus.RESERVED,
                1);
        return new InspectFrozenAttendanceSnapshot(period, SNAPSHOT_ID);
    }

    private static FrozenAttendanceSnapshot validSnapshot() {
        return snapshot(
                SNAPSHOT_ID,
                START,
                END,
                SnapshotState.CLOSED,
                1,
                Instant.parse("2026-08-02T00:00:00Z"),
                "a".repeat(64));
    }

    private static FrozenAttendanceSnapshot snapshot(
            ExternalPreciseId snapshotId,
            LocalDate start,
            LocalDate end,
            SnapshotState state,
            long version,
            Instant closedAt,
            String digest) {
        return new FrozenAttendanceSnapshot(
                snapshotId, start, end, state, version, closedAt, digest);
    }

    private static ExternalPreciseId id(String value) {
        return new ExternalPreciseId(value);
    }

    private record DeniedEvent(
            ExternalPreciseId payrollPeriodId,
            DenialReason reason) {
    }
}

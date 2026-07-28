package com.szsemicon.hr.attendance.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.MutationKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodErrorCode;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodIdentity;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodState;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionAllowed;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionRejected;
import com.szsemicon.hr.attendance.calculation.domain.FrozenPeriodProtection;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FrozenPeriodProtectionTest {

    private final FrozenPeriodProtection protection =
            new FrozenPeriodProtection();

    @Test
    void current_open_or_reopened_token_allows_ordinary_mutation() {
        assertThat(protection.assess(
                MutationKind.RECALCULATION,
                1,
                "token-v1",
                snapshot(1, PeriodState.OPEN, "token-v1", null)))
                .isInstanceOf(ProtectionAllowed.class);
        assertThat(protection.assess(
                MutationKind.ADJUSTMENT,
                2,
                "token-v2",
                snapshot(2, PeriodState.REOPENED, "token-v2", "close-v1")))
                .isInstanceOf(ProtectionAllowed.class);
    }

    @Test
    void stale_frozen_closed_unknown_and_snapshot_mutation_fail_closed() {
        assertRejected(
                protection.assess(
                        MutationKind.RECALCULATION,
                        1,
                        "stale",
                        snapshot(2, PeriodState.REOPENED, "token-v2", "close-v1")),
                PeriodErrorCode.ATTENDANCE_PERIOD_VERSION_STALE);
        assertRejected(
                protection.assess(
                        MutationKind.ADJUSTMENT,
                        1,
                        "token-v1",
                        snapshot(
                                1,
                                PeriodState.FROZEN_FOR_CLOSE,
                                "token-v1",
                                null)),
                PeriodErrorCode.ATTENDANCE_PERIOD_FROZEN);
        assertRejected(
                protection.assess(
                        MutationKind.RECALCULATION,
                        1,
                        "token-v1",
                        snapshot(1, PeriodState.CLOSED, "token-v1", "close-v1")),
                PeriodErrorCode.ATTENDANCE_PERIOD_CLOSED);
        assertRejected(
                protection.assess(
                        MutationKind.CORRECTION,
                        1,
                        "token-v1",
                        snapshot(1, PeriodState.UNKNOWN, "token-v1", null)),
                PeriodErrorCode.ATTENDANCE_PERIOD_UNKNOWN);
        assertRejected(
                protection.assess(
                        MutationKind.CLOSE_SNAPSHOT_MUTATION,
                        1,
                        "token-v1",
                        snapshot(1, PeriodState.OPEN, "token-v1", "close-v1")),
                PeriodErrorCode.ATTENDANCE_CLOSE_SNAPSHOT_IMMUTABLE);
    }

    private void assertRejected(
            Object decision, PeriodErrorCode errorCode) {
        assertThat(decision)
                .isInstanceOfSatisfying(
                        ProtectionRejected.class,
                        value -> assertThat(value.errorCode())
                                .isEqualTo(errorCode));
    }

    private PeriodStateSnapshot snapshot(
            long version,
            PeriodState state,
            String token,
            String closeSnapshotReference) {
        return new PeriodStateSnapshot(
                new PeriodIdentity(
                        "synthetic-period",
                        "synthetic-legal-entity",
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-08-01")),
                version,
                state,
                token,
                closeSnapshotReference);
    }
}

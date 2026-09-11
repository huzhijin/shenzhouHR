package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.MutationKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodErrorCode;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodState;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionAllowed;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionDecision;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionRejected;
import java.util.Objects;

public final class FrozenPeriodProtection {

    public ProtectionDecision assess(
            MutationKind mutationKind,
            long expectedPeriodVersion,
            String expectedPeriodToken,
            PeriodStateSnapshot authoritativeSnapshot) {
        Objects.requireNonNull(mutationKind, "mutationKind");
        Objects.requireNonNull(expectedPeriodToken, "expectedPeriodToken");
        Objects.requireNonNull(
                authoritativeSnapshot, "authoritativeSnapshot");
        if (mutationKind == MutationKind.CLOSE_SNAPSHOT_MUTATION) {
            return rejected(
                    PeriodErrorCode.ATTENDANCE_CLOSE_SNAPSHOT_IMMUTABLE,
                    authoritativeSnapshot);
        }
        if (expectedPeriodVersion != authoritativeSnapshot.version()
                || !expectedPeriodToken.equals(
                        authoritativeSnapshot.token())) {
            return rejected(
                    PeriodErrorCode.ATTENDANCE_PERIOD_VERSION_STALE,
                    authoritativeSnapshot);
        }
        PeriodState state = authoritativeSnapshot.state();
        return switch (state) {
            case OPEN, REOPENED ->
                    new ProtectionAllowed(authoritativeSnapshot);
            case FROZEN_FOR_CLOSE -> rejected(
                    PeriodErrorCode.ATTENDANCE_PERIOD_FROZEN,
                    authoritativeSnapshot);
            case CLOSED -> rejected(
                    PeriodErrorCode.ATTENDANCE_PERIOD_CLOSED,
                    authoritativeSnapshot);
            case UNKNOWN -> rejected(
                    PeriodErrorCode.ATTENDANCE_PERIOD_UNKNOWN,
                    authoritativeSnapshot);
        };
    }

    private ProtectionRejected rejected(
            PeriodErrorCode code,
            PeriodStateSnapshot authoritativeSnapshot) {
        return new ProtectionRejected(code, authoritativeSnapshot);
    }
}

package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.MutationKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.ProtectionDecision;

public final class FrozenPeriodProtection {

    public ProtectionDecision assess(
            MutationKind mutationKind,
            long expectedPeriodVersion,
            String expectedPeriodToken,
            PeriodStateSnapshot authoritativeSnapshot) {
        throw new UnsupportedOperationException("RED: frozen period protection");
    }
}

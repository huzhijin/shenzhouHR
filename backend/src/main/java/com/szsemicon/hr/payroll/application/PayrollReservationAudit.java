package com.szsemicon.hr.payroll.application;

import com.szsemicon.hr.shared.domain.ExternalPreciseId;

@FunctionalInterface
public interface PayrollReservationAudit {

    void recordDenied(ExternalPreciseId payrollPeriodId, DenialReason reason);

    enum DenialReason {
        FEATURE_DISABLED,
        CAPABILITY_NOT_GRANTED,
        W5_ADAPTER_UNAVAILABLE,
        SNAPSHOT_NOT_AVAILABLE,
        INVALID_FROZEN_SNAPSHOT
    }
}

package com.szsemicon.hr.evidenceingestion.port;

import java.time.LocalDate;

public interface AttendancePeriodProtectionPort {

    Protection protectionFor(
            String companyId,
            String employeeId,
            LocalDate businessDate);

    enum PeriodStatus {
        OPEN,
        FROZEN,
        CLOSED,
        REOPENED,
        UNKNOWN
    }

    record Protection(
            PeriodStatus status,
            String periodVersion,
            String snapshotDigest) {

        public boolean allowsEffectiveMutation() {
            return status == PeriodStatus.OPEN || status == PeriodStatus.REOPENED;
        }
    }
}

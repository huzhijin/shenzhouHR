package com.szsemicon.hr.attendance.application;

import java.time.Instant;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

@Component
final class ZeroAttendanceMonthlyExemptionUsageProvider
        implements AttendanceMonthlyExemptionUsageProvider {

    private static final String PROVENANCE =
            "W3_OFFICIAL_USAGE_NOT_AVAILABLE";

    @Override
    public UsageSnapshot findUsage(
            String employeeId,
            YearMonth naturalMonth,
            Instant correctionAsOf) {
        return new UsageSnapshot(
                employeeId, naturalMonth, 0, PROVENANCE, correctionAsOf);
    }
}

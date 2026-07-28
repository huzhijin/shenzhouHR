package com.szsemicon.hr.attendance.application;

import java.time.Instant;
import java.time.YearMonth;

public interface AttendanceMonthlyExemptionUsageProvider {

    UsageSnapshot findUsage(String employeeId, YearMonth naturalMonth, Instant correctionAsOf);

    record UsageSnapshot(
            String employeeId,
            YearMonth naturalMonth,
            int usedCount,
            String provenance,
            Instant knowledgeTime) {

        public UsageSnapshot {
            if (employeeId == null || employeeId.isBlank() || naturalMonth == null) {
                throw new IllegalArgumentException("usage identity is incomplete");
            }
            if (usedCount < 0) {
                throw new IllegalArgumentException("usedCount must not be negative");
            }
            if (provenance == null || provenance.isBlank() || knowledgeTime == null) {
                throw new IllegalArgumentException("usage provenance is incomplete");
            }
        }
    }
}

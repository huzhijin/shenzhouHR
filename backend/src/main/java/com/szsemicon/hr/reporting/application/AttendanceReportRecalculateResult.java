package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.util.List;

public record AttendanceReportRecalculateResult(
        String projectionVersion,
        Instant dataAsOf,
        List<String> sourceVersions,
        boolean sourcesNewerThanPin,
        List<String> skippedMonths) {

    public AttendanceReportRecalculateResult(
            String projectionVersion,
            Instant dataAsOf,
            List<String> sourceVersions,
            boolean sourcesNewerThanPin) {
        this(
                projectionVersion,
                dataAsOf,
                sourceVersions,
                sourcesNewerThanPin,
                List.of());
    }

    public AttendanceReportRecalculateResult {
        sourceVersions = List.copyOf(sourceVersions);
        skippedMonths = skippedMonths == null ? List.of() : List.copyOf(skippedMonths);
    }
}

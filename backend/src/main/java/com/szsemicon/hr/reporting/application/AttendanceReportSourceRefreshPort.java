package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** Refresh mutable source evidence before publishing a calculated report. */
public interface AttendanceReportSourceRefreshPort {
    /** Returns a cutoff at or after all refreshed evidence committed. */
    Instant refresh(String companyId, YearMonth period, Instant requestedAt);

    /** Explicit employee selection; unsupported adapters retain the stricter company refresh. */
    default Instant refreshRange(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt, java.util.List<String> employeeIds) {
        return refreshRange(companyId, fromInclusive, toExclusive, requestedAt);
    }

    /**
     * Refresh every source dependency within the supplied business-date range.
     * Implementations without range support conservatively refresh every touched
     * month; they must never treat an unsupported partial refresh as a no-op.
     */
    default Instant refreshRange(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("source refresh range must be nonempty");
        }
        Instant evidenceAsOf = requestedAt;
        YearMonth last = YearMonth.from(toExclusive.minusDays(1));
        for (YearMonth month = YearMonth.from(fromInclusive);
                !month.isAfter(last); month = month.plusMonths(1)) {
            Instant refreshed = refresh(companyId, month, requestedAt);
            if (refreshed == null || refreshed.isBefore(requestedAt)) {
                throw new IllegalStateException("source refresh returned an invalid cutoff");
            }
            if (refreshed.isAfter(evidenceAsOf)) {
                evidenceAsOf = refreshed;
            }
        }
        return evidenceAsOf;
    }
}

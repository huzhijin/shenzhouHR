package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** One immutable scope used both to refresh OA and to consume calculation evidence. */
public record AttendanceReportDependencyPlan(
        String companyId,
        YearMonth period,
        LocalDate writeStartInclusive,
        LocalDate writeEndExclusive,
        Instant evidenceStartInclusive,
        Instant evidenceEndExclusive,
        Instant punchStartInclusive,
        Instant punchEndExclusive,
        List<String> employeeIds) {
    public record Baseline(String projectionVersion, Instant dataAsOf, String periodState) { }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public AttendanceReportDependencyPlan {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(writeStartInclusive, "writeStartInclusive");
        Objects.requireNonNull(writeEndExclusive, "writeEndExclusive");
        Objects.requireNonNull(evidenceStartInclusive, "evidenceStartInclusive");
        Objects.requireNonNull(evidenceEndExclusive, "evidenceEndExclusive");
        Objects.requireNonNull(punchStartInclusive, "punchStartInclusive");
        Objects.requireNonNull(punchEndExclusive, "punchEndExclusive");
        employeeIds = employeeIds == null ? List.of() : employeeIds.stream().distinct().sorted().toList();
        if (writeStartInclusive.isBefore(period.atDay(1))
                || writeEndExclusive.isAfter(period.plusMonths(1).atDay(1))
                || !writeStartInclusive.isBefore(writeEndExclusive)
                || !evidenceStartInclusive.isBefore(evidenceEndExclusive)
                || !punchStartInclusive.isBefore(punchEndExclusive)) {
            throw new IllegalArgumentException("invalid report dependency plan");
        }
    }

    public LocalDate refreshFromInclusive() {
        return evidenceStartInclusive.atZone(ZONE).toLocalDate();
    }

    public LocalDate refreshToExclusive() {
        var end = evidenceEndExclusive.atZone(ZONE);
        return end.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? end.toLocalDate() : end.toLocalDate().plusDays(1);
    }

    public static AttendanceReportDependencyPlan bounded(
            String companyId, YearMonth period, LocalDate from, LocalDate to,
            java.util.Collection<String> employeeIds) {
        Instant start = from.minusDays(1).atStartOfDay(ZONE).toInstant();
        Instant end = to.atTime(12, 0).atZone(ZONE).toInstant();
        return new AttendanceReportDependencyPlan(companyId, period, from, to,
                start, end, start, end, employeeIds == null ? List.of() : List.copyOf(employeeIds));
    }
}

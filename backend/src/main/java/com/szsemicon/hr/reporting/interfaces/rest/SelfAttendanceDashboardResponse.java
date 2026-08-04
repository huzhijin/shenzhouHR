package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.SelfAttendanceDashboardService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

record SelfAttendanceDashboardResponse(
        String kind,
        LocalDate businessDate,
        ProjectionMetadata metadata,
        Summary summary,
        List<DailyTrendPoint> dailyTrend,
        Today today,
        List<ExceptionTypeCount> exceptionTypeDistribution,
        List<RecentException> recentExceptions) {

    static SelfAttendanceDashboardResponse from(
            SelfAttendanceDashboardService.Dashboard dashboard) {
        var sourceSummary = dashboard.summary();
        return new SelfAttendanceDashboardResponse(
                "SELF_ATTENDANCE_DASHBOARD",
                dashboard.businessDate(),
                new ProjectionMetadata(
                        dashboard.projectionVersion(),
                        dashboard.sourceVersions(),
                        dashboard.dataAsOf(),
                        SelfAttendanceDashboardService.BUSINESS_ZONE.getId(),
                        dashboard.businessDate()
                                .toString()
                                .substring(0, 7),
                        dashboard.periodState(),
                        new Scope(
                                "SELF",
                                "current-principal",
                                "本人")),
                new Summary(
                        sourceSummary.scheduledMinutes(),
                        sourceSummary.confirmedMinutes(),
                        sourceSummary.recognizedOvertimeMinutes(),
                        sourceSummary.leaveMinutes(),
                        sourceSummary.unresolvedExceptionCount()),
                dashboard.dailyTrend().stream()
                        .map(point -> new DailyTrendPoint(
                                point.businessDate(),
                                point.scheduledMinutes(),
                                point.confirmedMinutes(),
                                point.recognizedOvertimeMinutes(),
                                point.leaveMinutes(),
                                point.issueCount()))
                        .toList(),
                dashboard.today() == null
                        ? null
                        : new Today(
                                dashboard.today().shiftLabel(),
                                dashboard.today().firstPunchAt(),
                                dashboard.today().lastPunchAt(),
                                dashboard.today().statusLabel(),
                                dashboard.today().confirmedMinutes(),
                                dashboard.today().issueLabels()),
                dashboard.exceptionTypeDistribution().stream()
                        .map(item -> new ExceptionTypeCount(
                                item.type(), item.count()))
                        .toList(),
                dashboard.recentExceptions().stream()
                        .map(item -> new RecentException(
                                item.businessDate(),
                                item.type(),
                                item.severity(),
                                item.state(),
                                item.minutes(),
                                item.safeEvidenceSummary()))
                        .toList());
    }

    record ProjectionMetadata(
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String timeZone,
            String periodLabel,
            String periodState,
            Scope scope) {
    }

    record Scope(String type, String reference, String label) {
    }

    record Summary(
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            long unresolvedExceptionCount) {
    }

    record DailyTrendPoint(
            LocalDate businessDate,
            long scheduledMinutes,
            long confirmedMinutes,
            long recognizedOvertimeMinutes,
            long leaveMinutes,
            long issueCount) {
    }

    record Today(
            String shiftLabel,
            Instant firstPunchAt,
            Instant lastPunchAt,
            String statusLabel,
            long confirmedMinutes,
            List<String> issueLabels) {
    }

    record ExceptionTypeCount(String type, long count) {
    }

    record RecentException(
            LocalDate businessDate,
            String type,
            String severity,
            String state,
            long minutes,
            String safeEvidenceSummary) {
    }
}

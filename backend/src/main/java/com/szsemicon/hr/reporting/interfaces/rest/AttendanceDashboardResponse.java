package com.szsemicon.hr.reporting.interfaces.rest;

import com.szsemicon.hr.reporting.application.AttendanceDashboardRepository;
import com.szsemicon.hr.reporting.application.AttendanceDashboardService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

sealed interface AttendanceDashboardResponse
        permits AttendanceDashboardResponse.Ready,
                AttendanceDashboardResponse.CompanySelection {

    static AttendanceDashboardResponse from(
            AttendanceDashboardService.QueryResult result) {
        List<CompanyOption> companies = result.companies().stream()
                .map(company -> new CompanyOption(
                        company.companyId(), company.companyName()))
                .toList();
        if (result
                instanceof AttendanceDashboardService.CompanySelection
                        selection) {
            return new CompanySelection(
                    "DASHBOARD_COMPANY_SELECTION",
                    "今日异常考勤",
                    selection.businessDate(),
                    null,
                    companies,
                    "请选择公司后查看今日异常考勤");
        }
        var ready = (AttendanceDashboardService.Ready) result;
        var snapshot = ready.snapshot();
        var scope = snapshot.scope();
        var metadata = new ProjectionMetadata(
                snapshot.projectionVersion(),
                snapshot.sourceVersions(),
                snapshot.dataAsOf(),
                AttendanceDashboardService.BUSINESS_ZONE.getId(),
                ready.businessDate().toString().substring(0, 7),
                snapshot.periodState(),
                new Scope(
                        scope.type().name(),
                        scope.reference(),
                        scope.label()),
                ready.allowedActions());
        var summary = new Summary(
                snapshot.summary().unresolvedCount(),
                snapshot.summary().affectedEmployeeCount(),
                snapshot.summary().blockingCount());
        var sourceAnalytics = snapshot.analytics();
        var analytics = new Analytics(
                sourceAnalytics.dailyTrend().stream()
                        .map(point -> new DailyTrendPoint(
                                point.businessDate(),
                                point.exceptionCount(),
                                point.blockingCount(),
                                point.affectedEmployeeCount()))
                        .toList(),
                sourceAnalytics.severityDistribution().stream()
                        .map(item -> new SeverityDistributionItem(
                                item.severity(), item.count()))
                        .toList(),
                sourceAnalytics.typeDistribution().stream()
                        .map(item -> new TypeDistributionItem(
                                item.exceptionType(), item.count()))
                        .toList(),
                sourceAnalytics.organizationRanking().stream()
                        .map(item -> new OrganizationRankingItem(
                                item.organizationName(),
                                item.exceptionCount(),
                                item.blockingCount()))
                        .toList());
        List<ExceptionItem> exceptions = ready.allowedActions().contains(
                        "DASHBOARD_DRILL_DOWN")
                ? snapshot.exceptions().stream()
                        .map(AttendanceDashboardResponse::toExceptionItem)
                        .toList()
                : List.of();
        List<Metric> metrics = ready.metrics().stream()
                .map(item -> new Metric(
                        item.key(),
                        item.label(),
                        item.displayValue(),
                        item.suppressed(),
                        item.suppressed() ? "样本量不足，已隐藏" : null,
                        null))
                .toList();
        List<TodayPunch> todayPunches = ready.todayPunches().stream()
                .map(item -> new TodayPunch(
                        item.employeeNumber(),
                        item.employeeName(),
                        item.organizationName(),
                        item.firstPunchAt(),
                        item.lastPunchAt(),
                        item.punchCount()))
                .toList();
        return new Ready(
                "DASHBOARD",
                "今日异常考勤",
                ready.businessDate(),
                ready.selectedCompany().companyId(),
                metadata,
                summary,
                analytics,
                exceptions,
                companies,
                metrics,
                todayPunches);
    }

    private static ExceptionItem toExceptionItem(
            AttendanceDashboardRepository.ExceptionItem item) {
        return new ExceptionItem(
                item.exceptionReference(),
                item.employeeNumber(),
                item.employeeName(),
                item.organizationName(),
                item.businessDate(),
                item.exceptionType(),
                item.severity(),
                item.state(),
                item.exceptionMinutes(),
                item.evidenceSummary());
    }

    record Ready(
            String kind,
            String title,
            LocalDate businessDate,
            String selectedCompanyId,
            ProjectionMetadata metadata,
            Summary summary,
            Analytics analytics,
            List<ExceptionItem> exceptions,
            List<CompanyOption> companies,
            List<Metric> metrics,
            List<TodayPunch> todayPunches)
            implements AttendanceDashboardResponse {
    }

    record Metric(
            String key,
            String label,
            String displayValue,
            boolean suppressed,
            String suppressionLabel,
            String drillDownReference) {
    }

    record TodayPunch(
            String employeeNumber,
            String employeeName,
            String organizationName,
            String firstPunchAt,
            String lastPunchAt,
            long punchCount) {
    }

    record CompanySelection(
            String kind,
            String title,
            LocalDate businessDate,
            String selectedCompanyId,
            List<CompanyOption> companies,
            String message)
            implements AttendanceDashboardResponse {
    }

    record ProjectionMetadata(
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String timeZone,
            String periodLabel,
            String periodState,
            Scope scope,
            List<String> allowedActions) {
    }

    record Scope(String type, String reference, String label) {
    }

    record Summary(
            long unresolvedCount,
            long affectedEmployeeCount,
            long blockingCount) {
    }

    record Analytics(
            List<DailyTrendPoint> dailyTrend,
            List<SeverityDistributionItem> severityDistribution,
            List<TypeDistributionItem> typeDistribution,
            List<OrganizationRankingItem> organizationRanking) {
    }

    record DailyTrendPoint(
            LocalDate businessDate,
            long exceptionCount,
            long blockingCount,
            long affectedEmployeeCount) {
    }

    record SeverityDistributionItem(String severity, long count) {
    }

    record TypeDistributionItem(String exceptionType, long count) {
    }

    record OrganizationRankingItem(
            String organizationName,
            long exceptionCount,
            long blockingCount) {
    }

    record ExceptionItem(
            String exceptionReference,
            String employeeNumber,
            String employeeName,
            String organizationName,
            LocalDate businessDate,
            String exceptionType,
            String severity,
            String state,
            long exceptionMinutes,
            String evidenceSummary) {
    }

    record CompanyOption(String companyId, String companyName) {
    }
}

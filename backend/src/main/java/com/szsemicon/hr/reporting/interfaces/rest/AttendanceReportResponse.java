package com.szsemicon.hr.reporting.interfaces.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

record AttendanceReportResponse(
        String kind,
        ProjectionMetadata metadata,
        String reportType,
        String reportTitle,
        String queryFingerprint,
        String formulaVersion,
        ReportFilters filters,
        List<ReportColumn> columns,
        List<String> exportFieldAllowlist,
        long rowCount,
        List<ReportRow> rows,
        int page,
        int size,
        int totalPages) {

    record ProjectionMetadata(
            String projectionVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String timeZone,
            String periodLabel,
            String periodState,
            ProjectionScope scope,
            List<String> allowedActions,
            boolean sourcesNewerThanPin) {
    }

    record ProjectionScope(
            String type,
            String reference,
            String label) {
    }

    record ReportFilters(
            String period,
            String scopeReference,
            String companyId,
            String organizationId,
            String employeeId,
            String status,
            String fromDate,
            String toDate) {
    }

    record ReportColumn(String key, String label) {
    }

    record ReportRow(
            String rowReference,
            Map<String, String> values,
            String drillDownReference) {
    }
}

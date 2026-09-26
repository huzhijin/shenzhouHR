package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportColumn;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.Instant;
import java.util.List;

public record AttendanceReportPage(
        ReportType reportType,
        String title,
        String projectionVersion,
        String queryFingerprint,
        String formulaVersion,
        String periodState,
        Instant dataAsOf,
        List<String> sourceVersions,
        AuthorizedScope scope,
        ReportFilter filters,
        List<ReportColumn> columns,
        List<ReportField> exportAllowlist,
        List<String> allowedActions,
        List<ReportRow> rows,
        int page,
        int size,
        long totalRows,
        int totalPages,
        boolean sourcesNewerThanPin) {

    public AttendanceReportPage {
        sourceVersions = List.copyOf(sourceVersions);
        columns = List.copyOf(columns);
        exportAllowlist = List.copyOf(exportAllowlist);
        allowedActions = List.copyOf(allowedActions);
        rows = List.copyOf(rows);
    }
}

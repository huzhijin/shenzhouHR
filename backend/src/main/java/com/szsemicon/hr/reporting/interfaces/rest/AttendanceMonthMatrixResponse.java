package com.szsemicon.hr.reporting.interfaces.rest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

record AttendanceMonthMatrixResponse(
        String kind,
        ProjectionMetadata metadata,
        String queryFingerprint,
        String formulaVersion,
        ReportFilters filters,
        List<LocalDate> dates,
        long employeeCount,
        List<EmployeeRow> rows,
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

    record ProjectionScope(String type, String reference, String label) {
    }

    record ReportFilters(
            String period,
            String scopeReference,
            String companyId,
            String organizationId,
            String employeeId,
            String fromDate,
            String toDate) {
    }

    record EmployeeRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            List<DayCell> days) {
    }

    record DayCell(
            LocalDate date,
            String organizationName,
            String shiftLabel,
            Instant firstPunchAt,
            Instant lastPunchAt,
            List<String> badges,
            SlotDisplay morning,
            SlotDisplay afternoon,
            boolean merged,
            String hover) {
    }

    record SlotDisplay(
            String text,
            String tone,
            Instant punchAt) {
    }
}

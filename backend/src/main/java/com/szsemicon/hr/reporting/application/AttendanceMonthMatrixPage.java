package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Employee-paged monthly attendance matrix assembled only from an authorized
 * immutable report projection.
 */
public record AttendanceMonthMatrixPage(
        String projectionVersion,
        String queryFingerprint,
        String formulaVersion,
        String periodState,
        Instant dataAsOf,
        List<String> sourceVersions,
        AuthorizedScope scope,
        ReportFilter filters,
        List<String> allowedActions,
        List<LocalDate> dates,
        List<EmployeeRow> rows,
        int page,
        int size,
        long totalEmployees,
        int totalPages) {

    public AttendanceMonthMatrixPage {
        sourceVersions = List.copyOf(sourceVersions);
        allowedActions = List.copyOf(allowedActions);
        dates = List.copyOf(dates);
        rows = List.copyOf(rows);
    }

    public record EmployeeRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            List<DayCell> days) {

        public EmployeeRow {
            days = List.copyOf(days);
        }
    }

    public record DayCell(
            LocalDate date,
            String organizationName,
            String shiftLabel,
            Instant firstPunchAt,
            Instant lastPunchAt,
            List<BadgeCode> badges) {

        public DayCell {
            badges = List.copyOf(badges);
        }
    }

    /**
     * Stable semantic codes. Presentation colors deliberately do not belong
     * to the server-side business result.
     */
    public enum BadgeCode {
        LATE,
        EARLY_DEPARTURE,
        MISSING_PUNCH,
        ABSENCE,
        RECOGNIZED_OVERTIME,
        TIME_OFF,
        OUTING,
        TRIP,
        PERSONAL_LEAVE,
        SICK_LEAVE,
        ANNUAL_LEAVE,
        PUNCH_CORRECTION,
        REST_DAY,
        OTHER_LEAVE,
        LEAVE_REVOCATION,
        OVERTIME_APPLICATION,
        EXEMPT_PUNCH,
        OTHER_ATTENDANCE_DOCUMENT,
        OTHER_EXCEPTION
    }
}

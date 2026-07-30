package com.szsemicon.hr.attendance.application;

import java.time.LocalDate;

public final class AttendanceGroupCommands {

    private AttendanceGroupCommands() {
    }

    public record LocationCommand(
            String companyId,
            String code,
            String name,
            String timeZone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record GroupCommand(
            String companyId,
            String code,
            String name,
            String locationId,
            String calendarId,
            String shiftTemplateId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record AssignmentCommand(
            String employeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }
}

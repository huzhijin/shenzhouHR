package com.szsemicon.hr.reporting.infrastructure.persistence;

import java.time.Instant;

public final class DashboardWorkbenchRows {

    private DashboardWorkbenchRows() {
    }

    public record RosterRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName) {
    }

    public record PunchRow(
            String employeeId,
            Instant pointInstant,
            String employeeNumber) {

        public PunchRow(String employeeId, Instant pointInstant) {
            this(employeeId, pointInstant, null);
        }
    }

    public record EmployeeNumberRow(
            String employeeId,
            String employeeNumber) {
    }

    public record NegativeLeaveRow(
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            String accountType,
            java.math.BigDecimal balanceHours) {
    }
}

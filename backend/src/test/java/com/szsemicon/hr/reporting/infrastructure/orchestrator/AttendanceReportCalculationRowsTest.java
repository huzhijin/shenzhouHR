package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.EmployeeIdentityIntervalRow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceReportCalculationRowsTest {

    private static final LocalDate AUGUST_START =
            LocalDate.of(2026, 8, 1);
    private static final LocalDate SEPTEMBER_START =
            LocalDate.of(2026, 9, 1);

    @Test
    void assignmentEffectiveDateMovesAugustFifteenthToNewDepartment() {
        var departmentA = identity(
                "assignment-a", "organization-a", AUGUST_START);
        var departmentB = identity(
                "assignment-b",
                "organization-b",
                LocalDate.of(2026, 8, 15));

        assertThat(AttendanceReportCalculationRows.latestEffectiveAssignment(
                        List.of(departmentA, departmentB),
                        LocalDate.of(2026, 8, 14)))
                .isSameAs(departmentA);
        assertThat(AttendanceReportCalculationRows.latestEffectiveAssignment(
                        List.of(departmentA, departmentB),
                        LocalDate.of(2026, 8, 15)))
                .isSameAs(departmentB);
    }

    @Test
    void tiedLatestAssignmentDateRemainsAmbiguous() {
        var first = identity(
                "assignment-a", "organization-a", AUGUST_START);
        var second = identity(
                "assignment-b", "organization-b", AUGUST_START);

        assertThat(AttendanceReportCalculationRows.latestEffectiveAssignment(
                        List.of(first, second), AUGUST_START))
                .isNull();
    }

    private static EmployeeIdentityIntervalRow identity(
            String assignmentId,
            String organizationId,
            LocalDate assignmentStart) {
        return new EmployeeIdentityIntervalRow(
                "employee-1",
                "employee-version-1",
                "E001",
                "员工一",
                assignmentId,
                organizationId,
                organizationId + "-version",
                organizationId,
                AUGUST_START,
                SEPTEMBER_START,
                assignmentStart,
                SEPTEMBER_START,
                AUGUST_START,
                SEPTEMBER_START);
    }
}

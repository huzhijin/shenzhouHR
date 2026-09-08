package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmploymentValidityTest {

    @Test
    void leaverLastDayIsIncludedAndNextDayIsExcluded() {
        var chen = identity(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 8));
        assertThat(chen.validOn(LocalDate.of(2026, 8, 7))).isTrue();
        assertThat(chen.validOn(LocalDate.of(2026, 8, 8))).isFalse();

        var du = identity(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1));
        assertThat(du.validOn(LocalDate.of(2026, 8, 31))).isTrue();
        assertThat(du.validOn(LocalDate.of(2026, 9, 1))).isFalse();
    }

    private static AttendanceReportCalculationRows.EmployeeIdentityIntervalRow identity(
            LocalDate from,
            LocalDate toExclusive) {
        return new AttendanceReportCalculationRows.EmployeeIdentityIntervalRow(
                "employee-a",
                "version-a",
                "SZST0641",
                "陈柏宇",
                "assignment-a",
                "org-a",
                "org-version-a",
                "制造中心",
                from,
                null,
                from,
                toExclusive,
                from,
                null);
    }
}

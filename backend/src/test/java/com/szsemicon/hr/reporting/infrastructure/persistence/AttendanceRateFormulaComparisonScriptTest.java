package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AttendanceRateFormulaComparisonScriptTest {

    private static final Path SCRIPT = Path.of(
            "../deploy/mysql/sql/compare-attendance-rate-formulas.sql");

    @Test
    void comparisonIsReadOnlyScopedAndUsesTheOpenSpecDayFormula() throws Exception {
        String sql = Files.readString(SCRIPT);
        String executable = sql.lines()
                .map(line -> line.replaceFirst("--.*$", "").trim().toUpperCase())
                .filter(line -> !line.isEmpty())
                .reduce("", (left, right) -> left + right + "\n");

        assertThat(sql).contains(
                "projection.status = 'PUBLISHED'",
                "fact.attendance_report_projection_id",
                "fact.legal_entity_id = @attendance_rate_company_id",
                "SUM(fact.scheduled_attendance_days)",
                "THEN fact.actual_attendance_days",
                "NULLIF(totals.scheduled_attendance_days, 0)",
                "retired_minute_rate",
                "openspec_day_rate",
                "percentage_point_delta",
                "ERROR_COMPANY_ID_REQUIRED",
                "ERROR_PROJECTION_SCOPE_OR_STATUS");
        assertThat(executable).doesNotContainPattern(
                "(?m)^(UPDATE|DELETE|INSERT|TRUNCATE|ALTER|DROP|CREATE|REPLACE|CALL|LOCK)\\b");
    }
}

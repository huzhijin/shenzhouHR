package com.szsemicon.hr.reporting.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class AttendanceReportExportSensitiveDataContractTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SCOPE = "authorized-scope-set:" + FINGERPRINT;

    @Test
    void requestStringRepresentationRedactsPurpose() {
        String purpose = "董事会专项复核";
        var create =
                new AttendanceReportExportController.CreateExportRequest(
                        ReportType.ATTENDANCE_DETAIL,
                        "projection-1",
                        FINGERPRINT,
                        SCOPE,
                        filters(),
                        java.util.List.of("employee-number"),
                        purpose);

        assertThat(create.toString())
                .contains("purpose=<redacted>")
                .doesNotContain(purpose);
    }

    private static AttendanceReportExportController.ExportFilters filters() {
        return new AttendanceReportExportController.ExportFilters(
                YearMonth.of(2026, 7),
                SCOPE,
                "legal-1",
                null,
                null,
                null);
    }
}
